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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An embedding")
class EmbeddingTest {

  private static final float[] EMPTY = new float[0];

  @Test
  void is_a_model_and_a_vector_and_compares_by_content() {
    Embedding one = new Embedding("m", new float[] {1, 0});
    Embedding same = new Embedding("m", new float[] {1, 0});
    Embedding other = new Embedding("m", new float[] {0, 1});

    assertThat(one)
        .isEqualTo(same)
        .hasSameHashCodeAs(same)
        .isNotEqualTo(other)
        .hasToString("Embedding[model=m, dimension=2]");
    assertThat(one.dimension()).isEqualTo(2);
    // equals(Object) refuses anything that is not an Embedding, and says so without throwing.
    Object notAnEmbedding = new Object();
    assertThat(one).isNotEqualTo(notAnEmbedding);
  }

  @Test
  void the_vector_handed_in_and_handed_out_is_a_copy() {
    float[] source = {1, 2};
    Embedding embedding = new Embedding("m", source);
    source[0] = 9;
    float[] out = embedding.vector();
    out[1] = 9;

    assertThat(embedding.vector()).containsExactly(1, 2);
  }

  @Test
  void refuses_a_blank_model_and_an_empty_vector() {
    assertThatThrownBy(() -> new Embedding(" ", new float[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Embedding("m", EMPTY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void similarity_is_the_cosine_and_only_between_vectors_of_one_model() {
    Embedding east = new Embedding("m", new float[] {1, 0});
    Embedding north = new Embedding("m", new float[] {0, 1});
    Embedding northEast = new Embedding("m", new float[] {1, 1});
    Embedding west = new Embedding("m", new float[] {-2, 0});
    Embedding nowhere = new Embedding("m", new float[] {0, 0});
    Embedding elsewhere = new Embedding("other", new float[] {1, 0});
    Embedding longer = new Embedding("m", new float[] {1, 0, 0});

    assertThat(east.similarity(east)).isCloseTo(1, within(1e-9));
    assertThat(east.similarity(north)).isCloseTo(0, within(1e-9));
    assertThat(east.similarity(northEast)).isCloseTo(Math.sqrt(0.5), within(1e-9));
    assertThat(east.similarity(west)).isCloseTo(-1, within(1e-9));
    assertThat(east.similarity(nowhere)).isZero();
    assertThatThrownBy(() -> east.similarity(elsewhere))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other");
    assertThatThrownBy(() -> east.similarity(longer)).isInstanceOf(IllegalArgumentException.class);
  }
}
