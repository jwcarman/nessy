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
package org.jwcarman.nessy.embedding.voyage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.Embedding;

/** Against Voyage AI. Tagged {@code live}; needs {@code VOYAGE_API_KEY}. */
@Tag("live")
@DisplayName("The Voyage embedder, live")
class VoyageEmbedderLiveTest {

  @Test
  void near_texts_are_nearer_than_far_ones() {
    assumeTrue(System.getenv("VOYAGE_API_KEY") != null, "VOYAGE_API_KEY is not set");
    String model =
        System.getenv().getOrDefault("NESSY_EMBEDDING_MODEL", VoyageEmbedderConfig.DEFAULT_MODEL);
    try (VoyageEmbedder embedder = VoyageEmbedder.create(c -> c.fromEnv().model(model))) {
      List<Embedding> embeddings =
          embedder.embed(
              List.of(
                  "The Loch Ness monster is a creature said to live in a Scottish lake.",
                  "Nessie is a legendary animal reported in a loch in the Highlands.",
                  "The quarterly invoice for the office printer toner is overdue."));

      double near = embeddings.get(0).similarity(embeddings.get(1));
      double far = embeddings.get(0).similarity(embeddings.get(2));
      System.out.printf(
          "%s: %d dimensions; nessie~nessie %.3f, nessie~toner %.3f%n",
          embedder.model(), embedder.dimension(), near, far);
      assertThat(near).isGreaterThan(far);
    }
  }
}
