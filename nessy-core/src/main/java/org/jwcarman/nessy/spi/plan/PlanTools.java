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
package org.jwcarman.nessy.spi.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.memory.ContextTransformer;

/**
 * The two halves of one invariant, kept in one reviewable place (spec §3.3/§3.4): {@link
 * #updatePlan} lets the model write its own task list, and {@link #transformer} recalls it into
 * context every turn. They meet only at {@link PlanStore}.
 */
public final class PlanTools {

  private PlanTools() {}

  /**
   * The tool the model calls to maintain its own task list. Wholesale replacement, chosen
   * deliberately over CRUD: durable re-drives execute at-least-once, and a replayed wholesale write
   * stores the identical list — idempotent by construction, no task-id bookkeeping, no merge logic.
   * Never parks; a blank task title surfaces as a failed {@link ToolResult} rather than a thrown
   * exception, so the model can correct itself.
   *
   * @param store where the plan is durably kept
   */
  public static Tool<UpdatePlan> updatePlan(PlanStore store) {
    return new UpdatePlanTool(store);
  }

  /**
   * The context-pipeline stage that recalls the current plan as a checklist, ambient state at the
   * tail of context. Absent or empty plans leave the context unchanged — the "if applicable" rule —
   * so this stage never injects an empty block.
   *
   * @param store where the plan is durably kept
   */
  public static ContextTransformer transformer(PlanStore store) {
    return (id, context) -> {
      Optional<Plan> plan = store.find(id);
      if (plan.isEmpty() || plan.get().isEmpty()) {
        return context;
      }
      return context.enrich(new TextBlock(render(plan.get())));
    };
  }

  /** Renders {@code plan} as the checklist block the transformer appends. */
  private static String render(Plan plan) {
    List<PlannedTask> asPlanned =
        plan.tasks().stream().map(task -> new PlannedTask(task.title(), task.status())).toList();
    return renderChecklist(asPlanned);
  }

  /**
   * Renders {@code tasks} as the checklist block, byte-exact per spec §3.4. Works from the wire
   * shape rather than {@link Plan} so a {@code describe} preview never has to construct — and
   * validate — a {@link Plan} just to render one.
   */
  private static String renderChecklist(List<PlannedTask> tasks) {
    StringBuilder rendered = new StringBuilder("<current-plan>\n");
    for (PlannedTask task : tasks) {
      rendered.append("- [").append(markerFor(task.status())).append("] ").append(task.title());
      rendered.append('\n');
    }
    rendered.append("</current-plan>\n");
    rendered.append(
        "This is your task list, maintained by you through the update_plan tool. It is ambient"
            + " state, not a message from the user.");
    return rendered.toString();
  }

  /** The single-character marker a task's status renders as. */
  private static char markerFor(Plan.Status status) {
    return switch (status) {
      case PENDING -> ' ';
      case IN_PROGRESS -> '>';
      case DONE -> 'x';
    };
  }

  /**
   * The wire twin of {@link Plan}: the schema the model's tool call deserializes into, kept
   * separate so {@link Plan} itself never grows schema annotations.
   *
   * @param tasks the complete task list, in the model's own order; a null list is normalized to
   *     empty
   */
  public record UpdatePlan(List<PlannedTask> tasks) {

    public UpdatePlan {
      tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }
  }

  /**
   * One line of {@link UpdatePlan}'s task list — the wire twin of {@link Plan.Task}.
   *
   * @param title what the task is, in the model's own words
   * @param status how far along it is
   */
  public record PlannedTask(String title, Plan.Status status) {}

  /** The {@code update_plan} tool implementation. */
  private static final class UpdatePlanTool implements Tool<UpdatePlan> {

    private final PlanStore store;

    private UpdatePlanTool(PlanStore store) {
      this.store = store;
    }

    @Override
    public String name() {
      return "update_plan";
    }

    @Override
    public String description() {
      return "Maintain your task list for multi-step work. Send the COMPLETE list every time —"
          + " this replaces the whole plan. Keep at most one task IN_PROGRESS; mark tasks DONE as"
          + " you finish them. An empty list clears the plan.";
    }

    @Override
    public Class<UpdatePlan> inputType() {
      return UpdatePlan.class;
    }

    @Override
    public String describe(UpdatePlan input) {
      return renderChecklist(input.tasks());
    }

    @Override
    public Awaited<ToolResult> execute(UpdatePlan input, ToolContext context) {
      Plan plan;
      try {
        plan = toPlan(input);
      } catch (IllegalArgumentException | NullPointerException e) {
        return Awaited.ready(ToolResult.error(e.getMessage()));
      }
      store.save(context.conversationId(), plan);
      return Awaited.ready(ToolResult.ok(confirmationFor(plan)));
    }

    private static Plan toPlan(UpdatePlan input) {
      List<Plan.Task> tasks = new ArrayList<>(input.tasks().size());
      for (PlannedTask planned : input.tasks()) {
        tasks.add(new Plan.Task(planned.title(), planned.status()));
      }
      return new Plan(tasks);
    }

    /** The confirmation the model reads in-band after a successful {@code update_plan} call. */
    private static String confirmationFor(Plan plan) {
      long inProgress =
          plan.tasks().stream().filter(task -> task.status() == Plan.Status.IN_PROGRESS).count();
      long done = plan.tasks().stream().filter(task -> task.status() == Plan.Status.DONE).count();
      return "Plan updated: "
          + plan.tasks().size()
          + " tasks ("
          + inProgress
          + " in progress, "
          + done
          + " done).";
    }
  }
}
