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
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code toString()} is what a log line or a failed assertion prints -- the key and property values
 * must not be in it.
 */
class EmbedderSettingsTest {

  @Test
  void tostring_does_not_print_the_key() {
    EmbedderSettings settings =
        new EmbedderSettings(
            EmbeddingWire.OPENAI, "https://g/v1", "sk-super-secret", null, null, null);

    assertThat(settings.toString()).doesNotContain("sk-super-secret").contains("apiKey=***");
  }

  @Test
  void tostring_prints_property_names_never_values() {
    EmbedderSettings settings =
        new EmbedderSettings(null, null, null, null, null, Map.of("voyage.truncation", "false"));

    assertThat(settings.toString()).contains("voyage.truncation").doesNotContain("false");
  }
}
