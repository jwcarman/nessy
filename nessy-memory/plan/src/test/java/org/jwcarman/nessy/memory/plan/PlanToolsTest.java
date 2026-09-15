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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

@DisplayName("The plan a model works with")
class PlanToolsTest {

  private static final AgentId AGENT = Calls.agent();

  private PlanStore plans;

  @BeforeEach
  void fresh() {
    plans = new JdbcPlanStore(Calls.freshDatabase(), Calls.TYPE);
  }

  /** What the engine hands a running tool. No mocking library, and none needed. */
  private static <I> ToolResult run(Tool<I> tool, I input) {
    Awaited<ToolResult> answer = tool.call(Calls.by(AGENT, input));
    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<ToolResult>) answer).value();
  }

  /** What a tool actually said, which is now blocks rather than a string. */
  private static String said(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return ((Block.Text) ((ToolResult.Success) result).blocks().getFirst()).text();
  }

  private static PlanTools.UpdatePlan sending(PlanTools.PlannedTask... tasks) {
    return new PlanTools.UpdatePlan(List.of(tasks));
  }

  private static PlanTools.PlannedTask task(String title, Plan.Status status) {
    return new PlanTools.PlannedTask(title, status);
  }

  @Nested
  @DisplayName("the update_plan tool")
  class Updating {

    @Test
    void records_the_list_the_model_sent() {
      run(
          PlanTools.updatePlan(plans),
          sending(
              task("Read the spec", Plan.Status.DONE),
              task("Write the code", Plan.Status.IN_PROGRESS)));

      assertThat(plans.find(AGENT).orElseThrow().tasks())
          .containsExactly(
              new Plan.Task("Read the spec", Plan.Status.DONE),
              new Plan.Task("Write the code", Plan.Status.IN_PROGRESS));
    }

    @Test
    @DisplayName("a second call replaces the plan whole, which is the contract")
    void the_latest_call_is_the_whole_plan() {
      run(PlanTools.updatePlan(plans), sending(task("First", Plan.Status.PENDING)));

      run(PlanTools.updatePlan(plans), sending(task("Second", Plan.Status.DONE)));

      assertThat(plans.find(AGENT).orElseThrow().tasks())
          .containsExactly(new Plan.Task("Second", Plan.Status.DONE));
    }

    @Test
    void an_empty_list_clears_the_plan() {
      run(PlanTools.updatePlan(plans), sending(task("First", Plan.Status.PENDING)));

      ToolResult result = run(PlanTools.updatePlan(plans), sending());

      assertThat(result).isInstanceOf(ToolResult.Success.class);
      assertThat(plans.find(AGENT)).isEmpty();
    }

    /** A re-drive executes the same call again; the plan must not double or drift. */
    @Test
    void running_the_same_call_twice_leaves_the_same_plan() {
      PlanTools.UpdatePlan call =
          sending(task("Read", Plan.Status.DONE), task("Write", Plan.Status.PENDING));

      run(PlanTools.updatePlan(plans), call);
      run(PlanTools.updatePlan(plans), call);

      assertThat(plans.find(AGENT).orElseThrow().tasks()).hasSize(2);
    }

    /** The model can read this and send a better list; a thrown exception would end the turn. */
    @Test
    @DisplayName("a blank title is a failed call, not a failed turn")
    void a_blank_title_comes_back_as_an_error() {
      ToolResult result = run(PlanTools.updatePlan(plans), sending(task(" ", Plan.Status.PENDING)));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(plans.find(AGENT)).isEmpty();
    }

    @Test
    void a_missing_status_comes_back_as_an_error() {
      ToolResult result = run(PlanTools.updatePlan(plans), sending(task("Read", null)));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(plans.find(AGENT)).isEmpty();
    }

    @Test
    @DisplayName("the confirmation tells the model what plan it now has")
    void the_model_reads_back_a_count() {
      ToolResult result =
          run(
              PlanTools.updatePlan(plans),
              sending(task("Read", Plan.Status.DONE), task("Write", Plan.Status.IN_PROGRESS)));

      assertThat(said(result)).isEqualTo("Plan updated: 2 tasks (1 in progress, 1 done).");
    }

    /**
     * A model that omits the field entirely (rather than sending {@code []}) must clear the plan
     * the same way — {@code toPlan} treats a null list the same as an empty one.
     */
    @Test
    @DisplayName("an absent tasks field is treated as an empty list, not a null pointer")
    void a_null_tasks_list_clears_the_plan_like_an_empty_one() {
      run(PlanTools.updatePlan(plans), sending(task("First", Plan.Status.PENDING)));

      ToolResult result = run(PlanTools.updatePlan(plans), new PlanTools.UpdatePlan(null));

      assertThat(result).isInstanceOf(ToolResult.Success.class);
      assertThat(plans.find(AGENT)).isEmpty();
    }

    /**
     * These three answer the schema and the tool registry ask, not the model mid-turn -- but they
     * are load-bearing wiring and deserve pinning like anything else on {@link Tool}.
     */
    @Test
    @DisplayName("the tool declares its wire shape, name, and description")
    void the_tool_describes_itself() {
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(plans);

      assertThat(tool.inputType()).isEqualTo(PlanTools.UpdatePlan.class);
      assertThat(tool.name()).isEqualTo(new ToolName("update_plan"));
      assertThat(tool.description()).contains("COMPLETE list");
    }
  }

  @Nested
  @DisplayName("the plan as ambient background")
  class AsAmbient {

    /** What the harness would ask on the way into a turn. */
    private Optional<Ambient> ambient() {
      return PlanTools.plan(plans).forAgent(AGENT);
    }

    private String shown() {
      return ((Block.Text) ambient().orElseThrow().content().getFirst()).text();
    }

    private void planned(String... titles) {
      List<PlanTools.PlannedTask> tasks =
          java.util.Arrays.stream(titles)
              .map(title -> new PlanTools.PlannedTask(title, Plan.Status.PENDING))
              .toList();
      run(PlanTools.updatePlan(plans), new PlanTools.UpdatePlan(tasks));
    }

    /** A heading over no tasks tells a model it has a plan, which is a claim. */
    @Test
    @DisplayName("an agent with no plan contributes nothing at all")
    void no_plan_offers_no_ambient_at_all() {
      assertThat(ambient()).isEmpty();
    }

    @Test
    @DisplayName("the WHOLE plan reaches the model, not an index of it")
    void every_task_is_shown() {
      planned("Read the spec", "Write the code", "Ship it");

      assertThat(shown()).contains("Read the spec").contains("Write the code").contains("Ship it");
    }

    @Test
    @DisplayName("each task is marked with where it stands")
    void status_is_visible_per_task() {
      run(
          PlanTools.updatePlan(plans),
          new PlanTools.UpdatePlan(
              List.of(
                  new PlanTools.PlannedTask("Done one", Plan.Status.DONE),
                  new PlanTools.PlannedTask("Doing one", Plan.Status.IN_PROGRESS),
                  new PlanTools.PlannedTask("Later one", Plan.Status.PENDING))));

      assertThat(shown())
          .contains("- [x] Done one")
          .contains("- [>] Doing one")
          .contains("- [ ] Later one");
    }

    @Test
    @DisplayName("it says which background this is, so an adapter can label it")
    void the_ambient_is_kinded_plan() {
      planned("something");

      assertThat(ambient().orElseThrow().kind()).isEqualTo("plan");
    }

    /**
     * <b>Ambient, which is the whole reason a plan works.</b> A plan written into the story would
     * be re-sent as it was when written, so a task marked DONE on turn four would read as PENDING
     * forever and the model would be looking at work it has already finished. Asked afresh on every
     * call, it is the plan as it stands or nothing at all.
     */
    @Test
    @DisplayName("it is asked afresh, so a task added now is visible now")
    void the_ambient_reflects_the_plan_as_it_stands() {
      assertThat(ambient()).isEmpty();

      planned("Just planned");
      assertThat(shown()).contains("Just planned");

      run(PlanTools.updatePlan(plans), new PlanTools.UpdatePlan(List.of()));
      assertThat(ambient()).as("and an emptied plan stops being mentioned").isEmpty();
    }

    /** One agent's plan is not another's, which is the whole reason a tool is told whose. */
    @Test
    void another_agents_plan_is_not_this_agents_ambient() {
      run(PlanTools.updatePlan(plans), new PlanTools.UpdatePlan(List.of()));
      plans.save(
          Calls.agent(),
          new Plan(List.of(new Plan.Task("Somebody else's task", Plan.Status.PENDING))));

      assertThat(ambient()).isEmpty();
    }
  }
}
