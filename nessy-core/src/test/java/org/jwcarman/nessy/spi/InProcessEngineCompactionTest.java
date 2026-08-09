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

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.SessionStore;

/** Compaction performed by {@link InProcessEngine}: a summarization call, no different in kind. */
class InProcessEngineCompactionTest {

  private static final SessionId ID = new SessionId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of());

  /**
   * A trigger low enough that the huge scripted usage crosses it, and {@code keepRecentMessages}
   * low enough that the short transcripts these tests build still have something to cut at — the
   * default policy's {@code keepRecentMessages} of 10 would leave every one of them uncompactable.
   */
  private static Reducer compactingReducer() {
    return new Reducer(
        TerminationPolicy.defaults(), new CompactionPolicy(100_000, 0, 256, "Summarize."));
  }

  private static InProcessEngine engineWith(
      ModelProvider provider, Reducer reducer, EventHub hub, ObservationRegistry observations) {
    return new InProcessEngine(
        provider,
        ToolRegistry.of(),
        Approver.allowAll(),
        SessionStore.inMemory(),
        hub,
        reducer,
        CONFIG,
        new ObjectMapper(),
        observations);
  }

  /**
   * A provider whose second call (the compaction call) throws instead of returning a turn, proving
   * the engine recovers from a summarizer that blows up rather than propagating.
   */
  private static final class FailingCompactProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private int calls;

    FailingCompactProvider(List<List<ModelEvent>> scripted) {
      turns.addAll(scripted);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      calls++;
      if (calls == 2) {
        throw new IllegalStateException("summarizer exploded");
      }
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
  class A_successful_compaction {

    @Test
    void a_triggered_compaction_summarizes_and_the_conversation_continues() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10))),
                  List.of(
                      new ModelEvent.TextChunk("Summary of earlier turns."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Normal answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      InProcessEngine engine =
          engineWith(
              provider, compactingReducer(), EventHub.synchronous(), ObservationRegistry.create());

      engine.run(ID, Event.UserSaid.of("first question"));
      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("second question"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().generation()).isEqualTo(1);
      assertThat(completed.state().messages().getFirst().role()).isEqualTo(Role.USER);
      String summaryText =
          ((TextBlock) completed.state().messages().getFirst().content().getFirst()).text();
      assertThat(summaryText).contains("Summary of earlier turns.");
      assertThat(completed.state().messages().getLast())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
    }
  }

  @Nested
  class A_failing_compaction {

    @Test
    void a_failing_summarizer_emits_the_hub_event_and_the_turn_proceeds() {
      FailingCompactProvider provider =
          new FailingCompactProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10))),
                  List.of(
                      new ModelEvent.TextChunk("Normal answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      EventHub hub = EventHub.synchronous();
      List<CompactionFailed> failures = new ArrayList<>();
      hub.subscribe(CompactionFailed.class, failures::add);
      InProcessEngine engine =
          engineWith(provider, compactingReducer(), hub, ObservationRegistry.create());

      engine.run(ID, Event.UserSaid.of("first question"));
      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("second question"));

      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().sessionId()).isEqualTo(ID);
      assertThat(failures.getFirst().reason()).contains("summarizer exploded");
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().generation()).isZero();
      assertThat(completed.state().messages().getLast())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
    }
  }

  @Nested
  class Request_shape {

    @Test
    void the_compaction_call_carries_no_tools_and_the_policy_budget() {
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10))),
                  List.of(
                      new ModelEvent.TextChunk("Summary."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Normal answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      InProcessEngine engine =
          engineWith(
              provider, compactingReducer(), EventHub.synchronous(), ObservationRegistry.create());

      engine.run(ID, Event.UserSaid.of("first question"));
      engine.run(ID, Event.UserSaid.of("second question"));

      ModelRequest compactionRequest = provider.requests().get(1);
      assertThat(compactionRequest.tools()).isEmpty();
      assertThat(compactionRequest.maxTokens()).isEqualTo(256);
    }
  }

  @Nested
  class Observations {

    @Test
    void compaction_produces_its_own_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider =
          new EngineFixtures.FakeProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10))),
                  List.of(
                      new ModelEvent.TextChunk("Summary."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
                  List.of(
                      new ModelEvent.TextChunk("Normal answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      InProcessEngine engine =
          engineWith(provider, compactingReducer(), EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("first question"));
      engine.run(ID, Event.UserSaid.of("second question"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.compaction")
          .that()
          .hasContextualNameEqualTo("compact");
    }

    @Test
    void a_failing_compaction_marks_its_observation_with_an_error() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      FailingCompactProvider provider =
          new FailingCompactProvider(
              List.of(
                  List.of(
                      new ModelEvent.TextChunk("First answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10))),
                  List.of(
                      new ModelEvent.TextChunk("Normal answer."),
                      new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
      InProcessEngine engine =
          engineWith(provider, compactingReducer(), EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("first question"));
      engine.run(ID, Event.UserSaid.of("second question"));

      assertThat(observations).hasObservationWithNameEqualTo("nessy.compaction").that().hasError();
    }
  }
}
