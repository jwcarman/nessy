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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A claim takes at most the batch it was asked for, however many passes claim at once.
 *
 * <p>The dispatcher drains its permits, claims that many rows, and hands back the difference. A
 * claim that returns more rows than it was asked for makes that difference negative: the semaphore
 * throws, the drained permits are never returned, and the agent type stops dispatching for good.
 * Passes overlap by design -- a nudge per fold plus the poll -- so this is the case that matters.
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
}
