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
package org.jwcarman.nessy.engine.direct;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.inference.ContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceInvocation;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.OutputSchema;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.Toolset;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.narration.AgentNarrator;
import org.jwcarman.nessy.spi.store.PayloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * A turn, run on the calling thread.
 *
 * <p>The whole harness is the loop in {@link #ask}: hand the state a command, keep the facts it
 * produced, run whatever work came back, and turn each outcome into the next command. What a queued
 * harness spreads across a transaction, an outbox and a poller happens here between two statements,
 * and {@link AgentState} cannot tell the difference.
 *
 * <p>Note what is absent: no backlog, no coalescing, no claims, no leases, no deferral. Not
 * forbidden -- nothing in this world can produce them.
 */
public final class DefaultDirectHarness<I> implements DirectHarness<I> {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultDirectHarness.class);

  private final Locks locks;
  private final AgentType agentType;
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final InferenceProvider provider;
  private final Transcript transcript;
  private final SystemPromptSource systemPrompt;
  private final InferenceOptions options;
  private final Function<I, List<Block.ObservationContent>> renderer;
  private final Tools tools;

  /** How a Java type becomes a schema, and how an answer in that shape becomes the type back. */
  private final InputSchemaGenerator schemas;

  private final ObjectMapper mapper;

  /** What the model is shown, assembled the same way the queued door assembles it. */
  private final InferenceContextAssembler assembler;

  /**
   * Who is watching, told on the calling thread.
   *
   * <p>Synchronously and in order, which the queued door cannot do: there it hands narration to a
   * thread of its own so a slow listener never delays an agent. Here the caller IS the thing that
   * would be delayed, and it is already waiting -- so a listener that blocks blocks the turn it is
   * watching, which is the honest arrangement when somebody is holding the answer.
   */
  private final List<AgentEventListener> listeners;

  /** Built once: a tool's shape cannot change between calls, so neither can what is on offer. */
  private final Toolset toolset;

  public DefaultDirectHarness(
      Locks locks,
      AgentType agentType,
      AgentEventStore events,
      PayloadStore payloads,
      InferenceProvider provider,
      SystemPromptSource systemPrompt,
      InferenceOptions options,
      Function<I, List<Block.ObservationContent>> renderer,
      Tools tools,
      InputSchemaGenerator schemas,
      ObjectMapper mapper,
      List<Summarizer> summaries,
      int maxTail,
      List<AmbientSource> ambient,
      List<AgentEventListener> listeners) {
    this.locks = locks;
    this.agentType = agentType;
    this.events = events;
    this.payloads = payloads;
    this.provider = provider;
    this.transcript = new Transcript(payloads);
    this.systemPrompt = systemPrompt;
    this.options = options;
    this.renderer = renderer;
    this.tools = tools;
    this.schemas = schemas;
    this.mapper = mapper;
    // The queued door's assembler, unchanged. Ambient, the tail window and summaries are one job
    // however the turn was started, and a second implementation of it would drift.
    this.listeners = List.copyOf(listeners);
    this.assembler =
        new ContextAssembler(
            (type, id) -> new EventStreamHistory(events, transcript, id),
            summaries,
            maxTail,
            ambient);
    this.toolset = Toolset.of(tools.offers());
  }

  @Override
  public Outcome<String> ask(AgentId agent, I input) {
    return under(agent, () -> runTurn(agent, input, Optional.empty(), this::saidText));
  }

  @Override
  public <T> Outcome<T> ask(AgentId agent, I input, TypeRef<T> type) {
    Objects.requireNonNull(type, "type must not be null");
    // The same generator the tools use: turning a Java type into a JSON schema is one job, and a
    // provider constrains an answer with the same kind of document it constrains an argument with.
    OutputSchema shape = new OutputSchema(schemas.generate(type.rawClass()).json());
    return under(
        agent, () -> runTurn(agent, input, Optional.of(shape), answered -> read(answered, type)));
  }

  /** One turn at a time per agent, whatever shape was asked for. */
  private <T> Outcome<T> under(AgentId agent, Supplier<Outcome<T>> turn) {
    // Two callers asking at once used to race on that agent's stream and find out at the append;
    // now the second is told the agent is busy and nothing it did has to be undone. expectedLast
    // still guards the append, because a lease can expire under a holder that is merely slow --
    // this stops two callers, that stops two writers.
    return locks.tryWithLock(agent.value().toString(), turn).orElse(new Outcome.Busy<>());
  }

  private <T> Outcome<T> runTurn(
      AgentId agent,
      I input,
      Optional<OutputSchema> shape,
      Function<AgentEvent.InferenceAnswered, Outcome<T>> reading) {
    // TWO READS, and they are not the same read.
    //
    // The state is rebuilt from the watermark alone -- the latest turn, and nothing before it.
    // That is what the watermark is for: reconstitution costs one turn's events however long this
    // agent has lived, so a conversation of a thousand turns rebuilds as fast as its first.
    //
    // The transcript is a different question. What the model is shown is the conversation, which
    // is every turn before this one as well, so it takes its own read. Using the watermarked read
    // for both is what made a second ask forget the first.
    Seq watermark = events.watermark(agent);
    AgentState state = AgentState.idle(watermark).applyAll(events.readFrom(agent, watermark));

    // TODO: unwindowed. ContextConfig.maxTail is the knob this should hang off; until it does, a
    // long conversation sends the model all of it.
    List<AgentEvent> history = new ArrayList<>(events.readFrom(agent, Seq.NONE));

    // Claim-checked before it reaches the core, which never sees I and never sees blocks.
    Deque<AgentCommand> pending = new ArrayDeque<>();
    pending.add(new AgentCommand.StartTurn(payloads.put(renderer.apply(input))));

    while (!pending.isEmpty()) {
      Decision decision = state.execute(pending.poll());

      // expectedLast is vacuous here -- nothing else writes this agent -- and load-bearing for a
      // queued harness using the same seam. One signature rather than two.
      events.append(agent, decision.events(), state.seq());
      history.addAll(decision.events());
      state = state.applyAll(decision.events());

      for (AgentEffect effect : decision.effects()) {
        pending.add(perform(agent, effect, history, shape));
      }
    }

    // A closed turn is folded and will not change, so replay starts after it next time.
    events.watermark(agent, state.seq());
    return outcome(history, reading);
  }

  @Override
  public void terminate(AgentId agent) {
    // Under the same lock as a turn, because the core only takes Terminate from Idle: asking
    // while a turn is running would be declined silently, and waiting for the turn is not this
    // door's habit. A caller that was refused the lock asks again once the turn it saw has ended.
    locks.tryWithLock(
        agent.value().toString(),
        () -> {
          Seq watermark = events.watermark(agent);
          AgentState state = AgentState.idle(watermark).applyAll(events.readFrom(agent, watermark));
          Decision decision = state.execute(new AgentCommand.Terminate());
          events.append(agent, decision.events(), state.seq());
        });
  }

  /** Where the outside world happens: everything slow, everything non-deterministic. */
  private AgentCommand perform(
      AgentId agent, AgentEffect effect, List<AgentEvent> history, Optional<OutputSchema> shape) {
    return switch (effect) {
      case AgentEffect.Infer _ -> new AgentCommand.CompleteInference(infer(agent, shape));

      case AgentEffect.Approve approve -> approve(agent, approve, history);

      case AgentEffect.CallTool call -> callTool(agent, call, history);
    };
  }

  /**
   * Asks whoever guards this tool, now.
   *
   * <p>A person at a terminal is the case this door serves best: they are already waiting on the
   * answer, so asking them costs nothing that was not already being spent. What cannot cross is an
   * approver that parks -- a desk that replies tomorrow has nowhere to put the waiting here, so it
   * is a denial with a reason rather than a turn that never ends.
   */
  private AgentCommand approve(
      AgentId agent, AgentEffect.Approve approve, List<AgentEvent> history) {
    ToolBinding<?> binding = tools.find(approve.toolName()).orElse(null);
    if (binding == null) {
      return new AgentCommand.CompleteApproval(
          approve.callId(),
          new AgentCommand.ApprovalOutcome.Denied("no such tool", Optional.empty()));
    }
    ApprovalRequest question =
        binding.question(
            agentType,
            agent,
            turnOf(history),
            approve.callId(),
            argumentsOf(approve.callId(), history),
            Instant.now(),
            new ReplyToken(approve.callId().value()));
    return switch (binding.approve(question)) {
      case Awaited.Ready(ApprovalResult result) ->
          switch (result) {
            case ApprovalResult.Approved(var reference) ->
                new AgentCommand.CompleteApproval(
                    approve.callId(), new AgentCommand.ApprovalOutcome.Approved(reference));
            case ApprovalResult.Denied(String reason, var reference) ->
                new AgentCommand.CompleteApproval(
                    approve.callId(), new AgentCommand.ApprovalOutcome.Denied(reason, reference));
          };
      case Awaited.Deferred<ApprovalResult> _ ->
          new AgentCommand.CompleteApproval(
              approve.callId(),
              new AgentCommand.ApprovalOutcome.Denied(
                  "approval was deferred, and nothing here can wait for it", Optional.empty()));
    };
  }

  private AgentCommand callTool(
      AgentId agent, AgentEffect.CallTool call, List<AgentEvent> history) {
    ToolBinding<?> binding = tools.find(call.toolName()).orElse(null);
    if (binding == null) {
      return new AgentCommand.CompleteToolCall(
          call.callId(), new AgentCommand.ToolOutcome.Failed("no such tool"));
    }
    try {
      return switch (binding.call(
          agentType,
          agent,
          turnOf(history),
          call.callId(),
          call.toolName(),
          argumentsOf(call.callId(), history),
          Instant.now().plus(binding.timeout()),
          new ReplyToken(call.callId().value()))) {
        case Awaited.Ready(ToolResult result) -> completed(call, result);
        // A tool that wants to answer later has nowhere to put the answer on this door.
        case Awaited.Deferred<ToolResult> _ ->
            new AgentCommand.CompleteToolCall(
                call.callId(),
                new AgentCommand.ToolOutcome.Failed(
                    "the tool deferred, and nothing here can wait for it"));
      };
    } catch (RuntimeException broken) {
      // A sentence, because the model is going to read it. Names what went wrong, never the
      // values involved.
      return new AgentCommand.CompleteToolCall(
          call.callId(), new AgentCommand.ToolOutcome.Failed(broken.getMessage()));
    }
  }

  private AgentCommand completed(AgentEffect.CallTool call, ToolResult result) {
    return switch (result) {
      // Claim-checked on the way back, so a result crosses into the core as a reference and never
      // as content.
      case ToolResult.Success(var blocks) ->
          new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Succeeded(payloads.put(blocks)));
      case ToolResult.Failure(String message) ->
          new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Failed(message));
    };
  }

  /** The turn these events belong to: the last one started. */
  private TurnId turnOf(List<AgentEvent> history) {
    return history.reversed().stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .map(AgentEvent.TurnStarted.class::cast)
        .findFirst()
        .map(AgentEvent.TurnStarted::turn)
        .orElseThrow(() -> new IllegalStateException("a call outside any turn"));
  }

  private AgentCommand.InferenceOutcome infer(AgentId agent, Optional<OutputSchema> shape) {
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt.forAgent(agent),
            assembler.assemble(new InferenceInvocation(agentType, agent, options)),
            toolset,
            options,
            shape);

    return switch (provider.infer(request, narratorFor(agent))) {
      case InferenceResult.Answer(List<Block.AnswerContent> blocks, var _) ->
          new AgentCommand.InferenceOutcome.Answered(payloads.put(blocks));
      case InferenceResult.Refusal(String category, var _) ->
          new AgentCommand.InferenceOutcome.Refused(category);
      case InferenceResult.Fault(var failure, var _) ->
          new AgentCommand.InferenceOutcome.Failed(failure);
      case InferenceResult.Actions(List<Block.ActionRequestContent> blocks, var _) ->
          new AgentCommand.InferenceOutcome.RequestedActions(
              payloads.put(blocks), requested(blocks));
    };
  }

  /**
   * What a provider says while it is still saying it, turned into events for whoever is watching.
   *
   * <p>Best-effort by contract: a listener that throws is not allowed to fail a turn the caller is
   * waiting on, and a watcher that misses every fragment still gets the answer.
   */
  private InferenceNarrator narratorFor(AgentId agent) {
    if (listeners.isEmpty()) {
      return InferenceNarrator.silent();
    }
    AgentNarrator narrator = event -> tell(agent, event);
    return narrator.forInference();
  }

  private void tell(AgentId agent, org.jwcarman.nessy.api.AgentEvent event) {
    for (AgentEventListener listener : listeners) {
      try {
        listener.on(agentType, agent, event);
      } catch (RuntimeException broken) {
        LOG.warn("a listener threw while being told {}", event.getClass().getSimpleName(), broken);
      }
    }
  }

  private List<AgentEvent.Requested> requested(List<Block.ActionRequestContent> blocks) {
    return blocks.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(call -> new AgentEvent.Requested(call.id(), call.name()))
        .toList();
  }

  private String argumentsOf(CallId callId, List<AgentEvent> history) {
    return history.reversed().stream()
        .filter(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .findFirst()
        .map(asked -> resolveCall(asked, callId))
        .orElseThrow(() -> new IllegalStateException("no request holds " + callId));
  }

  private String resolveCall(AgentEvent.ActionsRequested asked, CallId callId) {
    return switch (payloads.get(asked.request())) {
      case PayloadStore.Resolved.Found(List<Block> content) ->
          content.stream()
              .filter(Block.ToolCall.class::isInstance)
              .map(Block.ToolCall.class::cast)
              .filter(tc -> tc.id().equals(callId))
              .findFirst()
              .map(Block.ToolCall::arguments)
              .orElseThrow(() -> new IllegalStateException("no call " + callId));
      case PayloadStore.Resolved.Missing _ ->
          throw new IllegalStateException("no payload behind " + asked.request());
    };
  }

  private <T> Outcome<T> outcome(
      List<AgentEvent> history, Function<AgentEvent.InferenceAnswered, Outcome<T>> reading) {
    return history.reversed().stream()
        .map(event -> asOutcome(event, reading))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("a turn that ended without ending"));
  }

  private <T> Outcome<T> asOutcome(
      AgentEvent event, Function<AgentEvent.InferenceAnswered, Outcome<T>> reading) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered -> reading.apply(answered);
      case AgentEvent.InferenceRefused refused -> new Outcome.Refused<>(refused.category());
      case AgentEvent.InferenceFailed failed -> new Outcome.Failed<>(failed.failure().reason());
      default -> null;
    };
  }

  /** What a caller who asked for no particular shape gets: whatever the model said. */
  private Outcome<String> saidText(AgentEvent.InferenceAnswered answered) {
    return new Outcome.Answered<>(textOf(answered));
  }

  /**
   * The answer, parsed into the shape it was asked for.
   *
   * <p>A model that answered around the schema fails the turn rather than handing back something
   * that does not fit -- which is the whole reason a caller asked for a shape instead of prose.
   */
  private <T> Outcome<T> read(AgentEvent.InferenceAnswered answered, TypeRef<T> type) {
    String json = textOf(answered);
    try {
      return new Outcome.Answered<>(mapper.readValue(json, mapper.constructType(type.getType())));
    } catch (RuntimeException notTheShape) {
      return new Outcome.Failed<>(
          "the answer did not fit " + type + ": " + notTheShape.getMessage());
    }
  }

  private String textOf(AgentEvent.InferenceAnswered answered) {
    return switch (payloads.get(answered.answer())) {
      case PayloadStore.Resolved.Found(List<Block> content) ->
          content.stream()
              .filter(Block.Text.class::isInstance)
              .map(Block.Text.class::cast)
              .map(Block.Text::text)
              .reduce("", String::concat);
      case PayloadStore.Resolved.Missing _ -> "";
    };
  }
}
