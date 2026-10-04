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
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.EmptyInput;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("A harness that is only nudged")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessEmptyInputTest {

  private static final AgentType TYPE = new AgentType("watch");

  private final List<InferenceRequest> requests = new CopyOnWriteArrayList<>();
  private final InMemoryAgentEvents events =
      new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final InMemoryPayloads payloads =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  private DefaultDirectHarnessFactory factory;

  @AfterEach
  void closeFactory() {
    if (factory != null) {
      factory.close();
    }
  }

  private void nudge() {
    InferenceProvider model =
        (request, narrator) -> {
          requests.add(request);
          return new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
        };
    factory =
        DefaultDirectHarnessFactory.of(
            c ->
                c.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                    .provider(ProviderId.of("test"), model)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build()));
    DirectHarness<EmptyInput, String> harness =
        factory.<EmptyInput>create(
            TYPE,
            c ->
                c.inputRenderer(_ -> List.of(new Block.Text("Do your rounds.")))
                    .inference(in -> in.provider("test").model("a-model")));
    harness.ask(agent, new EmptyInput());
  }

  @Test
  void the_model_is_sent_the_text_the_renderer_gives_for_the_nudge() {
    nudge();

    assertThat(requests).hasSize(1);
    assertThat(requests.getFirst().context().activeTurn().input().blocks())
        .containsExactly(new Block.Text("Do your rounds."));
  }

  @Test
  void the_turn_is_labelled_empty_input_when_no_label_is_configured() {
    nudge();

    List<AgentEvent.TurnStarted> starts =
        events.readAll(TYPE, agent).stream()
            .filter(AgentEvent.TurnStarted.class::isInstance)
            .map(AgentEvent.TurnStarted.class::cast)
            .toList();
    assertThat(starts).hasSize(1);
    assertThat(starts.getFirst().label()).isEqualTo("EmptyInput");
  }
}
