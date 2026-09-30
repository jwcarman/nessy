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

class ResolvedEmbedderTest {

  @Test
  void tostring_redacts_the_key_and_prints_property_names_never_values() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage",
            EmbeddingWire.VOYAGE,
            "https://api.voyageai.com/v1",
            "voyage",
            "pa-super-secret",
            Map.of("voyage.truncation", "false"));

    assertThat(resolved.toString())
        .contains("apiKey=***")
        .contains("voyage.truncation")
        .doesNotContain("pa-super-secret")
        .doesNotContain("false");
  }

  /**
   * The registry id is the id; the bean carries a suffix, because one Spring bean namespace also
   * holds the inference provider lit by the same key (spec §6f).
   */
  @Test
  void the_bean_is_named_for_the_id_with_the_embeddings_suffix() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of());

    assertThat(resolved.beanName()).isEqualTo("openaiEmbeddings");
  }
}
