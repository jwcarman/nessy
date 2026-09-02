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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * The settings a console application fills in.
 *
 * <p>These two — the substrate and the memory — exist so that {@link Repl} DEFAULTS them rather
 * than owning them: a notebook or a plan is opened over a substrate, and an application that cannot
 * name the one its agent writes to cannot give the agent either.
 */
@DisplayName("A REPL's configuration")
class ReplConfigTest {

  private final ReplConfig config = new ReplConfig();

  @Nested
  @DisplayName("the database")
  class TheDatabase {

    @Test
    @DisplayName("is there without being asked for, so the easy button stays easy")
    void defaults_to_a_database_that_works() {
      assertThat(config.dataSource()).isNotNull();
    }

    @Test
    @DisplayName("is the caller's own when they have one, which is the whole point")
    void a_supplied_database_is_the_one_used() {
      DataSource mine = TestDatabase.fresh();

      config.dataSource(mine);

      assertThat(config.dataSource()).isSameAs(mine);
    }

    @Test
    void two_configurations_do_not_share_a_default_database() {
      assertThat(config.dataSource()).isNotSameAs(new ReplConfig().dataSource());
    }

    @Test
    void null_is_refused_rather_than_silently_meaning_the_default() {
      assertThatThrownBy(() -> config.dataSource(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("the memory")
  class TheMemory {

    /** A memory that does nothing, because what it does is not what this asserts. */
    private final Memory mine =
        new Memory() {
          @Override
          public Context recall(AgentId agentId) {
            return Context.of(List.of());
          }

          @Override
          public void remember(AgentId agentId, HistoryMessage message) {
            // nothing to keep
          }

          @Override
          public void forget(AgentId agentId) {
            // Nothing kept here.
          }
        };

    /**
     * Empty rather than a stand-in, so {@link Repl} says nothing to the harness and the harness
     * keeps its OWN default. A default invented here would be a second answer to a question the
     * engine has already answered.
     */
    @Test
    @DisplayName("is unset by default, leaving the choice to the harness")
    void is_absent_until_someone_says_otherwise() {
      assertThat(config.memory()).isEmpty();
    }

    @Test
    void a_supplied_memory_is_the_one_used() {
      config.memory(mine);

      assertThat(config.memory()).containsSame(mine);
    }

    @Test
    void null_is_refused_rather_than_silently_meaning_the_default() {
      assertThatThrownBy(() -> config.memory(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
