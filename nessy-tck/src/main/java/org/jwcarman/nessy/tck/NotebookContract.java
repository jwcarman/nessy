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
 * subject isolation, and last-write-wins — pinned as law rather than left to each implementation's
 * own judgment.
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
    Entry entry = new Entry("user-taste", "Prefers terse answers", "Prefers terse, metric units.");

    notebook().save(subject, entry);

    assertThat(notebook().find(subject, "user-taste")).contains(entry);
  }

  @Test
  public void headings_carry_no_bodies_and_sort_by_name() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("zebra", "last alphabetically", "body one"));
    notebook().save(subject, new Entry("apple", "first alphabetically", "body two"));

    assertThat(notebook().headings(subject))
        .containsExactly(
            new Heading("apple", "first alphabetically"),
            new Heading("zebra", "last alphabetically"));
  }

  @Test
  public void saving_again_under_the_same_name_replaces_the_entry_upsert() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "original hook", "original body"));

    Entry replacement = new Entry("user-taste", "revised hook", "revised body");
    notebook().save(subject, replacement);

    assertThat(notebook().find(subject, "user-taste")).contains(replacement);
  }

  @Test
  public void forget_removes_the_entry() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "a hook", "a body"));

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
    Entry myEntry = new Entry("user-taste", "my hook", "my body");
    notebook().save(mine, myEntry);
    notebook().save(theirs, new Entry("user-taste", "their hook", "their body"));

    assertThat(notebook().find(mine, "user-taste")).contains(myEntry);
    assertThat(notebook().headings(theirs))
        .containsExactly(new Heading("user-taste", "their hook"));
  }

  @Test
  public void saving_again_is_last_write_wins() {
    SubjectId subject = new SubjectId("user-42");
    notebook().save(subject, new Entry("user-taste", "first hook", "first body"));
    notebook().save(subject, new Entry("user-taste", "second hook", "second body"));

    Entry latest = new Entry("user-taste", "third hook", "third body");
    notebook().save(subject, latest);

    assertThat(notebook().find(subject, "user-taste")).contains(latest);
  }
}
