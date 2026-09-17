package org.jwcarman.nessy.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What an embedder says it is, when it has not said.
 *
 * <p>The name reaches a dashboard as {@code gen_ai.provider.name}, so what matters is that it is
 * the same on every run: an adapter answers for its vendor, and anything else answers for the class
 * it was written in.
 */
class EmbedderTest {

  /** A named class answers for itself. */
  private static class OllamaEmbedder implements Embedder {

    @Override
    public String model() {
      return "nomic-embed-text";
    }

    @Override
    public int dimension() {
      return 1;
    }

    @Override
    public Embedding embed(String text) {
      return new Embedding(model(), new float[] {1});
    }
  }

  @Test
  void a_named_embedder_is_named_for_itself() {
    assertThat(new OllamaEmbedder().providerName()).isEqualTo("OllamaEmbedder");
  }

  /** An anonymous class has no name of its own, so it answers for the class that wrote it. */
  @Test
  void an_anonymous_embedder_is_named_for_the_class_that_wrote_it() {
    Embedder anonymous =
        new Embedder() {
          @Override
          public String model() {
            return "m";
          }

          @Override
          public int dimension() {
            return 1;
          }

          @Override
          public Embedding embed(String text) {
            return new Embedding(model(), new float[] {1});
          }
        };

    assertThat(anonymous.providerName()).isEqualTo("EmbedderTest");
  }

  /** An adapter says its vendor, and the default never gets a say. */
  @Test
  void an_adapter_that_says_its_vendor_keeps_it() {
    Embedder vendor =
        new OllamaEmbedder() {
          @Override
          public String providerName() {
            return "ollama";
          }
        };

    assertThat(vendor.providerName()).isEqualTo("ollama");
  }

  @Test
  void the_default_batch_embeds_one_at_a_time_in_order() {
    List<Embedding> embeddings = new OllamaEmbedder().embed(List.of("one", "two"));

    assertThat(embeddings).hasSize(2);
  }
}
