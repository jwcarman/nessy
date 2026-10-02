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
package org.jwcarman.nessy.engine.core;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * A proof of concept: the whole of a turn, driven on one thread, with nothing written down.
 *
 * <p>Everything the durable harness does with a database this does with a field. The events are an
 * {@link ArrayList}; the claim check is a {@link HashMap}; the "outbox" is a {@link Deque} that
 * never outlives the call. {@link AgentState} cannot tell the difference, which is the point.
 *
 * <p>Note what is <b>not</b> here: no backlog (nothing can queue work for an agent that exists for
 * one call), no coalescing, no claims, no leases, no deferral, and no {@code Terminate} (the loop
 * ends when the turn does). Those are not forbidden -- there is simply nothing in this world that
 * could produce them.
 */
final class InlineRunner {

  /** Stands in for the model. A POC, so the script is a list rather than a provider. */
  interface Model {
    AgentCommand.InferenceOutcome answer(List<AgentEvent> soFar);
  }

  private final Model model;
  private final Map<ToolName, UnaryOperator<Object>> tools;
  private final java.util.function.Predicate<ToolName> approves;

  /** The claim check. A map, because nothing here outlives the call. */
  private final Map<PayloadRef, Object> payloads = new HashMap<>();

  private final AtomicLong refs = new AtomicLong();
  private final AtomicLong calls = new AtomicLong();

  InlineRunner(
      Model model,
      Map<ToolName, UnaryOperator<Object>> tools,
      java.util.function.Predicate<ToolName> approves) {
    this.model = model;
    this.tools = Map.copyOf(tools);
    this.approves = approves;
  }

  /** What a turn came to, and everything it recorded on the way. */
  record Ran(AgentState state, List<AgentEvent> events, Object answer) {}

  /**
   * One turn, start to finish, on this thread.
   *
   * <p>The loop is the whole harness: hand the state a command, keep the facts, run whatever work
   * came back, and turn each outcome into the next command. What the durable harness spreads across
   * a transaction, an outbox and a poller happens here between two lines.
   */
  Ran run(Object question) {
    List<AgentEvent> events = new ArrayList<>();
    AgentState state = AgentState.idle(Seq.NONE);

    Deque<AgentCommand> pending = new ArrayDeque<>();
    pending.add(new AgentCommand.StartTurn(claimCheck(question), Instant.EPOCH));

    while (!pending.isEmpty()) {
      Decision decision = state.execute(pending.poll());

      events.addAll(decision.events());
      state = state.applyAll(decision.events());

      for (AgentEffect effect : decision.effects()) {
        pending.add(perform(effect, events));
      }
    }

    return new Ran(state, List.copyOf(events), answerIn(events));
  }

  /** Where the outside world happens. Everything slow, everything non-deterministic. */
  private AgentCommand perform(AgentEffect effect, List<AgentEvent> soFar) {
    return switch (effect) {
      case AgentEffect.Infer infer ->
          new AgentCommand.CompleteInference(infer.turn(), model.answer(soFar));

      case AgentEffect.Approve approve ->
          new AgentCommand.CompleteApproval(
              approve.turn(),
              approve.requestSeq(),
              approve.callId(),
              approves.test(approve.toolName())
                  ? new AgentCommand.ApprovalOutcome.Approved(Optional.empty())
                  : new AgentCommand.ApprovalOutcome.Denied("not allowed here", Optional.empty()));

      case AgentEffect.CallTool call -> {
        UnaryOperator<Object> tool = tools.get(call.toolName());
        if (tool == null) {
          yield new AgentCommand.CompleteToolCall(
              call.turn(),
              call.requestSeq(),
              call.callId(),
              new AgentCommand.ToolOutcome.Failed("no such tool"));
        }
        try {
          // Rendered and claim-checked on the way back, exactly as the durable harness would:
          // what crosses into the state is a reference, never the result.
          yield new AgentCommand.CompleteToolCall(
              call.turn(),
              call.requestSeq(),
              call.callId(),
              new AgentCommand.ToolOutcome.Succeeded(
                  claimCheck(tool.apply(argumentsFor())), "a tool result"));
        } catch (RuntimeException broken) {
          yield new AgentCommand.CompleteToolCall(
              call.turn(),
              call.requestSeq(),
              call.callId(),
              new AgentCommand.ToolOutcome.Failed(broken.getMessage()));
        }
      }
    };
  }

  /** A POC: the tool is handed the question, which is enough to watch a value flow through. */
  private Object argumentsFor() {
    return payloads.values().iterator().next();
  }

  PayloadRef claimCheck(Object content) {
    PayloadRef ref = PayloadRef.of("ref-" + refs.incrementAndGet());
    payloads.put(ref, content);
    return ref;
  }

  Object resolve(PayloadRef ref) {
    return payloads.get(ref);
  }

  CallId nextCallId() {
    return new CallId("call-" + calls.incrementAndGet());
  }

  private Object answerIn(List<AgentEvent> events) {
    return events.stream()
        .filter(AgentEvent.InferenceAnswered.class::isInstance)
        .map(AgentEvent.InferenceAnswered.class::cast)
        .map(answered -> resolve(answered.answer()))
        .findFirst()
        .orElse(null);
  }
}
