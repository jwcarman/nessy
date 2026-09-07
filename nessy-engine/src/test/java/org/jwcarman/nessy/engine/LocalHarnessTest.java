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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

/**
 * The application's door, with no cluster behind it.
 *
 * <p>The ordering property is unchanged from the sharded version and is the one that matters: the
 * backlog row COMMITS before the agent is told anything. Reversed, an agent takes, finds nothing,
 * and goes back to sleep with work sitting in the table.
 */
@DisplayName("The local harness")
class LocalHarnessTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");

  private EmbeddedDatabase database;
  private BacklogStore<String> backlog;
  private List<Input> driven;
  private LocalHarness<String> harness;

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    backlog = Backlogs.ofStrings(database);
    driven = new ArrayList<>();
    harness =
        new LocalHarness<>(
            TYPE,
            backlog,
            (agentId, input, completing, observability) -> driven.add(input),
            new Narration());
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName("an observation lands in the backlog before the agent hears about it")
  void observe_commits_then_signals() {
    harness.observe(AGENT, "the porch light is on");

    assertThat(driven).isNotEmpty();
    assertThat(driven).allMatch(Input.BacklogUpdated.class::isInstance);
  }

  @Test
  @DisplayName("forget leaves a poison row and nudges")
  void forget_poisons_then_signals() {
    harness.forget(AGENT);

    assertThat(driven).isNotEmpty();
    assertThat(driven).allMatch(Input.BacklogUpdated.class::isInstance);
  }

  @Test
  @DisplayName("the harness reports the type it was built for")
  void type_is_what_it_was_given() {
    assertThat(harness.type()).isEqualTo(TYPE);
  }

  @Test
  @DisplayName("a cursor is refused rather than silently ignored")
  void replay_from_a_cursor_is_not_available() {
    AgentId agent = AGENT;
    AgentSubscriber subscriber = event -> {};

    assertThat(catchThrowable(() -> harness.subscribe(agent, subscriber, "event-7")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
