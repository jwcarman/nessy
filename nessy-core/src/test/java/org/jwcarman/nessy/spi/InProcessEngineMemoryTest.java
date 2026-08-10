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
package org.jwcarman.nessy.spi;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.RecallFailed;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.session.SessionStore;

/**
 * Recall performed by {@link InProcessEngine} at request assembly: {@link Memory#recall} enriches
 * one conversational request, under its own observation, never the ledger {@link
 * org.jwcarman.nessy.api.session.SessionState} carries forward.
 */
class InProcessEngineMemoryTest {

  private static final SessionId ID = new SessionId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  private static InProcessEngine engineWith(
      EngineFixtures.FakeProvider provider,
      Memory memory,
      EventHub hub,
      ObservationRegistry observations) {
    ContextPipeline pipeline =
        (memory == null ? ContextPipeline.builder() : ContextPipeline.builder().recall(memory))
            .build(hub, observations);
    return new InProcessEngine(
        provider,
        ToolRegistry.of(),
        Map.of(),
        Approver.allowAll(),
        SessionStore.inMemory(),
        hub,
        Reducer.defaults(),
        CONFIG,
        new ObjectMapper(),
        observations,
        pipeline);
  }

  private static EngineFixtures.FakeProvider oneTurnProvider() {
    return new EngineFixtures.FakeProvider(
        List.of(
            List.of(
                new ModelEvent.TextChunk("An answer."),
                new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
  }

  @Nested
  class A_successful_recall {

    @Test
    void recall_enriches_the_request_but_never_the_ledger() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Message fact = Message.user("the sky is blue");
      Memory memory = state -> List.of(fact);
      InProcessEngine engine =
          engineWith(provider, memory, EventHub.synchronous(), ObservationRegistry.create());

      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("what color is the sky?"));

      List<ModelRequest> requests = provider.requests();
      assertThat(requests).hasSize(1);
      List<Message> requestMessages = requests.getFirst().context().messages();
      assertThat(requestMessages.getFirst()).isEqualTo(fact);
      assertThat(requestMessages).contains(Message.user("what color is the sky?"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().messages()).doesNotContain(fact);
    }
  }

  @Nested
  class A_failing_recall {

    @Test
    void a_failing_memory_costs_enrichment_not_the_turn() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Memory memory =
          state -> {
            throw new IllegalStateException("memory exploded");
          };
      EventHub hub = EventHub.synchronous();
      List<RecallFailed> failures = new ArrayList<>();
      hub.subscribe(RecallFailed.class, failures::add);
      InProcessEngine engine = engineWith(provider, memory, hub, ObservationRegistry.create());

      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("hi"));

      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().sessionId()).isEqualTo(ID);
      assertThat(failures.getFirst().reason()).contains("memory exploded");
      List<ModelRequest> requests = provider.requests();
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().context().messages()).containsExactly(Message.user("hi"));
      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
    }

    @Test
    void a_pair_breaking_memory_is_a_recall_failure() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Message orphan = Message.toolResults(List.of(new ToolResultBlock("orphan", "oops", false)));
      Memory memory = state -> List.of(orphan);
      EventHub hub = EventHub.synchronous();
      List<RecallFailed> failures = new ArrayList<>();
      hub.subscribe(RecallFailed.class, failures::add);
      InProcessEngine engine = engineWith(provider, memory, hub, ObservationRegistry.create());

      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("hi"));

      assertThat(failures).hasSize(1);
      List<ModelRequest> requests = provider.requests();
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().context().messages()).containsExactly(Message.user("hi"));
      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
    }
  }

  @Nested
  class Observations {

    @Test
    void no_recall_contributors_adds_nothing_and_no_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      InProcessEngine engine = engineWith(provider, null, EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("hi"));

      // Zero declared recall contributors is identity-skipped:
      // nessy.run/nessy.turn/nessy.model.call
      // still fire, but nessy.memory.recall never does.
      assertThatThrownBy(
              () -> assertThat(observations).hasObservationWithNameEqualTo("nessy.memory.recall"))
          .isInstanceOf(AssertionError.class);
      assertThat(provider.requests()).hasSize(1);
      assertThat(provider.requests().getFirst().context().messages())
          .containsExactly(Message.user("hi"));
    }

    @Test
    void memory_produces_its_own_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Memory memory = state -> List.of(Message.user("a fact"));
      InProcessEngine engine = engineWith(provider, memory, EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("hi"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.memory.recall")
          .that()
          .hasContextualNameEqualTo("recall");
    }

    @Test
    void a_failing_memory_marks_its_observation_with_an_error() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Memory memory =
          state -> {
            throw new IllegalStateException("memory exploded");
          };
      InProcessEngine engine = engineWith(provider, memory, EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("hi"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.memory.recall")
          .that()
          .hasError();
    }
  }
}
