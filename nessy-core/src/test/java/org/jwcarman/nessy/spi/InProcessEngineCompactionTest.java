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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.EventSpine;
import org.jwcarman.nessy.api.event.EventSpines;
import org.jwcarman.nessy.api.event.ListenerDeclaration;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.compaction.Compactor;
import org.jwcarman.nessy.spi.compaction.Compactors;
import org.jwcarman.nessy.spi.compaction.Summarizer;
import org.jwcarman.nessy.spi.context.ContextPipeline;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.conversation.TranscriptEntry;
import org.jwcarman.nessy.spi.conversation.TranscriptStore;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Compaction performed by {@link InProcessEngine}: the compactor's {@code compact()} runs under its
 * own observation, and its result — or its failure — is what the reducer sees next. Unlike before
 * this seam existed, most of these scenarios never touch the model provider at all: the compactor
 * decides how (or whether) to shrink the working set, and only the summarizing default happens to
 * do that by calling a model, which {@code SummarizerTest} covers on its own.
 */
class InProcessEngineCompactionTest {

  private static final ConversationId ID = new ConversationId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  /**
   * A trigger low enough that the huge scripted usage crosses it, and {@code keepRecent} low enough
   * that the short transcripts these tests build still have something to cut at.
   */
  private static Reducer reducerUsing(Summarizer summarizer) {
    Compactor compactor =
        Compactors.summarizing(summarizer).triggerTokens(100_000).keepRecent(0).build();
    return new Reducer(TerminationPolicy.defaults(), compactor);
  }

  private static Reducer reducerUsing(Compactor compactor) {
    return new Reducer(TerminationPolicy.defaults(), compactor);
  }

  private static InProcessEngine engineWith(
      EngineFixtures.FakeProvider provider,
      Reducer reducer,
      EventEmitter events,
      ObservationRegistry observations) {
    return new InProcessEngine(
        provider,
        ToolRegistry.of(),
        Map.of(),
        Approver.allowAll(),
        ConversationStore.inMemory(),
        events,
        reducer,
        CONFIG,
        new ObjectMapper(),
        observations,
        ContextPipeline.builder().build(events, observations));
  }

  /** A two-turn provider: a big-usage first answer, then a plain second answer once resumed. */
  private static EngineFixtures.FakeProvider twoTurnProvider() {
    return new EngineFixtures.FakeProvider(
        List.of(
            List.of(
                new ModelEvent.TextChunk("First answer."),
                new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(150_000, 10, 0))),
            List.of(
                new ModelEvent.TextChunk("Normal answer."),
                new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))));
  }

  /**
   * A compactor that triggers exactly like the summarizing default's token trigger would, but hands
   * back whatever {@code result} it is given rather than actually summarizing.
   */
  private static Compactor triggeringAt(long tokens, Compactor.Result result) {
    return new Compactor() {
      @Override
      public boolean requiresCompaction(ConversationState state) {
        return state.lastInputTokens() >= tokens;
      }

      @Override
      public Result compact(ConversationState state) {
        return result;
      }
    };
  }

  @Nested
  class A_successful_compaction {

    @Test
    void a_triggered_compaction_summarizes_and_the_conversation_continues() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer = (head) -> "Summary of earlier turns.";
      InProcessEngine engine =
          engineWith(
              provider,
              reducerUsing(summarizer),
              EventEmitter.noop(),
              ObservationRegistry.create());

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      RunOutcome outcome = engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

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
    void a_failing_compactor_emits_the_hub_event_and_the_turn_proceeds() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer =
          (head) -> {
            throw new IllegalStateException("summarizer exploded");
          };
      List<CompactionFailed> failures = new ArrayList<>();
      EventSpine hub =
          EventSpines.of(List.of(ListenerDeclaration.sync(CompactionFailed.class, failures::add)));
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), hub, ObservationRegistry.create());

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      RunOutcome outcome = engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().conversationId()).isEqualTo(ID);
      assertThat(failures.getFirst().reason()).contains("summarizer exploded");
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().generation()).isZero();
      assertThat(completed.state().messages().getLast())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
    }

    @Test
    void a_pair_breaking_compactor_is_a_failure_not_a_corruption() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      ToolCall orphan = new ToolCall("orphan", "read_file", JsonNodeFactory.instance.objectNode());
      List<Message> broken = List.of(Message.assistant(List.of(new ToolUseBlock(orphan))));
      Compactor compactor = triggeringAt(100_000, new Compactor.Result(broken));
      List<CompactionFailed> failures = new ArrayList<>();
      EventSpine hub =
          EventSpines.of(List.of(ListenerDeclaration.sync(CompactionFailed.class, failures::add)));
      InProcessEngine engine =
          engineWith(provider, reducerUsing(compactor), hub, ObservationRegistry.create());

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      RunOutcome outcome = engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

      assertThat(failures).hasSize(1);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().generation()).isZero();
      // The compactor's broken result never reached the reducer, so the working set that was
      // already there survives untouched, and the turn still completes normally.
      assertThat(completed.state().messages().getLast())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
    }
  }

  @Nested
  class Transcript {

    @Test
    void compaction_journals_the_summary_with_zero_usage() {
      // The jurisdiction rule (design §10.6): the journal, like the ledger, only ever attributes
      // the loop's own conversational spend. Whatever the summarizer's call cost is telemetry's
      // business, not the journal's — SummarizerTest pins where it actually surfaces.
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer = (head) -> "Summary.";
      InMemoryTranscriptStore transcriptStore = TranscriptStore.inMemory();
      EventSpine hub = EventSpines.of(List.of(transcriptStore.declareListener()));
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), hub, ObservationRegistry.create());

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

      List<TranscriptEntry> entries = transcriptStore.entries(ID);
      assertThat(entries).hasSize(5);
      assertThat(entries.get(0).message()).isEqualTo(Message.user("first question"));
      assertThat(entries.get(0).turnUsage()).isEqualTo(Usage.zero());
      assertThat(entries.get(1).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("First answer."))));
      assertThat(entries.get(1).turnUsage()).isEqualTo(new Usage(150_000, 10, 0));
      assertThat(entries.get(2).message()).isEqualTo(Message.user("second question"));
      assertThat(entries.get(2).turnUsage()).isEqualTo(Usage.zero());
      // The summary is the only newborn message the compaction produces; the originals it
      // replaced were already journaled at their own birth and must not be re-appended.
      String summaryText = ((TextBlock) entries.get(3).message().content().getFirst()).text();
      assertThat(summaryText).contains("Summary.");
      assertThat(entries.get(3).turnUsage()).isEqualTo(Usage.zero());
      assertThat(entries.get(4).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
      assertThat(entries.get(4).turnUsage()).isEqualTo(Usage.zero());
    }
  }

  @Nested
  class Observations {

    @Test
    void compaction_produces_its_own_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer = (head) -> "Summary.";
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), EventEmitter.noop(), observations);

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

      assertThat(observations)
          .hasObservationWithNameEqualTo("nessy.compaction")
          .that()
          .hasContextualNameEqualTo("compact");
    }

    @Test
    void a_failing_compaction_marks_its_observation_with_an_error() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer =
          (head) -> {
            throw new IllegalStateException("summarizer exploded");
          };
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), EventEmitter.noop(), observations);

      engine.run(ID, ConversationEvent.UserSaid.of(ID, "first question"));
      engine.run(ID, ConversationEvent.UserSaid.of(ID, "second question"));

      assertThat(observations).hasObservationWithNameEqualTo("nessy.compaction").that().hasError();
    }
  }
}
