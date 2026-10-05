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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.effect.LiveEffect;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Reading the live work of effects held in this process")
class InMemoryEffectsLiveTest {

  private static final AgentType TYPE = new AgentType("live");
  private static final AgentType OTHER_TYPE = new AgentType("other");
  private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(1);
  private static final Instant DEADLINE = START.plus(Duration.ofHours(1));
  private static final TurnId UNREADABLE = new TurnId(13);

  /** Writes bytes no effect codec can read for the one effect that names {@link #UNREADABLE}. */
  private static final class PoisoningCodecs implements CodecFactory {

    private final CodecFactory real = new JacksonCodecFactory(JsonMapper.builder().build());

    @Override
    public <T> Codec<T> create(TypeRef<T> type) {
      Codec<T> codec = real.create(type);
      return new Codec<>() {
        @Override
        public byte[] encode(T value) {
          boolean poisoned =
              value instanceof AgentEffect.Infer infer && infer.turn().equals(UNREADABLE);
          return poisoned ? new byte[] {1, 2, 3} : codec.encode(value);
        }

        @Override
        public T decode(byte[] bytes) {
          return codec.decode(bytes);
        }
      };
    }
  }

  private final Effects effects = new InMemoryEffects(new PoisoningCodecs());
  private final AgentId agent = AgentId.random();

  private void insert(AgentType type, AgentId owner, long turn, Instant at) {
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
      insert(TYPE, agent, UNREADABLE.value(), START.plusSeconds(1));
      List<Attempt> claimed = effects.markRunning(TYPE, START.plusSeconds(2), 10);
      claimed.forEach(a -> effects.park(a.effectId(), a.attemptsMade(), START.plusSeconds(2)));

      List<LiveEffect> live = effects.liveFor(TYPE, agent);
      List<LiveEffect> parked =
          effects.parkedNow(Optional.of(TYPE), START.plusSeconds(3), Optional.empty(), 10);

      assertThat(claimed).hasSize(2);
      assertThat(live)
          .extracting(LiveEffect::effect)
          .containsExactly(new AgentEffect.Infer(new TurnId(1)));
      assertThat(parked)
          .extracting(LiveEffect::effect)
          .containsExactly(new AgentEffect.Infer(new TurnId(1)));
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
    void a_row_marked_parked_that_is_pending_again_is_not_parked_now() {
      insert(TYPE, agent, 1, START);
      Attempt attempt = claimed(TYPE, START);
      effects.park(attempt.effectId(), attempt.attemptsMade(), START);
      boolean rescheduled =
          effects.reschedule(attempt.effectId(), attempt.attemptsMade(), START, List.of());

      LiveEffect live = effects.liveFor(TYPE, agent).getFirst();

      assertThat(rescheduled).isTrue();
      assertThat(live.running()).isFalse();
      assertThat(live.parkedAt()).as("the mark outlives the reschedule").isPresent();
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
    void a_row_that_cannot_be_decoded_does_not_end_a_page() {
      insert(TYPE, agent, UNREADABLE.value(), START);
      insert(TYPE, AgentId.random(), 1, START.plusSeconds(1));
      insert(TYPE, AgentId.random(), 2, START.plusSeconds(2));
      List<Attempt> claimed = effects.markRunning(TYPE, START.plusSeconds(3), 10);
      claimed.forEach(a -> effects.park(a.effectId(), a.attemptsMade(), START.plusSeconds(3)));
      Instant now = START.plusSeconds(4);

      List<LiveEffect> page = effects.parkedNow(Optional.of(TYPE), now, Optional.empty(), 1);

      assertThat(claimed).hasSize(3);
      assertThat(page).hasSize(1);
      assertThat(page.getFirst().effect()).isEqualTo(new AgentEffect.Infer(new TurnId(1)));
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
