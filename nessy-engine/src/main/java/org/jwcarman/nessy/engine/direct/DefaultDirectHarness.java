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
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ScopeId;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.SystemPrompt;
import org.jwcarman.nessy.inference.ToolOffer;
import org.jwcarman.nessy.inference.Toolset;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.ToolName;
import org.jwcarman.nessy.lease.Locks;
import org.jwcarman.nessy.spi.store.PayloadStore;

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
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final InferenceProvider provider;
  private final Transcript transcript;
  private final SystemPrompt systemPrompt;
  private final InferenceOptions options;
  private final Function<I, List<Block.ObservationContent>> renderer;
  private final Map<ToolName, DirectTool> tools;

  /** Built once: a tool's shape cannot change between calls, so neither can what is on offer. */
  private final Toolset toolset;

  public DefaultDirectHarness(
      Locks locks,
      AgentEventStore events,
      PayloadStore payloads,
      InferenceProvider provider,
      SystemPrompt systemPrompt,
      InferenceOptions options,
      Function<I, List<Block.ObservationContent>> renderer,
      Map<ToolName, DirectTool> tools) {
    this.locks = locks;
    this.events = events;
    this.payloads = payloads;
    this.provider = provider;
    this.transcript = new Transcript(payloads);
    this.systemPrompt = systemPrompt;
    this.options = options;
    this.renderer = renderer;
    this.tools = Map.copyOf(tools);
    this.toolset = Toolset.of(offersOf(this.tools));
  }

  private static List<ToolOffer> offersOf(Map<ToolName, DirectTool> tools) {
    return tools.entrySet().stream()
        .map(e -> new ToolOffer(e.getKey(), e.getValue().description(), e.getValue().schema()))
        .toList();
  }

  @Override
  public Outcome ask(ScopeId scope, I input) {
    // One turn at a time per scope. Two callers asking at once used to race on the scope's stream
    // and find out at the append; now the second is told the scope is busy and nothing it did has
    // to be undone. expectedLast still guards the append, because a lease can expire under a
    // holder that is merely slow -- this stops two callers, that stops two writers.
    return locks.tryWithLock(scope.value(), () -> runTurn(scope, input)).orElse(new Outcome.Busy());
  }

  private Outcome runTurn(ScopeId scope, I input) {
    // TWO READS, and they are not the same read.
    //
    // The state is rebuilt from the watermark alone -- the latest turn, and nothing before it.
    // That is what the watermark is for: reconstitution costs one turn's events however long this
    // scope has lived, so a conversation of a thousand turns rebuilds as fast as its first.
    //
    // The transcript is a different question. What the model is shown is the conversation, which
    // is every turn before this one as well, so it takes its own read. Using the watermarked read
    // for both is what made a second ask forget the first.
    Seq watermark = events.watermark(scope);
    AgentState state = AgentState.idle(watermark).applyAll(events.readFrom(scope, watermark));

    // TODO: unwindowed. ContextConfig.maxTail is the knob this should hang off; until it does, a
    // long conversation sends the model all of it.
    List<AgentEvent> history = new ArrayList<>(events.readFrom(scope, Seq.NONE));

    // Claim-checked before it reaches the core, which never sees I and never sees blocks.
    Deque<AgentCommand> pending = new ArrayDeque<>();
    pending.add(new AgentCommand.StartTurn(payloads.put(renderer.apply(input))));

    while (!pending.isEmpty()) {
      Decision decision = state.execute(pending.poll());

      // expectedLast is vacuous here -- nothing else writes this scope -- and load-bearing for a
      // queued harness using the same seam. One signature rather than two.
      events.append(scope, decision.events(), state.seq());
      history.addAll(decision.events());
      state = state.applyAll(decision.events());

      for (AgentEffect effect : decision.effects()) {
        pending.add(perform(effect, history));
      }
    }

    // A closed turn is folded and will not change, so replay starts after it next time.
    events.watermark(scope, state.seq());
    return outcome(history);
  }

  @Override
  public void terminate(ScopeId scope) {
    // Under the same lock as a turn, because the core only takes Terminate from Idle: asking
    // while a turn is running would be declined silently, and waiting for the turn is not this
    // door's habit. A caller that was refused the lock asks again once the turn it saw has ended.
    locks.tryWithLock(
        scope.value(),
        () -> {
          Seq watermark = events.watermark(scope);
          AgentState state = AgentState.idle(watermark).applyAll(events.readFrom(scope, watermark));
          Decision decision = state.execute(new AgentCommand.Terminate());
          events.append(scope, decision.events(), state.seq());
        });
  }

  /** Where the outside world happens: everything slow, everything non-deterministic. */
  private AgentCommand perform(AgentEffect effect, List<AgentEvent> history) {
    return switch (effect) {
      case AgentEffect.Infer _ -> new AgentCommand.CompleteInference(infer(history));

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

  private AgentCommand.InferenceOutcome infer(List<AgentEvent> history) {
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt, InferenceContext.of(transcript.of(history)), toolset, options);

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

  private Outcome outcome(List<AgentEvent> history) {
    return history.reversed().stream()
        .map(this::asOutcome)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("a turn that ended without ending"));
  }

  private Outcome asOutcome(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered -> new Outcome.Answered(textOf(answered));
      case AgentEvent.InferenceRefused refused -> new Outcome.Refused(refused.category());
      case AgentEvent.InferenceFailed failed -> new Outcome.Failed(failed.failure().reason());
      default -> null;
    };
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
