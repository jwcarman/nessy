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
package org.jwcarman.nessy.memory.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.testing.TestDatabase;

@DisplayName("The plan an agent keeps")
class JdbcPlanStoreTest {

  private static final AgentId ONE = AgentId.of("agent-one");
  private static final AgentId TWO = AgentId.of("agent-two");

  private static final Plan.Task WRITE = new Plan.Task("Write the store", Plan.Status.IN_PROGRESS);
  private static final Plan.Task TEST = new Plan.Task("Test it", Plan.Status.PENDING);

  private PlanStore plans;

  @BeforeEach
  void fresh() {
    plans = new JdbcPlanStore(TestDatabase.fresh(), AgentType.of("chat"));
  }

  @Test
  void an_agent_that_has_planned_nothing_has_no_plan() {
    assertThat(plans.find(ONE)).isEmpty();
  }

  @Test
  void a_saved_plan_comes_back_in_the_order_it_was_written() {
    plans.save(ONE, new Plan(List.of(WRITE, TEST)));

    assertThat(plans.find(ONE)).contains(new Plan(List.of(WRITE, TEST)));
  }

  /** The whole point of wholesale replacement: what you save is what is there, entirely. */
  @Test
  @DisplayName("saving replaces the plan rather than adding to it")
  void a_second_save_wins_outright() {
    plans.save(ONE, new Plan(List.of(WRITE, TEST)));

    plans.save(ONE, new Plan(List.of(new Plan.Task("Something else", Plan.Status.PENDING))));

    assertThat(plans.find(ONE).orElseThrow().tasks()).hasSize(1);
  }

  /**
   * A durable re-drive can execute the same tool call twice. Wholesale replacement makes that a
   * non-event: the second write stores the identical list.
   */
  @Test
  void saving_the_same_plan_twice_leaves_the_same_plan() {
    Plan plan = new Plan(List.of(WRITE, TEST));

    plans.save(ONE, plan);
    plans.save(ONE, plan);

    assertThat(plans.find(ONE)).contains(plan);
  }

  @Test
  @DisplayName("an emptied plan reads as no plan, not as a plan with nothing in it")
  void clearing_returns_the_agent_to_having_none() {
    plans.save(ONE, new Plan(List.of(WRITE)));

    plans.save(ONE, Plan.empty());

    assertThat(plans.find(ONE)).isEmpty();
  }

  @Test
  void one_agent_cannot_see_another_agents_plan() {
    plans.save(ONE, new Plan(List.of(WRITE)));

    assertThat(plans.find(TWO)).isEmpty();
  }

  @Test
  @DisplayName("two agent types keep separate plans even under the same id")
  void the_agent_type_scopes_the_store() {
    javax.sql.DataSource shared = TestDatabase.fresh();
    PlanStore chat = new JdbcPlanStore(shared, AgentType.of("chat"));
    PlanStore watchman = new JdbcPlanStore(shared, AgentType.of("watchman"));
    chat.save(ONE, new Plan(List.of(WRITE)));

    assertThat(watchman.find(ONE)).isEmpty();
  }

  @Test
  void a_blank_title_is_refused() {
    List<Plan.Task> blank = List.of(new Plan.Task(" ", Plan.Status.PENDING));

    assertThatThrownBy(() -> new Plan(blank)).isInstanceOf(IllegalArgumentException.class);
  }
}
