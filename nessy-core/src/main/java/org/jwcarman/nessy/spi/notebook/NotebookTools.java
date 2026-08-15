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

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.memory.ContextTransformer;

/**
 * The three verbs the model uses to keep durable notes about its subject — {@link #remember},
 * {@link #recall}, {@link #forget} — and the {@link #transformer} that recalls the index into
 * context every turn. All four meet only at {@link Notebook}.
 *
 * <p>Every factory takes a {@code Function<ConversationId, SubjectId>} resolver — the app's bridge
 * from a conversation to the subject its notes belong to (spec §2) — and every factory has a
 * resolver-less overload that degenerates to a per-conversation notebook: {@code subject = new
 * SubjectId(id.value())}.
 */
public final class NotebookTools {

  private NotebookTools() {}

  /**
   * The zero-configuration resolver: the subject is the conversation itself.
   *
   * @see #remember(Notebook)
   */
  private static SubjectId subjectOf(ConversationId id) {
    return new SubjectId(id.value());
  }

  /**
   * The tool the model calls to save a durable note. Validation failures (a blank name, hook, or
   * body) surface as a failed {@link ToolResult} rather than a thrown exception, so the model can
   * correct itself. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<RememberNote> remember(
      Notebook notebook, Function<ConversationId, SubjectId> resolver) {
    return new RememberNoteTool(notebook, resolver);
  }

  /** {@link #remember(Notebook, Function)} with the zero-configuration resolver. */
  public static Tool<RememberNote> remember(Notebook notebook) {
    return remember(notebook, NotebookTools::subjectOf);
  }

  /**
   * The tool the model calls to read a note's full body. An unknown name surfaces as a failed
   * {@link ToolResult} naming the notebook index, so the model self-corrects rather than guessing
   * again. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<RecallNote> recall(
      Notebook notebook, Function<ConversationId, SubjectId> resolver) {
    return new RecallNoteTool(notebook, resolver);
  }

  /** {@link #recall(Notebook, Function)} with the zero-configuration resolver. */
  public static Tool<RecallNote> recall(Notebook notebook) {
    return recall(notebook, NotebookTools::subjectOf);
  }

  /**
   * The tool the model calls to delete a note. Idempotent: forgetting an absent name still confirms
   * success. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<ForgetNote> forget(
      Notebook notebook, Function<ConversationId, SubjectId> resolver) {
    return new ForgetNoteTool(notebook, resolver);
  }

  /** {@link #forget(Notebook, Function)} with the zero-configuration resolver. */
  public static Tool<ForgetNote> forget(Notebook notebook) {
    return forget(notebook, NotebookTools::subjectOf);
  }

  /**
   * The context-pipeline stage that recalls the subject's notebook index, ambient state at the tail
   * of context. No headings leaves the context unchanged (same instance) — the "if applicable"
   * rule, same as {@link org.jwcarman.nessy.spi.plan.PlanTools#transformer}.
   *
   * @param notebook where notes are durably kept
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static ContextTransformer transformer(
      Notebook notebook, Function<ConversationId, SubjectId> resolver) {
    return (id, context) -> {
      SubjectId subject = resolver.apply(id);
      List<Notebook.Heading> headings = notebook.headings(subject);
      if (headings.isEmpty()) {
        return context;
      }
      return context.enrich(new TextBlock(render(headings)));
    };
  }

  /** {@link #transformer(Notebook, Function)} with the zero-configuration resolver. */
  public static ContextTransformer transformer(Notebook notebook) {
    return transformer(notebook, NotebookTools::subjectOf);
  }

  /** Renders {@code headings} as the notebook index block, byte-exact per spec §4. */
  private static String render(List<Notebook.Heading> headings) {
    StringBuilder rendered = new StringBuilder("<notebook>\n");
    for (Notebook.Heading heading : headings) {
      rendered.append("- ").append(heading.name()).append(" — ").append(heading.hook());
      rendered.append('\n');
    }
    rendered.append("</notebook>\n");
    rendered.append(
        "These are your saved notes, maintained by you through the remember and forget tools."
            + " Read a note's full content with the recall tool when it is relevant. This is"
            + " ambient state, not a message from the user.");
    return rendered.toString();
  }

