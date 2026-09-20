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
package org.jwcarman.nessy.api.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    public List<Embedding> embedDocuments(List<String> texts) {
      return texts.stream().map(t -> new Embedding(model(), new float[] {1})).toList();
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
          public List<Embedding> embedDocuments(List<String> texts) {
            return texts.stream().map(t -> new Embedding(model(), new float[] {1})).toList();
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

  /** One document is a batch of one, so an adapter never writes that method. */
  @Test
  void one_document_is_embedded_as_a_batch_of_one() {
    assertThat(new OllamaEmbedder().embedDocument("just this"))
        .isEqualTo(new Embedding("nomic-embed-text", new float[] {1}));
  }

  /** The degenerate case still refuses nothing rather than embedding a null. */
  @Test
  void embedding_no_document_at_all_is_refused() {
    Embedder embedder = new OllamaEmbedder();

    assertThatThrownBy(() -> embedder.embedDocument(null)).isInstanceOf(NullPointerException.class);
  }

  /**
   * A vendor with nothing to say about roles answers a query as it answers a document, and that is
   * the truth for it rather than a shortcut.
   */
  @Test
  void a_query_is_the_same_call_when_a_vendor_cannot_tell_them_apart() {
    Embedder embedder = new OllamaEmbedder();

    assertThat(embedder.embedQuery("how deep is it?")).isEqualTo(embedder.embedDocument("a fact"));
  }
}
