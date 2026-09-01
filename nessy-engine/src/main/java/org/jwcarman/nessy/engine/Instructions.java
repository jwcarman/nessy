/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.engine.agent.Instruction;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelReplies;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * Everything an agent's decisions actually DO, and none of the deciding.
 *
 * <p><b>The rule this class exists to keep.</b> Work handed to the blocking executor has its answer
 * addressed to a LOGICAL address, never to {@code getSelf()}. The executor outlives actors; a
 * reference does not. An {@link org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef} is
 * resolved by the shard at delivery, so if the agent was unloaded in the meantime the shard creates
 * one and delivers there — which makes the answer arriving its own knock on the door.
 *
 * <p><b>Content is claimed BEFORE the agent is told.</b> Every message this sends carries ids and
 * small statuses; a tool's result and the model's asking message go into claims first. That is what
 * makes a persisted state safe to reference them: a state saying a call completed cannot point at a
 * result that is not there.
 */
final class Instructions {

  private static final String ASKED_KEY = "asked";
  private static final String ANSWER_KEY = "answer";

  /**
   * What performing an instruction needs.
   *
   * <p>Flat rather than layered, because there is no longer a hierarchy to hand things down: one
   * actor does the whole turn, so one record holds what the whole turn needs.
   */
  record Dependencies(
      AgentType agentType,
      Memory memory,
      Model model,
      String systemPrompt,
      int maxTokens,
      ToolBindings bindings,
      Set<Capability> capabilities,
      Function<AgentId, Narrator> narrators,
      Claims claims,
      Reminders reminders,
      ReplyTokens tokens,
      Executor blocking,
      Traces traces,
      BacklogStore<?> backlog) {}

  private final ActorSystem<?> system;
  private final Dependencies deps;
  private final EntityTypeKey<NessyMessage> key;
  private final Codec<List<ExchangeContentBlock>> askedCodec;
  private final Codec<ToolResult> resultCodec;
  private final Codec<AnswerMessage> answerCodec;
  private final Codec<UserMessage> inputCodec;

  Instructions(ActorSystem<?> system, Dependencies deps) {
    this.system = Objects.requireNonNull(system, "system must not be null");
    this.deps = Objects.requireNonNull(deps, "deps must not be null");
    this.key = EntityTypeKey.create(NessyMessage.class, deps.agentType().name());
    ObjectMapper mapper = EngineMapper.INSTANCE;
    this.askedCodec = JsonCodec.ofList(mapper, ExchangeContentBlock.class);
    this.resultCodec = JsonCodec.of(mapper, ToolResult.class);
    this.answerCodec = JsonCodec.of(mapper, AnswerMessage.class);
    this.inputCodec = JsonCodec.of(mapper, UserMessage.class);
  }

  /** Performs one instruction. Never blocks the calling thread on anything that can be slow. */
  void perform(
      AgentId agentId, AgentState state, Instruction instruction, Map<String, String> carried) {
    switch (instruction) {
      case Instruction.TakeWork ignored -> takeWork(agentId, state, carried);
      case Instruction.CallModel ignored -> callModel(agentId, state, carried);
      case Instruction.AskApprover ask -> askApprover(agentId, state, ask, carried);
      case Instruction.RunTool run -> runTool(agentId, state, run, carried);
      case Instruction.Remember.Input ignored -> rememberInput(agentId, state);
      case Instruction.Remember.Answer ignored -> rememberAnswer(agentId, state);
      case Instruction.Remember.Exchange ignored -> rememberExchange(agentId, state);
      case Instruction.Release ignored -> deps.claims().deleteTurn(agentId, state.turnId());
      case Instruction.SetAlarm alarm -> setAlarm(agentId, alarm);
      case Instruction.CancelAlarm alarm ->
          deps.reminders()
              .cancel(
                  ReminderSweep.keyFor(deps.agentType().name(), agentId.value(), alarm.callId()));
      case Instruction.Sleep ignored -> {
        // The agent asks the shard to unload it; that lives on the actor, which owns the handle.
      }
      case Instruction.Narrate narrate -> narrate(agentId, state, narrate);
    }
  }

  /** Narration is per agent, so it is resolved per call: an entity ref is a routing decision. */
  private Narrator narrator(AgentId agentId) {
    return deps.narrators().apply(agentId);
  }

  private void tell(AgentId agentId, NessyMessage message) {
    ClusterSharding.get(system).entityRefFor(key, agentId.value()).tell(message);
  }