  /**
   * The wire twin of {@link Notebook.Entry}: the schema the model's {@code remember} call
   * deserializes into.
   *
   * @param name the short kebab-case key the model files the note under
   * @param hook the one-line summary the index shows
   * @param body the full note content
   */
  public record RememberNote(String name, String hook, String body) {}

  /**
   * The schema the model's {@code recall} call deserializes into.
   *
   * @param name the note to read, as it appears in the notebook index
   */
  public record RecallNote(String name) {}

  /**
   * The schema the model's {@code forget} call deserializes into.
   *
   * @param name the note to delete, as it appears in the notebook index
   */
  public record ForgetNote(String name) {}

  /** The {@code remember} tool implementation. */
  private static final class RememberNoteTool implements Tool<RememberNote> {

    private final Notebook notebook;
    private final Function<ConversationId, SubjectId> resolver;

    private RememberNoteTool(Notebook notebook, Function<ConversationId, SubjectId> resolver) {
      this.notebook = notebook;
      this.resolver = resolver;
    }

    @Override
    public String name() {
      return "remember";
    }

    @Override
    public String description() {
      return "Save a durable note under a short kebab-case name with a one-line hook;"
          + " remembering an existing name replaces that note.";
    }

    @Override
    public Class<RememberNote> inputType() {
      return RememberNote.class;
    }

    @Override
    public Awaited<ToolResult> execute(RememberNote input, ToolContext context) {
      Notebook.Entry entry;
      try {
        entry = new Notebook.Entry(input.name(), input.hook(), input.body());
      } catch (IllegalArgumentException | NullPointerException e) {
        return Awaited.ready(ToolResult.error(e.getMessage()));
      }
      SubjectId subject = resolver.apply(context.conversationId());
      notebook.save(subject, entry);
      return Awaited.ready(ToolResult.ok("Remembered '" + entry.name() + "'."));
    }
  }

  /** The {@code recall} tool implementation. */
  private static final class RecallNoteTool implements Tool<RecallNote> {

    private final Notebook notebook;
    private final Function<ConversationId, SubjectId> resolver;

    private RecallNoteTool(Notebook notebook, Function<ConversationId, SubjectId> resolver) {
      this.notebook = notebook;
      this.resolver = resolver;
    }

    @Override
    public String name() {
      return "recall";
    }

    @Override
    public String description() {
      return "Read the full body of a note whose name appears in your notebook index.";
    }

    @Override
    public Class<RecallNote> inputType() {
      return RecallNote.class;
    }

    @Override
    public Awaited<ToolResult> execute(RecallNote input, ToolContext context) {
      if (input.name() == null || input.name().isBlank()) {
        return Awaited.ready(ToolResult.error("name must be provided"));
      }
      SubjectId subject = resolver.apply(context.conversationId());
      Optional<Notebook.Entry> found = notebook.find(subject, input.name());
      if (found.isEmpty()) {
        return Awaited.ready(
            ToolResult.error(
                "no note named '"
                    + input.name()
                    + "' — check the notebook index in your"
                    + " context"));
      }
      return Awaited.ready(ToolResult.ok(found.get().body()));
    }
  }

  /** The {@code forget} tool implementation. */
  private static final class ForgetNoteTool implements Tool<ForgetNote> {

    private final Notebook notebook;
    private final Function<ConversationId, SubjectId> resolver;

    private ForgetNoteTool(Notebook notebook, Function<ConversationId, SubjectId> resolver) {
      this.notebook = notebook;
      this.resolver = resolver;
    }

    @Override
    public String name() {
      return "forget";
    }

    @Override
    public String description() {
      return "Permanently delete a note by name; safe to call even if the note is already gone.";
    }

    @Override
    public Class<ForgetNote> inputType() {
      return ForgetNote.class;
    }

    @Override
    public Awaited<ToolResult> execute(ForgetNote input, ToolContext context) {
      if (input.name() == null || input.name().isBlank()) {
        return Awaited.ready(ToolResult.error("name must be provided"));
      }
      SubjectId subject = resolver.apply(context.conversationId());
      notebook.forget(subject, input.name());
      return Awaited.ready(ToolResult.ok("Forgotten '" + input.name() + "'."));
    }
  }
}
