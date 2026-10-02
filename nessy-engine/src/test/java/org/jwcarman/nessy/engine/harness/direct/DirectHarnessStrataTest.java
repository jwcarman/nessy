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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.Memory;
import org.jwcarman.nessy.api.MemorySource;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.State;
import org.jwcarman.nessy.api.StateSource;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Memory, state and ambient sources go from a harness's configuration to the provider's request,
 * each in its own stratum: the way in is the configuration, and the way out is what the provider is
 * handed.
 */
@DisplayName("Sources configured on a harness reach the provider's request")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessStrataTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final String QUESTION = "what do I owe?";

  private final List<InferenceRequest> requests = new CopyOnWriteArrayList<>();
  private final List<Turn> askedOfMemory = new CopyOnWriteArrayList<>();

  private final InferenceProvider scripted =
      (request, narrator) -> {
        requests.add(request);
        return new InferenceResult.Answer(
            List.of(new Block.Text("ten dollars")), Usage.unreported("the-model"));
      };

  private DefaultDirectHarnessFactory factory;

  @AfterEach
  void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  private static String textOf(Turn turn) {
    return ((Block.Text) turn.input().blocks().getFirst()).text();
  }

  /** A memory that is derived from the turn it is handed, and remembers being handed it. */
  private MemorySource recallingTheQuestion() {
    return new MemorySource() {
      @Override
      public String kind() {
        return "recalled";
      }

      @Override
      public Optional<Memory> forAgent(AgentId agentId, Turn current) {
        askedOfMemory.add(current);
        return Optional.of(Memory.text("recalled", "you were asked: " + textOf(current)));
      }
    };
  }

  private Customizer<HarnessConfig<?>> sources() {
    return config ->
        config
            .memory(recallingTheQuestion())
            .state(StateSource.constant(State.text("plan", "step one of three")))
            .ambient(AmbientSource.constant(Ambient.text("clock", "it is Tuesday")));
  }

  private InferenceContext askOneQuestion(
      Customizer<DirectHarnessFactoryConfig> onFactory, Customizer<HarnessConfig<?>> onHarness) {
    JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
    factory =
        DefaultDirectHarnessFactory.of(
            f -> {
              f.backend(new InMemoryDirectBackend(codecs))
                  .provider(ProviderId.of("test"), scripted)
                  .schemas(new VictoolsJsonSchemaGenerator())
                  .mapper(JsonMapper.builder().build());
              onFactory.customize(f);
            });
    DirectHarness<String, String> harness =
        factory.<String>create(
            TYPE,
            c -> {
              c.systemPrompt("You are terse.")
                  .inputRenderer(said -> List.of(new Block.Text(said)))
                  .inference(in -> in.provider("test").model("the-model"));
              onHarness.customize(c);
            });

    harness.ask(AgentId.random(), QUESTION);

    assertThat(requests).hasSize(1);
    return requests.getFirst().context();
  }

  private void assertEachSourceIsInItsOwnStratum(InferenceContext context) {
    assertThat(context.memory())
        .containsExactly(Memory.text("recalled", "you were asked: " + QUESTION));
    assertThat(context.state()).containsExactly(State.text("plan", "step one of three"));
    assertThat(context.ambient()).containsExactly(Ambient.text("clock", "it is Tuesday"));
    assertThat(askedOfMemory).hasSize(1);
    assertThat(askedOfMemory.getFirst()).isEqualTo(context.activeTurn());
    assertThat(textOf(context.activeTurn())).isEqualTo(QUESTION);
    assertThat(context.tail()).isEmpty();
    assertThat(context.summaries()).isEmpty();
  }

  @Test
  void sources_set_on_the_harness_are_each_in_their_own_stratum() {
    InferenceContext context = askOneQuestion(f -> {}, c -> sources().customize(c));

    assertEachSourceIsInItsOwnStratum(context);
  }

  @Test
  void sources_installed_through_a_feature_reach_the_request_too() {
    InferenceContext context = askOneQuestion(f -> f.feature(sources()), c -> {});

    assertEachSourceIsInItsOwnStratum(context);
  }
}
