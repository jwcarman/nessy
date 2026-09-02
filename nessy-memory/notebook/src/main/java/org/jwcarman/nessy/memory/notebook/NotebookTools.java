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
package org.jwcarman.nessy.memory.notebook;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.AmbientContentBlock;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.memory.pipeline.ContextTransformer;

/**
 * The three verbs a model uses on its notebook, and the stage that shows it what it has.
 *
 * <p>{@code remember} / {@code recall} / {@code forget} — the words models are already trained on.
 * The {@code recall} tool shares only a word with {@code Memory.recall}, never a call path.
 *
 * <p><b>The tools are useless without the stage.</b> A model that cannot see its index does not
 * know a note exists, so it never calls for one. That is the whole design: the index makes the
 * model the judge of relevance, and the tools let it act on the judgement.
 */
public final class NotebookTools {

  /** What this background is, so an adapter can label it the way its vendor likes. */
  private static final String KIND = "notebook";

  private static final String NOTEBOOK_NOT_NULL = "notebook must not be null";

  private NotebookTools() {}

  /**
   * Files a note, or replaces one.
   *
   * <p>The id is optional and that is the whole distinction: absent files something new, present
   * replaces the note it names. The model is never asked to invent an id, only to pass back one it
   * can see in its index.
   */
  public record RememberNote(
      @JsonPropertyDescription("One line saying what this note is about; shown in your index")
          String hook,
      @JsonPropertyDescription("The note itself") String body) {}

  /** Replaces a note that already exists. The id comes from the index, never from invention. */
  public record ReviseNote(
      @JsonPropertyDescription("The id of the note to replace, exactly as it appears in your index")
          String id,
      @JsonPropertyDescription("One line saying what this note is about; shown in your index")
          String hook,
      @JsonPropertyDescription("The replacement text") String body) {}

  /** Reads one note back. */
  public record RecallNote(
      @JsonPropertyDescription("The id of the note, exactly as it appears in your index")
          String id) {}

  /** Drops a note. */
  public record ForgetNote(@JsonPropertyDescription("The id of the note to forget") String id) {}

  /**
   * The stage that puts the index in front of the model.
   *
   * <p>Contributes ONE {@link AmbientMessage} — background, not a turn, so it never reaches the
   * transcript and each adapter puts it wherever its vendor keeps background. An agent with no
   * notes contributes nothing at all rather than an empty block announcing its own emptiness.
   */
  public static ContextTransformer index(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return (agentId, context) -> {
      List<Notebook.Heading> headings = notebook.headings(agentId);
      if (headings.isEmpty()) {
        return context;
      }
      List<ContextMessage> messages = new java.util.ArrayList<>(context.messages());
      messages.add(
          new AmbientMessage(KIND, List.<AmbientContentBlock>of(new TextBlock(render(headings)))));
      return Context.of(messages);
    };
  }

  /**
   * What the model sees. Names and hooks only: the bodies are what {@code recall} is for.
   *
   * <p><b>No label here.</b> The design this came from wrote {@code <notebook>} tags into the text,
   * because the index was appended to a user message and the tags marked where it began and ended.
   * It is an {@link AmbientMessage} with a KIND now, so the label is structured data and each
   * adapter renders it the way its vendor prefers — tags for one, a heading for another. Writing it
   * here would take that choice away from them.
   */
  private static String render(List<Notebook.Heading> headings) {
    StringBuilder text = new StringBuilder("Notes you wrote, by id:\n");
    for (Notebook.Heading heading : headings) {
      text.append("- ").append(heading.id()).append(" — ").append(heading.hook()).append('\n');
    }
    return text.append(
            "Maintain these with the remember and forget tools, and read one in full with recall"
                + " when it is relevant.")
        .toString();
  }

  /**
   * Files a NEW note, always.
   *
   * <p>It used to take an optional id — absent to create, present to replace — and that was a
   * mistake worth naming: it made inventing an id a legal move. Ids here are ten random characters
   * from a no-vowel alphabet, so a model that half-remembers one produces something plausible and
   * wrong, and then does it again. Observed in the wild: four turns burnt guessing at ids before
   * landing on a real one.
   *
   * <p>Now the only way to name a note is to read one out of the index, which is in front of the
   * model on every call. A mistyped id is still possible, but it is a mistake the model can see and
   * correct rather than one the tool invited.
   */
  public static Tool<RememberNote> remember(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return new NotebookTool<>(
        RememberNote.class,
        "remember",
        "File a new note with a one-line hook. Returns the id it was given. To CHANGE a note that"
            + " already exists, use revise with the id from your notebook index — never guess an"
            + " id, and never use this tool to replace a note.",
        (agentId, note) -> {
          Notebook.Entry written = notebook.write(agentId, note.hook(), note.body());
          return ToolResult.ok("Remembered as '" + written.id() + "'.");
        });
  }

  /** Replaces a note the model has an id for. */
  public static Tool<ReviseNote> revise(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return new NotebookTool<>(
        ReviseNote.class,
        "revise",
        "Replace a note that already exists, using its id exactly as it appears in your notebook"
            + " index. If you do not have an id from the index, file a new note with remember"
            + " instead.",
        (agentId, note) ->
            notebook
                .revise(agentId, note.id(), note.hook(), note.body())
                .map(revised -> ToolResult.ok("Replaced '" + revised.id() + "'."))
                .orElseGet(() -> unknown(note.id())));
  }

  public static Tool<RecallNote> recall(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return new NotebookTool<>(
        RecallNote.class,
        "recall",
        "Read one of your notes in full, by the id shown in your notebook index.",
        (agentId, note) ->
            notebook
                .find(agentId, note.id())
                .map(entry -> ToolResult.ok(entry.body()))
                // An error the model can act on: it can see the index, so it can correct itself.
                .orElseGet(() -> unknown(note.id())));
  }

  public static Tool<ForgetNote> forget(Notebook notebook) {
    Objects.requireNonNull(notebook, NOTEBOOK_NOT_NULL);
    return new NotebookTool<>(
        ForgetNote.class,
        "forget",
        "Delete one of your notes by id.",
        (agentId, note) -> {
          notebook.forget(agentId, note.id());
          return ToolResult.ok("Forgotten '" + note.id() + "'.");
        });
  }

  /** The same answer wherever an id turns out to name nothing: look at the index again. */
  private static ToolResult unknown(String id) {
    return ToolResult.error(
        "no note with id '" + id + "' — check the notebook index in your context");
  }

  /** What a notebook verb does once it knows whose notebook it is. */
  @FunctionalInterface
  private interface Verb<I> {
    ToolResult apply(AgentId agentId, I input);
  }

  /**
   * The shape all three share.
   *
   * <p>Validation failures come back as {@link ToolResult#error}, never as a throw: a model that
   * sent a blank name should read what went wrong and try again, which is the difference between a
   * failed call and a failed turn.
   */
  private record NotebookTool<I>(Class<I> inputType, String name, String description, Verb<I> verb)
      implements Tool<I> {

    @Override
    public Awaited<ToolResult> execute(ToolCallRequest<I> call) {
      I input = call.input();
      try {
        return Awaited.ready(verb.apply(call.agentId(), input));
      } catch (IllegalArgumentException | NullPointerException invalid) {
        return Awaited.ready(ToolResult.error(invalid.getMessage()));
      }
    }
  }
}
