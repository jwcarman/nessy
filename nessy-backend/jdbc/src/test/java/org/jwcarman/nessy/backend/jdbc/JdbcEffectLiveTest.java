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
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.jwcarman.nessy.backend.effect.LiveEffect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an agent still has live in the effect table, and which rows are waiting on an answer right
 * now. Reads only: nothing here changes a row the claim, park, complete and reschedule statements
 * wrote.
 */
@Tag("container")
@DisplayName("Reading the live work of the effect table")
class JdbcEffectLiveTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static final AgentType TYPE = new AgentType("live");
  private static final AgentType OTHER_TYPE = new AgentType("other");
  private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);
  private static final Instant DEADLINE = START.plus(Duration.ofHours(1));

  private final DataSource dataSource = database();
  private final QueuedBackend backend =
      new JdbcQueuedBackend(
          dataSource,
          new JdbcTransactionManager(dataSource),
          new JacksonCodecFactory(JsonMapper.builder().build()));
  private final Effects effects = backend.effects();
  private final AgentId agent = AgentId.random();

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  @BeforeEach
  void an_empty_effect_table() {
    JdbcClient.create(dataSource).sql("DELETE FROM nessy_agent_effect").update();
  }

  private void insert(AgentType type, AgentId owner, long turn, Instant at) {
    backend.agents().ensure(type, owner);
    effects.insert(
        type,
        owner,
        new AgentEffect.Infer(new TurnId(turn)),
        TIMEOUT,
        new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported(), Optional.empty()),
        DEADLINE,
        null,
        at);
  }

  private Attempt claimed(AgentType type, Instant now) {
    return effects.markRunning(type, now, 1).getFirst();
  }

  private void parkedRows(AgentType type, int count, Instant at) {
    for (int turn = 1; turn <= count; turn++) {
      insert(type, AgentId.random(), turn, at);
    }
    List<Attempt> claimed = effects.markRunning(type, at, count);
    claimed.forEach(a -> effects.park(a.effectId(), a.attemptsMade(), at));
  }

  @Nested
  @DisplayName("The live rows of one agent")
  class TheLiveRowsOfOneAgent {

    @Test
    void an_agents_live_rows_come_oldest_first() {
      insert(TYPE, agent, 3, START.plusSeconds(2));
      insert(TYPE, agent, 1, START);
      insert(TYPE, AgentId.random(), 9, START);
      insert(TYPE, agent, 2, START.plusSeconds(1));

      List<LiveEffect> live = effects.liveFor(TYPE, agent);

      assertThat(live)
          .extracting(LiveEffect::effect)
          .containsExactly(
              new AgentEffect.Infer(new TurnId(1)),
              new AgentEffect.Infer(new TurnId(2)),
              new AgentEffect.Infer(new TurnId(3)));
    }

    @Test
    void a_pending_row_is_live_and_not_parked() {
      insert(TYPE, agent, 1, START);

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();

      assertThat(live.agentType()).isEqualTo(TYPE);
      assertThat(live.agentId()).isEqualTo(agent);
      assertThat(live.createdAt()).isEqualTo(START);
      assertThat(live.deadline()).isEqualTo(DEADLINE);
      assertThat(live.attemptsMade()).isZero();
      assertThat(live.running()).isFalse();
      assertThat(live.parkedAt()).isEmpty();
      assertThat(live.parkedNow(START)).isFalse();
      assertThat(effects.parkedNow(Optional.empty(), START, Optional.empty(), 10)).isEmpty();
    }

    @Test
    void a_finished_row_is_gone() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);
      effects.complete(attempt.effectId(), attempt.attemptsMade());

      assertThat(effects.liveFor(TYPE, agent)).isEmpty();
      assertThat(effects.parkedNow(Optional.empty(), START, Optional.empty(), 10)).isEmpty();
    }

    @Test
    void a_row_that_cannot_be_decoded_is_skipped() {
      insert(TYPE, agent, 1, START);
      insert(TYPE, agent, 2, START.plusSeconds(1));
      List<Attempt> claimed = effects.markRunning(TYPE, START.plusSeconds(2), 10);
      claimed.forEach(a -> effects.park(a.effectId(), a.attemptsMade(), START.plusSeconds(2)));
      int spoiled =
          JdbcClient.create(dataSource)
              .sql("UPDATE nessy_agent_effect SET payload = ? WHERE effect_id = ?")
              .params(new byte[] {1, 2, 3}, claimed.getLast().effectId())
              .update();

      List<LiveEffect> live = effects.liveFor(TYPE, agent);
      List<LiveEffect> parked =
          effects.parkedNow(Optional.of(TYPE), START.plusSeconds(3), Optional.empty(), 10);

      assertThat(spoiled).isOne();
      assertThat(claimed).hasSize(2);
      assertThat(live).hasSize(1);
      assertThat(live)
          .extracting(LiveEffect::effectId)
          .doesNotContain(claimed.getLast().effectId());
      assertThat(parked)
          .extracting(LiveEffect::effectId)
          .containsExactly(live.getFirst().effectId());
    }
  }

  @Nested
  @DisplayName("The rows parked now")
  class TheRowsParkedNow {

    @Test
    void a_parked_row_is_parked_now() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START.plusSeconds(5));
      Instant now = START.plusSeconds(10);

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();
      List<LiveEffect> parked = effects.parkedNow(Optional.of(TYPE), now, Optional.empty(), 10);

      assertThat(live.running()).isTrue();
      assertThat(live.attemptsMade()).isOne();
      assertThat(live.parkedAt()).contains(START.plusSeconds(5));
      assertThat(live.parkedNow(now)).isTrue();
      assertThat(parked).containsExactly(live);
    }

    @Test
    void a_parked_row_past_its_deadline_is_not_parked_now() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);
      Instant past = DEADLINE.plusSeconds(1);

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();

      assertThat(live.parkedNow(past)).isFalse();
      assertThat(effects.parkedNow(Optional.empty(), past, Optional.empty(), 10)).isEmpty();
    }

    @Test
    void a_row_marked_parked_that_is_pending_is_not_parked_now() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);
      int reset =
          JdbcClient.create(dataSource)
              .sql("UPDATE nessy_agent_effect SET status = 'PENDING' WHERE effect_id = ?")
              .params(attempt.effectId())
              .update();

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();

      assertThat(reset).isOne();
      assertThat(live.running()).isFalse();
      assertThat(live.parkedAt()).isPresent();
      assertThat(effects.parkedNow(Optional.empty(), START, Optional.empty(), 10)).isEmpty();
    }

    @Test
    void a_parked_row_claimed_again_at_its_deadline_is_not_parked_now() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);
      Attempt again = effects.markRunning(TYPE, DEADLINE, 1).getFirst();

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();

      assertThat(again.attemptsMade()).isEqualTo(2);
      assertThat(live.running()).isTrue();
      assertThat(live.parkedAt()).as("still marked until the row is deleted").isPresent();
      assertThat(live.parkedNow(DEADLINE)).isFalse();
      assertThat(effects.parkedNow(Optional.empty(), DEADLINE, Optional.empty(), 10)).isEmpty();
    }

    @Test
    void parked_rows_of_every_type_when_no_type_is_named() {
      parkedRows(TYPE, 1, START);
      parkedRows(OTHER_TYPE, 1, START.plusSeconds(1));

      List<LiveEffect> parked =
          effects.parkedNow(Optional.empty(), START.plusSeconds(2), Optional.empty(), 10);

      assertThat(parked).extracting(LiveEffect::agentType).containsExactly(TYPE, OTHER_TYPE);
    }

    @Test
    void parked_rows_of_one_type_when_one_is_named() {
      parkedRows(TYPE, 1, START);
      parkedRows(OTHER_TYPE, 1, START.plusSeconds(1));

      List<LiveEffect> parked =
          effects.parkedNow(Optional.of(OTHER_TYPE), START.plusSeconds(2), Optional.empty(), 10);

      assertThat(parked).extracting(LiveEffect::agentType).containsExactly(OTHER_TYPE);
    }

    @Test
    void a_row_that_cannot_be_decoded_does_not_end_a_page() {
      parkedRows(TYPE, 3, START);
      Instant now = START.plusSeconds(1);
      LiveEffect first = effects.parkedNow(Optional.of(TYPE), now, Optional.empty(), 1).getFirst();
      int spoiled =
          JdbcClient.create(dataSource)
              .sql("UPDATE nessy_agent_effect SET payload = ? WHERE effect_id = ?")
              .params(new byte[] {1, 2, 3}, first.effectId())
              .update();

      List<LiveEffect> page = effects.parkedNow(Optional.of(TYPE), now, Optional.empty(), 1);

      assertThat(spoiled).isOne();
      assertThat(page).hasSize(1);
      assertThat(page.getFirst().effectId()).isNotEqualTo(first.effectId());
    }

    @Test
    void a_read_continues_after_the_row_it_is_given() {
      parkedRows(TYPE, 5, START);
      Instant now = START.plusSeconds(1);
      List<LiveEffect> whole = effects.parkedNow(Optional.of(TYPE), now, Optional.empty(), 100);
      List<LiveEffect> paged = new ArrayList<>();
      Optional<LiveEffect> after = Optional.empty();

      List<LiveEffect> page;
      do {
        page = effects.parkedNow(Optional.of(TYPE), now, after, 2);
        paged.addAll(page);
        after = page.isEmpty() ? after : Optional.of(page.getLast());
        assertThat(paged)
            .as("a wrong cursor would repeat rows forever")
            .hasSizeLessThanOrEqualTo(5);
      } while (!page.isEmpty());

      assertThat(whole).hasSize(5);
      assertThat(paged).as("none skipped or repeated, in the same order").isEqualTo(whole);
    }
  }
}
