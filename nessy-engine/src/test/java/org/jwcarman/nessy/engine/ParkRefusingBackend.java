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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.backend.turn.AgentTurns;

/**
 * A queued backend whose effect table cannot mark a row parked, and does everything else as the
 * real one does -- for showing what a deferral stands on when the mark fails after the event was
 * appended.
 */
public final class ParkRefusingBackend implements QueuedBackend {

  private final QueuedBackend backend;
  private final Effects effects;

  /**
   * @param refusals counts each time the effect table was asked to mark a row and refused, so a
   *     test knows the moment has come without waiting a guessed time
   */
  public ParkRefusingBackend(QueuedBackend backend, AtomicInteger refusals) {
    this.backend = backend;
    this.effects = new RefusingToPark(backend.effects(), refusals);
  }

  @Override
  public AgentEvents events() {
    return backend.events();
  }

  @Override
  public Payloads payloads() {
    return backend.payloads();
  }

  @Override
  public Locks locks() {
    return backend.locks();
  }

  @Override
  public Agents agents() {
    return backend.agents();
  }

  @Override
  public Effects effects() {
    return effects;
  }

  @Override
  public Chapters chapters() {
    return backend.chapters();
  }

  @Override
  public Leases leases() {
    return backend.leases();
  }

  @Override
  public AgentTurns turns() {
    return backend.turns();
  }

  @Override
  public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
    return backend.backlogs(inputType);
  }

  @Override
  public int queued(AgentType type, AgentId agent) {
    return backend.queued(type, agent);
  }

  private record RefusingToPark(Effects effects, AtomicInteger refusals) implements Effects {

    @Override
    public void insert(
        AgentType type,
        AgentId agent,
        AgentEffect effect,
        Duration timeout,
        EffectOutcome undispatchable,
        Instant deadline,
        String traceContext,
        Instant at) {
      effects.insert(type, agent, effect, timeout, undispatchable, deadline, traceContext, at);
    }

    @Override
    public List<Attempt> markRunning(AgentType type, Instant now, int batchSize) {
      return effects.markRunning(type, now, batchSize);
    }

    @Override
    public List<Attempt> runningFor(AgentType type, AgentId agent) {
      return effects.runningFor(type, agent);
    }

    @Override
    public boolean complete(UUID effectId, int attemptsMade) {
      return effects.complete(effectId, attemptsMade);
    }

    @Override
    public boolean reschedule(
        UUID effectId, int attemptsMade, Instant at, List<FailedAttempt> failedAttempts) {
      return effects.reschedule(effectId, attemptsMade, at, failedAttempts);
    }

    @Override
    public boolean park(UUID effectId, int attemptsMade, Instant at) {
      refusals.incrementAndGet();
      throw new IllegalStateException("the effect table refused to mark the row");
    }

    @Override
    public List<LiveEffect> liveFor(AgentType type, AgentId agent) {
      return effects.liveFor(type, agent);
    }

    @Override
    public List<LiveEffect> parkedNow(
        Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit) {
      return effects.parkedNow(type, now, after, limit);
    }

    @Override
    public List<FailedAttempt> attemptsOf(Attempt attempt) {
      return effects.attemptsOf(attempt);
    }

    @Override
    public AgentEffect effectOf(Attempt attempt) {
      return effects.effectOf(attempt);
    }

    @Override
    public EffectOutcome failureOf(Attempt attempt) {
      return effects.failureOf(attempt);
    }
  }
}
