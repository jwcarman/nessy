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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loop that was a mailbox: transition, then do what it committed.
 *
 * <p><b>The caller drives.</b> Whoever brought the input runs the turn on its own virtual thread --
 * an HTTP request, a queue consumer, a reaper. Nothing is resident, nothing is addressed, and an
 * agent nobody is talking to costs a row.
 *
 * <p><b>The committing thread claims its own work.</b> Not because correctness needs it -- any node
 * may claim any pending effect -- but because it is already here and does not have to poll. The
 * reaper's floor sits above normal hand-off latency, so this thread wins that race essentially
 * always and the reaper only picks up what was genuinely abandoned.
 *
 * <p><b>Effects run after commit, never inside the lock.</b> The transition is microseconds; a
 * model call is seconds. Holding the row across one would serialize an agent against the network.
 */
final class AgentRuntime implements Dispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

  /**
   * What performs one obligation. {@link EffectWorker#perform} in production.
   *
   * <p>{@code turnId} travels separately from {@code state} on purpose: {@code state} is whatever
   * this agent is NOW, and an obligation claimed off the effect table belongs to whichever turn
   * decided it, which may not be the same turn any more. See {@link #work}.
   */
  @FunctionalInterface
  interface Performer {
    void perform(
        AgentId agentId, AgentState state, TurnId turnId, Effect effect, EffectId effectId);
  }

  private final AgentType agentType;
  private final Transition transition;
  private final EffectStore effects;
  private final Performer performer;
  private final Executor threads;
  private final Duration watchdog;
  private final Traces traces;

  AgentRuntime(
      AgentType agentType,
      Transition transition,
      EffectStore effects,
      Performer performer,
      Executor threads,
      Duration watchdog,
      Traces traces) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.transition = Objects.requireNonNull(transition, "transition must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.performer = Objects.requireNonNull(performer, "performer must not be null");
    this.threads = Objects.requireNonNull(threads, "threads must not be null");
    this.watchdog = Objects.requireNonNull(watchdog, "watchdog must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
  }

  /**
   * One thing happened to one agent.
   *
   * <p>Runs the transition on the calling thread, so an observe blocks only for the fold. A failure
   * is logged rather than thrown: nothing downstream is waiting, so an exception would otherwise
   * stop the turn with no message and no line -- and the obligations are rows, so another node
   * finishes what this one dropped.
   *
   * <p><b>This method hands off to {@code threads} even though {@link #dispatch} already did.</b>
   * The two hops protect different callers and neither is redundant. {@code dispatch}'s hop
   * protects whoever is delivering an outcome from outside -- an HTTP handler must return after the
   * fold, not after the model call its own effect may start. {@code drive}'s own hop protects
   * whoever calls it DIRECTLY, which {@code dispatch} is not involved in: {@link #recover}, called
   * by the effect reaper and the stall sweep on every pass. Without this second hop, a sweep would
   * run whatever obligation it just re-armed inline, and a model call on one stalled agent would
   * stall every sweep behind it in the same loop. A virtual thread costs on the order of a
   * microsecond, so paying for a hop that is sometimes redundant is far cheaper than the loop it
   * would otherwise be possible to serialize. Do not collapse this into one hop.
   */
  void drive(AgentId agentId, Input input, EffectId completing, String observability) {
    Transition.Applied applied;
    try {
      applied = transition.apply(agentId, input, completing, observability);
    } catch (RuntimeException failure) {
      LOG.error("[{}] transition failed for {}", agentId.value(), input, failure);
      return;
    }
    if (!applied.changed() && answerShaped(input)) {
      reportDropped(agentId, input, applied);
    }
    narrate(agentId, applied);
    // Hand off. The transition thread's job ended at COMMIT: the obligations are durable rows and
    // whoever drains them does not have to be this thread. Running them inline here would tie a
    // short transaction to a model call that may take seconds, and would mean the thread that
    // accepted an observation is still busy when the tool it started finally answers.
    threads.execute(() -> work(agentId));
  }

  @Override
  public void dispatch(AgentId agentId, Input input, EffectId completing, String observability) {
    threads.execute(() -> drive(agentId, input, completing, observability));
  }

  /**
   * Wakes an agent that may have been left mid-turn by a node that died.
   *
   * <p>{@code Recovered} is the same input activation fed on every actor start, so the rare path
   * stays the common one. Safe on an agent that is perfectly fine: a healthy idle agent asks for
   * work, and a take is stranded-first, so it gets back the row it already holds.
   */
  void recover(AgentId agentId) {
    drive(agentId, new Input.Recovered(), null, null);
  }

  /**
   * Claims and performs whatever this agent owes, oldest decision first.
   *
   * <p><b>Peeks the state rather than locking it.</b> The drain never writes -- state is written by
   * {@link #drive}, before this ever runs -- so taking {@code lockAndLoad}'s exclusive {@code
   * SELECT ... FOR UPDATE} here would serialize a read-only pass against every other transition for
   * no reason, and {@code lockAndLoad} conjuring an idle row for an agent nobody has ever heard of
   * would create one purely because its effect table was asked about. An agent with no row has no
   * work to drain, full stop.
   *
   * <p><b>Reads the state itself rather than being handed one.</b> No lock spans the gap between a
   * transition committing and its effects being drained, so a state captured before the claim can
   * already describe a different turn by the time an effect runs. That window existed when this ran
   * inline and merely widened when the drain became a hand-off; reading inside the drain closes it
   * rather than narrowing it.
   */
  void work(AgentId agentId) {
    Optional<AgentState> state = transition.peek(agentId);
    if (state.isEmpty()) {
      return;
    }
    List<EffectStore.Claimed> claimed =
        effects.claim(agentType, agentId, Instant.now().plus(watchdog));
    for (EffectStore.Claimed effect : claimed) {
      perform(agentId, state.get(), effect);
    }
  }

  /** What the agent is, right now. A read, and it changes nothing. */
  AgentState inspect(AgentId agentId) {
    return transition.read(agentId);
  }

  /**
   * One claimed obligation, performed. Package-private because the reaper retries through it.
   *
   * <p>{@code effect.turnId()} travels to the performer, not {@code state.turnId()} -- see {@link
   * Performer}. An effect decided in turn T must be performed against turn T, whether or not this
   * agent has since moved on to T+1: the coordinates a reply token names are {@code (agentType,
   * agentId, turnId, callId)} (see {@code ReplyTokens.Coordinates}), never "whatever turn is
   * current when the answer happens to arrive".
   */
  void perform(AgentId agentId, AgentState state, EffectStore.Claimed effect) {
    try {
      performer.perform(
          agentId,
          state,
          effect.turnId(),
          EffectStore.PAYLOADS.decode(effect.payload()),
          effect.id());
    } catch (RuntimeException failure) {
      // Left outstanding on purpose, with its watchdog armed. An obligation that threw is one
      // somebody should try again -- and deciding here that it never will is exactly the judgment
      // a reaper is forbidden from making.
      LOG.error(
          "[{}] obligation {} threw and stays outstanding", agentId.value(), effect.id(), failure);
    }
  }

  /**
   * Delivers what the transition decided should be said, now that it has committed.
   *
   * <p>Through the same {@link Performer} as everything else, because building an AgentEvent needs
   * the claims and the renderer that {@link EffectWorker} already holds. A null effect id, because
   * narration discharges no obligation -- it was never a row.
   */
  private void narrate(AgentId agentId, Transition.Applied applied) {
    for (Effect effect : applied.narrations()) {
      try {
        performer.perform(agentId, applied.next(), applied.next().turnId(), effect, null);
      } catch (RuntimeException failure) {
        LOG.warn("[{}] narration failed and was dropped", agentId.value(), failure);
      }
    }
  }

  /** An input whose whole reason to exist is answering a call this agent was waiting on. */
  private static boolean answerShaped(Input input) {
    return input instanceof Input.ApprovalGiven
        || input instanceof Input.ToolCompleted
        || input instanceof Input.DeadlinePassed
        || input instanceof Input.WorkTaken;
  }

  /**
   * An answer-shaped input that moved nothing: a person clicking a stale approval button, a vendor
   * answering after its deadline denied the call, a backlog take answering an agent that already
   * heard from a different one. {@code AgentLogic} correctly drops it and returns {@code
   * Decision.nothing} -- this is the shell noticing that happened, without re-deriving {@code
   * AgentLogic.awaiting} or keeping a second opinion about what "still waiting" means.
   */
  private void reportDropped(AgentId agentId, Input input, Transition.Applied applied) {
    LOG.warn("[{}] dropped: {} answered nothing this agent was waiting on", agentId.value(), input);
    traces.tag("nessy.effect.dropped", "true");
    traces.detail("nessy.agent.id", agentId.value());
    TurnId turnId = applied.next().turnId();
    if (turnId != null) {
      traces.detail("nessy.turn.id", turnId.value());
    }
    CallId callId = callIdOf(input);
    if (callId != null) {
      traces.detail("nessy.call.id", callId.value());
    }
  }

  private static CallId callIdOf(Input input) {
    return switch (input) {
      case Input.ApprovalGiven given -> given.callId();
      case Input.ToolCompleted done -> done.callId();
      case Input.DeadlinePassed passed -> passed.callId();
      default -> null;
    };
  }
}
