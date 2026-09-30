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
package org.jwcarman.nessy.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.embedding.Embedding;

class EmbeddingOptionsTest {

  @Test
  void options_carry_properties_and_the_short_forms_carry_none() {
    EmbeddingOptions options =
        new EmbeddingOptions("m", OptionalInt.of(256), Map.of("voyage.truncation", "false"));

    assertThat(options.properties()).containsExactly(Map.entry("voyage.truncation", "false"));
    assertThat(new EmbeddingOptions("m", OptionalInt.empty()).properties()).isEmpty();
    assertThat(EmbeddingOptions.of("m").properties()).isEmpty();
    assertThat(options.toString()).contains("voyage.truncation").doesNotContain("false");
  }

  @Test
  void the_default_validate_accepts_everything() {
    EmbeddingProvider provider =
        new EmbeddingProvider() {
          @Override
          public List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options) {
            return List.of();
          }

          @Override
          public Embedding embedQuery(String query, EmbeddingOptions options) {
            return new Embedding("m", new float[] {1f});
          }

          @Override
          public String vendor() {
            return "test";
          }
        };
    EmbeddingOptions options =
        new EmbeddingOptions("m", OptionalInt.empty(), Map.of("anything.at", "all"));

    provider.validate(options);

    assertThat(options.properties()).containsKey("anything.at");
  }
}
