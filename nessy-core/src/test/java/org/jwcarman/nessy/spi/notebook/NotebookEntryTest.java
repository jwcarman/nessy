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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link Notebook.Entry} and {@link Notebook.Heading}: the notebook's two record shapes. */
class NotebookEntryTest {

  @Nested
  class Entry_construction {

    @Test
    void a_blank_name_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("  ", "hook", "body", "writer"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_name_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry(null, "hook", "body", "writer"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_blank_hook_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("name", "  ", "body", "writer"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_hook_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("name", null, "body", "writer"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_blank_body_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("name", "hook", "  ", "writer"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_body_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("name", "hook", null, "writer"))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_null_source_is_rejected() {
      assertThatThrownBy(() -> new Notebook.Entry("name", "hook", "body", null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_fully_populated_entry_is_kept_verbatim() {
      Notebook.Entry entry =
          new Notebook.Entry("user-taste", "Prefers terse answers", "Full body", "writer");

      assertThat(entry.name()).isEqualTo("user-taste");
      assertThat(entry.hook()).isEqualTo("Prefers terse answers");
      assertThat(entry.body()).isEqualTo("Full body");
      assertThat(entry.source()).isEqualTo("writer");
    }
  }

  @Nested
  class Heading_construction {

    @Test
    void a_heading_carries_name_hook_and_source() {
      Notebook.Heading heading =
          new Notebook.Heading("user-taste", "Prefers terse answers", "writer");

      assertThat(heading.name()).isEqualTo("user-taste");
      assertThat(heading.hook()).isEqualTo("Prefers terse answers");
      assertThat(heading.source()).isEqualTo("writer");
    }
  }
}
