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
package org.jwcarman.nessy.tck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.notebook.Notebook.Entry;
import org.jwcarman.nessy.spi.notebook.Notebook.Heading;

/**
 * The technology-compatibility kit every {@link Notebook} implementation must pass (design §5):
 * round-trip, index-only headings in name order, upsert replacement, forget and its idempotence,
 * subject isolation, last-write-wins, and — design of record 2026-08-16 §2 — the {@code source}
 * field round-trips faithfully and the store itself stays neutral about who wrote what (the grant
 * principle: authorship is enforced by {@code NotebookTools}, not the store) — pinned as law rather
 * than left to each implementation's own judgment.
 *
 * <p>Test methods are {@code public} — a nested-subscriber discovery lesson learned elsewhere in
 * this kit: a package-private {@code @Test} method inherited into a {@code @Nested} class is not
 * always picked up the same way by every JUnit runner, so this contract states its methods public
 * rather than risk it.
 */
public abstract class NotebookContract {

  /** The notebook under test — fresh and empty for each test. */
  protected abstract Notebook notebook();

  @Test
  public void a_saved_entry_is_found_by_subject_and_name() {
    SubjectId subject = new SubjectId("user-42");
    Entry entry =
        new Entry("user-taste", "Prefers terse answers", "Prefers terse, metric units.", "writer");

    notebook().save(subject, entry);

    assertThat(notebook().find(subject, "user-taste")).contains(entry);
  }

  @Test
  public void headings_carry_no_bodies_and_sort_by_name() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("zebra", "last alphabetically", "body one", "writer"));
    notebook().save(subject, new Entry("apple", "first alphabetically", "body two", "writer"));

    assertThat(notebook().headings(subject))
        .containsExactly(
            new Heading("apple", "first alphabetically", "writer"),
            new Heading("zebra", "last alphabetically", "writer"));
  }

  @Test
  public void saving_again_under_the_same_name_replaces_the_entry_upsert() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "original hook", "original body", "writer"));

    Entry replacement = new Entry("user-taste", "revised hook", "revised body", "writer");
    notebook().save(subject, replacement);

    assertThat(notebook().find(subject, "user-taste")).contains(replacement);
  }

  @Test
  public void forget_removes_the_entry() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "a hook", "a body", "writer"));

    notebook().forget(subject, "user-taste");

    assertThat(notebook().find(subject, "user-taste")).isEmpty();
  }

  @Test
  public void forgetting_an_absent_entry_is_a_no_op() {
    SubjectId subject = new SubjectId("user-42");

    notebook().forget(subject, "never-saved");

    assertThat(notebook().find(subject, "never-saved")).isEmpty();
  }

  @Test
  public void two_subjects_never_see_each_others_notes() {
    SubjectId mine = new SubjectId("user-42");
    SubjectId theirs = new SubjectId("user-99");
    Entry myEntry = new Entry("user-taste", "my hook", "my body", "writer");
    Entry theirEntry = new Entry("user-taste", "their hook", "their body", "writer");
    notebook().save(mine, myEntry);
    notebook().save(theirs, theirEntry);

    assertThat(notebook().find(mine, "user-taste")).contains(myEntry);
    assertThat(notebook().headings(theirs))
        .containsExactly(new Heading("user-taste", "their hook", "writer"));

    // Forgetting my same-named note must not touch theirs — closes the hole a subject-blind
    // `DELETE WHERE name = ?` (no subject_id in the WHERE clause) would leave open.
    notebook().forget(mine, "user-taste");

    assertThat(notebook().find(mine, "user-taste")).isEmpty();
    assertThat(notebook().find(theirs, "user-taste")).contains(theirEntry);
  }

  @Test
  public void saving_again_is_last_write_wins() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "first hook", "first body", "writer"));
    notebook().save(subject, new Entry("user-taste", "second hook", "second body", "writer"));

    Entry latest = new Entry("user-taste", "third hook", "third body", "writer");
    notebook().save(subject, latest);

    assertThat(notebook().find(subject, "user-taste")).contains(latest);
  }

  @Test
  public void an_entrys_source_round_trips_through_find() {
    SubjectId subject = new SubjectId("user-42");
    Entry lesson = new Entry("lesson-1", "what went wrong", "the full lesson body", "reflection");

    notebook().save(subject, lesson);

    assertThat(notebook().find(subject, "lesson-1")).contains(lesson);
  }

  @Test
  public void headings_carry_the_entrys_source_for_index_annotation() {
    SubjectId subject = new SubjectId("user-42");
    notebook()
        .save(
            subject,
            new Entry("lesson-1", "what went wrong", "the full lesson body", "reflection"));

    assertThat(notebook().headings(subject))
        .containsExactly(new Heading("lesson-1", "what went wrong", "reflection"));
  }

  @Test
  public void the_store_lets_any_source_overwrite_an_entry_regardless_of_who_wrote_it_first() {
    // Grant principle (design of record 2026-08-16 §2): the store is dumb CRUD and enforces no
    // authorship — only NotebookTools, the model-facing layer, gates by source.
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("shared-note", "first hook", "first body", "writer"));

    Entry overwritten = new Entry("shared-note", "second hook", "second body", "reflection");
    notebook().save(subject, overwritten);

    assertThat(notebook().find(subject, "shared-note")).contains(overwritten);
  }

  @Test
  public void the_store_lets_any_source_forget_an_entry_regardless_of_who_wrote_it() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("shared-note", "a hook", "a body", "writer"));

    // The store itself has no notion of "foreign" — that gate lives in NotebookTools, not here.
    notebook().forget(subject, "shared-note");

    assertThat(notebook().find(subject, "shared-note")).isEmpty();
  }
}
