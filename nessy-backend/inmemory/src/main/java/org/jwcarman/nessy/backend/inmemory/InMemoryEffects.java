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
package org.jwcarman.nessy.backend.inmemory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The work queue as a map, for a door with no database.
 *
 * <p>The contract is small and this keeps every part of it: take up to {@code batchSize} due rows
 * of one agent type, oldest first, that nobody else holds; count the attempt; and set the next due
 * moment to the earlier of when this attempt stops being believed and when the agent stops waiting.
 * The durable one says that in three statements with {@code FOR UPDATE SKIP LOCKED}, {@code LEAST}
 * and {@code RETURNING}; none of those is the contract, they are how one database spells it. Here
 * exclusion is a {@code synchronized} claim and the fence is a compare-and-act, which is the same
 * promise made a different way.
 *
 * <p><b>What it cannot promise, and the durable one can.</b> {@code insert} is called from inside
 * the fold's transaction, so a durable store commits the effect with the state change that owed it
 * or not at all. Nothing here has a transaction, so a throw between appending the event and
 * inserting the effect would leave an agent waiting on work no watchdog can find. Putting an entry
 * in a map does not throw, so it is a hole nobody can fall into today, but it is a weaker guarantee
 * and a reader should not have to infer that the two are the same.
 */
public final class InMemoryEffects implements Effects {

  private static final Logger LOG = LoggerFactory.getLogger(InMemoryEffects.class);

  /**
   * The durable table's order: creation time, then id. The id compares as unsigned bytes, as the
   * database's uuid type does, which is not what {@link UUID#compareTo} does.
   */
  private static final Comparator<Row> LIVE_ORDER =
      Comparator.<Row, Instant>comparing(row -> row.createdAt)
          .thenComparing(row -> row.effectId, InMemoryEffects::byUnsignedBytes);

  private static final String TYPE_REQUIRED = "type must not be null";

  /** Two meanings for one moment, decided by status -- exactly as the durable row has it. */
  private enum Status {
    PENDING,
    RUNNING
  }

  private static final class Row {
    private final UUID effectId;
    private final AgentType type;
    private final AgentId agent;
    private final byte[] payload;
    private final byte[] failurePayload;
    private final Duration timeout;
    private final Instant deadline;
    private final String traceContext;
    private final Instant createdAt;
    private Instant parkedAt;
    private Status status = Status.PENDING;
    private int attemptsMade;
    private Instant actionableAt;

    private Row(
        UUID effectId,
        AgentType type,
        AgentId agent,
        byte[] payload,
        byte[] failurePayload,
        Duration timeout,
        Instant deadline,
        String traceContext,
        Instant at) {
      this.effectId = effectId;
      this.type = type;
      this.agent = agent;
      this.payload = payload;
      this.failurePayload = failurePayload;
      this.timeout = timeout;
      this.deadline = deadline;
      this.traceContext = traceContext;
      this.actionableAt = at;
      this.createdAt = at;
    }

    private List<FailedAttempt> failedAttempts = List.of();

    private Attempt asAttempt() {
      return new Attempt(
          effectId, agent, payload, failurePayload, attemptsMade, deadline, traceContext, null);
    }
  }

  private static final String ATTEMPT_REQUIRED = "attempt must not be null";

  private final Codec<AgentEffect> effects;
  private final Codec<EffectOutcome> outcomes;
  private final Map<UUID, Row> rows = new ConcurrentHashMap<>();

