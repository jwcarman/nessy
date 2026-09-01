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

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.memory.pipeline.MemoryPipeline;
import org.jwcarman.nessy.testing.TestDatabase;

@DisplayName("The notebook a model works with")
class NotebookToolsTest {

  private static final AgentId AGENT = AgentId.of("cli");
  private static final AgentType TYPE = AgentType.of("chat");

  private Notebook notebook;

  @BeforeEach
  void fresh() {
    notebook = new JdbcNotebook(TestDatabase.fresh(), TYPE);
  }

  /** What the engine hands a running tool. No mocking library, and none needed. */
  private record Call(AgentType agentType, AgentId agentId, ReplyToken replyToken)
      implements ToolContext {

    static Call by(AgentId agentId) {
      return new Call(TYPE, agentId, ReplyToken.of("unused"));
    }
  }

  /**
   * Runs a tool with its own input type.
   *
   * <p>Generic in {@code I} rather than taking {@code Tool<?>} and casting: the cast would need a
   * suppression, and a test that suppresses a warning to check a type-safe API is testing the wrong
   * thing.
   */
  private static <I> ToolResult run(Tool<I> tool, I input) {
    Awaited<ToolResult> answer = tool.execute(input, Call.by(AGENT));
    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<ToolResult>) answer).result();
  }

  @Nested
  class Remembering {

    @Test
    void files_a_note_and_tells_the_model_its_id() {
      ToolResult result =
          run(
              NotebookTools.remember(notebook),
              new NotebookTools.RememberNote("Prefers terse answers", "Short answers."));

      String id = notebook.headings(AGENT).getFirst().id();
      assertThat(result.toString()).contains(id);
      assertThat(notebook.find(AGENT, id).orElseThrow().body()).isEqualTo("Short answers.");
    }

    /** The id is the whole difference between adding and overwriting, and the model states it. */
    /** Two notes, because remember has exactly one job now and replacing is not it. */
    @Test
    void remembering_twice_files_two_notes() {
      run(NotebookTools.remember(notebook), new NotebookTools.RememberNote("a hook", "one"));
      run(NotebookTools.remember(notebook), new NotebookTools.RememberNote("a hook", "two"));

      assertThat(notebook.headings(AGENT)).hasSize(2);
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
      Notebook.Entry first = notebook.write(AGENT, "Prefers terse", "old");

      run(
          NotebookTools.revise(notebook),
          new NotebookTools.ReviseNote(first.id(), "Prefers terse", "new"));

      assertThat(notebook.headings(AGENT)).hasSize(1);
      assertThat(notebook.find(AGENT, first.id()).orElseThrow().body()).isEqualTo("new");
    }

    @Test
    @DisplayName("an id naming nothing points the model back at its index")
    void revising_a_note_that_is_gone_is_an_error() {
      ToolResult result =
          run(NotebookTools.revise(notebook), new NotebookTools.ReviseNote("nosuchid00", "h", "b"));

      assertThat(result).isInstanceOf(ToolResult.Failure.class);
      assertThat(((ToolResult.Failure) result).message()).contains("notebook index");
      assertThat(notebook.headings(AGENT)).isEmpty();
    }
  }

  @Nested
  class Recalling {

    @Test
    void returns_the_body() {
      Notebook.Entry plans = notebook.write(AGENT, "What we are doing", "Ship on Friday.");

      ToolResult result =
          run(NotebookTools.recall(notebook), new NotebookTools.RecallNote(plans.id()));

      assertThat(result).isEqualTo(ToolResult.ok("Ship on Friday."));
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
      Notebook.Entry stale = notebook.write(AGENT, "Old news", "body");

      run(NotebookTools.forget(notebook), new NotebookTools.ForgetNote(stale.id()));

      assertThat(notebook.find(AGENT, stale.id())).isEmpty();
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

    /** A memory that is a list, so the stage can be seen doing its work. */
    private static final class Listing implements Memory {
      private final List<HistoryMessage> told = new java.util.ArrayList<>();

      @Override
      public Context recall(AgentId agentId) {
        return Context.of(List.copyOf(told));
      }

      @Override
      public void remember(AgentId agentId, HistoryMessage message) {
        told.add(message);
      }
    }

    private Memory withIndex(Notebook notebook, Listing bootstrap) {
      return MemoryPipeline.of(bootstrap, p -> p.stage(NotebookTools.index(notebook)));
    }

    @Test
    @DisplayName("an agent with no notes contributes nothing at all")
    void an_empty_notebook_adds_no_message() {
      Listing bootstrap = new Listing();
      Memory memory = withIndex(notebook, bootstrap);
      memory.remember(AGENT, UserMessage.of("hello"));

      assertThat(memory.recall(AGENT).messages()).containsExactly(UserMessage.of("hello"));
    }

    @Test
    void names_and_hooks_reach_the_model() {
      Notebook.Entry note = notebook.write(AGENT, "Prefers terse answers", "body");
      Memory memory = withIndex(notebook, new Listing());

      String shown = ambientOf(memory.recall(AGENT));

      assertThat(shown).contains(note.id()).contains("Prefers terse answers");
    }

    @Test
    @DisplayName("bodies do not: the model asks for those, which is the whole design")
    void bodies_stay_out_of_the_context() {
      notebook.write(AGENT, "Prefers terse", "THE SECRET BODY");
      Memory memory = withIndex(notebook, new Listing());

      assertThat(ambientOf(memory.recall(AGENT))).doesNotContain("THE SECRET BODY");
    }

    /**
     * Background, not a turn. It cannot be remembered — {@code Memory.remember} takes a {@link
     * HistoryMessage} and this is not one — so it can never silt up the transcript.
     */
    @Test
    void the_index_is_ambient_rather_than_something_anyone_said() {
      notebook.write(AGENT, "hook", "body");
      Listing bootstrap = new Listing();
      Memory memory = withIndex(notebook, bootstrap);

      memory.recall(AGENT);
      memory.recall(AGENT);

      assertThat(memory.recall(AGENT).messages()).hasSize(1);
      assertThat(memory.recall(AGENT).messages().getFirst()).isInstanceOf(AmbientMessage.class);
      assertThat(bootstrap.recall(AGENT).messages()).isEmpty();
    }

    @Test
    @DisplayName("it is rebuilt every recall, so a note written now is visible now")
    void the_index_reflects_the_notebook_as_it_stands() {
      Memory memory = withIndex(notebook, new Listing());
      assertThat(memory.recall(AGENT).messages()).isEmpty();

      notebook.write(AGENT, "Just written", "body");

      assertThat(ambientOf(memory.recall(AGENT))).contains("Just written");
    }

    private static String ambientOf(Context context) {
      ContextMessage last = context.messages().getLast();
      assertThat(last).isInstanceOf(AmbientMessage.class);
      return ((TextBlock) ((AmbientMessage) last).content().getFirst()).text();
    }
  }
}
