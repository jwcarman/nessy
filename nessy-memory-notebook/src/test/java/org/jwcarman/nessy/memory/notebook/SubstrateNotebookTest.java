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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

@DisplayName("A notebook an agent keeps")
class SubstrateNotebookTest {

  private static final AgentId ONE = AgentId.of("agent-one");
  private static final AgentId TWO = AgentId.of("agent-two");

  private Notebook notebook;

  @BeforeEach
  void fresh() {
    notebook =
        new SubstrateNotebook(new InMemorySubstrate(Clock.systemUTC()), AgentType.of("chat"));
  }

  @Test
  void a_fresh_agent_has_written_nothing() {
    assertThat(notebook.headings(ONE)).isEmpty();
    assertThat(notebook.find(ONE, "anything")).isEmpty();
  }

  @Test
  void a_written_note_comes_back_whole() {
    Notebook.Entry written = notebook.write(ONE, "Prefers terse answers", "Short. Metric units.");

    assertThat(notebook.find(ONE, written.id())).contains(written);
  }

  @Test
  @DisplayName("an id is minted, short, and never chosen by the caller")
  void ids_are_minted() {
    Notebook.Entry written = notebook.write(ONE, "hook", "body");

    assertThat(written.id()).hasSize(10).matches("[bcdfghjkmnpqrstvwxz23456789]+");
  }

  /**
   * Two notes filed with identical text are still two notes. Under the old scheme they were one,
   * because the name was the key and identical notes chose identical names.
   */
  @Test
  void two_notes_that_say_the_same_thing_are_still_two_notes() {
    Notebook.Entry first = notebook.write(ONE, "same hook", "same body");
    Notebook.Entry second = notebook.write(ONE, "same hook", "same body");

    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(notebook.headings(ONE)).hasSize(2);
  }

  @Test
  @DisplayName("revising replaces in place, keeping the id the model already has")
  void revising_replaces_the_note() {
    Notebook.Entry written = notebook.write(ONE, "Prefers terse", "old");

    notebook.revise(ONE, written.id(), "Prefers terse", "new");

    assertThat(notebook.headings(ONE)).hasSize(1);
    assertThat(notebook.find(ONE, written.id()).orElseThrow().body()).isEqualTo("new");
  }

  @Test
  @DisplayName("revising a note that is gone says so rather than filing a new one")
  void revising_an_unknown_id_does_nothing() {
    assertThat(notebook.revise(ONE, "nosuchid00", "hook", "body")).isEmpty();
    assertThat(notebook.headings(ONE)).isEmpty();
  }

  @Test
  void forgetting_removes_it() {
    Notebook.Entry written = notebook.write(ONE, "Prefers terse", "body");

    notebook.forget(ONE, written.id());

    assertThat(notebook.find(ONE, written.id())).isEmpty();
    assertThat(notebook.headings(ONE)).isEmpty();
  }

  @Test
  @DisplayName("forgetting what was never there is not an error: forgetting twice is forgetting")
  void forgetting_an_absent_note_is_a_no_op() {
    notebook.forget(ONE, "never-written");

    assertThat(notebook.headings(ONE)).isEmpty();
  }

  /**
   * The index is read on every turn, so its order must not shuffle — a context that changes without
   * the notes changing invalidates a cached prefix for nothing.
   */
  @Test
  @DisplayName("headings keep the order the notes were written in")
  void headings_are_in_writing_order() {
    notebook.write(ONE, "first", "a");
    notebook.write(ONE, "second", "b");
    notebook.write(ONE, "third", "c");

    assertThat(notebook.headings(ONE))
        .extracting(Notebook.Heading::hook)
        .containsExactly("first", "second", "third");
  }

  @Test
  @DisplayName("a heading carries the hook and NOT the body — that absence is the point")
  void headings_carry_no_bodies() {
    notebook.write(ONE, "A hook", "a body nobody asked for yet");

    List<Notebook.Heading> headings = notebook.headings(ONE);

    assertThat(headings).hasSize(1);
    assertThat(headings.getFirst().hook()).isEqualTo("A hook");
    assertThat(headings.toString()).doesNotContain("a body nobody asked for yet");
  }

  @Test
  void agents_do_not_read_each_others_notes() {
    Notebook.Entry mine = notebook.write(ONE, "Mine", "private");

    assertThat(notebook.headings(TWO)).isEmpty();
    assertThat(notebook.find(TWO, mine.id())).isEmpty();
  }

  @Nested
  @DisplayName("an entry")
  class Entries {

    @Test
    void refuses_a_blank_id() {
      assertThatThrownBy(() -> new Notebook.Entry(" ", "hook", "body"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("id");
    }

    @Test
    @DisplayName("refuses a blank hook, because a hook is what the index is made of")
    void refuses_a_blank_hook() {
      assertThatThrownBy(() -> new Notebook.Entry("id", "", "body"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hook");
    }

    @Test
    void refuses_a_blank_body() {
      assertThatThrownBy(() -> new Notebook.Entry("id", "hook", ""))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("body");
    }
  }
}