  public InMemoryEffects(CodecFactory codecs) {
    Objects.requireNonNull(codecs, "codecs must not be null");
    this.effects = codecs.create(AgentEffect.class);
    this.outcomes = codecs.create(EffectOutcome.class);
  }

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
    UUID effectId = UUID.randomUUID();
    rows.put(
        effectId,
        new Row(
            effectId,
            Objects.requireNonNull(type, TYPE_REQUIRED),
            Objects.requireNonNull(agent, "agent must not be null"),
            effects.encode(Objects.requireNonNull(effect, "effect must not be null")),
            outcomes.encode(
                Objects.requireNonNull(undispatchable, "undispatchable must not be null")),
            Objects.requireNonNull(timeout, "timeout must not be null"),
            Objects.requireNonNull(deadline, "deadline must not be null"),
            traceContext,
            Objects.requireNonNull(at, "at must not be null")));
  }

  /**
   * Oldest first, and never past the deadline: a row coming due at its deadline comes due to be
   * given up on rather than tried again, which is what the durable one's {@code LEAST} says.
   *
   * <p>{@code synchronized} is the whole of the exclusion. Two callers cannot take the same row
   * because they cannot be inside this at once, which is what {@code FOR UPDATE SKIP LOCKED} buys
   * across processes and what a single process gets for nothing.
   */
  @Override
  public synchronized List<Attempt> markRunning(AgentType type, Instant now, int batchSize) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(now, "now must not be null");
    List<Attempt> claimed = new ArrayList<>();
    rows.values().stream()
        .filter(row -> row.type.equals(type))
        .filter(row -> !row.actionableAt.isAfter(now))
        .sorted(Comparator.comparing(row -> row.actionableAt))
        .limit(Math.max(batchSize, 0))
        .forEach(
            row -> {
              row.status = Status.RUNNING;
              row.attemptsMade++;
              Instant believedUntil = now.plus(row.timeout);
              row.actionableAt = believedUntil.isAfter(row.deadline) ? row.deadline : believedUntil;
              claimed.add(row.asAttempt());
            });
    return List.copyOf(claimed);
  }

  @Override
  public synchronized List<Attempt> runningFor(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, "agent must not be null");
    return rows.values().stream()
        .filter(row -> row.status == Status.RUNNING)
        .filter(row -> row.type.equals(type) && row.agent.equals(agent))
        .map(Row::asAttempt)
        .toList();
  }

  /**
   * First-wins, which is what fences two performers of the same effect for free: whoever removes
   * the row has finished it, and the loser's remove matches nothing and says so.
   */
  @Override
  public synchronized boolean complete(UUID effectId, int attemptsMade) {
    Row row = rows.get(effectId);
    if (row == null || row.status != Status.RUNNING || row.attemptsMade != attemptsMade) {
      return false;
    }
    rows.remove(effectId);
    return true;
  }

  /** The same fence, putting the row back rather than taking it away. */
  @Override
  public synchronized boolean reschedule(
      UUID effectId, int attemptsMade, Instant at, List<FailedAttempt> failedAttempts) {
    Objects.requireNonNull(at, "at must not be null");
    Row row = rows.get(effectId);
    if (row == null || row.status != Status.RUNNING || row.attemptsMade != attemptsMade) {
      return false;
    }
    row.status = Status.PENDING;
    row.actionableAt = at.isAfter(row.deadline) ? row.deadline : at;
    row.failedAttempts = failedAttempts == null ? List.of() : List.copyOf(failedAttempts);
    return true;
  }

  /**
   * The same fence: only a running row of that attempt is parked, and it comes due at its deadline,
   * whatever moment the claim that took it had put it at.
   */
  @Override
  public synchronized boolean park(UUID effectId, int attemptsMade, Instant at) {
    Objects.requireNonNull(at, "at must not be null");
    Row row = rows.get(effectId);
    if (row == null || row.status != Status.RUNNING || row.attemptsMade != attemptsMade) {
      return false;
    }
    row.actionableAt = row.deadline;
    row.parkedAt = at;
    return true;
  }

  /** Oldest first, and every live row there is: a finished row is no longer held at all. */
  @Override
  public synchronized List<LiveEffect> liveFor(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(agent, "agent must not be null");
    return rows.values().stream()
        .filter(row -> row.type.equals(type) && row.agent.equals(agent))
        .sorted(LIVE_ORDER)
        .map(this::live)
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * Parked, running and short of the deadline, which is what the durable query says. A row claimed
   * again at its deadline keeps its mark but its deadline is not after {@code now}.
   */
  @Override
  public synchronized List<LiveEffect> parkedNow(
      Optional<AgentType> type, Instant now, Optional<LiveEffect> after, int limit) {
    Objects.requireNonNull(type, TYPE_REQUIRED);
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }
    return rows.values().stream()
        .filter(row -> row.parkedAt != null && row.status == Status.RUNNING)
        .filter(row -> row.deadline.isAfter(now))
        .filter(row -> type.isEmpty() || row.type.equals(type.get()))
        .filter(row -> after.isEmpty() || isAfter(row, after.get()))
        .sorted(LIVE_ORDER)
        .map(this::live)
        .flatMap(Optional::stream)
        .limit(limit)
        .toList();
  }

  private static boolean isAfter(Row row, LiveEffect last) {
    int byTime = row.createdAt.compareTo(last.createdAt());
    return byTime > 0 || (byTime == 0 && byUnsignedBytes(row.effectId, last.effectId()) > 0);
  }

  private static int byUnsignedBytes(UUID a, UUID b) {
    int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
  }

  /** Empty for a row whose payload cannot be decoded, which is logged and passed over. */
  private Optional<LiveEffect> live(Row row) {
    AgentEffect effect;
    try {
      effect = effects.decode(row.payload);
    } catch (RuntimeException e) {
      LOG.warn("Skipping effect {}: its payload cannot be decoded", row.effectId, e);
      return Optional.empty();
    }
    return Optional.of(
        new LiveEffect(
            row.effectId,
            row.type,
            row.agent,
            effect,
            row.createdAt,
            Optional.ofNullable(row.parkedAt),
            row.deadline,
            row.attemptsMade,
            row.status == Status.RUNNING));
  }

  /**
   * Kept as objects here, so there is nothing to decode and nothing that can fail to.
   *
   * <p>On the monitor like every other reader of a row: the list is replaced wholesale by a
   * reschedule, and a reader off the lock could see the one before it. Note this reads the LIVE row
   * where the JDBC store reads the snapshot taken when the row was claimed -- a difference that
   * only shows when two threads hold the same row, which the attempt fence then settles.
   */
  @Override
  public synchronized List<FailedAttempt> attemptsOf(Attempt attempt) {
    Objects.requireNonNull(attempt, ATTEMPT_REQUIRED);
    Row row = rows.get(attempt.effectId());
    return row == null ? List.of() : row.failedAttempts;
  }

  @Override
  public AgentEffect effectOf(Attempt attempt) {
    return effects.decode(Objects.requireNonNull(attempt, ATTEMPT_REQUIRED).payload());
  }

  @Override
  public EffectOutcome failureOf(Attempt attempt) {
    return outcomes.decode(Objects.requireNonNull(attempt, ATTEMPT_REQUIRED).failurePayload());
  }
}