  /**
   * Asks the store for the next row, naming the turn this agent has finished so its row is swept.
   *
   * <p>{@code state.observation()} survives a finished turn precisely for this: it is the id the
   * sweep has to name, and naming it is what distinguishes a turn that ended from a take the agent
   * never recorded.
   */
  private void takeWork(AgentId agentId, AgentState state, Map<String, String> carried) {
    // The TURN id, which is the backlog row's id — not the claim key. Null until this agent has
    // finished one, and null while it is busy, because a turn in flight is nobody's to sweep.
    String finished = state.busy() ? null : state.turnId();
    run(
        () -> {
          var t = deps.backlog().take(agentId, finished);
          System.out.println(
              "PROBE take agent=" + agentId.value() + " finished=" + finished + " -> " + t);
          return t;
        },
        taken ->
            taken
                .<NessyMessage>map(
                    work ->
                        new NessyMessage.WorkTaken(work.turnId(), work.observationClaim(), carried))
                .orElseGet(() -> new NessyMessage.NoWork(carried)),
        failure ->
            new NessyMessage.ModelFailed("the backlog could not be read: " + failure, carried),
        agentId);
  }

  /**
   * Asks the model, on the blocking executor, with the transcript read INSIDE the hop.
   *
   * <p>{@code recall()} is an application's own implementation and may do arbitrary IO, so building
   * the request out here would put it on the actor's thread — three lines above the hop that exists
   * for exactly that reason.
   */
  private void callModel(AgentId agentId, AgentState state, Map<String, String> carried) {
    run(
        () ->
            deps.traces()
                .inSpan(
                    "agent call model",
                    carried,
                    () ->
                        ModelReplies.drain(
                            deps.model().stream(
                                new ModelRequest(
                                    deps.memory().recall(agentId),
                                    deps.systemPrompt(),
                                    deps.maxTokens(),
                                    deps.bindings().tools(),
                                    deps.capabilities())),
                            event -> narrateChunk(agentId, event))),
        result -> answerOf(agentId, state, result, carried),
        failure -> new NessyMessage.ModelFailed(failure, carried),
        agentId);
  }

  /**
   * Turns what the model said into a message carrying no content.
   *
   * <p>The asking message is claimed before the agent hears about it, and it is what pins the CALL
   * IDS: without it a recovered turn would have to ask the model again, get fresh ids, and re-run
   * tools whose answers it already had.
   */
  private NessyMessage answerOf(
      AgentId agentId, AgentState state, ModelResult result, Map<String, String> carried) {
    return switch (result) {
      case ModelResult.Refused refused ->
          new NessyMessage.ModelRefused(
              refused.category(), refused.explanation(), refused.usage(), carried);
      case ModelResult.Answered answered -> {
        deps.claims()
            .put(agentId, state.turnId(), ANSWER_KEY, answerCodec.encode(answered.message()));
        yield new NessyMessage.ModelAnswered(answered.stopReason(), answered.usage(), carried);
      }
      case ModelResult.Asked asked -> {
        deps.claims().put(agentId, state.turnId(), ASKED_KEY, askedCodec.encode(asked.content()));
        yield new NessyMessage.ModelAsked(
            callsIn(asked.content()).stream()
                .map(call -> new Input.CallSummary(call.id(), call.name()))
                .toList(),
            asked.usage(),
            carried);
      }
    };
  }

  private void askApprover(
      AgentId agentId, AgentState state, Instruction.AskApprover ask, Map<String, String> carried) {
    ToolCall call = callOf(agentId, state, ask.callId());
    if (call == null) {
      completed(
          agentId,
          state,
          ask.callId(),
          ToolResult.error("the asking message is gone; the call was not made"));
      return;
    }
    deps.bindings()
        .binding(call.name())
        .ifPresentOrElse(
            binding -> {
              String description = deps.bindings().describe(binding, call.arguments());
              ApprovalRequest request =
                  new ApprovalRequest(deps.agentType(), agentId, call, description, Instant.now());
              // Minted before anyone is asked, because the approver may hand it to a person and
              // the tool may hand it to the outside world, and both settle the same call.
              org.jwcarman.nessy.api.tool.ApprovalContext context =
                  () -> deps.tokens().mint(deps.agentType(), agentId, state.turnId(), call.id());
              run(
                  () ->
                      deps.traces()
                          .inSpan(
                              "approval " + call.name(),
                              carried,
                              () -> deps.bindings().approve(binding, request, context)),
                  answer ->
                      switch (answer) {
                        case Awaited.Ready<ApprovalResult> ready ->
                            new NessyMessage.ApprovalGiven(
                                call.id(), call.name(), ready.result(), carried);
                        case Awaited.Deferred<ApprovalResult> deferred -> {
                          // Narrated HERE and nowhere else: an ungated tool answers on the spot,
                          // and only a deferral means a person is actually being asked. The desk
                          // needs the deadline, which is knowable at exactly this moment.
                          narrator(agentId)
                              .narrate(
                                  new AgentEvent.ApprovalRequested(
                                      Identifiers.next(),
                                      call.id(),
                                      call.name(),
                                      description,
                                      deferred.expiresAt()));
                          yield new NessyMessage.ToolParked(
                              call.id(), deferred.expiresAt(), carried);
                        }
                      },
                  failure ->
                      new NessyMessage.ApprovalGiven(
                          call.id(),
                          call.name(),
                          ApprovalResult.denied("the approver failed: " + failure),
                          carried),
                  agentId);
            },
            () ->
                completed(
                    agentId,
                    state,
                    call.id(),
                    ToolResult.error("no such tool: " + call.name() + "; the call was not made")));
  }

