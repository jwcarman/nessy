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

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.backlog.Backlog;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Counting the inputs an in-memory agent has been told")
class InMemoryQueuedCountTest {

  private static final AgentType TYPE = new AgentType("counted");
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  private final QueuedBackend backend =
      new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  @Test
  void the_count_of_inputs_told_and_not_started() {
    Backlog<String> backlog = backend.backlogs(TypeRef.of(String.class)).forAgent(TYPE, agent);
    backlog.append(new BacklogItem<>("one", NOW));
    backlog.append(new BacklogItem<>("two", NOW));
    backlog.append(new BacklogItem<>("three", NOW));
    backlog.take();

    assertThat(backend.queued(TYPE, agent)).isEqualTo(2);
  }

  @Test
  void an_agent_nobody_told_anything_has_nothing_queued() {
    assertThat(backend.queued(TYPE, agent)).isZero();
  }
}
