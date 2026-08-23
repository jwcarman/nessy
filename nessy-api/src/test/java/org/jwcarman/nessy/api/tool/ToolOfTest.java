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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;

class ToolOfTest {

  record CreateAccount(String name) {}

  record Widget3D(String label) {}

  private static ToolContext contextFor(ToolEventListener listener) {
    ToolCall call = new ToolCall("c1", "create-account", JsonNodeFactory.instance.objectNode());
    return new ToolContext(call, listener, ComputationId.of("execution-id"));
  }

  private static ToolContext noopContext() {
    return contextFor(ToolEventListener.noop());
  }

  @Nested
  class Naming {

    @Test
    void defaults_the_name_to_the_kebab_case_of_the_records_simple_name() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").executes(cmd -> "ok"));

      assertThat(tool.name()).isEqualTo("create-account");
    }

    @Test
    void keeps_digits_attached_to_the_preceding_word_segment() {
      Tool<Widget3D> tool =
          Tool.of(Widget3D.class, t -> t.description("Make a widget.").executes(cmd -> "ok"));

      assertThat(tool.name()).isEqualTo("widget3-d");
    }

    @Test
    void an_explicit_name_overrides_the_kebab_case_default() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.name("open-account")
                      .description("Create a new bank account.")
                      .executes(cmd -> "ok"));

      assertThat(tool.name()).isEqualTo("open-account");
    }

    @Test
    void a_blank_explicit_name_is_rejected_at_finish_time() {
      assertThatThrownBy(
              () ->
                  Tool.of(
                      CreateAccount.class,
                      t ->
                          t.name("   ")
                              .description("Create a new bank account.")
                              .executes(cmd -> "ok")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("name must not be blank");
    }
  }

  @Nested
  class Description {

    @Test
    void a_missing_description_is_rejected_at_finish_time() {
      assertThatThrownBy(() -> Tool.of(CreateAccount.class, t -> t.executes(cmd -> "ok")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("description must be provided — it is written for the model");
    }

    @Test
    void a_blank_description_is_rejected_at_finish_time() {
      assertThatThrownBy(
              () -> Tool.of(CreateAccount.class, t -> t.description("   ").executes(cmd -> "ok")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("description must be provided — it is written for the model");
    }
  }

  @Nested
  class Handler_door_enforcement {

    @Test
    void zero_handler_doors_is_rejected_naming_the_tool() {
      ToolCustomizer<CreateAccount> noHandler =
          t -> t.name("create-account").description("Create a new bank account.");

      assertThatThrownBy(() -> Tool.of(CreateAccount.class, noHandler))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("create-account");
    }

    @Test
    void two_handler_doors_is_rejected_naming_the_tool() {
      ToolCustomizer<CreateAccount> twoHandlers =
          t ->
              t.name("create-account")
                  .description("Create a new bank account.")
                  .executes(cmd -> "ok")
                  .executes((cmd, ctx) -> "ok");

      assertThatThrownBy(() -> Tool.of(CreateAccount.class, twoHandlers))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("create-account");
    }
  }

  @Nested
  class Return_rendering {

    @Test
    void a_string_return_renders_as_an_ok_result_with_that_content() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .executes(cmd -> "created " + cmd.name()));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), noopContext());

      assertThat(result).isEqualTo(Awaited.ready(ToolResult.ok("created ann")));
    }

    @Test
    void a_tool_result_return_passes_through_unchanged() {
      ToolResult errored = ToolResult.error("nope");
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").executes(cmd -> errored));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), noopContext());

      assertThat(result).isEqualTo(Awaited.ready(errored));
    }

    @Test
    void a_null_return_renders_as_ok_done() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").executes(cmd -> null));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), noopContext());

      assertThat(result).isEqualTo(Awaited.ready(ToolResult.ok("done")));
    }

    @Test
    void any_other_object_return_renders_as_ok_json() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .executes(cmd -> new CreateAccount(cmd.name())));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), noopContext());

      assertThat(result).isEqualTo(Awaited.ready(ToolResult.ok("{\"name\":\"ann\"}")));
    }
  }

  @Nested
  class The_context_door {

    @Test
    void the_bi_function_handler_receives_the_real_tool_context_and_can_progress() {
      List<ToolEvent> heard = new ArrayList<>();
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .executes(
                          (cmd, ctx) -> {
                            ctx.progress("working on " + cmd.name());
                            return "done";
                          }));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), contextFor(heard::add));

      assertThat(heard).containsExactly(new ToolEvent.Progress("working on ann"));
      assertThat(result).isEqualTo(Awaited.ready(ToolResult.ok("done")));
    }
  }

  @Nested
  class Deferral {

    @Test
    void defers_runs_the_starter_with_input_and_context_and_returns_deferred() {
      List<String> starterSaw = new ArrayList<>();
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .defers((cmd, ctx) -> starterSaw.add(cmd.name() + ":" + ctx.call().id())));

      Awaited<ToolResult> result = tool.execute(new CreateAccount("ann"), noopContext());

      assertThat(result).isInstanceOf(Awaited.Deferred.class);
      assertThat(starterSaw).containsExactly("ann:c1");
    }

    @Test
    void defers_sets_required_completion_to_durable_by_default() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").defers((cmd, ctx) -> {}));

      assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.DURABLE);
    }

    @Test
    void requires_called_after_defers_overrides_the_automatic_durable_policy() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .defers((cmd, ctx) -> {})
                      .requires(CompletionPolicy.AWAITABLE));

      assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.AWAITABLE);
    }

    @Test
    void requires_called_before_defers_still_overrides_the_automatic_durable_policy() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .requires(CompletionPolicy.AWAITABLE)
                      .defers((cmd, ctx) -> {}));

      assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.AWAITABLE);
    }
  }

  @Nested
  class Retry_and_deadline {

    @Test
    void a_tool_defaults_to_non_retryable_with_no_timeout() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").executes(cmd -> "ok"));

      assertThat(tool.retrySemantics()).isEqualTo(RetrySemantics.NON_RETRYABLE);
      assertThat(tool.timeout()).isEmpty();
    }

    @Test
    void retry_semantics_can_be_declared_retryable() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .retrySemantics(RetrySemantics.RETRYABLE)
                      .executes(cmd -> "ok"));

      assertThat(tool.retrySemantics()).isEqualTo(RetrySemantics.RETRYABLE);
    }

    @Test
    void a_declared_timeout_is_carried_on_the_built_tool() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .timeout(Duration.ofMinutes(5))
                      .executes(cmd -> "ok"));

      assertThat(tool.timeout()).contains(Duration.ofMinutes(5));
    }
  }

  @Nested
  class Spec_derivation {

    @Test
    void spec_still_derives_from_schemas_of_the_input_type() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t -> t.description("Create a new bank account.").executes(cmd -> "ok"));

      assertThat(tool.spec())
          .isEqualTo(
              new ToolSpec(
                  "create-account", "Create a new bank account.", Schemas.of(CreateAccount.class)));
    }
  }

  @Nested
  class Handler_exceptions {

    @Test
    void a_thrown_runtime_exception_propagates_from_execute() {
      Tool<CreateAccount> tool =
          Tool.of(
              CreateAccount.class,
              t ->
                  t.description("Create a new bank account.")
                      .executes(
                          cmd -> {
                            throw new IllegalArgumentException("boom");
                          }));
      var command = new CreateAccount("ann");
      var context = noopContext();

      assertThatThrownBy(() -> tool.execute(command, context))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("boom");
    }
  }
}
