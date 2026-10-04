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
package org.jwcarman.nessy.engine.harness.queued;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessConfig;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedHarnessTurnLabelTest {

  private static final AgentType TYPE = new AgentType("labelled-queue");

  record Invoice(String id) {}

  private final InMemoryQueuedBackend backend =
      new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();

  private static InferenceProvider answering() {
    return (request, narrator) ->
        new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
  }

  private DefaultQueuedHarnessFactory factory(InferenceProvider model) {
    return DefaultQueuedHarnessFactory.of(
        config -> config.backend(backend).provider(ProviderId.of("test"), model));
  }

  private QueuedHarness<Invoice> harness(
      DefaultQueuedHarnessFactory factory, Customizer<QueuedHarnessConfig<Invoice>> labelling) {
    return factory.create(
        TYPE,
        Invoice.class,
        config -> {
          config
              .systemPrompt("You are terse.")
              .inputRenderer(invoice -> List.of(new Block.Text(invoice.id())))
              .inference(in -> in.provider("test").model("a-model"))
              .effects(e -> e.pollInterval(Duration.ofMillis(20)));
          labelling.customize(config);
        });
  }

  private List<AgentEvent.TurnStarted> turnStarts() {
    return backend.events().readAll(TYPE, agent).stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .map(AgentEvent.TurnStarted.class::cast)
        .toList();
  }

  private String labelOfTheOnlyTurnWhen(Customizer<QueuedHarnessConfig<Invoice>> labelling) {
    try (DefaultQueuedHarnessFactory factory = factory(answering())) {
      harness(factory, labelling).tell(agent, new Invoice("inv-1"));
      await().atMost(Duration.ofSeconds(10)).until(() -> !turnStarts().isEmpty());
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  backend.events().readAll(TYPE, agent).stream()
                      .anyMatch(AgentEvent.InferenceAnswered.class::isInstance));
      return turnStarts().getFirst().label();
    }
  }

  @Nested
  class A_turns_start {

    @Test
    void carries_the_label_the_application_configured() {
      assertThat(labelOfTheOnlyTurnWhen(c -> c.inputLabel(invoice -> "Invoice " + invoice.id())))
          .isEqualTo("Invoice inv-1");
    }

    @Test
    void is_labelled_with_the_inputs_simple_class_name_when_no_label_is_configured() {
      assertThat(labelOfTheOnlyTurnWhen(_ -> {})).isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_and_the_turn_still_runs_when_the_label_throws() {
      assertThat(
              labelOfTheOnlyTurnWhen(
                  c ->
                      c.inputLabel(
                          _ -> {
                            throw new IllegalStateException("no label today");
                          })))
          .isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_when_the_label_is_blank() {
      assertThat(labelOfTheOnlyTurnWhen(c -> c.inputLabel(_ -> ""))).isEqualTo("Invoice");
    }

    @Test
    void falls_back_to_the_class_name_when_the_label_is_null() {
      assertThat(labelOfTheOnlyTurnWhen(c -> c.inputLabel(_ -> null))).isEqualTo("Invoice");
    }

    @Test
    void says_its_input_arrived_when_tell_was_called_earlier_than_the_turn_started_when_busy()
        throws InterruptedException {
      CountDownLatch firstIsThinking = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      InferenceProvider holdsTheFirstTurnOpen =
          (request, narrator) -> {
            firstIsThinking.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            return new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
          };
      try (DefaultQueuedHarnessFactory factory = factory(holdsTheFirstTurnOpen)) {
        QueuedHarness<Invoice> harness = harness(factory, _ -> {});
        harness.tell(agent, new Invoice("first"));
        assertThat(firstIsThinking.await(10, TimeUnit.SECONDS)).isTrue();

        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        harness.tell(agent, new Invoice("second"));
        Instant after = Instant.now();
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> turnStarts().size() == 2);

        AgentEvent.TurnStarted second = turnStarts().get(1);
        assertThat(second.arrivedAt()).isBetween(before, after);
        assertThat(second.arrivedAt()).isBefore(second.startedAt());
        assertThat(second.arrivedAt().truncatedTo(ChronoUnit.MICROS)).isEqualTo(second.arrivedAt());
      }
    }
  }
}
