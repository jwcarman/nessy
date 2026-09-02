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

import com.fasterxml.jackson.databind.JsonNode;
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
import org.apache.pekko.persistence.state.DurableStateStoreRegistry;
import org.apache.pekko.persistence.state.javadsl.DurableStateStore;
import org.apache.pekko.persistence.state.javadsl.DurableStateUpdateStore;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
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
import org.jwcarman.nessy.api.tool.ToolCallRequest;
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

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(Instructions.class);

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

  /**
   * Performs a decision's instructions, IN ORDER, on the blocking executor.
   *
   * <p>One hop for the whole list rather than one per instruction, because the order is load
   * bearing: a turn remembers before it releases, since releasing drops the claims the exchange is
   * written from. Firing each instruction independently would let the release win that race.
   *
   * <p>The hop is why the instructions below may block. They read claims, write the transcript and
   * arm alarms — all JDBC, and none of it belongs on an actor's thread, which is also the thread
   * running sharding and cluster gossip. Instructions that wait on something SLOW (a model, a tool,
   * an approver) hand that off again and return, so one long call never occupies this worker.
   *
   * <p>Returns immediately: the caller is an actor, and an actor waiting on storage is the thing
   * this exists to prevent.
   */
  void performAll(
      AgentId agentId,
      AgentState state,
      List<Instruction> instructions,
      Map<String, String> carried) {
    if (instructions.isEmpty()) {
      return;
    }
    CompletableFuture.runAsync(
            () ->
                instructions.forEach(instruction -> perform(agentId, state, instruction, carried)),
            deps.blocking())
        .exceptionally(
            failure -> {
              // Nothing downstream is waiting on these, so a failure here would otherwise be a
              // silent no-op: the turn simply stops, with no message and no log line.
              LOG.error(
                  "[{}] instructions failed for turn {}", agentId.value(), state.turnId(), failure);
              return null;
            });
  }

  /** Performs one instruction. May block: {@link #performAll} put it on the right thread. */
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
          deps.reminders().cancel(deps.agentType(), agentId, alarm.callId());
      case Instruction.Forget ignored -> forget(agentId);
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
    TurnId finished = state.busy() ? null : state.turnId();
    run(
        () -> deps.backlog().take(agentId, finished),
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
              // Rendered ONCE, here, and used for both the narration and the question. It used to
              // be rendered again from a Narrate instruction, which meant reading the asking claim
              // back to find the call first.
              String action = deps.bindings().actionOf(binding, call.arguments());
              narrator(agentId)
                  .narrate(
                      new AgentEvent.ToolCallRequested(
                          Identifiers.next(), call.id(), call.name(), action));
              ApprovalRequest request =
                  new ApprovalRequest(
                      deps.agentType(),
                      agentId,
                      state.turnId(),
                      call.id(),
                      call.name(),
                      call.arguments(),
                      action,
                      Instant.now(),
                      // Not minted unless somebody asks. An approver that answers on the spot —
                      // and most do — hands the address to nobody.
                      () ->
                          deps.tokens().mint(deps.agentType(), agentId, state.turnId(), call.id()));
              run(
                  () ->
                      deps.traces()
                          .inSpan(
                              "approval " + call.name(),
                              carried,
                              () -> deps.bindings().approve(binding, request)),
                  answer ->
                      switch (answer) {
                        case Awaited.Ready<ApprovalResult> ready -> {
                          // A DENIAL is a result, so it is claimed here like any other — the model
                          // is told it was refused and gets to decide what to do about that. The
                          // logic marks the call completed and cannot write anything itself, so
                          // without this the exchange reaches the transcript saying "no result was
                          // recorded", which reads to the model as a broken tool rather than a
                          // person saying no. Measured in the browser.
                          denialResult(ready.result())
                              .ifPresent(denied -> hold(agentId, state, call.id(), denied));
                          yield new NessyMessage.ApprovalGiven(
                              call.id(), call.name(), ready.result(), carried);
                        }
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
                                      action,
                                      deferred.expiresAt()));
                          yield new NessyMessage.ToolParked(
                              call.id(), deferred.expiresAt(), carried);
                        }
                      },
                  failure -> {
                    ApprovalResult broke = ApprovalResult.denied("the approver failed: " + failure);
                    denialResult(broke)
                        .ifPresent(denied -> hold(agentId, state, call.id(), denied));
                    return new NessyMessage.ApprovalGiven(call.id(), call.name(), broke, carried);
                  },
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
              run(
                  () ->
                      deps.traces()
                          .inSpan(
                              "tool " + call.name(),
                              carried,
                              () -> deps.bindings().run(binding, requestFor(agentId, state, call))),
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

  /** What a denied call answers with, or empty when it was approved and will answer for itself. */
  static Optional<ToolResult> denialResult(ApprovalResult result) {
    if (result instanceof ApprovalResult.Denied denied) {
      return Optional.of(
          ToolResult.error("denied: " + denied.reason() + "; the call was not made"));
    }
    return Optional.empty();
  }

  /** Writes a result and tells the agent — in that order, always. */
  private void completed(AgentId agentId, AgentState state, CallId callId, ToolResult result) {
    hold(agentId, state, callId, result);
    tell(agentId, new NessyMessage.ToolCompleted(callId, Map.of()));
  }

  private void hold(AgentId agentId, AgentState state, CallId callId, ToolResult result) {
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

  /**
   * Erases an agent: everything it remembered, everything waiting for it, everything it held, and
   * finally the record that it existed.
   *
   * <p><b>State last, deliberately.</b> A crash partway through should leave LESS behind rather
   * than an agent whose state is gone but whose transcript is not — a ghost that recovers into
   * emptiness and cannot be found again to clean up. Deleting the state object last means every
   * intermediate failure leaves an agent that is still findable and still forgettable.
   *
   * <p><b>Only ever issued when idle.</b> {@code AgentLogic} holds a busy agent's request until the
   * turn ends, so nothing here races work in flight.
   *
   * <p>The state object goes through Pekko's own store rather than SQL. The durable-state table is
   * not Nessy's — an application picks the plugin and ships the DDL — so deleting by statement
   * would mean knowing a table name from configuration this engine never reads.
   */
  private void forget(AgentId agentId) {
    deps.memory().forget(agentId);
    deps.backlog().deleteAgent(agentId);
    deps.claims().deleteAgent(agentId);
    deleteState(agentId);
    LOG.info("[{}] forgotten", agentId.value());
  }

  private void deleteState(AgentId agentId) {
    String plugin = system.settings().config().getString("pekko.persistence.state.plugin");
    if (plugin.isBlank()) {
      // No durable-state plugin configured means nothing was ever written to delete.
      return;
    }
    DurableStateStore<AgentState> store =
        DurableStateStoreRegistry.get(system)
            .getDurableStateStoreFor(DurableStateStore.class, plugin);
    if (store instanceof DurableStateUpdateStore<AgentState> deletable) {
      String persistenceId = PersistenceId.of(deps.agentType().name(), agentId.value()).id();
      deletable.deleteObject(persistenceId).toCompletableFuture().join();
      return;
    }
    // A read-only store cannot forget. Saying so is better than a silent partial deletion, since
    // "we deleted it" is not a thing to be wrong about.
    LOG.error(
        "[{}] the durable-state plugin \"{}\" cannot delete, so this agent's state survives being"
            + " forgotten",
        agentId.value(),
        plugin);
  }

  private void setAlarm(AgentId agentId, Instruction.SetAlarm alarm) {
    deps.reminders().remind(deps.agentType(), agentId, alarm.callId(), alarm.expiresAt());
  }

  private void narrate(AgentId agentId, AgentState state, Instruction.Narrate narrate) {
    switch (narrate) {
      case Instruction.Narrate.TurnStarted ignored ->
          narrator(agentId).narrate(new AgentEvent.TurnStarted(Identifiers.next()));
      case Instruction.Narrate.TurnEnded ended ->
          narrator(agentId)
              .narrate(new AgentEvent.TurnEnded(Identifiers.next(), ended.result(), ended.usage()));
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

  private String nameOf(AgentId agentId, AgentState state, CallId callId) {
    ToolCall call = callOf(agentId, state, callId);
    return call == null ? "" : call.name();
  }

  private ToolCall callOf(AgentId agentId, AgentState state, CallId callId) {
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

  /**
   * Everything anyone answering this call needs, built once per ask or run.
   *
   * <p>One record rather than the two context objects this replaced: a tool and an approver were
   * handed different views of the same call, so the pair had to be kept in step and an approver
   * could not see what the tool would be given.
   */
  private ToolCallRequest<JsonNode> requestFor(AgentId agentId, AgentState state, ToolCall call) {
    return new ToolCallRequest(
        deps.agentType(),
        agentId,
        state.turnId(),
        call.id(),
        call.name(),
        call.arguments(),
        // Not minted here: a token is a capability, and most calls are answered on the spot and
        // never hand one out. ToolCallRequest mints on the first replyToken() and remembers it.
        () -> deps.tokens().mint(deps.agentType(), agentId, state.turnId(), call.id()));
  }

  static String resultKey(CallId callId) {
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
}
