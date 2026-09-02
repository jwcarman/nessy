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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.memory.pipeline.MemoryPipeline;
import org.jwcarman.nessy.testing.TestDatabase;

@DisplayName("The plan a model works with")
class PlanToolsTest {

  private static final AgentId AGENT = AgentId.of("cli");
  private static final AgentType TYPE = AgentType.of("chat");

  private PlanStore plans;

  @BeforeEach
  void fresh() {
    plans = new JdbcPlanStore(TestDatabase.fresh(), TYPE);
  }

  /** What the engine hands a running tool. No mocking library, and none needed. */
  private static <I> ToolCallRequest<I> callBy(AgentId agentId, I input) {
    return new ToolCallRequest<>(
        TYPE,
        agentId,
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "a_tool",
        input,
        ReplyToken.of("unused"));
  }

  private static <I> ToolResult run(Tool<I> tool, I input) {
    Awaited<ToolResult> answer = tool.execute(callBy(AGENT, input));
    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<ToolResult>) answer).result();
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

      assertThat(result).isEqualTo(ToolResult.ok("Plan updated: 2 tasks (1 in progress, 1 done)."));
    }
  }

  @Nested
  @DisplayName("the plan stage")
  class Carrying {

    /** A memory that is a list, so the stage can be seen doing its work. */
    private static final class Listing implements Memory {
      private final List<HistoryMessage> told = new ArrayList<>();

      @Override
      public Context recall(AgentId agentId) {
        return Context.of(List.copyOf(told));
      }

      @Override
      public void remember(AgentId agentId, HistoryMessage message) {
        told.add(message);
      }

      @Override
      public void forget(AgentId agentId) {
        // Nothing kept, so nothing to drop -- this memory exists to be recalled from.
      }
    }

    private Memory withPlan(Listing bootstrap) {
      return MemoryPipeline.of(bootstrap, p -> p.stage(PlanTools.plan(plans)));
    }

    @Test
    @DisplayName("an agent with no plan contributes nothing at all")
    void no_plan_adds_no_message() {
      Memory memory = withPlan(new Listing());
      memory.remember(AGENT, UserMessage.of("hello"));

      assertThat(memory.recall(AGENT).messages()).containsExactly(UserMessage.of("hello"));
    }

    @Test
    @DisplayName("the WHOLE plan reaches the model, not an index of it")
    void every_task_is_shown() {
      plans.save(
          AGENT,
          new Plan(
              List.of(
                  new Plan.Task("Read the spec", Plan.Status.DONE),
                  new Plan.Task("Write the code", Plan.Status.IN_PROGRESS),
                  new Plan.Task("Ship it", Plan.Status.PENDING))));

      String shown = ambientOf(withPlan(new Listing()).recall(AGENT));

      assertThat(shown).contains("Read the spec").contains("Write the code").contains("Ship it");
    }

    @Test
    @DisplayName("each task is marked with where it stands")
    void status_is_visible_per_task() {
      plans.save(
          AGENT,
          new Plan(
              List.of(
                  new Plan.Task("Done one", Plan.Status.DONE),
                  new Plan.Task("Doing one", Plan.Status.IN_PROGRESS),
                  new Plan.Task("Later one", Plan.Status.PENDING))));

      String shown = ambientOf(withPlan(new Listing()).recall(AGENT));

      assertThat(shown)
          .contains("- [x] Done one")
          .contains("- [>] Doing one")
          .contains("- [ ] Later one");
    }

    /** Background, not a turn: it can never silt up the transcript. */
    @Test
    void the_plan_is_ambient_rather_than_something_anyone_said() {
      plans.save(AGENT, new Plan(List.of(new Plan.Task("Ship it", Plan.Status.PENDING))));
      Listing bootstrap = new Listing();
      Memory memory = withPlan(bootstrap);

      memory.recall(AGENT);
      memory.recall(AGENT);

      assertThat(memory.recall(AGENT).messages()).hasSize(1);
      assertThat(memory.recall(AGENT).messages().getFirst()).isInstanceOf(AmbientMessage.class);
      assertThat(bootstrap.recall(AGENT).messages()).isEmpty();
    }

    @Test
    @DisplayName("it says which background this is, so an adapter can label it")
    void the_message_is_kinded_plan() {
      plans.save(AGENT, new Plan(List.of(new Plan.Task("Ship it", Plan.Status.PENDING))));

      ContextMessage last = withPlan(new Listing()).recall(AGENT).messages().getLast();

      assertThat(((AmbientMessage) last).kind()).isEqualTo("plan");
    }

    @Test
    @DisplayName("it is rebuilt every recall, so a task added now is visible now")
    void the_stage_reflects_the_plan_as_it_stands() {
      Memory memory = withPlan(new Listing());
      assertThat(memory.recall(AGENT).messages()).isEmpty();

      plans.save(AGENT, new Plan(List.of(new Plan.Task("Just planned", Plan.Status.PENDING))));

      assertThat(ambientOf(memory.recall(AGENT))).contains("Just planned");
    }

    private static String ambientOf(Context context) {
      ContextMessage last = context.messages().getLast();
      assertThat(last).isInstanceOf(AmbientMessage.class);
      return ((TextBlock) ((AmbientMessage) last).content().getFirst()).text();
    }
  }
}
