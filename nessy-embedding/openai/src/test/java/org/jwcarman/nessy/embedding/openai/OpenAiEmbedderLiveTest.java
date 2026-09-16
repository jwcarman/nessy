package org.jwcarman.nessy.embedding.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.Embedding;

/**
 * Against a real OpenAI-compatible endpoint: LM Studio on {@code :1234} serving an embedding model.
 * Tagged {@code live}, so CI skips it; run it with {@code -Dnessy.excludedGroups=} and the two
 * properties below.
 */
@Tag("live")
@DisplayName("The OpenAI embedder against a local model")
class OpenAiEmbedderLiveTest {

  private static final String BASE_URL =
      System.getProperty("nessy.embedding.base-url", "http://127.0.0.1:1234/v1");
  private static final String MODEL =
      System.getProperty("nessy.embedding.model", "text-embedding-nomic-embed-text-v1.5");

  @Test
  void near_texts_are_nearer_than_far_ones() {
    try (OpenAiEmbedder embedder =
        OpenAiEmbedder.create(c -> c.apiKey("lm-studio").baseUrl(BASE_URL).model(MODEL))) {

      List<Embedding> embeddings =
          embedder.embed(
              List.of(
                  "The Loch Ness monster is a creature said to live in a Scottish lake.",
                  "Nessie is a legendary animal reported in a loch in the Highlands.",
                  "The quarterly invoice for the office printer toner is overdue."));

      Embedding nessie = embeddings.get(0);
      Embedding alsoNessie = embeddings.get(1);
      Embedding toner = embeddings.get(2);

      assertThat(embedder.model()).isEqualTo(MODEL);
      assertThat(embedder.dimension()).isEqualTo(nessie.dimension()).isPositive();
      assertThat(embeddings)
          .allSatisfy(e -> assertThat(e.dimension()).isEqualTo(nessie.dimension()));

      double near = nessie.similarity(alsoNessie);
      double far = nessie.similarity(toner);
      System.out.printf(
          "%s: %d dimensions; nessie~nessie %.3f, nessie~toner %.3f%n",
          MODEL, nessie.dimension(), near, far);
      assertThat(near).isGreaterThan(far);
      assertThat(nessie.similarity(nessie))
          .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }
  }
}
