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

package org.jwcarman.nessy.engine.harness;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Backlog;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.Pull;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.store.JdbcAgents;
import org.jwcarman.nessy.engine.store.JdbcBacklog;
import org.jwcarman.nessy.engine.trace.Traces;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.nessy.spi.store.PayloadStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A harness for work nobody is waiting on, and the callback its own effects report through.
 *
 * <p>The same fold the direct door runs. What is different is everything around it: a backlog in
 * front, an outbox behind, and a row lock holding the agent still while both are written.
 *
 * <p><b>Nothing here holds a lock while a model is called.</b> A transaction takes the agent, folds
 * a command, writes the events and the effects it decided on, and commits. Performing those effects
 * happens afterwards and elsewhere; the answer comes back through {@link #deliverOutcome} as a
 * second transaction. That is the whole reason this door exists and the whole reason it can wait
 * hours for a person to approve something.
 *
 * <p><b>Transactions are explicit.</b> A {@link TransactionTemplate} rather than
 * {@code @Transactional}, because the annotation only works through a Spring proxy and nothing
 * makes a harness a bean. The failure mode there is a successful write with no transaction, silent
 * until a crash lands between the events and the outbox.
 *
 * @param <O> the observation type
 */
final class DefaultQueuedHarness<O>
    implements QueuedHarness<O>, AgentEffectCallback, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultQueuedHarness.class);

  private final AgentType agentType;
  private final BacklogPolicy<O> policy;
  private final ObservationRenderer<O> renderer;
  private final JdbcAgents agents;
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final Backlogs<O> backlogs;
  private final EffectStore effects;
  private final TransactionTemplate transactions;
  private final Narrator narrator;
  private final Clock clock;
  private final Traces traces;

  private EffectDispatcher dispatcher;

  /** Makes this agent type's backlog for one agent, inside the transaction that holds its row. */
  @FunctionalInterface
  interface Backlogs<O> {
    JdbcBacklog<O> forAgent(AgentType agentType, AgentId agent);
  }

  DefaultQueuedHarness(
      AgentType agentType,
      BacklogPolicy<O> policy,
      ObservationRenderer<O> renderer,
      JdbcAgents agents,
      AgentEventStore events,
      PayloadStore payloads,
      Backlogs<O> backlogs,
      EffectStore effects,
      TransactionTemplate transactions,
      Narrator narrator,
      Clock clock,
      Traces traces) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.agents = Objects.requireNonNull(agents, "agents must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
    this.backlogs = Objects.requireNonNull(backlogs, "backlogs must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
  }

  void dispatchWith(EffectDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Override
  public void close() {
    if (dispatcher != null) {
      dispatcher.close();
    }
  }

  /**
   * Admits an observation.
   *
   * <p>Always accepts, which is the promise this door keeps -- unless the agent has ended, which is
   * the one thing that can refuse. What happens to the arrival is the policy's business: appended,
   * replacing what was waiting, or dropped to hold a bound.
   *
   * <p>Then, if the agent is idle, the arrival becomes a turn in this same transaction. An agent is
   * never left idle with work waiting.
   */
  @Override
  public void observe(AgentId agentId, O observation) {
    BacklogItem<O> arrival = new BacklogItem<>(observation, clock.instant());
    log.debug("[{}] observing for agent {}", agentType.value(), agentId.value());
    traces.in(
        "nessy.observe",
        new Identity(agentType, agentId),
        () -> {
          String trace = traces.capture();
          boolean nudge =
              Boolean.TRUE.equals(
                  transactions.execute(
                      _ -> {
                        if (agents.lock(agentType, agentId)) {
                          // Ended. Coalescing now would put something into an emptied backlog and
                          // be read as work next time, undoing a termination that has happened.
                          log.debug(
                              "[{}] agent {} has ended; the observation is refused",
                              agentType.value(),
                              agentId.value());
                          return false;
                        }
                        Backlog<O> backlog = backlogs.forAgent(agentType, agentId);
                        policy.coalesce(backlog, arrival);
                        return driveIfIdle(agentId, backlog, trace);
                      }));
          if (nudge) {
            dispatch();
          }
          return null;
        });
  }

  /**
   * Ends an agent.
   *
   * <p>It cannot be delivered to one mid-turn, because the fold takes it only from idle. So the
   * backlog is emptied and the agent marked, and the next time it is idle and asks for work, ending
   * is the work.
   */
  @Override
  public void terminate(AgentId agentId) {
    log.info("[{}] terminating agent {}", agentType.value(), agentId.value());
    String trace = traces.capture();
    boolean nudge =
        Boolean.TRUE.equals(
            transactions.execute(
                _ -> {
                  agents.lock(agentType, agentId);
                  JdbcBacklog<O> backlog = backlogs.forAgent(agentType, agentId);
                  int abandoned = backlog.seal();
                  if (abandoned > 0) {
                    log.info(
                        "[{}] agent {} ended with {} observation(s) waiting; abandoned",
                        agentType.value(),
                        agentId.value(),
                        abandoned);
                  }
                  return driveIfIdle(agentId, backlog, trace);
                }));
    if (nudge) {
      dispatch();
    }
  }

  /** What an effect came to, folded in a transaction of its own. */
  @Override
  public void deliverOutcome(AgentId agentId, EffectOutcome outcome, String traceContext) {
    log.debug(
        "[{}] delivering {} to agent {}",
        agentType.value(),
        outcome.getClass().getSimpleName(),
        agentId.value());
    boolean nudge =
        Boolean.TRUE.equals(
            transactions.execute(
                _ -> {
                  agents.lock(agentType, agentId);
                  boolean wrote = apply(agentId, command(outcome), traceContext);
                  // A turn that ended leaves the agent idle, and the next thing waiting becomes
                  // the next turn -- here, before this transaction commits.
                  return wrote
                      | driveIfIdle(agentId, backlogs.forAgent(agentType, agentId), traceContext);
                }));
    if (nudge) {
      dispatch();
    }
  }

  /**
   * Takes the next thing waiting, if the agent has nothing else to do.
   *
   * <p>Where an agent comes out of idle, and it happens inside the transaction that made it idle --
   * so at every commit an agent is busy, its backlog is empty, or it has ended. Never idle with
   * work waiting.
   *
   * @return whether anything was written that an effect dispatcher should be told about
   */
  private boolean driveIfIdle(AgentId agentId, Backlog<O> backlog, String trace) {
    if (!(reconstitute(agentId) instanceof AgentState.Idle)) {
      return false;
    }
    return switch (backlog.take()) {
      // Claim-checked here, and this is the only place a harness does it by hand: an arrival is
      // the application's own object, not an outcome, and no executor produced it.
      case Pull.Item<O>(BacklogItem<O> next) ->
          apply(
              agentId,
              new AgentCommand.StartTurn(
                  payloads.forAgent(agentId).put(renderer.render(next.observation()))),
              trace);
      case Pull.Pill<O> _ -> apply(agentId, new AgentCommand.Terminate(), trace);
      case Pull.Empty<O> _ -> false;
    };
  }

  /** Folds one command and writes what it decided. */
  private boolean apply(AgentId agentId, AgentCommand command, String trace) {
    AgentState state = reconstitute(agentId);
    if (!(state.execute(command) instanceof Decision.Advance advance)) {
      // Ignore writes nothing at all. A record showing something happening when nothing did is
      // worse than no record.
      log.debug(
          "[{}] agent {}: ignoring {}",
          agentType.value(),
          agentId.value(),
          command.getClass().getSimpleName());
      return false;
    }
    events.append(agentId, advance.events(), state.seq());
    for (AgentEffect effect : advance.effects()) {
      effects.insert(agentId, effect, clock.instant(), trace);
    }
    advance.events().forEach(event -> narrate(agentId, event));
    return !advance.effects().isEmpty();
  }

  /** The agent as it stands: the last turn that started, replayed onto idle. */
  private AgentState reconstitute(AgentId agentId) {
    List<AgentEvent> lastTurn = events.sinceLastTurnStarted(agentId);
    Seq from =
        lastTurn.isEmpty() ? Seq.NONE : new Seq(Math.max(0, lastTurn.getFirst().seq().value() - 1));
    return AgentState.idle(from).applyAll(lastTurn);
  }

  /** Committed rows are there to be claimed, so ask now rather than wait out a poll. */
  private void dispatch() {
    if (dispatcher != null) {
      dispatcher.nudge();
    }
  }

  /** An outcome, as the command it becomes. Nothing is unpacked: it already holds references. */
  private static AgentCommand command(EffectOutcome outcome) {
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered(var answer) ->
          new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Answered(answer));
      case EffectOutcome.InferenceRefused(String category) ->
          new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Refused(category));
      case EffectOutcome.InferenceFailed(var failure) ->
          new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Failed(failure));
      case EffectOutcome.InferenceRequestedActions(var request, var calls) ->
          new AgentCommand.CompleteInference(
              new AgentCommand.InferenceOutcome.RequestedActions(request, calls));
      case EffectOutcome.ToolSucceeded(var callId, var result) ->
          new AgentCommand.CompleteToolCall(callId, new AgentCommand.ToolOutcome.Succeeded(result));
      case EffectOutcome.ToolFailed(var callId, String message) ->
          new AgentCommand.CompleteToolCall(callId, new AgentCommand.ToolOutcome.Failed(message));
      case EffectOutcome.ToolApproved(var callId, var reference) ->
          new AgentCommand.CompleteApproval(
              callId, new AgentCommand.ApprovalOutcome.Approved(reference));
      case EffectOutcome.ToolDenied(var callId, String reason, var reference) ->
          new AgentCommand.CompleteApproval(
              callId, new AgentCommand.ApprovalOutcome.Denied(reason, reference));
    };
  }

  /**
   * What just became true, told to whoever is watching.
   *
   * <p>After the append and inside the transaction that made it true. A watcher told about a fold a
   * rollback could still undo would be told something untrue; one told a moment late has only been
   * told late.
   */
  private void narrate(AgentId agentId, AgentEvent event) {
    switch (event) {
      case AgentEvent.ActionsRequested asked ->
          say(
              agentId,
              new org.jwcarman.nessy.api.AgentEvent.ActionsRequested(
                  asked.calls().stream().map(AgentEvent.Requested::toolName).toList()));
      case AgentEvent.ToolApproved approved ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.CallApproved(approved.callId()));
      case AgentEvent.ToolDenied denied ->
          say(
              agentId,
              new org.jwcarman.nessy.api.AgentEvent.CallDenied(denied.callId(), denied.reason()));
      case AgentEvent.ToolSucceeded done ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.CallFinished(done.callId()));
      case AgentEvent.ToolFailed failed ->
          say(
              agentId,
              new org.jwcarman.nessy.api.AgentEvent.CallFailed(failed.callId(), failed.message()));
      case AgentEvent.InferenceRefused _ ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.TurnRefused());
      case AgentEvent.InferenceFailed _ ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.TurnFailed());
      case AgentEvent.Terminated _ ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.Terminated());
      case AgentEvent.TurnStarted started ->
          say(agentId, new org.jwcarman.nessy.api.AgentEvent.TurnStarted(started.turn(), ""));
      // The answer is narrated by whoever streamed it, delta by delta, and saying it again here
      // would say it twice to anybody listening.
      case AgentEvent.InferenceAnswered _ -> {
        /* already said */
      }
    }
  }

  private void say(AgentId agentId, org.jwcarman.nessy.api.AgentEvent event) {
    narrator.narrate(agentType, agentId, event);
  }
}
