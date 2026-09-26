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
package org.jwcarman.nessy.engine.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentNarrator;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.inference.InferenceNarrator;

@DisplayName("The wire view of a narrator")
class InferenceNarratorsTest {

  /** A provider is handed text and thinking, and the engine is what turns them into events. */
  @Test
  void mints_the_delta_events() {
    AtomicReference<Narration> heard = new AtomicReference<>();
    InferenceNarrator wire = InferenceNarrators.of((AgentNarrator) heard::set);

    wire.text("hel");
    assertThat(heard).hasValue(new Narration.ContentDelta("hel"));

    wire.thinking("hmm");
    assertThat(heard).hasValue(new Narration.ThinkingDelta("hmm"));

    assertThatCode(() -> InferenceNarrator.silent().text("x")).doesNotThrowAnyException();
    assertThatCode(() -> InferenceNarrator.silent().thinking("x")).doesNotThrowAnyException();
  }
}
