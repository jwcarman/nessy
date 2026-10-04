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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("What an agent type is told about itself is fixed when its harness is built")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessInstructionsTest {

  private static final AgentType TYPE = new AgentType("instructed");

  private static final Customizer<HarnessConfig<?>> NO_FEATURE = _ -> {};
  private static final Customizer<DirectHarnessConfig<?>> NO_BLANKET = _ -> {};

  /** Answers every call, and remembers each request it was handed. */
  private static final class Recording implements InferenceProvider {
    private final List<InferenceRequest> seen = new ArrayList<>();

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      seen.add(request);
      return new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
    }

    List<String> prompts() {
      return seen.stream().map(request -> request.systemPrompt().value()).toList();
    }
  }

  private static DefaultDirectHarnessFactory factory(
      Recording model,
      Customizer<HarnessConfig<?>> feature,
      Customizer<DirectHarnessConfig<?>> blanket) {
    InMemoryAgentEvents events =
        new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
    InMemoryPayloads payloads =
        new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));
    return DefaultDirectHarnessFactory.of(
        c ->
            c.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                .provider(ProviderId.of("test"), model)
                .schemas(new VictoolsJsonSchemaGenerator())
                .mapper(JsonMapper.builder().build())
                .feature(feature)
                .harness(blanket));
  }

  private static DirectHarness<String, String> harness(
      DefaultDirectHarnessFactory factory, Customizer<DirectHarnessConfig<String>> caller) {
    return factory.<String>create(
        TYPE,
        c -> {
          c.inference(in -> in.provider("test").model("a-model"));
          caller.customize(c);
        });
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class The_system_prompt {

    @Test
    void the_system_prompt_is_the_prompt_then_each_instruction_in_order() {
      Recording model = new Recording();
      DirectHarness<String, String> harness =
          harness(
              factory(model, NO_FEATURE, NO_BLANKET),
              c -> c.systemPrompt("You are terse.").instructions("First.").instructions("Second."));

      harness.ask(AgentId.random(), "hello");

      assertThat(model.prompts()).containsExactly("You are terse.\n\nFirst.\n\nSecond.");
    }

    @Test
    void the_system_prompt_is_the_same_on_every_call_and_for_every_agent() {
      Recording model = new Recording();
      DirectHarness<String, String> harness =
          harness(
              factory(model, NO_FEATURE, NO_BLANKET),
              c -> c.systemPrompt("You are terse.").instructions("Be kind."));
      AgentId again = AgentId.random();

      harness.ask(AgentId.random(), "one");
      harness.ask(AgentId.random(), "two");
      harness.ask(again, "three");
      harness.ask(again, "four");

      assertThat(model.prompts()).hasSize(4).containsOnly("You are terse.\n\nBe kind.");
    }

    @Test
    void instructions_alone_follow_the_default_prompt() {
      Recording model = new Recording();
      DirectHarness<String, String> harness =
          harness(factory(model, NO_FEATURE, NO_BLANKET), c -> c.instructions("Be kind."));

      harness.ask(AgentId.random(), "hello");

      assertThat(model.prompts()).containsExactly("You are a helpful assistant.\n\nBe kind.");
    }

    @Test
    void blank_instructions_are_refused() {
      DirectHarnessConfig<String> config =
          new DefaultDirectHarnessConfig<>(TYPE, ObservationRegistry.NOOP);

      assertThatThrownBy(() -> config.instructions("  "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void instructions_from_a_feature_the_factory_and_the_caller_land_in_the_order_added() {
      Recording model = new Recording();
      DirectHarness<String, String> harness =
          harness(
              factory(
                  model,
                  config -> config.instructions("From a feature."),
                  config -> config.instructions("From the factory.")),
              c -> c.systemPrompt("You are terse.").instructions("From the caller."));

      harness.ask(AgentId.random(), "hello");

      assertThat(model.prompts())
          .containsExactly(
              "You are terse.\n\nFrom a feature.\n\nFrom the factory.\n\nFrom the caller.");
    }
  }
}
