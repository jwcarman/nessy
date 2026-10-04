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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FirstStoreHoldingStoriesTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents first = new InMemoryAgentEvents(codecs);
  private final AgentEvents second = new InMemoryAgentEvents(codecs);
  private final Payloads firstPayloads = new InMemoryPayloads(codecs);
  private final Payloads secondPayloads = new InMemoryPayloads(codecs);
  private final FirstStoreHoldingStories stories =
      new FirstStoreHoldingStories(
          List.of(
              new FirstStoreHoldingStories.Store(first, firstPayloads),
              new FirstStoreHoldingStories.Store(second, secondPayloads)));
  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq) {
    return new AgentEvent.TurnStarted(
        new Seq(seq), new TurnId(seq), new PayloadRef("p"), Instant.EPOCH);
  }

  @Test
  void a_story_is_read_from_the_first_store_that_holds_it() {
    second.append(TYPE, agent, List.of(started(1)), Seq.NONE, AT);
    assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 10)).hasSize(1);
  }

  @Test
  void paging_past_the_end_of_a_story_does_not_fall_through_to_another_store() {
    first.append(TYPE, agent, List.of(started(1), started(2)), Seq.NONE, AT);
    second.append(TYPE, agent, List.of(started(1), started(2), started(3)), Seq.NONE, AT);
    List<Narrated> past = stories.of(TYPE, agent).replay(new Seq(2), 10);

    assertThat(past).isEmpty();
  }

  @Test
  void an_agent_no_store_holds_has_an_empty_story() {
    assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 10)).isEmpty();
  }

  private static final StoryProjection<Integer> TURNS_STARTED =
      new StoryProjection<>() {
        @Override
        public Integer initial() {
          return -1;
        }

        @Override
        public Integer apply(Integer soFar, Narrated story) {
          int base = soFar < 0 ? 0 : soFar;
          return story.event() instanceof Narration.TurnStarted ? base + 1 : base;
        }
      };

  @Test
  void a_projection_folds_the_story_of_the_store_that_holds_it() {
    first.append(TYPE, agent, List.of(started(1), started(2)), Seq.NONE, AT);
    second.append(TYPE, agent, List.of(started(1), started(2), started(3)), Seq.NONE, AT);
    assertThat(stories.of(TYPE, agent).project(TURNS_STARTED)).isEqualTo(2);
  }

  @Test
  void a_projection_over_an_agent_no_store_holds_is_its_initial_value() {
    assertThat(stories.of(TYPE, agent).project(TURNS_STARTED)).isEqualTo(-1);
  }

  @Test
  void content_is_read_from_the_store_that_holds_the_agent() {
    PayloadRef input = firstPayloads.forAgent(agent).put(List.of(new Block.Text("hello")));
    first.append(
        TYPE,
        agent,
        List.of(new AgentEvent.TurnStarted(new Seq(1), new TurnId(1), input, Instant.EPOCH)),
        Seq.NONE,
        AT);
    second.append(TYPE, agent, List.of(started(1)), Seq.NONE, AT);

    assertThat(stories.of(TYPE, agent).content().turn(new TurnId(1)).input())
        .containsExactly(new Block.Text("hello"));
  }

  @Test
  void content_of_an_agent_no_store_holds_is_empty() {
    StoryContent content = stories.of(TYPE, agent).content();

    assertThat(content.results(Seq.NONE, 10)).isEmpty();
    assertThat(content.result(IdempotencyKey.of(UUID.randomUUID()))).isEmpty();
  }

  @Test
  void a_turn_of_an_agent_no_store_holds_is_refused() {
    StoryContent content = stories.of(TYPE, agent).content();

    TurnId missing = new TurnId(1);
    assertThatThrownBy(() -> content.turn(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no turn 1 in this agent's story");
  }

  @Test
  void results_of_an_agent_no_store_holds_are_refused_a_limit_that_is_not_positive() {
    StoryContent content = stories.of(TYPE, agent).content();

    assertThatThrownBy(() -> content.results(Seq.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be positive");
  }

  @Test
  void replaying_with_a_limit_that_is_not_positive_is_refused_for_an_agent_no_store_holds() {
    AgentStory story = stories.of(TYPE, agent);

    assertThatThrownBy(() -> story.replay(Seq.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("limit must be positive");
  }

  @Test
  void a_story_without_a_type_is_refused() {
    assertThatThrownBy(() -> stories.of(null, agent))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("type must not be null");
  }

  @Test
  void a_story_without_an_id_is_refused() {
    assertThatThrownBy(() -> stories.of(TYPE, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("id must not be null");
  }

  @Test
  void a_missing_turn_of_an_agent_no_store_holds_is_refused() {
    StoryContent content = stories.of(TYPE, agent).content();

    assertThatThrownBy(() -> content.turn(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("turn must not be null");
  }
}
