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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.backlog.Pull;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.core.TurnRecorder;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.harness.InputLabels;
import org.jwcarman.nessy.engine.narration.AfterCommit;
import org.jwcarman.nessy.engine.narration.AfterCommit.Step;
import org.jwcarman.nessy.engine.narration.StoryEvents;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.store.Outbox;
import org.jwcarman.nessy.engine.trace.Traces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

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
  private final InputLabels<I> labels;

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
  private final AfterCommit narrator;
  private final Clock clock;
  private final TurnPolicy turnPolicy;
  private final Traces traces;
  private final TurnRecorder turnRecorder;

  private EffectDispatcher dispatcher;

  DefaultQueuedHarness(
      AgentType agentType,
      BacklogPolicy<I> policy,
      InputRenderer<I> renderer,
      InputLabels<I> labels,
      QueuedBackend backend,
      Backlogs<I> backlogs,
      Outbox effects,
      AfterCommit narrator,
      Clock clock,
      TurnPolicy turnPolicy,
      Traces traces,
      TurnRecorder turnRecorder) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.labels = Objects.requireNonNull(labels, "labels must not be null");
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.backlogs = Objects.requireNonNull(backlogs, "backlogs must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.turnPolicy = Objects.requireNonNull(turnPolicy, "turn policy must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
    this.turnRecorder = Objects.requireNonNull(turnRecorder, "turnRecorder must not be null");
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
   * <p>Answers {@link TellOutcome.Terminated} for an agent that has been terminated, and stores
   * nothing. Otherwise hands the input to the backlog policy and answers {@link
   * TellOutcome.Accepted}. What happens to an accepted arrival is the policy's business: appended,
   * merged, replacing what was waiting, dropped to hold a bound, or discarded as a repeat.
   *
   * <p>Then, if the agent is idle, the arrival becomes a turn under this same lock. An agent is
   * never left idle with work waiting.
   */
  @Override
  public TellOutcome tell(AgentId agentId, I input) {
    BacklogItem<I> arrival = new BacklogItem<>(input, clock.instant());
    log.debug("[{}] admitting input for agent {}", agentType.value(), agentId.value());
    return traces.in(
        "nessy.tell",
        new Identity(agentType, agentId),
        () -> {
          String trace = traces.capture();
          Told told =
              narrator.locked(
                  backend.locks(),
                  agentType,
                  agentId,
                  step -> {
                    backend.agents().ensure(agentType, agentId);
                    Backlog<I> backlog = backlogs.forAgent(agentType, agentId);
                    if (backend.agents().terminated(agentType, agentId)) {
                      // Terminated. Coalescing now would put something into an emptied backlog
                      // and be read as work next time, undoing a termination that has happened.
                      log.debug(
                          "[{}] agent {} has been terminated; the input is dropped",
                          agentType.value(),
                          agentId.value());
                      return new Told(new TellOutcome.Terminated(), false);
                    }
                    policy.coalesce(backlog, arrival);
                    return new Told(
                        new TellOutcome.Accepted(), driveIfIdle(step, agentId, backlog, trace));
                  });
          if (told.nudge()) {
            dispatch();
          }
          return told.outcome();
        });
  }

  /**
   * What a locked tell came to: what to answer the caller, and whether the dispatcher is nudged.
   */
  private record Told(TellOutcome outcome, boolean nudge) {}

  /**
   * Terminates an agent.
   *
   * <p>It cannot be delivered to one mid-turn, because the fold takes it only from idle. So the
   * backlog is emptied and the agent marked, and the next time it is idle and asks for work,
   * terminating is the work.
   */
  @Override
  public void terminate(AgentId agentId) {
    log.info("[{}] terminating agent {}", agentType.value(), agentId.value());
    String trace = traces.capture();
    boolean nudge =
        narrator.locked(
            backend.locks(),
            agentType,
            agentId,
            step -> {
              backend.agents().ensure(agentType, agentId);
              Backlog<I> backlog = backlogs.forAgent(agentType, agentId);
              int abandoned = backend.agents().seal(agentType, agentId);
              if (abandoned > 0) {
                log.info(
                    "[{}] agent {} terminated with {} input(s) waiting; abandoned",
                    agentType.value(),
                    agentId.value(),
                    abandoned);
              }
              return driveIfIdle(step, agentId, backlog, trace);
            });
    if (nudge) {
      dispatch();
    }
  }

  /**
   * What an effect came to, folded under a lock of its own.
   *
   * @return whether the fold wrote an event for this outcome. That is not the question the
   *     dispatcher is nudged on, which is whether work was left for it: an accepted denial can
   *     write an event while other calls are outstanding and ask for nothing.
   */
  @Override
  public boolean deliverOutcome(
      AgentId agentId,
      Optional<TurnId> turn,
      Optional<Seq> request,
      EffectOutcome outcome,
      String traceContext,
      List<FailedAttempt> priorAttempts) {
    log.debug(
        "[{}] delivering {} to agent {}",
        agentType.value(),
        outcome.getClass().getSimpleName(),
        agentId.value());
    Delivered delivered =
        narrator.locked(
            backend.locks(),
            agentType,
            agentId,
            step -> {
              backend.agents().ensure(agentType, agentId);
              Folded folded =
                  fold(step, agentId, turn, request, outcome, traceContext, priorAttempts);
              // A turn that ended leaves the agent idle, and the next thing waiting becomes
              // the next turn -- here, before this transaction commits.
              boolean driven =
                  driveIfIdle(step, agentId, backlogs.forAgent(agentType, agentId), traceContext);
              return new Delivered(folded.wrote(), folded.asked() | driven);
            });
    if (delivered.nudge()) {
      dispatch();
    }
    return delivered.accepted();
  }

  /**
   * What delivering an outcome came to: whether the fold took it, and whether the dispatcher has
   * something to look for. Two facts, because an outcome can be taken and leave nothing to do.
   */
  private record Delivered(boolean accepted, boolean nudge) {}

  /**
   * What folding one command did: whether it wrote an event, and whether it left work for the
   * dispatcher -- an effect emitted, or a turn started.
   */
  private record Folded(boolean wrote, boolean asked) {

    static final Folded NOTHING = new Folded(false, false);
  }

  /**
   * Records that an attempt deferred, and marks its row, in one locked step.
   *
   * <p>The fold writes the deferral -- until the attempt's deadline -- only when the call is still
   * outstanding in the phase the effect was performed for, and the row is marked only when it did.
   * An answer that landed between the handler returning and this lock being taken leaves nothing to
   * record and nothing to mark: the call has moved on, and the answer settled the row. Both writes
   * happen inside the lock's own transaction, so a failure of either leaves neither.
   *
   * <p>Starts no inference, drives no backlog and nudges no dispatch: a deferral decides nothing
   * and emits no effect, so there is nothing for any of them to find.
   */
  @Override
  public void park(Attempt attempt, AgentEffect effect, ObjectNode facts) {
    AgentId agentId = attempt.agentId();
    AgentCommand deferral =
        switch (effect) {
          case AgentEffect.Approve approve ->
              new AgentCommand.DeferApproval(
                  approve.turn(),
                  approve.requestSeq(),
                  approve.callId(),
                  attempt.deadline(),
                  facts);
          case AgentEffect.CallTool call ->
              new AgentCommand.DeferToolCall(
                  call.turn(), call.requestSeq(), call.callId(), attempt.deadline());
          case AgentEffect.Infer _ -> null;
        };
    if (deferral == null) {
      log.warn(
          "[{}] effect {} for agent {} is an inference, which cannot defer; nothing is recorded",
          agentType.value(),
          attempt.effectId(),
          agentId.value());
      return;
    }
    narrator.locked(
        backend.locks(),
        agentType,
        agentId,
        step -> {
          Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
          AgentState state = reconstitute(agentId);
          if (!(state.execute(deferral, turnPolicy, clock.instant())
              instanceof Decision.Advance advance)) {
            log.debug(
                "[{}] agent {}: ignoring {}; the call has moved on, so its row is not marked",
                agentType.value(),
                agentId.value(),
                deferral.getClass().getSimpleName());
            return null;
          }
          // No turn ends here: a deferral only parks the call it names.
          backend.events().append(agentType, agentId, advance.events(), state.seq(), at);
          advance.events().forEach(event -> narrate(step, event, at));
          if (!effects.park(attempt.effectId(), attempt.attemptsMade(), at)) {
            // Not an error: the row was settled, or another attempt holds it, between the claim
            // and this lock. The event is the record of what was true when it was written.
            log.debug(
                "[{}] effect {} for agent {} was not marked parked; it is no longer this attempt's",
                agentType.value(),
                attempt.effectId(),
                agentId.value());
          }
          return null;
        });
  }

  /**
   * Folds one outcome into the turn and the request it answers.
   *
   * <p>An outcome whose row could not be decoded names no turn and no request, and the only ones it
   * can be attributed to are those the agent is on -- which is what the fold used to assume of
   * every outcome, and the reason a late answer could be written down as somebody else's. An idle
   * or terminated agent has no turn at all, so there is nothing such an outcome could settle, and
   * an agent not waiting on a request has no request for it to settle.
   */
  private Folded fold(
      Step step,
      AgentId agentId,
      Optional<TurnId> turn,
      Optional<Seq> request,
      EffectOutcome outcome,
      String trace,
      List<FailedAttempt> priorAttempts) {
    boolean needsRequest = request.isEmpty() && EffectOutcomes.answersARequest(outcome);
    AgentState state = turn.isEmpty() || needsRequest ? reconstitute(agentId) : null;
    Optional<TurnId> answered = turn.or(() -> turnOf(state));
    Optional<Seq> answeredRequest = needsRequest ? requestOf(state) : request;
    if (answered.isEmpty()) {
      log.debug(
          "[{}] agent {} is not on a turn; {} settles nothing",
          agentType.value(),
          agentId.value(),
          outcome.getClass().getSimpleName());
      return Folded.NOTHING;
    }
    if (needsRequest && answeredRequest.isEmpty()) {
      log.debug(
          "[{}] agent {} is not waiting on a request; {} settles nothing",
          agentType.value(),
          agentId.value(),
          outcome.getClass().getSimpleName());
      return Folded.NOTHING;
    }
    return apply(
        step,
        agentId,
        EffectOutcomes.command(answered.get(), answeredRequest, outcome, priorAttempts),
        trace);
  }

  /** The request an agent is waiting on, if it is waiting on one. */
  private static Optional<Seq> requestOf(AgentState state) {
    return state instanceof AgentState.AwaitingActions awaiting
        ? Optional.of(awaiting.requestSeq())
        : Optional.empty();
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
   * so at every commit an agent is busy, its backlog is empty, or it has been terminated. Never
   * idle with work waiting.
   *
   * @return whether anything was written that an effect dispatcher should be told about
   */
  private boolean driveIfIdle(Step step, AgentId agentId, Backlog<I> backlog, String trace) {
    return startNext(step, agentId, backlog, trace).asked();
  }

  private Folded startNext(Step step, AgentId agentId, Backlog<I> backlog, String trace) {
    if (!(reconstitute(agentId) instanceof AgentState.Idle)) {
      return Folded.NOTHING;
    }
    return switch (backlog.take()) {
      // Claim-checked here, and this is the only place a harness does it by hand: an arrival is
      // the application's own object, not an outcome, and no executor produced it.
      case Pull.Item<I>(BacklogItem<I> next) ->
          apply(
              step,
              agentId,
              new AgentCommand.StartTurn(
                  backend.payloads().forAgent(agentId).put(renderer.render(next.input())),
                  labels.of(next.input()),
                  next.arrivedAt().truncatedTo(ChronoUnit.MICROS),
                  clock.instant()),
              trace);
      case Pull.Pill<I> _ -> apply(step, agentId, new AgentCommand.Terminate(), trace);
      case Pull.Empty<I> _ -> Folded.NOTHING;
    };
  }

  /** Folds one command and writes what it decided. */
  private Folded apply(Step step, AgentId agentId, AgentCommand command, String trace) {
    Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
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
      return Folded.NOTHING;
    }
    backend.events().append(agentType, agentId, advance.events(), state.seq(), at);
    turnRecorder.recordEnding(agentId, state, advance.events(), at);
    for (AgentEffect effect : advance.effects()) {
      effects.insert(agentId, effect, clock.instant(), trace);
    }
    advance.events().forEach(event -> narrate(step, event, at));
    // Read off the effect rather than the state. Inferring is where an agent sits for the whole
    // of a call, so a fold that stays there without emitting anything -- an input queued
    // mid-turn -- would announce a second "thinking" for a call already in flight. The effect is
    // emitted exactly once per call, which is what this means.
    if (advance.effects().stream().anyMatch(AgentEffect.Infer.class::isInstance)) {
      step.narrate(new Narration.Thinking());
    }
    return new Folded(!advance.events().isEmpty(), !advance.effects().isEmpty());
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
   * What just became true, handed to the step that wrote it, to be told to whoever is watching when
   * the locked step returns.
   *
   * <p>Not told here: the step holds it until {@code withLock} returns, and drops it if the step
   * fails. The nudge to the dispatcher is sent at the same moment. When the step opened its own
   * transaction, that is after the commit. When a reply or a {@code tell} joined a caller's
   * transaction, it is before the caller commits, so a watcher can be told of a write that the
   * caller's later rollback undoes.
   */
  private void narrate(Step step, AgentEvent event, Instant at) {
    // Some of these mean resolving what a reference stands for, which is real work: skipped
    // entirely when nobody is there to be told. Narrating anyway would still be correct.
    if (!narrator.listening()) {
      return;
    }
    step.narrate(StoryEvents.of(event), event.seq(), at);
  }
}
