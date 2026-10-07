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

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnDecision;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.turn.AgentTurn;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A queued turn that a reply ends. A reply is folded on the replier's own thread, under whatever
 * observation the replier has open, so that is where the trajectory tags land: the docs say the
 * replier's current observation, not an {@code nessy.effect} span, when a reply ends the turn. The
 * row is written either way.
 */
@Tag("container")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class QueuedReplyEndedTurnSpanTest {

  private static final AgentType TYPE = new AgentType("reply-ended");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "start_job", "{\"what\":\"reindex\"}")));

  /** Stops the turn at the first moment it would go round again: when the reply lands. */
  private static final TurnPolicy STOP_AFTER_ONE_CALL =
      (stats, now) ->
          stats.modelCalls() >= 1
              ? new TurnDecision.FailTurn("enough")
              : new TurnDecision.Continue();

  record Job(String what) {}

  private final ConcurrentLinkedQueue<ToolCallRequest<?>> handed = new ConcurrentLinkedQueue<>();

  private final Tool<Job> slowJob =
      new Tool<>() {
        @Override
        public Class<Job> inputType() {
          return Job.class;
        }

        @Override
        public ToolName name() {
          return new ToolName("start_job");
        }

        @Override
        public String description() {
          return "starts a long job and reports back when it finishes";
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<Job> request) {
          handed.add(request);
          return Awaited.deferred();
        }
      };

  private EngineFixture engine;

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void a_turn_a_reply_ends_writes_its_row_and_tags_the_repliers_observation_not_an_effect_span() {
    List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
    ObservationRegistry observations = ObservationRegistry.create();
    observations
        .observationConfig()
        .observationHandler(
            new ObservationHandler<Observation.Context>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }
            });
    engine = new EngineFixture(MODEL, NarrationListener.none(), observations);
    QueuedHarness<String> harness =
        engine
            .harnesses()
            .create(
                TYPE,
                String.class,
                c ->
                    c.systemPrompt("You are terse.")
                        .turnPolicy(STOP_AFTER_ONE_CALL)
                        .tool(slowJob, t -> t.timeout(Duration.ofMinutes(30)))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));
    AgentId agent = AgentId.random();
    harness.tell(agent, "kick off the reindex");
    await().atMost(PATIENCE).untilAsserted(() -> assertThat(handed).hasSize(1));
    ToolCallRequest<?> call = handed.peek();

    Observation.createNotStarted("app.request", observations)
        .observe(
            () ->
                engine
                    .replies()
                    .complete(
                        call.agentType(),
                        call.agentId(),
                        call.idempotencyKey(),
                        ToolResult.ok(new Block.Text("reindexed 91"))));

    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(engine.backend().turns().of(TYPE, agent)).hasSize(1));
    AgentTurn row = engine.backend().turns().of(TYPE, agent).getFirst();
    assertThat(row.outcome()).isEqualTo(TurnOutcome.STOPPED);

    assertThat(row.trajectory().hash()).hasSize(64);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(stopped)
                    .filteredOn(c -> c.getName().equals("app.request"))
                    .singleElement()
                    .satisfies(
                        c -> {
                          KeyValue hash = c.getHighCardinalityKeyValue("nessy.trajectory.hash");
                          assertThat(hash).isNotNull();
                          assertThat(hash.getValue()).isEqualTo(row.trajectory().hash());
                        }));
    List<Observation.Context> effects =
        stopped.stream().filter(c -> c.getName().equals("nessy.effect")).toList();
    assertThat(effects)
        .isNotEmpty()
        .allSatisfy(
            c -> assertThat(c.getHighCardinalityKeyValue("nessy.trajectory.hash")).isNull());
  }
}
