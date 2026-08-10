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
package org.jwcarman.nessy.spi.compaction;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Summarizer#usingProvider(ModelProvider, ModelSettings, int, String, ObservationRegistry)},
 * the production summarizer, over a hand-rolled fake provider.
 */
class SummarizerTest {

  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1_024, Set.of(), null);

  private static final String INSTRUCTIONS = "Summarize the conversation so far.";

  /** Replays one scripted turn per call and records every request it was handed. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeProvider(List<ModelEvent> turn) {
      turns.add(turn);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  @Nested
  class Request_shape {

    @Test
    void the_head_and_instructions_become_a_tool_free_request() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())));
      Summarizer summarizer =
          Summarizer.usingProvider(provider, CONFIG, 500, INSTRUCTIONS, ObservationRegistry.NOOP);
      Context head = Context.of(List.of(Message.user("hi")));

      summarizer.summarize(head);

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.tools()).isEmpty();
      assertThat(request.maxTokens()).isEqualTo(500);
      assertThat(request.context().messages())
          .containsExactly(Message.user("hi"), Message.user(INSTRUCTIONS));
    }
  }

  @Nested
  class The_summary {

    @Test
    void the_summary_carries_the_text() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the "),
                  new ModelEvent.TextChunk("gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(100, 20, 0))));
      Summarizer summarizer =
          Summarizer.usingProvider(provider, CONFIG, 500, INSTRUCTIONS, ObservationRegistry.NOOP);

      String summary = summarizer.summarize(Context.of(List.of(Message.user("hi"))));

      assertThat(summary).isEqualTo("the gist");
    }

    @Test
    void a_blank_summary_is_a_failure() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("   "),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())));
      Summarizer summarizer =
          Summarizer.usingProvider(provider, CONFIG, 500, INSTRUCTIONS, ObservationRegistry.NOOP);
      Context head = Context.of(List.of(Message.user("hi")));

      assertThatThrownBy(() -> summarizer.summarize(head))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no text");
    }
  }

  @Nested
  class Validation {

    @Test
    void a_summary_max_tokens_below_one_is_rejected() {
      FakeProvider provider = new FakeProvider(List.of());

      assertThatThrownBy(
              () ->
                  Summarizer.usingProvider(
                      provider, CONFIG, 0, INSTRUCTIONS, ObservationRegistry.NOOP))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_instructions_are_rejected() {
      FakeProvider provider = new FakeProvider(List.of());

      assertThatThrownBy(
              () -> Summarizer.usingProvider(provider, CONFIG, 500, null, ObservationRegistry.NOOP))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_null_provider_is_rejected() {
      assertThatThrownBy(
              () ->
                  Summarizer.usingProvider(
                      null, CONFIG, 500, INSTRUCTIONS, ObservationRegistry.NOOP))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_null_config_is_rejected() {
      FakeProvider provider = new FakeProvider(List.of());

      assertThatThrownBy(
              () ->
                  Summarizer.usingProvider(
                      provider, null, 500, INSTRUCTIONS, ObservationRegistry.NOOP))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_null_observation_registry_is_rejected() {
      FakeProvider provider = new FakeProvider(List.of());

      assertThatThrownBy(() -> Summarizer.usingProvider(provider, CONFIG, 500, INSTRUCTIONS, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class The_convenience_factory {

    @Test
    void usingProvider_without_explicit_knobs_defaults_the_ceiling_and_instructions() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())));
      Summarizer summarizer = Summarizer.usingProvider(provider, CONFIG, ObservationRegistry.NOOP);

      summarizer.summarize(Context.of(List.of(Message.user("hi"))));

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.maxTokens()).isEqualTo(2_048);
      assertThat(request.context().messages())
          .containsExactly(Message.user("hi"), Message.user(Summarizer.DEFAULT_INSTRUCTIONS));
    }
  }

  @Nested
  class Telemetry {

    /**
     * The jurisdiction rule (design §10.6): a summarizer's own call is telemetry's, never the
     * ledger's. This is the one pin for that rule at the summarizer level — {@code
     * InProcessEngineCompactionTest} and {@code EndToEndTest} pin the corresponding negative (the
     * ledger and journal never see this spend); this test pins where it actually surfaces.
     */
    @Test
    void the_summarizers_own_call_is_a_nessy_model_call_observation() {
      Usage usage = new Usage(321, 45, 0);
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, usage)));
      TestObservationRegistry observations = TestObservationRegistry.create();
      Summarizer summarizer =
          Summarizer.usingProvider(provider, CONFIG, 500, INSTRUCTIONS, observations);

      summarizer.summarize(Context.of(List.of(Message.user("hi"))));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.model.call")
          .that()
          .hasContextualNameEqualTo("chat fake-model")
          .hasLowCardinalityKeyValue("gen_ai.operation.name", "chat")
          .hasLowCardinalityKeyValue("gen_ai.request.model", "fake-model")
          .hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "321")
          .hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "45");
    }
  }
}
