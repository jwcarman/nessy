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

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.SubjectId;

/** {@link InMemoryNotebook}, reached through {@link Notebook#inMemory()}. */
class InMemoryNotebookTest {

  private final Notebook notebook = Notebook.inMemory();

  @Nested
  class Finding {

    @Test
    void a_subject_never_saved_to_has_no_headings() {
      assertThat(notebook.headings(new SubjectId("subject-1"))).isEmpty();
    }

    @Test
    void an_unknown_name_is_not_found() {
      SubjectId subject = new SubjectId("subject-1");

      assertThat(notebook.find(subject, "user-taste")).isEmpty();
    }

    @Test
    void a_saved_entry_is_found_by_subject_and_name() {
      SubjectId subject = new SubjectId("subject-1");
      Notebook.Entry entry =
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer");

      notebook.save(subject, entry);

      assertThat(notebook.find(subject, "user-taste")).contains(entry);
    }
  }

  @Nested
  class Saving {

    @Test
    void saving_twice_under_the_same_name_replaces_the_entry() {
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject, new Notebook.Entry("user-taste", "Prefers terse answers", "v1", "writer"));

      notebook.save(
          subject, new Notebook.Entry("user-taste", "Prefers metric units", "v2", "writer"));

      assertThat(notebook.find(subject, "user-taste"))
          .contains(new Notebook.Entry("user-taste", "Prefers metric units", "v2", "writer"));
    }

    @Test
    void replaying_the_same_save_stores_the_identical_entry() {
      SubjectId subject = new SubjectId("subject-1");
      Notebook.Entry entry =
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer");

      notebook.save(subject, entry);
      notebook.save(subject, entry);

      assertThat(notebook.find(subject, "user-taste")).contains(entry);
    }
  }

  @Nested
  class Forgetting {

    @Test
    void forgetting_a_saved_entry_removes_it() {
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));

      notebook.forget(subject, "user-taste");

      assertThat(notebook.find(subject, "user-taste")).isEmpty();
    }

    @Test
    void forgetting_an_absent_entry_is_a_no_op() {
      SubjectId subject = new SubjectId("subject-1");

      notebook.forget(subject, "user-taste");

      assertThat(notebook.find(subject, "user-taste")).isEmpty();
    }
  }

  @Nested
  class Headings {

    @Test
    void headings_come_back_alphabetical_by_name_regardless_of_save_order() {
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(subject, new Notebook.Entry("zebra-facts", "Hook z", "Body z", "writer"));
      notebook.save(subject, new Notebook.Entry("apple-facts", "Hook a", "Body a", "writer"));
      notebook.save(subject, new Notebook.Entry("mango-facts", "Hook m", "Body m", "writer"));

      assertThat(notebook.headings(subject))
          .containsExactly(
              new Notebook.Heading("apple-facts", "Hook a", "writer"),
              new Notebook.Heading("mango-facts", "Hook m", "writer"),
              new Notebook.Heading("zebra-facts", "Hook z", "writer"));
    }

    @Test
    void headings_carry_no_bodies() {
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));

      List<Notebook.Heading> headings = notebook.headings(subject);

      assertThat(headings)
          .containsExactly(new Notebook.Heading("user-taste", "Prefers terse answers", "writer"));
    }

    @Test
    void headings_carry_the_entrys_source() {
      SubjectId subject = new SubjectId("subject-1");
      notebook.save(
          subject,
          new Notebook.Entry("lesson", "what went wrong", "full lesson body", "reflection"));

      List<Notebook.Heading> headings = notebook.headings(subject);

      assertThat(headings)
          .containsExactly(new Notebook.Heading("lesson", "what went wrong", "reflection"));
    }
  }

  @Nested
  class Subject_isolation {

    @Test
    void two_subjects_never_see_each_others_notes() {
      SubjectId first = new SubjectId("subject-1");
      SubjectId second = new SubjectId("subject-2");
      notebook.save(
          first, new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer"));

      assertThat(notebook.find(second, "user-taste")).isEmpty();
      assertThat(notebook.headings(second)).isEmpty();
    }
  }
}
