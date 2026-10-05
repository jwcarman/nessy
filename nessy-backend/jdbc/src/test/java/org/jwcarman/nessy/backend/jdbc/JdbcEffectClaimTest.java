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
package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A claim takes at most the batch it was asked for, however many passes claim at once.
 *
 * <p>Contract tests, not a reproduction: the overshoot that stalled a live dispatcher depends on
 * the query plan of a grown table, and neither test here fails against the old statement. What
 * proves the dispatcher survives an overshoot is {@code DispatcherFailureTest}.
 *
 * <p>The dispatcher drains its permits, claims that many rows, and hands back the difference. A
 * claim that returns more rows than it was asked for makes that difference negative: the semaphore
 * throws, the drained permits are never returned, and the agent type stops dispatching for good.
 * Passes overlap by design -- a nudge per fold plus the poll -- so this is the case that matters.
 *
 * <p>The park tests show that a running row of the right attempt can be marked parked, that it is
 * then due at its deadline and not before, that it is still found running and retired as any other,
 * and that a settled row, a pending row or another attempt's number marks nothing.
 */
@Tag("container")
@DisplayName("Claiming effects")
class JdbcEffectClaimTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("claimed");
  private static final AgentType TYPE_CHURNED = new AgentType("churned");
  private static final int ROUNDS = 150;
  private static final int EFFECTS = 400;
  private static final int CLAIMERS = 8;
  private static final int BATCH = 4;

  private final DataSource dataSource = database();

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private QueuedBackend backend() {
    return new JdbcQueuedBackend(
        dataSource,
        new JdbcTransactionManager(dataSource),
        new JacksonCodecFactory(JsonMapper.builder().build()));
  }

  @Test
  @DisplayName("never takes more than its batch, nor a row another claim took, under contention")
  void concurrent_claims_never_exceed_their_batch_or_overlap() throws Exception {
    QueuedBackend backend = backend();
    Instant now = Instant.parse("2026-10-03T00:00:00Z");
    for (int i = 0; i < EFFECTS; i++) {
      AgentId agent = AgentId.random();
      backend.agents().ensure(TYPE, agent);
      backend
          .effects()
          .insert(
              TYPE,
              agent,
              new AgentEffect.Infer(new TurnId(1)),
              Duration.ofMinutes(1),
              new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
              now.plus(Duration.ofHours(1)),
              null,
              now);
    }
    List<Integer> sizes = new ArrayList<>();
    Set<Object> claimed = ConcurrentHashMap.newKeySet();
    List<Object> duplicates = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(CLAIMERS)) {
      List<Future<List<Integer>>> claimers = new ArrayList<>();
      for (int c = 0; c < CLAIMERS; c++) {
        QueuedBackend own = backend();
        claimers.add(
            pool.submit(
                () -> {
                  start.await();
                  List<Integer> mine = new ArrayList<>();
                  List<Attempt> batch;
                  do {
                    batch = own.effects().markRunning(TYPE, now, BATCH);
                    mine.add(batch.size());
                    for (Attempt attempt : batch) {
                      if (!claimed.add(attempt.effectId())) {
                        synchronized (duplicates) {
                          duplicates.add(attempt.effectId());
                        }
                      }
                    }
                  } while (!batch.isEmpty());
                  return mine;
                }));
      }
      start.countDown();
      for (Future<List<Integer>> claimer : claimers) {
        sizes.addAll(claimer.get());
      }
    }

    assertThat(sizes).isNotEmpty().allMatch(size -> size <= BATCH);
    assertThat(duplicates).isEmpty();
    assertThat(claimed).hasSize(EFFECTS);
  }

  @Test
  @DisplayName("never takes more than its batch while claimed rows come due again underneath it")
  void claims_never_exceed_their_batch_while_rows_are_rescheduled() throws Exception {
    QueuedBackend backend = backend();
    Instant now = Instant.parse("2026-10-03T00:00:00Z");
    for (int i = 0; i < EFFECTS; i++) {
      AgentId agent = AgentId.random();
      backend.agents().ensure(TYPE_CHURNED, agent);
      backend
          .effects()
          .insert(
              TYPE_CHURNED,
              agent,
              new AgentEffect.Infer(new TurnId(1)),
              Duration.ofMinutes(1),
              new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
              now.plus(Duration.ofHours(1)),
              null,
              now.minusSeconds(i));
    }
    List<Integer> sizes = new ArrayList<>();
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(CLAIMERS)) {
      List<Future<List<Integer>>> claimers = new ArrayList<>();
      for (int c = 0; c < CLAIMERS; c++) {
        QueuedBackend own = backend();
        int seed = c;
        claimers.add(
            pool.submit(
                () -> {
                  start.await();
                  List<Integer> mine = new ArrayList<>();
                  for (int round = 0; round < ROUNDS; round++) {
                    List<Attempt> batch = own.effects().markRunning(TYPE_CHURNED, now, BATCH);
                    mine.add(batch.size());
                    for (Attempt attempt : batch) {
                      // Due again at once, at a fresh place in the order: the churn a live
                      // dispatcher sees from retries and timed-out claims.
                      own.effects()
                          .reschedule(
                              attempt.effectId(),
                              attempt.attemptsMade(),
                              now.minusMillis((round * 7L + seed * 13L) % 5000),
                              List.of());
                    }
                  }
                  return mine;
                }));
      }
      start.countDown();
      for (Future<List<Integer>> claimer : claimers) {
        sizes.addAll(claimer.get());
      }
    }

    assertThat(sizes).isNotEmpty().allMatch(size -> size <= BATCH);
  }

  private static final Instant START = Instant.parse("2026-10-03T00:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);
  private static final Instant DEADLINE = START.plus(Duration.ofHours(1));

  /** One pending effect of its own agent type, due at START, with a one-hour deadline. */
  private Effects insertedEffect(AgentType type) {
    Effects effects = backend().effects();
    AgentId agent = AgentId.random();
    backend().agents().ensure(type, agent);
    effects.insert(
        type,
        agent,
        new AgentEffect.Infer(new TurnId(1)),
        TIMEOUT,
        new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
        DEADLINE,
        null,
        START);
    return effects;
  }

  private OffsetDateTime column(UUID effectId, String column) {
    return JdbcClient.create(dataSource)
        .sql("SELECT " + column + " FROM nessy_agent_effect WHERE effect_id = ?")
        .param(effectId)
        .query((rs, n) -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)))
        .single()
        .orElse(null);
  }

  private String statusOf(UUID effectId) {
    return JdbcClient.create(dataSource)
        .sql("SELECT status FROM nessy_agent_effect WHERE effect_id = ?")
        .param(effectId)
        .query(String.class)
        .single();
  }

  private static AgentType uniqueType() {
    return new AgentType("parked-" + UUID.randomUUID());
  }

  @Test
  @DisplayName("a parked row is due at its deadline, and records when it was parked")
  void a_parked_row_is_due_at_its_deadline() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    Instant parkedAt = START.plusSeconds(5).truncatedTo(ChronoUnit.MICROS);

    boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), parkedAt);

    assertThat(parked).isTrue();
    assertThat(column(attempt.effectId(), "actionable_at").toInstant()).isEqualTo(DEADLINE);
    assertThat(column(attempt.effectId(), "deadline").toInstant()).isEqualTo(DEADLINE);
    assertThat(column(attempt.effectId(), "parked_at").toInstant()).isEqualTo(parkedAt);
    assertThat(column(attempt.effectId(), "updated_at").toInstant()).isEqualTo(parkedAt);
    assertThat(statusOf(attempt.effectId())).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("an unparked claim is taken again after its timeout, before the deadline")
  void an_unparked_row_is_claimed_again_when_its_timeout_passes() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    effects.markRunning(type, START, 1);

    List<Attempt> again = effects.markRunning(type, START.plus(TIMEOUT).plusSeconds(1), 1);

    assertThat(again).hasSize(1);
    assertThat(again.getFirst().attemptsMade()).isEqualTo(2);
  }

  @Test
  @DisplayName("a parked row is not claimed before its deadline when the claimer's clock is behind")
  void a_parked_row_is_not_claimed_before_its_deadline_when_the_claimers_clock_is_behind() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    assertThat(column(attempt.effectId(), "actionable_at").toInstant())
        .as("a claim alone is due again after its timeout, long before the deadline")
        .isEqualTo(START.plus(TIMEOUT))
        .isBefore(DEADLINE);
    effects.park(attempt.effectId(), attempt.attemptsMade(), START.plusSeconds(5));

    List<Attempt> afterTimeout = effects.markRunning(type, START.plus(TIMEOUT).plusSeconds(1), 1);
    List<Attempt> justBeforeDeadline = effects.markRunning(type, DEADLINE.minusSeconds(1), 1);

    assertThat(afterTimeout).isEmpty();
    assertThat(justBeforeDeadline).isEmpty();
  }

  @Test
  @DisplayName("a parked row is claimed at its deadline")
  void a_parked_row_is_claimed_at_its_deadline() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    effects.park(attempt.effectId(), attempt.attemptsMade(), START.plusSeconds(5));

    List<Attempt> atDeadline = effects.markRunning(type, DEADLINE, 1);

    assertThat(atDeadline).hasSize(1);
    assertThat(atDeadline.getFirst().effectId()).isEqualTo(attempt.effectId());
    assertThat(atDeadline.getFirst().attemptsMade()).isEqualTo(2);
  }

  @Test
  @DisplayName("parking a row that was settled marks nothing")
  void parking_a_row_that_was_settled_marks_nothing() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    effects.complete(attempt.effectId(), attempt.attemptsMade());

    boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), START);

    assertThat(parked).isFalse();
  }

  @Test
  @DisplayName("parking a pending row marks nothing")
  void parking_a_pending_row_marks_nothing() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    effects.reschedule(attempt.effectId(), attempt.attemptsMade(), START, List.of());

    boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade(), START);

    assertThat(parked).isFalse();
    assertThat(column(attempt.effectId(), "parked_at")).isNull();
  }

  @Test
  @DisplayName("parking under another attempt's number marks nothing")
  void parking_under_another_attempts_number_marks_nothing() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();

    boolean parked = effects.park(attempt.effectId(), attempt.attemptsMade() + 1, START);

    assertThat(parked).isFalse();
    assertThat(column(attempt.effectId(), "parked_at")).isNull();
    assertThat(column(attempt.effectId(), "actionable_at").toInstant())
        .isEqualTo(START.plus(TIMEOUT));
  }

  @Test
  @DisplayName("a parked row is still found running, so a later reply finds it")
  void a_parked_row_is_still_found_running() {
    AgentType type = uniqueType();
    AgentId agent = AgentId.random();
    QueuedBackend backend = backend();
    backend.agents().ensure(type, agent);
    Effects effects = backend.effects();
    effects.insert(
        type,
        agent,
        new AgentEffect.Infer(new TurnId(1)),
        TIMEOUT,
        new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported()),
        DEADLINE,
        null,
        START);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    effects.park(attempt.effectId(), attempt.attemptsMade(), START);

    List<Attempt> running = effects.runningFor(type, agent);

    assertThat(running).extracting(Attempt::effectId).containsExactly(attempt.effectId());
  }

  @Test
  @DisplayName("a parked row is retired as any other")
  void a_parked_row_is_retired_as_any_other() {
    AgentType type = uniqueType();
    Effects effects = insertedEffect(type);
    Attempt attempt = effects.markRunning(type, START, 1).getFirst();
    effects.park(attempt.effectId(), attempt.attemptsMade(), START);

    boolean retired = effects.complete(attempt.effectId(), attempt.attemptsMade());

    assertThat(retired).isTrue();
    assertThat(effects.markRunning(type, DEADLINE, 1)).isEmpty();
  }
}
