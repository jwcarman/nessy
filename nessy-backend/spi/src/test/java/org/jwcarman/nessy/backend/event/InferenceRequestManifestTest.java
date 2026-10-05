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
package org.jwcarman.nessy.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.event.InferenceRequestManifest.Section;
import org.jwcarman.nessy.backend.event.InferenceRequestManifest.TurnRange;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InferenceRequestManifestTest {

  private static final PayloadRef REF = new PayloadRef("a1b2");

  private static InferenceRequestManifest minimal() {
    return new InferenceRequestManifest(
        "0.5.0",
        REF,
        REF,
        Optional.empty(),
        REF,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of());
  }

  @Nested
  class A_manifest {

    @Test
    void refuses_a_missing_engine_version() {
      assertThatThrownBy(
              () ->
                  new InferenceRequestManifest(
                      null,
                      REF,
                      REF,
                      Optional.empty(),
                      REF,
                      Optional.empty(),
                      Optional.empty(),
                      List.of(),
                      List.of(),
                      List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("engineVersion must not be null");
    }

    @Test
    void refuses_missing_instructions() {
      assertThatThrownBy(
              () ->
                  new InferenceRequestManifest(
                      "0.5.0",
                      null,
                      REF,
                      Optional.empty(),
                      REF,
                      Optional.empty(),
                      Optional.empty(),
                      List.of(),
                      List.of(),
                      List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("instructions must not be null");
    }

    @Test
    void refuses_a_missing_tools_reference() {
      assertThatThrownBy(
              () ->
                  new InferenceRequestManifest(
                      "0.5.0",
                      REF,
                      null,
                      Optional.empty(),
                      REF,
                      Optional.empty(),
                      Optional.empty(),
                      List.of(),
                      List.of(),
                      List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("tools must not be null");
    }

    @Test
    void refuses_missing_options() {
      assertThatThrownBy(
              () ->
                  new InferenceRequestManifest(
                      "0.5.0",
                      REF,
                      REF,
                      Optional.empty(),
                      null,
                      Optional.empty(),
                      Optional.empty(),
                      List.of(),
                      List.of(),
                      List.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("options must not be null");
    }

    @Test
    void reads_absent_optionals_and_lists_as_empty() {
      InferenceRequestManifest manifest =
          new InferenceRequestManifest("0.5.0", REF, REF, null, REF, null, null, null, null, null);

      assertThat(manifest.answerShape()).isEmpty();
      assertThat(manifest.tail()).isEmpty();
      assertThat(manifest.summarizedThrough()).isEmpty();
      assertThat(manifest.memory()).isEmpty();
      assertThat(manifest.state()).isEmpty();
      assertThat(manifest.ambient()).isEmpty();
    }

    @Test
    void copies_its_lists() {
      List<Section> memory = new ArrayList<>(List.of(new Section("facts", REF)));
      InferenceRequestManifest manifest =
          new InferenceRequestManifest(
              "0.5.0",
              REF,
              REF,
              Optional.empty(),
              REF,
              Optional.empty(),
              Optional.empty(),
              memory,
              List.of(),
              List.of());

      memory.add(new Section("later", REF));

      assertThat(manifest.memory()).containsExactly(new Section("facts", REF));
      assertThat(minimal().memory()).isEmpty();
    }

    @Test
    void holds_lists_that_cannot_be_changed() {
      InferenceRequestManifest manifest = minimal();
      Section section = new Section("facts", REF);
      List<Section> state = manifest.state();

      assertThatThrownBy(() -> state.add(section))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class A_section {

    @Test
    void refuses_a_missing_kind() {
      assertThatThrownBy(() -> new Section(null, REF))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("kind must not be null");
    }

    @Test
    void refuses_a_blank_kind() {
      assertThatThrownBy(() -> new Section("  ", REF))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a section's kind must name something");
    }

    @Test
    void refuses_missing_content() {
      assertThatThrownBy(() -> new Section("facts", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("content must not be null");
    }
  }

  @Nested
  class A_turn_range {

    @Test
    void refuses_a_missing_start() {
      assertThatThrownBy(() -> new TurnRange(null, new TurnId(2)))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("from must not be null");
    }

    @Test
    void refuses_a_missing_end() {
      assertThatThrownBy(() -> new TurnRange(new TurnId(2), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("through must not be null");
    }

    @Test
    void refuses_an_end_before_its_start() {
      TurnId from = new TurnId(5);
      TurnId through = new TurnId(4);

      assertThatThrownBy(() -> new TurnRange(from, through))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("a range must run forwards: from %s through %s".formatted(from, through));
    }

    @Test
    void accepts_a_single_turn() {
      TurnRange range = new TurnRange(new TurnId(5), new TurnId(5));

      assertThat(range.from()).isEqualTo(range.through());
    }
  }
}
