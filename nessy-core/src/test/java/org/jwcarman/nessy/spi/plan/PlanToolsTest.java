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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.memory.ContextTransformer;

/** {@link PlanTools}: the {@code update_plan} tool and the transformer that recalls its plan. */
class PlanToolsTest {

  private static ToolContext toolContext(ConversationId conversationId) {
    ToolCall call = new ToolCall("call-1", "update_plan", JsonNodeFactory.instance.objectNode());
    return new ToolContext(conversationId, call, EventEmitter.noop());
  }

  private static Awaited.Ready<ToolResult> readyResultOf(Awaited<ToolResult> awaited) {
    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    return (Awaited.Ready<ToolResult>) awaited;
  }

  @Nested
  class The_update_plan_tool {

    @Test
    void update_plan_replaces_the_whole_plan_wholesale() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);

      tool.execute(
          new PlanTools.UpdatePlan(
              List.of(
                  new PlanTools.PlannedTask("Fetch the order history", Plan.Status.DONE),
                  new PlanTools.PlannedTask("Summarize the disputes", Plan.Status.IN_PROGRESS),
                  new PlanTools.PlannedTask("Draft the refund email", Plan.Status.PENDING))),
          context);
      tool.execute(
          new PlanTools.UpdatePlan(
              List.of(
                  new PlanTools.PlannedTask("Send the refund", Plan.Status.IN_PROGRESS),
                  new PlanTools.PlannedTask("Close the ticket", Plan.Status.PENDING))),
          context);