  private void runTool(
      AgentId agentId, AgentState state, Instruction.RunTool run, Map<String, String> carried) {
    ToolCall call = callOf(agentId, state, run.callId());
    if (call == null) {
      completed(
          agentId,
          state,
          run.callId(),
          ToolResult.error("the asking message is gone; it was not run"));
      return;
    }
    deps.bindings()
        .binding(call.name())
        .ifPresent(
            binding -> {
              ToolContext context =
                  new CallContext(
                      deps.agentType(),
                      agentId,
                      deps.tokens().mint(deps.agentType(), agentId, state.turnId(), call.id()));
              run(
                  () ->
                      deps.traces()
                          .inSpan(
                              "tool " + call.name(),
                              carried,
                              () -> deps.bindings().run(binding, call.arguments(), context)),
                  answer ->
                      switch (answer) {
                        case Awaited.Ready<ToolResult> ready -> {
                          hold(agentId, state, call.id(), ready.result());
                          yield new NessyMessage.ToolCompleted(call.id(), carried);
                        }
                        case Awaited.Deferred<ToolResult> deferred ->
                            new NessyMessage.ToolParked(call.id(), deferred.expiresAt(), carried);
                      },
                  failure -> {
                    hold(
                        agentId,
                        state,
                        call.id(),
                        ToolResult.error(failure + "; it may have partially completed"));
                    return new NessyMessage.ToolCompleted(call.id(), carried);
                  },
                  agentId);
            });
  }

  /** Writes a result and tells the agent — in that order, always. */
  private void completed(AgentId agentId, AgentState state, String callId, ToolResult result) {
    hold(agentId, state, callId, result);
    tell(agentId, new NessyMessage.ToolCompleted(callId, Map.of()));
  }

  private void hold(AgentId agentId, AgentState state, String callId, ToolResult result) {
    deps.claims().put(agentId, state.turnId(), resultKey(callId), resultCodec.encode(result));
  }

  private void rememberInput(AgentId agentId, AgentState state) {
    redeem(agentId, state, state.observation(), inputCodec)
        .ifPresent(input -> deps.memory().remember(agentId, input));
  }

  private void rememberAnswer(AgentId agentId, AgentState state) {
    redeem(agentId, state, ANSWER_KEY, answerCodec)
        .ifPresent(
            answer -> {
              narrator(agentId).narrate(new AgentEvent.Answered(Identifiers.next(), answer));
              deps.memory().remember(agentId, answer);
            });
  }

  /**
   * The asking message and every result, in ONE write.
   *
   * <p>An assistant turn naming unanswered calls is not a valid transcript, so it is held until
   * every call has settled and then written together with the message answering it. That is what
   * keeps a transcript from ever holding half an exchange, and therefore what makes re-driving
   * always safe.
   */
  private void rememberExchange(AgentId agentId, AgentState state) {
    redeem(agentId, state, ASKED_KEY, askedCodec)
        .ifPresent(
            asked -> {
              List<ToolResultBlock> answers = new ArrayList<>();
              for (ToolCall call : callsIn(asked)) {
                ToolResult result =
                    redeem(agentId, state, resultKey(call.id()), resultCodec)
                        .orElseGet(() -> ToolResult.error("no result was recorded"));
                answers.add(ToolResultBlock.of(call.id(), result));
              }
              deps.memory().remember(agentId, new ExchangeMessage(asked, answers));
            });
  }

  private void setAlarm(AgentId agentId, Instruction.SetAlarm alarm) {
    deps.reminders()
        .remind(
            ReminderSweep.keyFor(deps.agentType().name(), agentId.value(), alarm.callId()),
            alarm.expiresAt(),
            ReminderSweep.encode(
                new ReminderSweep.Coordinates(
                    deps.agentType().name(), agentId.value(), alarm.callId())));
  }

