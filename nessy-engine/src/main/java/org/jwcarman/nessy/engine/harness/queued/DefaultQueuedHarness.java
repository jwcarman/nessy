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

package org.jwcarman.nessy.engine.harness.queued;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.backlog.Pull;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.store.Outbox;
import org.jwcarman.nessy.engine.trace.Traces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A harness for work nobody is waiting on, and the callback its own effects report through.
 *
 * <p>The same fold the direct door runs. What is different is everything around it: a backlog in
 * front, an outbox behind, and a row lock holding the agent still while both are written.
 *
 * <p><b>Nothing here holds a lock while a model is called.</b> {@link Locks#withLock} takes the
 * agent, folds a command, writes the events and the effects it decided on, and commits -- the lock
 * absorbs the transaction, so there is no separate transaction boundary here. Performing those
 * effects happens afterwards and elsewhere; the answer comes back through {@link #deliverOutcome}
 * as a lock of its own. That is the whole reason this door exists and the whole reason it can wait
 * hours for a person to approve something.
 *
 * @param <I> the input type
 */
final class DefaultQueuedHarness<I>
    implements QueuedHarness<I>, AgentEffectCallback, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultQueuedHarness.class);

  private final AgentType agentType;
  private final BacklogPolicy<I> policy;
  private final InputRenderer<I> renderer;

  /**
   * Where agents, events, content and the lock all come from -- held whole rather than torn into
   * separate fields, because {@code events()}, {@code payloads()}, {@code agents()} and {@code
   * locks()} are one decision chosen together, and a harness holding four of its own fields would
   * be exactly what let them drift apart.
   */
  private final QueuedBackend backend;

  /**
   * Resolved once, at construction, rather than asked of the backend on every call: which type
   * {@code I} is is a per-harness fact settled the moment this harness is made, not something to
   * rediscover on every {@link #tell}.
   */
  private final Backlogs<I> backlogs;

  private final Outbox effects;
  private final Narrator narrator;
  private final Clock clock;
  private final TurnPolicy turnPolicy;
  private final Traces traces;

  private EffectDispatcher dispatcher;

  DefaultQueuedHarness(
      AgentType agentType,
      BacklogPolicy<I> policy,
      InputRenderer<I> renderer,
      QueuedBackend backend,
      Backlogs<I> backlogs,
      Outbox effects,
      Narrator narrator,
      Clock clock,
      TurnPolicy turnPolicy,
      Traces traces) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.backlogs = Objects.requireNonNull(backlogs, "backlogs must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.turnPolicy = Objects.requireNonNull(turnPolicy, "turn policy must not be null");
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
   * Admits an input.
   *
   * <p>Always accepts, which is the promise this door keeps -- unless the agent has ended, which is
   * the one thing that can refuse. What happens to the arrival is the policy's business: appended,
   * replacing what was waiting, or dropped to hold a bound.
   *
   * <p>Then, if the agent is idle, the arrival becomes a turn under this same lock. An agent is
   * never left idle with work waiting.
   */
  @Override
  public void tell(AgentId agentId, I input) {
    BacklogItem<I> arrival = new BacklogItem<>(input, clock.instant());
    log.debug("[{}] admitting input for agent {}", agentType.value(), agentId.value());
    traces.in(
        "nessy.tell",
        new Identity(agentType, agentId),
        () -> {
          String trace = traces.capture();
          boolean nudge =
              backend
                  .locks()
                  .withLock(
                      Locks.TURN,
                      agentType,
                      agentId,
                      () -> {
                        backend.agents().ensure(agentType, agentId);
                        Backlog<I> backlog = backlogs.forAgent(agentType, agentId);
                        if (backend.agents().terminated(agentType, agentId)) {
                          // Ended. Coalescing now would put something into an emptied backlog and
                          // be
                          // read as work next time, undoing a termination that has happened.
                          log.debug(
                              "[{}] agent {} has ended; the input is refused",
                              agentType.value(),
                              agentId.value());
                          return false;
                        }
                        policy.coalesce(backlog, arrival);
                        return driveIfIdle(agentId, backlog, trace);
                      });
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
        backend
            .locks()
            .withLock(
                Locks.TURN,
                agentType,
                agentId,
                () -> {
                  backend.agents().ensure(agentType, agentId);
                  Backlog<I> backlog = backlogs.forAgent(agentType, agentId);
                  int abandoned = backend.agents().seal(agentType, agentId);
                  if (abandoned > 0) {
                    log.info(
                        "[{}] agent {} ended with {} input(s) waiting; abandoned",
                        agentType.value(),
                        agentId.value(),
                        abandoned);
                  }
                  return driveIfIdle(agentId, backlog, trace);
                });
    if (nudge) {
      dispatch();
    }
  }

  /** What an effect came to, folded under a lock of its own. */
  @Override
  public void deliverOutcome(
      AgentId agentId,
      Optional<TurnId> turn,
      EffectOutcome outcome,
      String traceContext,
      List<FailedAttempt> priorAttempts) {
    log.debug(
        "[{}] delivering {} to agent {}",
        agentType.value(),
        outcome.getClass().getSimpleName(),
        agentId.value());
    boolean nudge =
        backend
            .locks()
            .withLock(
                Locks.TURN,
                agentType,
                agentId,
                () -> {
                  backend.agents().ensure(agentType, agentId);
                  boolean wrote = fold(agentId, turn, outcome, traceContext, priorAttempts);
                  // A turn that ended leaves the agent idle, and the next thing waiting becomes
                  // the next turn -- here, before this transaction commits.
                  return wrote
                      | driveIfIdle(agentId, backlogs.forAgent(agentType, agentId), traceContext);
                });
    if (nudge) {
      dispatch();
    }
  }

  /**
   * Folds one outcome into the turn it answers.
   *
   * <p>An outcome whose row could not be decoded names no turn, and the only turn it can be
   * attributed to is the one the agent is on -- which is what the fold used to assume of every
   * outcome, and the reason a late answer could be written down as somebody else's. An idle or
   * ended agent has no turn at all, so there is nothing such an outcome could settle.
   */
  private boolean fold(
      AgentId agentId,
      Optional<TurnId> turn,
      EffectOutcome outcome,
      String trace,
      List<FailedAttempt> priorAttempts) {
    Optional<TurnId> answered = turn.or(() -> turnOf(reconstitute(agentId)));
    if (answered.isEmpty()) {
      log.debug(
          "[{}] agent {} is not on a turn; {} settles nothing",
          agentType.value(),
          agentId.value(),
          outcome.getClass().getSimpleName());
      return false;
    }
    return apply(agentId, EffectOutcomes.command(answered.get(), outcome, priorAttempts), trace);
  }

  /** The turn an agent is on, if it is on one. */
  private static Optional<TurnId> turnOf(AgentState state) {
    return switch (state) {
      case AgentState.Inferring inferring -> Optional.of(inferring.turn());
      case AgentState.AwaitingActions awaiting -> Optional.of(awaiting.turn());
      case AgentState.Idle _, AgentState.Terminal _ -> Optional.empty();
    };
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
  private boolean driveIfIdle(AgentId agentId, Backlog<I> backlog, String trace) {
    if (!(reconstitute(agentId) instanceof AgentState.Idle)) {
      return false;
    }
    return switch (backlog.take()) {
      // Claim-checked here, and this is the only place a harness does it by hand: an arrival is
      // the application's own object, not an outcome, and no executor produced it.
      case Pull.Item<I>(BacklogItem<I> next) ->
          apply(
              agentId,
              new AgentCommand.StartTurn(
                  backend.payloads().forAgent(agentId).put(renderer.render(next.input())),
                  clock.instant()),
              trace);
      case Pull.Pill<I> _ -> apply(agentId, new AgentCommand.Terminate(), trace);
      case Pull.Empty<I> _ -> false;
    };
  }

  /** Folds one command and writes what it decided. */
  private boolean apply(AgentId agentId, AgentCommand command, String trace) {
    AgentState state = reconstitute(agentId);
    if (!(state.execute(command, turnPolicy, clock.instant())
        instanceof Decision.Advance advance)) {
      // Ignore writes nothing at all. A record showing something happening when nothing did is
      // worse than no record.
      log.debug(
          "[{}] agent {}: ignoring {}",
          agentType.value(),
          agentId.value(),
          command.getClass().getSimpleName());
      return false;
    }
    backend.events().append(agentType, agentId, advance.events(), state.seq());
    for (AgentEffect effect : advance.effects()) {
      effects.insert(agentId, effect, clock.instant(), trace);
    }
    advance.events().forEach(event -> narrate(agentId, event));
    // Read off the effect rather than the state. Inferring is where an agent sits for the whole
    // of a call, so a fold that stays there without emitting anything -- an input queued
    // mid-turn -- would announce a second "thinking" for a call already in flight. The effect is
    // emitted exactly once per call, which is what this means.
    if (advance.effects().stream().anyMatch(AgentEffect.Infer.class::isInstance)) {
      say(agentId, new Narration.Thinking());
    }
    return !advance.effects().isEmpty();
  }

  /** The agent as it stands: the last turn that started, replayed onto idle. */
  private AgentState reconstitute(AgentId agentId) {
    List<AgentEvent> lastTurn = backend.events().sinceLastTurnStarted(agentType, agentId);
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

  /**
   * What just became true, told to whoever is watching.
   *
   * <p>After the append and inside the transaction that made it true. A watcher told about a fold a
   * rollback could still undo would be told something untrue; one told a moment late has only been
   * told late.
   */
  private void narrate(AgentId agentId, AgentEvent event) {
    // Some of these mean resolving what a reference stands for, which is real work: skipped
    // entirely when nobody is there to be told. Narrating anyway would still be correct.
    if (!narrator.listening()) {
      return;
    }
    switch (event) {
      // Not narrated. A watcher is told what is happening to a turn, and a call being tried
      // again is the engine keeping its own promise rather than anything the turn did. It is in
      // the story for whoever is counting what the turn spent.
      case AgentEvent.InferenceAttempted _ -> {}
      case AgentEvent.ActionsRequested asked ->
          say(
              agentId,
              new Narration.ActionsRequested(
                  asked.actions().stream()
                      .filter(ActionRequest.ToolCall.class::isInstance)
                      .map(ActionRequest.ToolCall.class::cast)
                      .map(ActionRequest.ToolCall::name)
                      .toList()));
      case AgentEvent.ToolApproved approved ->
          say(agentId, new Narration.CallApproved(approved.callId()));
      case AgentEvent.ToolDenied denied ->
          say(agentId, new Narration.CallDenied(denied.callId(), denied.reason()));
      case AgentEvent.ToolSucceeded done -> say(agentId, new Narration.CallFinished(done.callId()));
      case AgentEvent.ToolFailed failed ->
          say(agentId, new Narration.CallFailed(failed.callId(), failed.message()));
      // However it ended, it ended: the one event to hear when the story grew by a turn. An
      // answer, a refusal and a fault all close one; asking for actions does not.
      case AgentEvent.InferenceRefused refused -> {
        say(agentId, new Narration.TurnRefused(refused.category()));
        say(agentId, new Narration.TurnEnded(refused.turn()));
      }
      case AgentEvent.InferenceFailed failed -> {
        say(agentId, new Narration.TurnFailed(failed.failure().reason()));
        say(agentId, new Narration.TurnEnded(failed.turn()));
      }
      // Heard exactly as any other failed turn is. A watcher does not care whether the model
      // could not answer or a policy decided it had answered enough; either way the turn is over.
      case AgentEvent.TurnFailed ended -> {
        say(agentId, new Narration.TurnFailed(ended.reason()));
        say(agentId, new Narration.TurnEnded(ended.turn()));
      }
      case AgentEvent.Terminated _ -> say(agentId, new Narration.Terminated());
      case AgentEvent.TurnStarted started ->
          say(agentId, new Narration.TurnStarted(started.turn()));
      // Said as a fact once the fold has committed, exactly as the direct door says it. The
      // deltas a provider streamed are what is ARRIVING; this is what was said, and a watcher
      // that saw neither -- a page opened mid-turn -- would otherwise never learn the answer.
      case AgentEvent.InferenceAnswered answered -> {
        say(agentId, new Narration.Answered());
        say(agentId, new Narration.TurnEnded(answered.turn()));
      }
    }
  }

  private void say(AgentId agentId, org.jwcarman.nessy.api.Narration event) {
    narrator.narrate(agentType, agentId, event);
  }
}
