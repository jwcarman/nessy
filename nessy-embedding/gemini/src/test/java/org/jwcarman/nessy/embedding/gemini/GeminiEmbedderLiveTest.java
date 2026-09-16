package org.jwcarman.nessy.embedding.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.Embedding;

/** Against the Gemini Developer API. Tagged {@code live}; needs {@code GEMINI_API_KEY}. */
@Tag("live")
@DisplayName("The Gemini embedder, live")
class GeminiEmbedderLiveTest {

  @Test
  void near_texts_are_nearer_than_far_ones() {
    assumeTrue(
        System.getenv("GEMINI_API_KEY") != null || System.getenv("GOOGLE_API_KEY") != null,
        "GEMINI_API_KEY is not set");
    try (GeminiEmbedder embedder = GeminiEmbedder.create(c -> c.fromEnv().dimension(768))) {
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
      assertThat(embedder.dimension()).isEqualTo(768);
      assertThat(near).isGreaterThan(far);
    }
  }
}
