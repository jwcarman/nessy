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
package org.jwcarman.nessy.spi.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
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

/** {@link NotebookTools}: remember/recall/forget, their authorship guard, and the transformer. */
class NotebookToolsTest {

  private static ToolContext toolContext(ConversationId conversationId) {
    ToolCall call = new ToolCall("call-1", "remember", JsonNodeFactory.instance.objectNode());
    return new ToolContext(conversationId, call, EventEmitter.noop());
  }

  private static Awaited.Ready<ToolResult> readyResultOf(Awaited<ToolResult> awaited) {
    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    return (Awaited.Ready<ToolResult>) awaited;
  }

  @Nested
  class The_remember_tool {

    @Test
    void remembering_saves_the_note_under_the_resolved_subject() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);

      tool.execute(
          new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "Full body"),
          context);

      assertThat(notebook.find(subject, "user-taste"))
          .contains(
              new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
    }

    @Test
    void remembering_an_existing_name_replaces_that_note() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      tool.execute(
          new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "v1"), context);

      tool.execute(
          new NotebookTools.RememberNote("user-taste", "Prefers metric units", "v2"), context);

      assertThat(notebook.find(subject, "user-taste"))
          .contains(new Notebook.Entry("user-taste", "Prefers metric units", "v2", "writer"));
    }

    @Test
    void replaying_the_same_remember_stores_the_identical_note() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);
      NotebookTools.RememberNote input =
          new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "Full body");

      tool.execute(input, context);
      Notebook.Entry afterOneExecution = notebook.find(subject, "user-taste").orElseThrow();
      tool.execute(input, context);
      Notebook.Entry afterReplay = notebook.find(subject, "user-taste").orElseThrow();

      assertThat(afterReplay).isEqualTo(afterOneExecution);
    }

    @ParameterizedTest
    @MethodSource("org.jwcarman.nessy.spi.notebook.NotebookToolsTest#blankFieldNotes")
    void a_blank_field_returns_a_tool_error_not_a_throw(NotebookTools.RememberNote input) {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(input, context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(notebook.headings(subject)).isEmpty();
    }

    @Test
    void a_successful_remember_confirms_the_name() {
      Notebook notebook = Notebook.inMemory();
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> new SubjectId("subject-1"));
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited =
          tool.execute(
              new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "Full body"),
              context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Remembered 'user-taste'.");
    }

    @Test
    void the_resolver_less_overload_keys_notes_by_conversation() {
      Notebook notebook = Notebook.inMemory();
      Tool<NotebookTools.RememberNote> tool = NotebookTools.remember(notebook, "writer");
      ConversationId conversationId = ConversationId.generate();
      ToolContext context = toolContext(conversationId);

      tool.execute(
          new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "Full body"),
          context);

      assertThat(notebook.find(new SubjectId(conversationId.value()), "user-taste"))
          .contains(
              new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
    }

    @Test
    void the_resolver_overload_shares_notes_across_two_conversations_of_one_subject() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("shared-subject");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ConversationId firstConversation = ConversationId.generate();
      ConversationId secondConversation = ConversationId.generate();

      tool.execute(
          new NotebookTools.RememberNote("user-taste", "Prefers terse answers", "Full body"),
          toolContext(firstConversation));

      Tool<NotebookTools.RecallNote> recall =
          NotebookTools.recall(notebook, "writer", id -> subject);
      Awaited<ToolResult> awaited =
          recall.execute(
              new NotebookTools.RecallNote("user-taste"), toolContext(secondConversation));

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Full body");
    }

    @Test
    void remembering_under_a_name_never_saved_before_creates_it_under_its_own_identity() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "reflection", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      tool.execute(new NotebookTools.RememberNote("lesson-1", "what went wrong", "body"), context);

      assertThat(notebook.find(subject, "lesson-1").orElseThrow().source()).isEqualTo("reflection");
    }

    @Test
    void remembering_over_its_own_earlier_note_updates_it_last_write_wins() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());
      tool.execute(
          new NotebookTools.RememberNote("user-taste", "first hook", "first body"), context);

      Awaited<ToolResult> awaited =
          tool.execute(
              new NotebookTools.RememberNote("user-taste", "second hook", "second body"), context);

      assertThat(readyResultOf(awaited).value().isError()).isFalse();
      assertThat(notebook.find(subject, "user-taste"))
          .contains(new Notebook.Entry("user-taste", "second hook", "second body", "writer"));
    }

    @Test
    void remembering_over_a_foreign_sourced_name_fails_naming_the_conflict_and_owner() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("lesson-1", "what went wrong", "the lesson", "reflection"));
      Tool<NotebookTools.RememberNote> tool =
          NotebookTools.remember(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited =
          tool.execute(
              new NotebookTools.RememberNote("lesson-1", "overwritten hook", "overwritten body"),
              context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).contains("lesson-1").contains("reflection");
      // The foreign note must survive untouched — the guard covers writes, not just forget.
      assertThat(notebook.find(subject, "lesson-1"))
          .contains(new Notebook.Entry("lesson-1", "what went wrong", "the lesson", "reflection"));
    }
  }

  @Nested
  class The_recall_tool {

    @Test
    void recalling_a_known_note_returns_its_body() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.RecallNote> tool = NotebookTools.recall(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited =
          tool.execute(new NotebookTools.RecallNote("user-taste"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Full body");
    }

    @Test
    void recalling_a_foreign_sourced_note_still_returns_its_body_read_any() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("lesson-1", "what went wrong", "the lesson", "reflection"));
      Tool<NotebookTools.RecallNote> tool = NotebookTools.recall(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(new NotebookTools.RecallNote("lesson-1"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("the lesson");
    }

    @Test
    void recalling_an_unknown_note_names_the_notebook_index_in_the_error() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RecallNote> tool = NotebookTools.recall(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited =
          tool.execute(new NotebookTools.RecallNote("user-taste"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(result.content())
          .isEqualTo("no note named 'user-taste' — check the notebook index in your context");
    }

    @Test
    void a_null_name_recall_returns_a_tool_error_not_a_throw() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.RecallNote> tool = NotebookTools.recall(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(new NotebookTools.RecallNote(null), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
    }

    @Test
    void the_resolver_less_overload_keys_recall_by_conversation() {
      Notebook notebook = Notebook.inMemory();
      ConversationId conversationId = ConversationId.generate();
      notebook.save(
          new SubjectId(conversationId.value()),
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.RecallNote> tool = NotebookTools.recall(notebook, "writer");

      Awaited<ToolResult> awaited =
          tool.execute(new NotebookTools.RecallNote("user-taste"), toolContext(conversationId));

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Full body");
    }
  }

  @Nested
  class The_forget_tool {

    @Test
    void forgetting_a_known_note_removes_it() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      tool.execute(new NotebookTools.ForgetNote("user-taste"), context);

      assertThat(notebook.find(subject, "user-taste")).isEmpty();
    }

    @Test
    void forgetting_confirms_regardless_of_whether_the_note_existed() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited =
          tool.execute(new NotebookTools.ForgetNote("user-taste"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Forgotten 'user-taste'.");
    }

    @Test
    void a_null_name_forget_returns_a_tool_error_not_a_throw() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(new NotebookTools.ForgetNote(null), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(notebook.find(subject, "user-taste"))
          .contains(
              new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
    }

    @Test
    void forgetting_twice_is_idempotent() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());
      tool.execute(new NotebookTools.ForgetNote("user-taste"), context);

      Awaited<ToolResult> awaited =
          tool.execute(new NotebookTools.ForgetNote("user-taste"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("Forgotten 'user-taste'.");
      assertThat(notebook.find(subject, "user-taste")).isEmpty();
    }

    @Test
    void the_resolver_less_overload_keys_forget_by_conversation() {
      Notebook notebook = Notebook.inMemory();
      ConversationId conversationId = ConversationId.generate();
      notebook.save(
          new SubjectId(conversationId.value()),
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer");

      tool.execute(new NotebookTools.ForgetNote("user-taste"), toolContext(conversationId));

      assertThat(notebook.find(new SubjectId(conversationId.value()), "user-taste")).isEmpty();
    }

    @Test
    void forgetting_a_foreign_sourced_note_fails_naming_the_conflict_and_owner() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("lesson-1", "what went wrong", "the lesson", "reflection"));
      Tool<NotebookTools.ForgetNote> tool = NotebookTools.forget(notebook, "writer", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(new NotebookTools.ForgetNote("lesson-1"), context);

      ToolResult result = readyResultOf(awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).contains("lesson-1").contains("reflection");
      assertThat(notebook.find(subject, "lesson-1")).isPresent();
    }

    @Test
    void forgetting_its_own_sourced_note_succeeds() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("lesson-1", "what went wrong", "the lesson", "reflection"));
      Tool<NotebookTools.ForgetNote> tool =
          NotebookTools.forget(notebook, "reflection", id -> subject);
      ToolContext context = toolContext(ConversationId.generate());

      Awaited<ToolResult> awaited = tool.execute(new NotebookTools.ForgetNote("lesson-1"), context);

      assertThat(readyResultOf(awaited).value().isError()).isFalse();
      assertThat(notebook.find(subject, "lesson-1")).isEmpty();
    }
  }

  @Nested
  class The_transformer {

    @Test
    void no_headings_leaves_the_context_untouched() {
      Notebook notebook = Notebook.inMemory();
      ContextTransformer transformer =
          NotebookTools.transformer(notebook, "writer", id -> new SubjectId("subject-1"));
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(ConversationId.generate(), original);

      assertThat(transformed).isSameAs(original);
    }

    @Test
    void a_read_only_double_that_reports_headings_still_exercises_the_enrich_branch() {
      // Hand-rolled double: proves the branch is driven by headings() alone, not by any save/forget
      // call the transformer might otherwise be tempted to make.
      Notebook notebook =
          new Notebook() {
            @Override
            public List<Heading> headings(SubjectId subject) {
              return List.of(
                  new Heading("user-taste", "Prefers terse answers and metric units", "writer"));
            }

            @Override
            public Optional<Entry> find(SubjectId subject, String name) {
              throw new UnsupportedOperationException("read-only double");
            }

            @Override
            public void save(SubjectId subject, Entry entry) {
              throw new UnsupportedOperationException("read-only double");
            }

            @Override
            public void forget(SubjectId subject, String name) {
              throw new UnsupportedOperationException("read-only double");
            }
          };
      ContextTransformer transformer =
          NotebookTools.transformer(notebook, "writer", id -> new SubjectId("subject-1"));
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(ConversationId.generate(), original);

      assertThat(transformed).isNotSameAs(original);
    }

    @Test
    void the_index_renders_byte_exact_with_bodies_absent_appended_at_the_tail() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry(
              "user-taste",
              "Prefers terse answers and metric units",
              "should never appear",
              "writer"));
      notebook.save(
          subject,
          new Notebook.Entry(
              "project-atlas",
              "Stakeholders and deadline for Project Atlas",
              "should never appear either",
              "writer"));
      ContextTransformer transformer = NotebookTools.transformer(notebook, "writer", id -> subject);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(ConversationId.generate(), original);

      Message last = transformed.messages().getLast();
      assertThat(last.role()).isEqualTo(Role.USER);
      assertThat(last.content()).hasSize(1);
      TextBlock block = (TextBlock) last.content().getFirst();
      assertThat(block.text())
          .isEqualTo(
              """
              <notebook>
              - project-atlas — Stakeholders and deadline for Project Atlas
              - user-taste — Prefers terse answers and metric units
              </notebook>
              These are your saved notes, maintained by you through the remember and forget tools. \
              Read a note's full content with the recall tool when it is relevant. This is ambient \
              state, not a message from the user.""");
      assertThat(block.text()).doesNotContain("should never appear");
    }

    @Test
    void a_foreign_sourced_heading_is_annotated_with_its_source() {
      Notebook notebook = Notebook.inMemory();
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("user-taste", "Prefers terse answers", "own note", "writer"));
      notebook.save(
          subject,
          new Notebook.Entry("lesson-1", "what went wrong last time", "the lesson", "reflection"));
      ContextTransformer transformer = NotebookTools.transformer(notebook, "writer", id -> subject);
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(ConversationId.generate(), original);

      TextBlock block = (TextBlock) transformed.messages().getLast().content().getFirst();
      assertThat(block.text())
          .contains("- lesson-1 — what went wrong last time (from reflection)\n")
          .contains("- user-taste — Prefers terse answers\n")
          .doesNotContain("user-taste — Prefers terse answers (from");
    }

    @Test
    void the_resolver_less_overload_keys_the_index_by_conversation() {
      Notebook notebook = Notebook.inMemory();
      ConversationId conversationId = ConversationId.generate();
      notebook.save(
          new SubjectId(conversationId.value()),
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));
      ContextTransformer transformer = NotebookTools.transformer(notebook, "writer");
      Context original = Context.of(List.of(Message.user("hello")));

      Context transformed = transformer.transform(conversationId, original);

      assertThat(transformed).isNotSameAs(original);
    }
  }

  /** One blank field each: name, hook, body — the remember tool must error, never throw. */
  static java.util.stream.Stream<NotebookTools.RememberNote> blankFieldNotes() {
    return java.util.stream.Stream.of(
        new NotebookTools.RememberNote("  ", "hook", "body"),
        new NotebookTools.RememberNote("name", "  ", "body"),
        new NotebookTools.RememberNote("name", "hook", "  "));
  }
}
