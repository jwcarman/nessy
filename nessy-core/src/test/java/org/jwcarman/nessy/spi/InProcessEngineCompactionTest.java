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
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CompactionPolicy;
import org.jwcarman.nessy.api.CompactionStrategy;
import org.jwcarman.nessy.api.CompactionTrigger;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.Role;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolUseBlock;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.event.CompactionFailed;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.compaction.CompactionStrategies;
import org.jwcarman.nessy.spi.compaction.Summarizer;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.session.SessionStore;

/**
 * Compaction performed by {@link InProcessEngine}: the strategy's {@code compact()} runs under its
 * own observation, and its result — or its failure — is what the reducer sees next. Unlike before
 * this seam existed, most of these scenarios never touch the model provider at all: the strategy
 * decides how (or whether) to shrink the working set, and only the summarizing default happens to
 * do that by calling a model, which {@code SummarizerTest} covers on its own.
 */
class InProcessEngineCompactionTest {

  private static final SessionId ID = new SessionId("s1");
  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);

  /**
   * A trigger low enough that the huge scripted usage crosses it, and {@code keepRecentMessages}
   * low enough that the short transcripts these tests build still have something to cut at.
   */
  private static Reducer reducerUsing(Summarizer summarizer) {
    CompactionPolicy policy =
        new CompactionPolicy(CompactionTrigger.atTokens(100_000), 0, 256, "Summarize.");
    return new Reducer(
        TerminationPolicy.defaults(), CompactionStrategies.summarizing(policy, summarizer));
  }

  private static Reducer reducerUsing(CompactionStrategy strategy) {
    return new Reducer(TerminationPolicy.defaults(), strategy);
  }

  private static InProcessEngine engineWith(
      EngineFixtures.FakeProvider provider,
      Reducer reducer,
      EventHub hub,
      ObservationRegistry observations) {
    return new InProcessEngine(
        provider,
        ToolRegistry.of(),
        Approver.allowAll(),
        SessionStore.inMemory(),
        hub,
        reducer,
        CONFIG,
        new ObjectMapper(),
        observations,
        ContextBuilder.identity());
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
   * A strategy that triggers exactly like the summarizing default's token trigger would, but hands
   * back whatever {@code result} it is given rather than actually summarizing.
   */
  private static CompactionStrategy triggeringAt(long tokens, CompactionStrategy.Result result) {
    return new CompactionStrategy() {
      @Override
      public boolean requiresCompaction(SessionState state) {
        return state.lastInputTokens() >= tokens;
      }

      @Override
      public Result compact(List<Message> workingSet) {
        return result;
      }
    };
  }

  @Nested
  class A_successful_compaction {

    @Test
    void a_triggered_compaction_summarizes_and_the_conversation_continues() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer =
          (head, policy) -> new Summarizer.Summary("Summary of earlier turns.", Usage.zero());
      InProcessEngine engine =
          engineWith(
              provider,
              reducerUsing(summarizer),
              EventHub.synchronous(),
              ObservationRegistry.create());

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

    @Test
    void the_engine_reports_what_the_strategy_spent() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Usage spend = new Usage(500, 20, 0);
      Summarizer summarizer = (head, policy) -> new Summarizer.Summary("Summary.", spend);
      InProcessEngine engine =
          engineWith(
              provider,
              reducerUsing(summarizer),
              EventHub.synchronous(),
              ObservationRegistry.create());

      engine.run(ID, Event.UserSaid.of("first question"));
      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("second question"));

      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      // First turn's usage (150_000, 10, 0) + the strategy's spend (500, 20, 0); the second,
      // uncompacted turn contributes zero.
      assertThat(completed.state().usage()).isEqualTo(new Usage(150_500, 30, 0));
    }
  }

  @Nested
  class A_failing_compaction {

    @Test
    void a_failing_strategy_emits_the_hub_event_and_the_turn_proceeds() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer =
          (head, policy) -> {
            throw new IllegalStateException("summarizer exploded");
          };
      EventHub hub = EventHub.synchronous();
      List<CompactionFailed> failures = new ArrayList<>();
      hub.subscribe(CompactionFailed.class, failures::add);
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), hub, ObservationRegistry.create());

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

    @Test
    void a_pair_breaking_strategy_is_a_failure_not_a_corruption() {
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      ToolCall orphan = new ToolCall("orphan", "read_file", JsonNodeFactory.instance.objectNode());
      List<Message> broken = List.of(Message.assistant(List.of(new ToolUseBlock(orphan))));
      CompactionStrategy strategy =
          triggeringAt(100_000, new CompactionStrategy.Result(broken, Usage.zero()));
      EventHub hub = EventHub.synchronous();
      List<CompactionFailed> failures = new ArrayList<>();
      hub.subscribe(CompactionFailed.class, failures::add);
      InProcessEngine engine =
          engineWith(provider, reducerUsing(strategy), hub, ObservationRegistry.create());

      engine.run(ID, Event.UserSaid.of("first question"));
      RunOutcome outcome = engine.run(ID, Event.UserSaid.of("second question"));

      assertThat(failures).hasSize(1);
      RunOutcome.Completed completed = (RunOutcome.Completed) outcome;
      assertThat(completed.state().generation()).isZero();
      // The strategy's broken result never reached the reducer, so the working set that was
      // already there survives untouched, and the turn still completes normally.
      assertThat(completed.state().messages().getLast())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Normal answer."))));
    }
  }

  @Nested
  class Observations {

    @Test
    void compaction_produces_its_own_observation() {
      TestObservationRegistry observations = TestObservationRegistry.create();
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer = (head, policy) -> new Summarizer.Summary("Summary.", Usage.zero());
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), EventHub.synchronous(), observations);

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
      EngineFixtures.FakeProvider provider = twoTurnProvider();
      Summarizer summarizer =
          (head, policy) -> {
            throw new IllegalStateException("summarizer exploded");
          };
      InProcessEngine engine =
          engineWith(provider, reducerUsing(summarizer), EventHub.synchronous(), observations);

      engine.run(ID, Event.UserSaid.of("first question"));
      engine.run(ID, Event.UserSaid.of("second question"));

      assertThat(observations).hasObservationWithNameEqualTo("nessy.compaction").that().hasError();
    }
  }
}
