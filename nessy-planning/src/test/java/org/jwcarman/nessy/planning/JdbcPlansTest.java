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
package org.jwcarman.nessy.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("The plan an agent keeps")
class JdbcPlansTest {

  // Fresh per test: the database is shared by the whole JVM, and an agent is the unit of isolation.
  private final AgentId agentOne = Calls.agent();
  private final AgentId agentTwo = Calls.agent();

  private static final Plan.Task WRITE = new Plan.Task("Write the store", Plan.Status.IN_PROGRESS);
  private static final Plan.Task TEST = new Plan.Task("Test it", Plan.Status.PENDING);

  private Plans plans;

  @BeforeEach
  void fresh() {
    plans = new JdbcPlans(Calls.database(), Calls.TYPE, Calls.codecs());
  }

  @Test
  void an_agent_that_has_planned_nothing_has_no_plan() {
    assertThat(plans.find(agentOne)).isEmpty();
  }

  @Test
  void a_saved_plan_comes_back_in_the_order_it_was_written() {
    plans.save(agentOne, new Plan(List.of(WRITE, TEST)));

    assertThat(plans.find(agentOne)).contains(new Plan(List.of(WRITE, TEST)));
  }

  /** The whole point of wholesale replacement: what you save is what is there, entirely. */
  @Test
  @DisplayName("saving replaces the plan rather than adding to it")
  void a_second_save_wins_outright() {
    plans.save(agentOne, new Plan(List.of(WRITE, TEST)));

    plans.save(agentOne, new Plan(List.of(new Plan.Task("Something else", Plan.Status.PENDING))));

    assertThat(plans.find(agentOne).orElseThrow().tasks()).hasSize(1);
  }

  /**
   * A durable re-drive can execute the same tool call twice. Wholesale replacement makes that a
   * non-event: the second write stores the identical list.
   */
  @Test
  void saving_the_same_plan_twice_leaves_the_same_plan() {
    Plan plan = new Plan(List.of(WRITE, TEST));

    plans.save(agentOne, plan);
    plans.save(agentOne, plan);

    assertThat(plans.find(agentOne)).contains(plan);
  }

  @Test
  @DisplayName("an emptied plan reads as no plan, not as a plan with nothing in it")
  void clearing_returns_the_agent_to_having_none() {
    plans.save(agentOne, new Plan(List.of(WRITE)));

    plans.save(agentOne, Plan.empty());

    assertThat(plans.find(agentOne)).isEmpty();
  }

  @Test
  void one_agent_cannot_see_another_agents_plan() {
    plans.save(agentOne, new Plan(List.of(WRITE)));

    assertThat(plans.find(agentTwo)).isEmpty();
  }

  @Test
  @DisplayName("two agent types keep separate plans even under the same id")
  void the_agent_type_scopes_the_store() {
    DataSource shared = Calls.database();
    Plans chat = new JdbcPlans(shared, new AgentType("chat"), Calls.codecs());
    Plans watchman = new JdbcPlans(shared, new AgentType("watchman"), Calls.codecs());
    chat.save(agentOne, new Plan(List.of(WRITE)));

    assertThat(watchman.find(agentOne)).isEmpty();
  }

  @Nested
  @DisplayName("with a storage transform applied")
  class Encoded {

    private final DataSource database = Calls.database();
    private final Plans encoded = new JdbcPlans(database, Calls.TYPE, Calls.transforming());
    private final Plan plan =
        new Plan(
            List.of(
                new Plan.Task(
                    "Email Ms. Okonkwo-Reyes about invoice 9087-1123", Plan.Status.PENDING),
                new Plan.Task("Close ticket Bluebird-4471", Plan.Status.IN_PROGRESS)));

    private List<String> rawTitles() {
      return JdbcClient.create(database)
          .sql("SELECT title FROM nessy_plan_task WHERE agent_id = ? ORDER BY ordinal")
          .params(agentOne.value().toString())
          .query(byte[].class)
          .list()
          .stream()
          .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
          .toList();
    }

    @Test
    void the_stored_titles_do_not_contain_the_text() {
      encoded.save(agentOne, plan);

      List<String> titles = rawTitles();

      assertThat(titles)
          .hasSize(2)
          .noneMatch(title -> title.contains("Okonkwo"))
          .noneMatch(title -> title.contains("9087-1123"))
          .noneMatch(title -> title.contains("Bluebird"));
    }

    @Test
    void the_status_stays_readable_in_its_own_column() {
      encoded.save(agentOne, plan);

      List<String> statuses =
          JdbcClient.create(database)
              .sql("SELECT status FROM nessy_plan_task WHERE agent_id = ? ORDER BY ordinal")
              .params(agentOne.value().toString())
              .query(String.class)
              .list();

      assertThat(statuses).containsExactly("PENDING", "IN_PROGRESS");
    }

    @Test
    void the_plan_reads_its_own_writes_back_exactly() {
      encoded.save(agentOne, plan);

      assertThat(encoded.find(agentOne)).contains(plan);
    }
  }

  @Test
  void a_blank_title_is_refused() {
    List<Plan.Task> blank = List.of(new Plan.Task(" ", Plan.Status.PENDING));

    assertThatThrownBy(() -> new Plan(blank)).isInstanceOf(IllegalArgumentException.class);
  }
}
