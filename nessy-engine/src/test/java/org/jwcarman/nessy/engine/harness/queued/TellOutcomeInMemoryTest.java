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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * What telling an agent answers on the in-memory backend, so that the default build, which runs no
 * container, still holds a tell to a terminated agent to its answer.
 */
class TellOutcomeInMemoryTest {

  private static final AgentType TYPE = new AgentType("told-in-memory");
  private static final Duration PATIENT = Duration.ofSeconds(10);

  private final InMemoryQueuedBackend backend =
      new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final AgentId agent = AgentId.random();
  private final CountDownLatch thinking = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);

  private final InferenceProvider heldOpen =
      (request, narrator) -> {
        thinking.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
        return new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
      };

  private DefaultQueuedHarnessFactory factory() {
    return DefaultQueuedHarnessFactory.of(
        config -> config.backend(backend).provider(ProviderId.of("test"), heldOpen));
  }

  private static QueuedHarness<String> harness(DefaultQueuedHarnessFactory factory) {
    return factory.create(
        TYPE,
        String.class,
        config ->
            config
                .systemPrompt("You are terse.")
                .inference(in -> in.provider("test").model("a-model"))
                .effects(e -> e.pollInterval(Duration.ofMillis(20))));
  }

  private List<String> story() {
    return backend.events().readAll(TYPE, agent).stream()
        .map(event -> event.getClass().getSimpleName())
        .toList();
  }

  private long turnsStarted() {
    return backend.events().readAll(TYPE, agent).stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .count();
  }

  @Test
  void an_input_told_to_an_idle_agent_is_accepted_and_runs() {
    release.countDown();
    try (DefaultQueuedHarnessFactory factory = factory()) {
      TellOutcome outcome = harness(factory).tell(agent, "hello");

      assertThat(outcome).isEqualTo(new TellOutcome.Accepted());
      await().atMost(PATIENT).until(() -> turnsStarted() == 1);
    }
  }

  @Test
  void an_input_told_to_a_terminated_agent_is_terminated_and_nothing_is_stored() {
    release.countDown();
    try (DefaultQueuedHarnessFactory factory = factory()) {
      QueuedHarness<String> harness = harness(factory);
      harness.terminate(agent);
      assertThat(story()).containsExactly("Terminated");

      TellOutcome outcome = harness.tell(agent, "too late");

      assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
      assertThat(backend.queued(TYPE, agent)).isZero();
      assertThat(story()).containsExactly("Terminated");
    }
  }

  @Test
  void an_agent_terminated_during_a_turn_answers_terminated_at_once_and_runs_nothing_more()
      throws InterruptedException {
    try (DefaultQueuedHarnessFactory factory = factory()) {
      QueuedHarness<String> harness = harness(factory);
      harness.tell(agent, "first");
      assertThat(thinking.await(10, TimeUnit.SECONDS)).isTrue();
      harness.terminate(agent);

      TellOutcome outcome = harness.tell(agent, "too late");

      assertThat(outcome).isEqualTo(new TellOutcome.Terminated());
      assertThat(backend.queued(TYPE, agent)).isZero();
      release.countDown();
      await().atMost(PATIENT).until(() -> story().contains("Terminated"));
      assertThat(turnsStarted()).as("the input told after the termination never ran").isOne();
    }
  }
}
