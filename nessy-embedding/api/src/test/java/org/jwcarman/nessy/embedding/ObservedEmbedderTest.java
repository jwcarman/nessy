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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Embedder;
import org.jwcarman.nessy.api.Embedding;

class ObservedEmbedderTest {

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  ObservedEmbedderTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
  }

  private static Embedder answering() {
    return new Embedder() {
      @Override
      public String providerName() {
        return "openai";
      }

      @Override
      public String model() {
        return "text-embedding-3-small";
      }

      @Override
      public int dimension() {
        return 3;
      }

      @Override
      public List<Embedding> embedDocuments(List<String> texts) {
        return texts.stream().map(t -> new Embedding(model(), new float[] {1, 0, 0})).toList();
      }
    };
  }

  private static Embedder failing() {
    return new Embedder() {
      @Override
      public String providerName() {
        return "openai";
      }

      @Override
      public String model() {
        return "text-embedding-3-small";
      }

      @Override
      public int dimension() {
        return 3;
      }

      @Override
      public List<Embedding> embedDocuments(List<String> texts) {
        throw new IllegalStateException("the endpoint is down");
      }
    };
  }

  /** Named as semconv names an embeddings call, and said to be whoever's span it opened under. */
  @Test
  void a_call_is_an_embeddings_span_in_semconv_terms() {
    Embedder embedder = ObservedEmbedder.wrap(answering(), registry);

    Observation.createNotStarted("nessy.context.memory", registry)
        .lowCardinalityKeyValue("gen_ai.agent.name", "chat")
        .highCardinalityKeyValue("gen_ai.conversation.id", "agent-1")
        .observe(() -> embedder.embedDocument("what did we decide about the deploy?"));

    Observation.Context call = stopped.getFirst();
    assertThat(call.getContextualName()).isEqualTo("embeddings text-embedding-3-small");
    assertThat(call.getLowCardinalityKeyValue("gen_ai.operation.name").getValue())
        .isEqualTo("embeddings");
    assertThat(call.getLowCardinalityKeyValue("gen_ai.provider.name").getValue())
        .isEqualTo("openai");
    assertThat(call.getLowCardinalityKeyValue("gen_ai.request.model").getValue())
        .isEqualTo("text-embedding-3-small");
    assertThat(call.getHighCardinalityKeyValue("gen_ai.embeddings.dimension.count").getValue())
        .isEqualTo("3");
    assertThat(call.getLowCardinalityKeyValue("gen_ai.agent.name").getValue()).isEqualTo("chat");
    assertThat(call.getHighCardinalityKeyValue("gen_ai.conversation.id").getValue())
        .isEqualTo("agent-1");
  }

  @Test
  void a_failed_call_says_what_failed() {
    Embedder embedder = ObservedEmbedder.wrap(failing(), registry);

    assertThatThrownBy(() -> embedder.embedDocument("anything"))
        .isInstanceOf(IllegalStateException.class);

    Observation.Context call = stopped.getFirst();
    assertThat(call.getLowCardinalityKeyValue("error.type").getValue())
        .isEqualTo("IllegalStateException");
    assertThat(call.getError()).hasMessage("the endpoint is down");
  }

  /** Anything handed an embedder may observe it without doubling its spans. */
  @Test
  void observing_an_observed_embedder_returns_it_as_it_is() {
    Embedder once = ObservedEmbedder.wrap(answering(), registry);

    assertThat(ObservedEmbedder.wrap(once, registry)).isSameAs(once);

    once.embedDocument("anything");
    assertThat(stopped).hasSize(1);
  }

  /** Switching tracing off costs a check per call and nothing else. */
  @Test
  void with_nothing_tracing_it_is_the_embedder_it_wraps() {
    Embedder embedder = ObservedEmbedder.wrap(answering(), ObservationRegistry.NOOP);

    assertThat(embedder.embedDocument("anything").dimension()).isEqualTo(3);
    assertThat(embedder.model()).isEqualTo("text-embedding-3-small");
  }
}