  private void narrate(AgentId agentId, AgentState state, Instruction.Narrate narrate) {
    switch (narrate) {
      case Instruction.Narrate.TurnStarted ignored ->
          narrator(agentId).narrate(new AgentEvent.TurnStarted(Identifiers.next()));
      case Instruction.Narrate.TurnEnded ended ->
          narrator(agentId)
              .narrate(new AgentEvent.TurnEnded(Identifiers.next(), ended.result(), ended.usage()));
      case Instruction.Narrate.ToolCallRequested requested ->
          narrator(agentId)
              .narrate(
                  new AgentEvent.ToolCallRequested(
                      Identifiers.next(),
                      requested.callId(),
                      requested.toolName(),
                      describe(agentId, state, requested.callId())));
      case Instruction.Narrate.ApprovalDecided decided ->
          narrator(agentId)
              .narrate(
                  new AgentEvent.ApprovalDecided(
                      Identifiers.next(),
                      decided.callId(),
                      nameOf(agentId, state, decided.callId()),
                      decided.result()));
      case Instruction.Narrate.ToolCallCompleted done ->
          narrator(agentId)
              .narrate(
                  new AgentEvent.ToolCallCompleted(
                      Identifiers.next(),
                      done.callId(),
                      nameOf(agentId, state, done.callId()),
                      redeem(agentId, state, resultKey(done.callId()), resultCodec)
                          .orElseGet(() -> ToolResult.error("no result was recorded"))));
    }
  }

  private String nameOf(AgentId agentId, AgentState state, String callId) {
    ToolCall call = callOf(agentId, state, callId);
    return call == null ? "" : call.name();
  }

  /** What the model asked this tool to do, in the words the binding's describer chose. */
  private String describe(AgentId agentId, AgentState state, String callId) {
    ToolCall call = callOf(agentId, state, callId);
    if (call == null) {
      return "";
    }
    return deps.bindings()
        .binding(call.name())
        .map(binding -> deps.bindings().describe(binding, call.arguments()))
        .orElse("");
  }

  private ToolCall callOf(AgentId agentId, AgentState state, String callId) {
    return redeem(agentId, state, ASKED_KEY, askedCodec)
        .flatMap(
            asked -> callsIn(asked).stream().filter(call -> call.id().equals(callId)).findFirst())
        .orElse(null);
  }

  private <T> Optional<T> redeem(AgentId agentId, AgentState state, String key, Codec<T> codec) {
    if (key == null) {
      return Optional.empty();
    }
    return deps.claims().get(agentId, state.turnId(), key).map(codec::decode);
  }

  /**
   * Hands work to the blocking executor and posts the answer to the agent's LOGICAL address.
   *
   * <p>This is the whole safety property in one method: nothing here holds an actor reference, so
   * an agent unloaded while the work ran is simply started again by the shard to receive it.
   */
  private <T> void run(
      java.util.function.Supplier<T> work,
      Function<T, NessyMessage> answer,
      Function<String, NessyMessage> broke,
      AgentId agentId) {
    CompletableFuture.supplyAsync(work, deps.blocking())
        .whenComplete(
            (value, failure) ->
                tell(
                    agentId,
                    failure == null ? answer.apply(value) : broke.apply(describe(failure))));
  }

  /** Painted as it arrives, on the thread draining the stream — narrating is a tell. */
  private void narrateChunk(AgentId agentId, ModelEvent event) {
    switch (event) {
      case ModelEvent.TextChunk chunk ->
          narrator(agentId).narrate(new AgentEvent.TextDelta(Identifiers.next(), chunk.text()));
      case ModelEvent.ReasoningChunk chunk ->
          narrator(agentId)
              .narrate(new AgentEvent.ReasoningDelta(Identifiers.next(), chunk.text()));
      default -> {
        // Assembled into the message, or narrated by whoever owns the fact.
      }
    }
  }

  static String resultKey(String callId) {
    return "result-" + callId;
  }

  private static List<ToolCall> callsIn(List<ExchangeContentBlock> content) {
    return content.stream()
        .filter(ToolCallBlock.class::isInstance)
        .map(block -> ((ToolCallBlock) block).call())
        .toList();
  }

  private static String describe(Throwable failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    String message = cause.getMessage();
    return message == null ? cause.getClass().getSimpleName() : message;
  }

  /**
   * One call's address for the outside world.
   *
   * <p>The same token reaches the approver and the tool, because both settle the same call and two
   * addresses meaning one thing is two things to get wrong.
   */
  private record CallContext(
      AgentType agentType, AgentId agentId, org.jwcarman.nessy.api.tool.ReplyToken replyToken)
      implements ToolContext {}
}
