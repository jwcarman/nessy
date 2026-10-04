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
package org.jwcarman.nessy.engine.story;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryChapters;
import org.jwcarman.nessy.backend.inmemory.InMemoryLeases;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.narration.StoryEvents;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventAgentStoriesTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents events = new InMemoryAgentEvents(codecs);
  private final EventAgentStories stories = new EventAgentStories(events);
  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq) {
    return new AgentEvent.TurnStarted(
        new Seq(seq), new TurnId(seq), new PayloadRef("p"), Instant.EPOCH);
  }

  private static AgentEvent answered(long seq, long turn) {
    return new AgentEvent.InferenceAnswered(
        new Seq(seq), new TurnId(turn), new PayloadRef("a"), Usage.unreported());
  }

  private void threeEvents() {
    events.append(TYPE, agent, List.of(started(1), answered(2, 1)), Seq.NONE, AT);
    events.append(TYPE, agent, List.of(started(3)), new Seq(2), AT.plusSeconds(60));
  }

  @Nested
  class A_story {

    @Test
    void an_agent_nobody_told_anything_has_an_empty_story() {
      assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 10)).isEmpty();
    }

    @Test
    void is_replayed_oldest_first_with_each_events_position() {
      threeEvents();

      List<Narrated> replayed = stories.of(TYPE, agent).replay(Seq.NONE, 10);

      assertThat(replayed)
          .containsExactly(
              Narrated.story(TYPE, agent, StoryEvents.of(started(1)), new Seq(1), AT),
              Narrated.story(TYPE, agent, StoryEvents.of(answered(2, 1)), new Seq(2), AT),
              Narrated.story(
                  TYPE, agent, StoryEvents.of(started(3)), new Seq(3), AT.plusSeconds(60)));
    }

    @Test
    void replayed_after_a_position_starts_with_the_next_event() {
      threeEvents();

      List<Narrated> replayed = stories.of(TYPE, agent).replay(new Seq(2), 10);

      assertThat(replayed)
          .extracting(n -> n.position().orElseThrow().seq())
          .containsExactly(new Seq(3));
    }

    @Test
    void replays_no_more_than_its_limit() {
      threeEvents();

      List<Narrated> replayed = stories.of(TYPE, agent).replay(Seq.NONE, 2);

      assertThat(replayed)
          .extracting(n -> n.position().orElseThrow().seq())
          .containsExactly(new Seq(1), new Seq(2));
    }

    @Test
    void a_limit_above_a_thousand_is_capped_at_a_thousand() {
      long count = 1_005;
      List<AgentEvent> many = new ArrayList<>();
      for (long seq = 1; seq <= count; seq++) {
        many.add(started(seq));
      }
      events.append(TYPE, agent, many, Seq.NONE, AT);

      List<Narrated> replayed = stories.of(TYPE, agent).replay(Seq.NONE, Integer.MAX_VALUE);

      assertThat(replayed).hasSize(1_000);
    }

    @Test
    void a_limit_of_zero_is_refused() {
      AgentStory story = stories.of(TYPE, agent);

      assertThatThrownBy(() -> story.replay(Seq.NONE, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("limit must be positive");
    }

    @Test
    void a_negative_limit_is_refused() {
      AgentStory story = stories.of(TYPE, agent);

      assertThatThrownBy(() -> story.replay(Seq.NONE, -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("limit must be positive");
    }

    @Test
    void a_missing_position_is_refused() {
      AgentStory story = stories.of(TYPE, agent);

      assertThatThrownBy(() -> story.replay(null, 10))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("after must not be null");
    }

    @Test
    void what_a_listener_heard_live_is_what_the_replay_returns() {
      Payloads payloads = new InMemoryPayloads(codecs);
      DirectBackend backend = new Backend(events, payloads, new InMemoryLocks(), codecs);

      List<Narrated> heard = StoryTurn.heard(TYPE, agent, backend, Clock.fixed(AT, ZoneOffset.UTC));

      assertThat(heard).isNotEmpty();
      assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 100)).isEqualTo(heard);
    }
  }

  /** The three stores a test holds, and in-memory chapters and leases for the rest. */
  record Backend(
      AgentEvents events, Payloads payloads, Locks locks, Chapters chapters, Leases leases)
      implements DirectBackend {

    Backend(AgentEvents events, Payloads payloads, Locks locks, JacksonCodecFactory codecs) {
      this(events, payloads, locks, new InMemoryChapters(codecs), new InMemoryLeases());
    }
  }
}
