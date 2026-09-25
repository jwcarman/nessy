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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.inference.ContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceInvocation;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.OutputSchema;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.SystemPrompt;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.ToolName;
import org.jwcarman.nessy.lease.Locks;
import org.jwcarman.nessy.spi.store.PayloadStore;
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

  private final Locks locks;
  private final AgentType agentType;
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final InferenceProvider provider;
  private final Transcript transcript;
  private final SystemPrompt systemPrompt;
  private final InferenceOptions options;
  private final Function<I, List<Block.ObservationContent>> renderer;
  private final Map<ToolName, DirectTool> tools;

  /** How a Java type becomes a schema, and how an answer in that shape becomes the type back. */
  private final InputSchemaGenerator schemas;

  private final ObjectMapper mapper;

  /** What the model is shown, assembled the same way the queued door assembles it. */
  private final InferenceContextAssembler assembler;

  /** Built once: a tool's shape cannot change between calls, so neither can what is on offer. */
  private final Toolset toolset;

  public DefaultDirectHarness(
      Locks locks,
      AgentType agentType,
      AgentEventStore events,
      PayloadStore payloads,
      InferenceProvider provider,
      SystemPrompt systemPrompt,
      InferenceOptions options,
      Function<I, List<Block.ObservationContent>> renderer,
      Map<ToolName, DirectTool> tools,
      InputSchemaGenerator schemas,
      ObjectMapper mapper,
      List<Summarizer> summaries,
      int maxTail,
      List<AmbientSource> ambient) {
    this.locks = locks;
    this.agentType = agentType;
    this.events = events;
    this.payloads = payloads;
    this.provider = provider;
    this.transcript = new Transcript(payloads);
    this.systemPrompt = systemPrompt;
    this.options = options;
    this.renderer = renderer;
    this.tools = Map.copyOf(tools);
    this.schemas = schemas;
    this.mapper = mapper;
    // The queued door's assembler, unchanged. Ambient, the tail window and summaries are one job
    // however the turn was started, and a second implementation of it would drift.
    this.assembler =
        new ContextAssembler(
            (type, id) -> new EventStreamHistory(events, transcript, id),
            summaries,
            maxTail,
            ambient);
    this.toolset = Toolset.of(offersOf(this.tools));
  }

  private static List<ToolOffer> offersOf(Map<ToolName, DirectTool> tools) {
    return tools.entrySet().stream()
        .map(e -> new ToolOffer(e.getKey(), e.getValue().description(), e.getValue().schema()))
        .toList();
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

      // Nothing here can park, so approval is a policy answering now. A desk that needs a person
      // belongs on the queued door, which has somewhere to put the waiting.
      case AgentEffect.Approve approve ->
          new AgentCommand.CompleteApproval(
              approve.callId(), new AgentCommand.ApprovalOutcome.Approved(Optional.empty()));

      case AgentEffect.CallTool call -> {
        DirectTool tool = tools.get(call.toolName());
        if (tool == null) {
          yield new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Failed("no such tool"));
        }
        try {
          // Rendered and claim-checked on the way back, so a result crosses into the core as a
          // reference and never as content.
          yield new AgentCommand.CompleteToolCall(
              call.callId(),
              new AgentCommand.ToolOutcome.Succeeded(
                  payloads.put(tool.call(argumentsOf(call, history)))));
        } catch (RuntimeException broken) {
          // A sentence, because the model is going to read it. Names what went wrong, never the
          // values involved.
          yield new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Failed(broken.getMessage()));
        }
      }
    };
  }

  private AgentCommand.InferenceOutcome infer(AgentId agent, Optional<OutputSchema> shape) {
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt,
            assembler.assemble(new InferenceInvocation(agentType, agent, options)),
            toolset,
            options,
            shape);

    return switch (provider.infer(request)) {
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

  private List<AgentEvent.Requested> requested(List<Block.ActionRequestContent> blocks) {
    return blocks.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(call -> new AgentEvent.Requested(call.id(), call.name()))
        .toList();
  }

  private String argumentsOf(AgentEffect.CallTool call, List<AgentEvent> history) {
    return history.reversed().stream()
        .filter(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .findFirst()
        .map(asked -> resolveCall(asked, call))
        .orElseThrow(() -> new IllegalStateException("no request holds " + call.callId()));
  }

  private String resolveCall(AgentEvent.ActionsRequested asked, AgentEffect.CallTool call) {
    return switch (payloads.get(asked.request())) {
      case PayloadStore.Resolved.Found(List<Block> content) ->
          content.stream()
              .filter(Block.ToolCall.class::isInstance)
              .map(Block.ToolCall.class::cast)
              .filter(tc -> tc.id().equals(call.callId()))
              .findFirst()
              .map(Block.ToolCall::arguments)
              .orElseThrow(() -> new IllegalStateException("no call " + call.callId()));
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

  /** A tool, for a door where nothing can park. */
  public interface DirectTool {
    String description();

    org.jwcarman.nessy.inference.tool.InputSchema schema();

    List<Block.ToolResultContent> call(String arguments);
  }
}
