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
import java.util.Objects;
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
 *
 * <p>Every factory also takes {@code identity} — the caller's own author identity, an agent name
 * (or {@code "reflection"} for the critic), supplied once at wiring time. {@link #remember} and
 * {@link #forget} enforce it: a tool may create a new entry under its own identity, and may update
 * or delete only an entry whose stored {@link Notebook.Entry#source()} already matches that
 * identity — a foreign-sourced collision or forget fails with a {@link ToolResult#error} naming the
 * conflict and the entry's true owner (design of record 2026-08-16 §2). {@link #recall} is read-any
 * and does not gate on identity, though its factory accepts one too, for wiring parity with its
 * siblings. {@link #transformer} uses identity only to annotate the rendered index — every heading
 * whose {@link Notebook.Heading#source()} differs from the caller's own identity is marked {@code
 * (from <source>)} so the model can tell its own notes from notes another author (an agent, or the
 * critic) left it. The store itself (an {@link InMemoryNotebook} or a {@code JdbcNotebook})
 * enforces none of this — it is dumb CRUD over any {@code source} a trusted caller hands it (the
 * grant principle); only this model-facing layer gates by identity.
 *
 * <p>The authorship check is check-then-act, not atomic with the {@link Notebook#save}/{@link
 * Notebook#forget} it guards: the store beneath it keeps no lock of its own (the grant principle
 * again — a dumb store, not a coordinating one), so two concurrent mutations racing the same {@code
 * (subject, name)} under different identities can both pass the check before either writes and then
 * interleave at the store. The guard is advisory under real concurrency, not a mutual-exclusion
 * lock; it is honest about who owns a name once the dust settles, not a guarantee that a race never
 * happens.
 */
public final class NotebookTools {

  private static final String IDENTITY_NOT_NULL = "identity must not be null";

  private NotebookTools() {}

  /**
   * The zero-configuration resolver: the subject is the conversation itself.
   *
   * @see #remember(Notebook, String)
   */
  private static SubjectId subjectOf(ConversationId id) {
    return new SubjectId(id.value());
  }

  /**
   * The one identity check every factory below shares: null and blank are both rejected, matching
   * {@link Notebook.Entry}'s own blank rejection on {@code source} — an identity too degenerate to
   * be a meaningful author name is refused at wiring time rather than silently becoming one.
   */
  private static String requireIdentity(String identity) {
    Objects.requireNonNull(identity, IDENTITY_NOT_NULL);
    if (identity.isBlank()) {
      throw new IllegalArgumentException("identity must not be blank");
    }
    return identity;
  }

  /**
   * The tool the model calls to save a durable note. Validation failures (a blank name, hook, or
   * body) surface as a failed {@link ToolResult} rather than a thrown exception, so the model can
   * correct itself. A new entry is created under {@code identity}; remembering an existing name
   * whose {@link Notebook.Entry#source()} is some other identity fails with a {@link
   * ToolResult#error} naming the conflict and the owning source, rather than silently overwriting
   * another author's note. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param identity this tool's own author identity — an agent name, or {@code "reflection"}
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<RememberNote> remember(
      Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
    return new RememberNoteTool(notebook, requireIdentity(identity), resolver);
  }

  /** {@link #remember(Notebook, String, Function)} with the zero-configuration resolver. */
  public static Tool<RememberNote> remember(Notebook notebook, String identity) {
    return remember(notebook, identity, NotebookTools::subjectOf);
  }

  /**
   * The tool the model calls to read a note's full body. An unknown name surfaces as a failed
   * {@link ToolResult} naming the notebook index, so the model self-corrects rather than guessing
   * again. Read-any: unlike {@link #remember} and {@link #forget}, recall never gates on {@code
   * identity} — it exists on this factory only for wiring parity with its siblings. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param identity this tool's own author identity, accepted but not enforced (recall is read-any)
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<RecallNote> recall(
      Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
    requireIdentity(identity);
    return new RecallNoteTool(notebook, resolver);
  }

  /** {@link #recall(Notebook, String, Function)} with the zero-configuration resolver. */
  public static Tool<RecallNote> recall(Notebook notebook, String identity) {
    return recall(notebook, identity, NotebookTools::subjectOf);
  }

  /**
   * The tool the model calls to delete a note. Idempotent: forgetting an absent name still confirms
   * success. Forgetting an entry whose {@link Notebook.Entry#source()} is some other identity fails
   * with a {@link ToolResult#error} naming the conflict and the owning source, rather than letting
   * one author erase another's note. Never parks.
   *
   * @param notebook where notes are durably kept
   * @param identity this tool's own author identity — an agent name, or {@code "reflection"}
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static Tool<ForgetNote> forget(
      Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
    return new ForgetNoteTool(notebook, requireIdentity(identity), resolver);
  }

  /** {@link #forget(Notebook, String, Function)} with the zero-configuration resolver. */
  public static Tool<ForgetNote> forget(Notebook notebook, String identity) {
    return forget(notebook, identity, NotebookTools::subjectOf);
  }

  /**
   * The context-pipeline stage that recalls the subject's notebook index, ambient state at the tail
   * of context. No headings leaves the context unchanged (same instance) — the "if applicable"
   * rule, same as {@link org.jwcarman.nessy.spi.plan.PlanTools#transformer}. Every heading whose
   * {@link Notebook.Heading#source()} differs from {@code identity} is annotated {@code (from
   * <source>)} in the rendered index, so the model can tell its own notes from notes another author
   * left it.
   *
   * @param notebook where notes are durably kept
   * @param identity this caller's own author identity — an agent name, or {@code "reflection"}
   * @param resolver the conversation-to-subject bridge (spec §2)
   */
  public static ContextTransformer transformer(
      Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
    requireIdentity(identity);
    return (id, context) -> {
      SubjectId subject = resolver.apply(id);
      List<Notebook.Heading> headings = notebook.headings(subject);
      if (headings.isEmpty()) {
        return context;
      }
      return context.enrich(new TextBlock(render(headings, identity)));
    };
  }

  /** {@link #transformer(Notebook, String, Function)} with the zero-configuration resolver. */
  public static ContextTransformer transformer(Notebook notebook, String identity) {
    return transformer(notebook, identity, NotebookTools::subjectOf);
  }

  /**
   * Renders {@code headings} as the notebook index block, byte-exact per spec §4, annotating each
   * heading not sourced from {@code identity} with {@code (from <source>)}.
   */
  private static String render(List<Notebook.Heading> headings, String identity) {
    StringBuilder rendered = new StringBuilder("<notebook>\n");
    for (Notebook.Heading heading : headings) {
      rendered.append("- ").append(heading.name()).append(" — ").append(heading.hook());
      if (!identity.equals(heading.source())) {
        rendered.append(" (from ").append(heading.source()).append(")");
      }
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

  /** Renders the shared foreign-source error: names the note, the verb, and the true owner. */
  private static String foreignSourceError(String verb, String name, String owner) {
    return "'"
        + name
        + "' belongs to '"
        + owner
        + "' — you can only "
        + verb
        + " notes you"
        + " created.";
  }

  /** The {@code remember} tool implementation. */
  private static final class RememberNoteTool implements Tool<RememberNote> {

    private final Notebook notebook;
    private final String identity;
    private final Function<ConversationId, SubjectId> resolver;

    private RememberNoteTool(
        Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
      this.notebook = notebook;
      this.identity = Objects.requireNonNull(identity, IDENTITY_NOT_NULL);
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
        entry = new Notebook.Entry(input.name(), input.hook(), input.body(), identity);
      } catch (IllegalArgumentException | NullPointerException e) {
        return Awaited.ready(ToolResult.error(e.getMessage()));
      }
      SubjectId subject = resolver.apply(context.conversationId());
      Optional<Notebook.Entry> existing = notebook.find(subject, entry.name());
      if (existing.isPresent() && !identity.equals(existing.get().source())) {
        return Awaited.ready(
            ToolResult.error(
                foreignSourceError("remember", entry.name(), existing.get().source())));
      }
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
      if (input.name() == null) {
        return Awaited.ready(ToolResult.error("name must not be null"));
      }
      if (input.name().isBlank()) {
        return Awaited.ready(ToolResult.error("name must not be blank"));
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
    private final String identity;
    private final Function<ConversationId, SubjectId> resolver;

    private ForgetNoteTool(
        Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver) {
      this.notebook = notebook;
      this.identity = Objects.requireNonNull(identity, IDENTITY_NOT_NULL);
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
      if (input.name() == null) {
        return Awaited.ready(ToolResult.error("name must not be null"));
      }
      if (input.name().isBlank()) {
        return Awaited.ready(ToolResult.error("name must not be blank"));
      }
      SubjectId subject = resolver.apply(context.conversationId());
      Optional<Notebook.Entry> existing = notebook.find(subject, input.name());
      if (existing.isPresent() && !identity.equals(existing.get().source())) {
        return Awaited.ready(
            ToolResult.error(foreignSourceError("forget", input.name(), existing.get().source())));
      }
      notebook.forget(subject, input.name());
      return Awaited.ready(ToolResult.ok("Forgotten '" + input.name() + "'."));
    }
  }
}