      assertThat(store.find(conversationId))
          .contains(
              new Plan(
                  List.of(
                      new Plan.Task("Send the refund", Plan.Status.IN_PROGRESS),
                      new Plan.Task("Close the ticket", Plan.Status.PENDING))));
    }

    @Test
    void replaying_the_same_update_stores_the_identical_plan() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      PlanTools.UpdatePlan input =
          new PlanTools.UpdatePlan(
              List.of(new PlanTools.PlannedTask("Fetch the order history", Plan.Status.DONE)));

      tool.execute(input, context);
      Plan afterOneExecution = store.find(conversationId).orElseThrow();
      tool.execute(input, context);
      Plan afterReplay = store.find(conversationId).orElseThrow();

      assertThat(afterReplay).isEqualTo(afterOneExecution);
    }

    @Test
    void an_empty_task_list_clears_the_plan() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      tool.execute(
          new PlanTools.UpdatePlan(
              List.of(new PlanTools.PlannedTask("Fetch the order history", Plan.Status.DONE))),
          context);

      tool.execute(new PlanTools.UpdatePlan(List.of()), context);

      assertThat(store.find(conversationId)).isEmpty();
    }

    @Test
    void a_null_task_list_is_treated_as_empty() {
      PlanTools.UpdatePlan input = new PlanTools.UpdatePlan(null);

      assertThat(input.tasks()).isEmpty();
    }

    @Test
    void a_blank_title_returns_a_tool_error_not_a_throw() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      PlanTools.UpdatePlan input =
          new PlanTools.UpdatePlan(List.of(new PlanTools.PlannedTask("  ", Plan.Status.PENDING)));

      Awaited<ToolResult> awaited = tool.execute(input, context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(store.find(conversationId)).isEmpty();
    }

    @Test
    void a_null_title_returns_a_tool_error_not_a_throw() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      PlanTools.UpdatePlan input =
          new PlanTools.UpdatePlan(List.of(new PlanTools.PlannedTask(null, Plan.Status.PENDING)));

      Awaited<ToolResult> awaited = tool.execute(input, context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(store.find(conversationId)).isEmpty();
    }

    @Test
    void effect_renders_the_full_checklist_with_wrapper_and_framing_sentence() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      PlanTools.UpdatePlan input =
          new PlanTools.UpdatePlan(
              List.of(
                  new PlanTools.PlannedTask("Fetch the order history", Plan.Status.PENDING),
                  new PlanTools.PlannedTask("Summarize the disputes", Plan.Status.IN_PROGRESS),
                  new PlanTools.PlannedTask("Draft the refund email", Plan.Status.DONE)));

      Object described = tool.effect(input);

      assertThat(described)
          .isEqualTo(
              """
              <current-plan>
              - [ ] Fetch the order history
              - [>] Summarize the disputes
              - [x] Draft the refund email
              </current-plan>
              This is your task list, maintained by you through the update_plan tool. It is \
              ambient state, not a message from the user.""");
    }

    @Test
    void the_confirmation_counts_the_statuses() {
      PlanStore store = PlanStore.inMemory();
      Tool<PlanTools.UpdatePlan> tool = PlanTools.updatePlan(store);
      ToolContext context = toolContext(ConversationId.generate());
      PlanTools.UpdatePlan input =
          new PlanTools.UpdatePlan(
              List.of(
                  new PlanTools.PlannedTask("Fetch the order history", Plan.Status.DONE),
                  new PlanTools.PlannedTask("Summarize the disputes", Plan.Status.IN_PROGRESS),
                  new PlanTools.PlannedTask("Draft the refund email", Plan.Status.PENDING),
                  new PlanTools.PlannedTask("Close the ticket", Plan.Status.PENDING)));

      Awaited<ToolResult> awaited = tool.execute(input, context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Plan updated: 4 tasks (1 in progress, 1 done).");
    }
  }

  @Nested
  class The_transformer {

    @Test
    void an_absent_plan_leaves_the_context_untouched() {
      PlanStore store = PlanStore.inMemory();
      ContextTransformer transformer = PlanTools.transformer(store);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(ConversationId.generate(), original);

      assertThat(transformed).isSameAs(original);
    }

    @Test
    void an_empty_plan_leaves_the_context_untouched() {
      // Hand-rolled store double: the shipped stores clear on empty save (spec §3.2), so only a
      // custom backend can still answer with a present-but-empty plan — the branch stays covered.
      PlanStore store =
          new PlanStore() {
            @Override
            public Optional<Plan> find(ConversationId id) {
              return Optional.of(Plan.empty());
            }

            @Override
            public void save(ConversationId id, Plan plan) {
              throw new UnsupportedOperationException("read-only double");
            }
          };
      ConversationId conversationId = ConversationId.generate();
      ContextTransformer transformer = PlanTools.transformer(store);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(conversationId, original);

      assertThat(transformed).isSameAs(original);
    }

    @Test
    void a_plan_renders_as_the_checklist_at_the_tail() {
      PlanStore store = PlanStore.inMemory();
      ConversationId conversationId = ConversationId.generate();
      store.save(
          conversationId,
          new Plan(List.of(new Plan.Task("Fetch the order history", Plan.Status.DONE))));
      ContextTransformer transformer = PlanTools.transformer(store);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(conversationId, original);

      Message last = transformed.messages().getLast();
      assertThat(last.role()).isEqualTo(Role.USER);
      assertThat(last.content()).hasSize(1);
      TextBlock block = (TextBlock) last.content().getFirst();
      assertThat(block.text())
          .isEqualTo(
              """
              <current-plan>
              - [x] Fetch the order history
              </current-plan>
              This is your task list, maintained by you through the update_plan tool. It is \
              ambient state, not a message from the user.""");
    }

    @Test
    void all_three_markers_render() {
      PlanStore store = PlanStore.inMemory();
      ConversationId conversationId = ConversationId.generate();
      store.save(
          conversationId,
          new Plan(
              List.of(
                  new Plan.Task("Fetch the order history", Plan.Status.PENDING),
                  new Plan.Task("Summarize the disputes", Plan.Status.IN_PROGRESS),
                  new Plan.Task("Draft the refund email", Plan.Status.DONE))));
      ContextTransformer transformer = PlanTools.transformer(store);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(conversationId, original);

      TextBlock block = (TextBlock) transformed.messages().getLast().content().getFirst();
      assertThat(block.text())
          .isEqualTo(
              """
              <current-plan>
              - [ ] Fetch the order history
              - [>] Summarize the disputes
              - [x] Draft the refund email
              </current-plan>
              This is your task list, maintained by you through the update_plan tool. It is \
              ambient state, not a message from the user.""");
    }
  }
}
