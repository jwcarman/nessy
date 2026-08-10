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
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EnrichmentFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.context.ContextEnricher;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Enrichment performed by {@link InProcessEngine} at request assembly: {@link
 * ContextEnricher#enrich} enriches one conversational request, under its own observation, never the
 * ledger {@link org.jwcarman.nessy.api.conversation.ConversationState} carries forward.
 */
class InProcessEngineEnrichmentTest {

  private static final ConversationId ID = new ConversationId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  private static InProcessEngine engineWith(
      EngineFixtures.FakeProvider provider,
      ContextEnricher enricher,
      EventHub hub,
      ObservationRegistry observations) {
    ContextPipeline pipeline =
        (enricher == null ? ContextPipeline.builder() : ContextPipeline.builder().enrich(enricher))
            .build(hub, observations);
    return new InProcessEngine(
        provider,
        ToolRegistry.of(),
        Map.of(),
        Approver.allowAll(),
        ConversationStore.inMemory(),
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
  class A_successful_enrichment {

    @Test
    void enrichment_enriches_the_request_but_never_the_ledger() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Message fact = Message.user("the sky is blue");
      ContextEnricher enricher = state -> List.of(fact);
      InProcessEngine engine =
          engineWith(provider, enricher, EventHub.synchronous(), ObservationRegistry.create());

      RunOutcome outcome =
          engine.run(ID, ConversationEvent.UserSaid.of(ID, "what color is the sky?"));

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
  class A_failing_enrichment {

    @Test
    void a_failing_enricher_costs_enrichment_not_the_turn() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      ContextEnricher enricher =
          state -> {
            throw new IllegalStateException("enricher exploded");
          };
      EventHub hub = EventHub.synchronous();
      List<EnrichmentFailed> failures = new ArrayList<>();
      hub.subscribe(EnrichmentFailed.class, failures::add);
      InProcessEngine engine = engineWith(provider, enricher, hub, ObservationRegistry.create());

      RunOutcome outcome = engine.run(ID, ConversationEvent.UserSaid.of(ID, "hi"));

      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().conversationId()).isEqualTo(ID);
      assertThat(failures.getFirst().reason()).contains("enricher exploded");
      List<ModelRequest> requests = provider.requests();
      assertThat(requests).hasSize(1);
      assertThat(requests.getFirst().context().messages()).containsExactly(Message.user("hi"));
      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
    }

    @Test
    void a_pair_breaking_enrichment_is_an_enrichment_failure() {
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      Message orphan = Message.toolResults(List.of(new ToolResultBlock("orphan", "oops", false)));
      ContextEnricher enricher = state -> List.of(orphan);
      EventHub hub = EventHub.synchronous();
      List<EnrichmentFailed> failures = new ArrayList<>();
      hub.subscribe(EnrichmentFailed.class, failures::add);
      InProcessEngine engine = engineWith(provider, enricher, hub, ObservationRegistry.create());

      RunOutcome outcome = engine.run(ID, ConversationEvent.UserSaid.of(ID, "hi"));

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
    void no_enrichment_contributors_adds_nothing_and_no_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      InProcessEngine engine = engineWith(provider, null, EventHub.synchronous(), observations);

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "hi"));

      // Zero declared enrichment contributors is identity-skipped:
      // nessy.run/nessy.turn/nessy.model.call
      // still fire, but nessy.context.enrich never does.
      assertThatThrownBy(
              () -> assertThat(observations).hasObservationWithNameEqualTo("nessy.context.enrich"))
          .isInstanceOf(AssertionError.class);
      assertThat(provider.requests()).hasSize(1);
      assertThat(provider.requests().getFirst().context().messages())
          .containsExactly(Message.user("hi"));
    }

    @Test
    void an_enricher_produces_its_own_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      ContextEnricher enricher = state -> List.of(Message.user("a fact"));
      InProcessEngine engine = engineWith(provider, enricher, EventHub.synchronous(), observations);

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "hi"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.context.enrich")
          .that()
          .hasContextualNameEqualTo("enrich");
    }

    @Test
    void a_failing_enricher_marks_its_observation_with_an_error() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = oneTurnProvider();
      ContextEnricher enricher =
          state -> {
            throw new IllegalStateException("enricher exploded");
          };
      InProcessEngine engine = engineWith(provider, enricher, EventHub.synchronous(), observations);

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "hi"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.context.enrich")
          .that()
          .hasError();
    }
  }
}
