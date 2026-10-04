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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FirstStoreHoldingStoriesTest {

  private static final AgentType TYPE = new AgentType("desk");
  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final AgentEvents first = new InMemoryAgentEvents(codecs);
  private final AgentEvents second = new InMemoryAgentEvents(codecs);
  private final AgentId agent = AgentId.random();

  private static AgentEvent started(long seq) {
    return new AgentEvent.TurnStarted(
        new Seq(seq), new TurnId(seq), new PayloadRef("p"), Instant.EPOCH);
  }

  @Test
  void a_story_is_read_from_the_first_store_that_holds_it() {
    second.append(TYPE, agent, List.of(started(1)), Seq.NONE, AT);
    FirstStoreHoldingStories stories = new FirstStoreHoldingStories(List.of(first, second));

    assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 10)).hasSize(1);
  }

  @Test
  void paging_past_the_end_of_a_story_does_not_fall_through_to_another_store() {
    first.append(TYPE, agent, List.of(started(1), started(2)), Seq.NONE, AT);
    second.append(TYPE, agent, List.of(started(1), started(2), started(3)), Seq.NONE, AT);
    FirstStoreHoldingStories stories = new FirstStoreHoldingStories(List.of(first, second));

    List<Narrated> past = stories.of(TYPE, agent).replay(new Seq(2), 10);

    assertThat(past).isEmpty();
  }

  @Test
  void an_agent_no_store_holds_has_an_empty_story() {
    FirstStoreHoldingStories stories = new FirstStoreHoldingStories(List.of(first, second));

    assertThat(stories.of(TYPE, agent).replay(Seq.NONE, 10)).isEmpty();
  }
}
