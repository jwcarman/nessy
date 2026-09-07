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
 * an HTTP request, a queue consumer, {@link EffectPoller}. Nothing is resident, nothing is
 * addressed, and an agent nobody is talking to costs a row.
 *
 * <p><b>{@link #drive} never performs an effect.</b> It commits the fold and returns; {@link
 * EffectPoller} is the ONLY path that attempts a durable obligation. Two mechanisms racing to
 * perform the same freshly-committed row were never a correctness problem -- {@code SKIP LOCKED}
 * already prevented double-performing -- but they were two answers to one question, and the poller,
 * running continuously rather than only after abandonment, is the one answer this engine keeps.
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
   * this agent is NOW, and an obligation attempted off the effect table belongs to whichever turn
   * decided it, which may not be the same turn any more. See {@link #perform}.
   *
   * <p>{@code attempts} is how many times this obligation has already FAILED -- {@code
   * EffectStore.Attempted#attempts()}, verbatim -- or {@code -1} for narration, which is never a
   * row and never consults a {@link RetryPolicy}.
   */
  @FunctionalInterface
  interface Performer {
    void perform(
        AgentId agentId,
        AgentState state,
        TurnId turnId,
        Effect effect,
        EffectId effectId,
        int attempts);
  }

  private final AgentType agentType;
  private final Transition transition;
  private final Performer performer;
  private final Executor threads;
  private final Traces traces;

  AgentRuntime(
      AgentType agentType,
      Transition transition,
      Performer performer,
      Executor threads,
      Traces traces) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.transition = Objects.requireNonNull(transition, "transition must not be null");
    this.performer = Objects.requireNonNull(performer, "performer must not be null");
    this.threads = Objects.requireNonNull(threads, "threads must not be null");
    this.traces = Objects.requireNonNull(traces, "traces must not be null");
  }

  /** What kind of agent this runtime drives -- the wiring key {@link EffectPoller} pairs it by. */
  AgentType agentType() {
    return agentType;
  }

  /**
   * One thing happened to one agent.
   *
   * <p>Runs the transition on the calling thread, so an observe blocks only for the fold. A failure
   * is logged rather than thrown: nothing downstream is waiting, so an exception would otherwise
   * stop the turn with no message and no line -- and the obligations are rows, so another node
   * finishes what this one dropped.
   *
   * <p><b>Commits and returns, full stop.</b> There used to be a hand-off here to drain this
   * agent's new obligations inline, on {@code threads}. That is deleted: {@link EffectPoller} is
   * now the ONLY path that performs a durable effect, so this thread's job ends at commit. Running
   * effects from two places that can both race to claim the same row was never a correctness
   * problem -- {@code SKIP LOCKED} already prevented that -- but it was two mechanisms doing one
   * job, and the local hand-off's only advantage (usually winning the race to perform its own
   * freshly-committed work before a reaper's floor kicked in) evaporates once there is no reaper
   * floor to beat: the poller is not idle-until-abandoned, it is running continuously.
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

  /** What the agent is, right now. A read, and it changes nothing. */
  AgentState inspect(AgentId agentId) {
    return transition.read(agentId);
  }

  /**
   * The state an agent is in right now, or empty if nobody has ever heard of it -- no lock, no
   * fold, no idle row conjured for a stranger. For {@link EffectPoller}: a batch it attempted names
   * agents, not states, and every row in one agent's group is performed against the SAME read of
   * that agent, taken once per group rather than once per row.
   */
  Optional<AgentState> peek(AgentId agentId) {
    return transition.peek(agentId);
  }

  /**
   * One attempted obligation, performed. Package-private because {@link EffectPoller} is the only
   * caller.
   *
   * <p>{@code effect.turnId()} travels to the performer, not {@code state.turnId()} -- see {@link
   * Performer}. An effect decided in turn T must be performed against turn T, whether or not this
   * agent has since moved on to T+1: the coordinates a reply token names are {@code (agentType,
   * agentId, turnId, callId)} (see {@code ReplyTokens.Coordinates}), never "whatever turn is
   * current when the answer happens to arrive".
   */
  void perform(AgentId agentId, AgentState state, EffectStore.Attempted effect) {
    try {
      performer.perform(
          agentId,
          state,
          effect.turnId(),
          EffectStore.PAYLOADS.decode(effect.payload()),
          effect.id(),
          effect.attempts());
    } catch (RuntimeException failure) {
      // Left outstanding on purpose, with its watchdog armed. An obligation that threw is one
      // somebody should try again -- and deciding here that it never will is not this method's
      // judgment to make; that is EffectPoller and RetryPolicy's, on a LATER attempt.
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
        performer.perform(agentId, applied.next(), applied.next().turnId(), effect, null, -1);
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
