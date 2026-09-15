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

import static org.assertj.core.api.Assertions.assertThat;

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
import org.jwcarman.nessy.api.tool.ToolResult;

@DisplayName("The notebook a model works with")
class NotebookToolsTest {

  // Fresh per test: the database is shared by the whole JVM, and an agent is the unit of isolation.
  private final AgentId thisAgent = Calls.agent();

  private Notebook notebook;

  @BeforeEach
  void fresh() {
    notebook = new JdbcNotebook(Calls.database(), Calls.TYPE);
  }

  /**
   * Runs a tool with its own input type.
   *
   * <p>Generic in {@code I} rather than taking {@code Tool<?>} and casting: the cast would need a
   * suppression, and a test that suppresses a warning to check a type-safe API is testing the wrong
   * thing.
   */
  private <I> ToolResult run(Tool<I> tool, I input) {
    Awaited<ToolResult> answer = tool.call(Calls.by(thisAgent, input));
    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<ToolResult>) answer).value();
  }

  /** What a tool actually said, which is now blocks rather than a string. */
  private static String said(ToolResult result) {
    assertThat(result).isInstanceOf(ToolResult.Success.class);
    return ((Block.Text) ((ToolResult.Success) result).blocks().getFirst()).text();
  }

  @Nested
  class Remembering {

    @Test
    void files_a_note_and_tells_the_model_its_id() {
      ToolResult result =
          run(
              NotebookTools.remember(notebook),
              new NotebookTools.RememberNote("Prefers terse answers", "Short answers."));

      String id = notebook.headings(thisAgent).getFirst().id();
      assertThat(said(result)).contains(id);
      assertThat(notebook.find(thisAgent, id).orElseThrow().body()).isEqualTo("Short answers.");
    }

    /**
     * The id is the whole difference between adding and overwriting, and the model states it.
     *
     * <p>Two notes, because remember has exactly one job now and replacing is not it.
     */
    @Test
    void remembering_twice_files_two_notes() {
      run(NotebookTools.remember(notebook), new NotebookTools.RememberNote("a hook", "one"));
      run(NotebookTools.remember(notebook), new NotebookTools.RememberNote("a hook", "two"));

      assertThat(notebook.headings(thisAgent)).hasSize(2);
    }

    /**
     * The tool no longer HAS a slot for an id, which is the point: inventing one used to be a legal
     * move, and a ten-character random id is exactly the kind a model produces plausibly and
     * wrongly.
     */
    @Test
    @DisplayName("revising is a different tool, so an id cannot be invented here")
    void remember_takes_no_id_at_all() {
      assertThat(NotebookTools.RememberNote.class.getRecordComponents())
          .extracting(java.lang.reflect.RecordComponent::getName)
          .containsExactly("hook", "body");
    }

    /**
     * A model that sends nonsense gets something it can read and retry from. A thrown exception
     * would fail the whole turn for a mistake the model could have fixed itself.
     */
    @Test
    @DisplayName("a blank hook is a failed call, not a failed turn")
    void a_blank_hook_comes_back_as_an_error() {
      ToolResult result =
          run(NotebookTools.remember(notebook), new NotebookTools.RememberNote(" ", "body"));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message()).contains("hook");
    }
  }

  @Nested
  class Revising {

    @Test
    void replaces_the_note_in_place() {
      Notebook.Entry first = notebook.write(thisAgent, "Prefers terse", "old");

      run(
          NotebookTools.revise(notebook),
          new NotebookTools.ReviseNote(first.id(), "Prefers terse", "new"));

      assertThat(notebook.headings(thisAgent)).hasSize(1);
      assertThat(notebook.find(thisAgent, first.id()).orElseThrow().body()).isEqualTo("new");
    }

    @Test
    @DisplayName("an id naming nothing points the model back at its index")
    void revising_a_note_that_is_gone_is_an_error() {
      ToolResult result =
          run(NotebookTools.revise(notebook), new NotebookTools.ReviseNote("nosuchid00", "h", "b"));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message()).contains("notebook index");
      assertThat(notebook.headings(thisAgent)).isEmpty();
    }
  }

  @Nested
  class Recalling {

    @Test
    void returns_the_body() {
      Notebook.Entry plans = notebook.write(thisAgent, "What we are doing", "Ship on Friday.");

      ToolResult result =
          run(NotebookTools.recall(notebook), new NotebookTools.RecallNote(plans.id()));

      assertThat(said(result)).isEqualTo("Ship on Friday.");
    }

    @Test
    @DisplayName("an unknown name points the model back at its own index")
    void an_unknown_name_is_an_error_naming_the_index() {
      ToolResult result =
          run(NotebookTools.recall(notebook), new NotebookTools.RecallNote("ghost"));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message())
          .contains("ghost")
          .contains("notebook index");
    }
  }

  @Nested
  class Forgetting {

    @Test
    void removes_the_note() {
      Notebook.Entry stale = notebook.write(thisAgent, "Old news", "body");

      run(NotebookTools.forget(notebook), new NotebookTools.ForgetNote(stale.id()));

      assertThat(notebook.find(thisAgent, stale.id())).isEmpty();
    }

    @Test
    void forgetting_an_absent_note_still_confirms() {
      ToolResult result =
          run(NotebookTools.forget(notebook), new NotebookTools.ForgetNote("neverthere"));

      assertThat(result).isInstanceOf(ToolResult.Success.class);
    }
  }

  @Nested
  @DisplayName("the index")
  class Index {

    /** What the harness would ask on the way into a turn. */
    private Optional<Ambient> index() {
      return NotebookTools.index(notebook).forAgent(thisAgent);
    }

    private String shown() {
      Ambient ambient = index().orElseThrow();
      assertThat(ambient.kind()).isEqualTo("notebook");
      return ((Block.Text) ambient.content().getFirst()).text();
    }

    /**
     * A heading over no notes tells a model it has a notebook, which is a claim. Saying nothing is
     * not -- and an empty section costs every turn of every agent that never writes one.
     */
    @Test
    @DisplayName("an agent with no notes contributes nothing at all")
    void an_empty_notebook_offers_no_ambient_at_all() {
      assertThat(index()).isEmpty();
    }

    @Test
    void names_and_hooks_reach_the_model() {
      Notebook.Entry note = notebook.write(thisAgent, "Prefers terse answers", "body");

      assertThat(shown()).contains(note.id()).contains("Prefers terse answers");
    }

    @Test
    @DisplayName("bodies do not: the model asks for those, which is the whole design")
    void bodies_stay_out_of_the_context() {
      notebook.write(thisAgent, "Prefers terse", "THE SECRET BODY");

      assertThat(shown()).doesNotContain("THE SECRET BODY");
    }

    /**
     * <b>Ambient, so it can never silt up the transcript.</b> An index written into the story would
     * be re-sent exactly as it was written, so a note deleted on Tuesday would still be listed on
     * Friday. There is no door through which this can reach history: an {@link
     * org.jwcarman.nessy.api.AmbientSource} is asked on the way into a call and its answer is never
     * recorded.
     */
    @Test
    @DisplayName("it is asked afresh, so a note written now is visible now and a gone one is gone")
    void the_index_reflects_the_notebook_as_it_stands() {
      assertThat(index()).isEmpty();

      Notebook.Entry note = notebook.write(thisAgent, "Just written", "body");
      assertThat(shown()).contains("Just written");

      notebook.forget(thisAgent, note.id());
      assertThat(index()).as("and stops saying it the moment it stops being true").isEmpty();
    }

    /** One agent's notes are not another's, which is the whole reason a tool is told whose. */
    @Test
    void another_agents_notes_are_not_in_this_agents_index() {
      notebook.write(Calls.agent(), "Somebody else's note", "body");

      assertThat(index()).isEmpty();
    }
  }
}
