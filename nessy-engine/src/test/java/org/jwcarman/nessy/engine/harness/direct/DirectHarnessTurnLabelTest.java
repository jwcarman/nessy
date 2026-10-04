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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DirectHarnessTurnLabelTest {

  private static final AgentType TYPE = new AgentType("labelled");

  /** Nanosecond precision on purpose: what is stored is cut to microseconds. */
  private static final Instant NOW = Instant.parse("2026-03-04T05:06:07.123456789Z");

  record Invoice(String id) {}

  private final InMemoryAgentEvents events =
      new InMemoryAgentEvents(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final InMemoryPayloads payloads =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  private Outcome<String> askWith(Customizer<DirectHarnessConfig<Invoice>> labelling) {
    InferenceProvider model =
        (request, narrator) ->
            new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
    try (DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            c ->
                c.backend(new FixedDirectBackend(new InMemoryLocks(), events, payloads))
                    .provider(ProviderId.of("test"), model)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build())
                    .clock(Clock.fixed(NOW, ZoneOffset.UTC)))) {
      DirectHarness<Invoice, String> harness =
          factory.<Invoice>create(
              TYPE,
              c -> {
                c.inputRenderer(invoice -> List.of(new Block.Text(invoice.id())))
                    .inference(in -> in.provider("test").model("a-model"));
                labelling.customize(c);
              });
      return harness.ask(agent, new Invoice("inv-1"));
    }
  }

  private AgentEvent.TurnStarted theTurnStart() {
    return events.readAll(TYPE, agent).stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .map(AgentEvent.TurnStarted.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Nested
  class A_turns_start {

    @Test
    void carries_the_label_the_application_configured() {
      askWith(c -> c.inputLabel(invoice -> "Invoice " + invoice.id()));

      assertThat(theTurnStart().label()).isEqualTo("Invoice inv-1");
    }

    @Test
    void is_labelled_with_the_inputs_simple_class_name_when_no_label_is_configured() {
      askWith(_ -> {});

      assertThat(theTurnStart().label()).isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_and_the_turn_still_runs_when_the_label_throws() {
      Outcome<String> outcome =
          askWith(
              c ->
                  c.inputLabel(
                      _ -> {
                        throw new IllegalStateException("no label today");
                      }));

      assertThat(outcome).isInstanceOf(Outcome.Answered.class);
      assertThat(theTurnStart().label()).isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_when_the_label_is_blank() {
      askWith(c -> c.inputLabel(_ -> "   "));

      assertThat(theTurnStart().label()).isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_when_the_label_is_null() {
      askWith(c -> c.inputLabel(_ -> null));

      assertThat(theTurnStart().label()).isEqualTo("Invoice");
    }

    @Test
    void says_its_input_arrived_when_ask_read_the_clock_cut_to_microseconds() {
      askWith(_ -> {});

      assertThat(theTurnStart().arrivedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    }
  }
}
