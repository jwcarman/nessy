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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.AmbientContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.memory.pipeline.ContextTransformer;

/**
 * The one verb a model uses on its plan, and the stage that keeps the plan in front of it.
 *
 * <p><b>Wholesale replacement, not CRUD.</b> The model sends the ENTIRE task list every time
 * anything changes. That is the shape models are trained on, and it is the one that survives this
 * engine: a durable re-drive executes at-least-once, and a replayed wholesale write stores the
 * identical list — idempotent by construction, with no task ids to keep straight and no merge to
 * get wrong. An empty list clears the plan.
 *
 * <p>Note the contrast with the notebook next door, which mints ids. Neither is a style choice: a
 * plan is small enough to resend whole, so replacement is atomic and replay-safe; a notebook is
 * not, so it needs addressable notes and pays for them by having to say which one it means.
 */
public final class PlanTools {

  /** What this background is, so an adapter can label it the way its vendor likes. */
  private static final String KIND = "plan";

  private PlanTools() {}

  /**
   * The wire twin of {@link Plan}, kept separate so the domain record grows no schema annotations.
   */
  public record UpdatePlan(
      @JsonPropertyDescription(
              "The complete task list, in order. Send every task every time, including the ones"
                  + " that have not changed. An empty list clears the plan.")
          List<PlannedTask> tasks) {}

  public record PlannedTask(
      @JsonPropertyDescription("What this step is") String title,
      @JsonPropertyDescription("PENDING, IN_PROGRESS or DONE") Plan.Status status) {}

  /**
   * The tool.
   *
   * <p>Its description is written for the model, and says the three things that make a plan useful
   * rather than decorative: send the whole list, keep one task in progress, mark work done as it
   * finishes.
   */
  public static Tool<UpdatePlan> updatePlan(PlanStore store) {
    Objects.requireNonNull(store, "store must not be null");
    return new PlanTool(store);
  }

  /**
   * The stage that carries the plan.
   *
   * <p>The WHOLE plan, unlike the notebook's index: a task list is small, and a plan you can only
   * see the headings of is not a plan you can stick to. No plan, or an empty one, contributes
   * nothing at all.
   */
  public static ContextTransformer plan(PlanStore store) {
    Objects.requireNonNull(store, "store must not be null");
    return (agentId, context) ->
        store
            .find(agentId)
            .filter(plan -> !plan.isEmpty())
            .map(plan -> carrying(context, plan))
            .orElse(context);
  }

  private static Context carrying(Context context, Plan plan) {
    List<ContextMessage> messages = new ArrayList<>(context.messages());
    messages.add(
        new AmbientMessage(KIND, List.<AmbientContentBlock>of(new TextBlock(render(plan)))));
    return Context.of(messages);
  }

  /** A checklist, in the model's own order. */
  static String render(Plan plan) {
    StringBuilder text = new StringBuilder();
    for (Plan.Task task : plan.tasks()) {
      text.append(marker(task.status())).append(' ').append(task.title()).append('\n');
    }
    return text.append("Keep this current with the update_plan tool as you work.").toString();
  }

  private static String marker(Plan.Status status) {
    return switch (status) {
      case PENDING -> "- [ ]";
      case IN_PROGRESS -> "- [>]";
      case DONE -> "- [x]";
    };
  }

  private record PlanTool(PlanStore store) implements Tool<UpdatePlan> {

    @Override
    public Class<UpdatePlan> inputType() {
      return UpdatePlan.class;
    }

    @Override
    public String name() {
      return "update_plan";
    }

    @Override
    public String description() {
      return "Record or update your task list for multi-step work. Send the COMPLETE list every"
          + " time, including unchanged tasks — this replaces the whole plan. Keep at most one"
          + " task IN_PROGRESS, and mark tasks DONE as you finish them. An empty list clears the"
          + " plan.";
    }

    @Override
    public Awaited<ToolResult> execute(UpdatePlan input, ToolContext context) {
      try {
        Plan plan = toPlan(input);
        store.save(context.agentId(), plan);
        return Awaited.ready(ToolResult.ok(confirm(plan)));
      } catch (IllegalArgumentException | NullPointerException invalid) {
        // A failed call, not a failed turn: the model can read this and send a better list.
        return Awaited.ready(ToolResult.error(invalid.getMessage()));
      }
    }

    private static Plan toPlan(UpdatePlan input) {
      List<PlannedTask> tasks = input.tasks() == null ? List.of() : input.tasks();
      return new Plan(
          tasks.stream().map(task -> new Plan.Task(task.title(), task.status())).toList());
    }

    /** One line the model reads back in-band, so it knows the plan it now has. */
    private static String confirm(Plan plan) {
      if (plan.isEmpty()) {
        return "Plan cleared.";
      }
      long inProgress = count(plan, Plan.Status.IN_PROGRESS);
      long done = count(plan, Plan.Status.DONE);
      return "Plan updated: %d tasks (%d in progress, %d done)."
          .formatted(plan.tasks().size(), inProgress, done);
    }

    private static long count(Plan plan, Plan.Status status) {
      return plan.tasks().stream().filter(task -> task.status() == status).count();
    }
  }
}
