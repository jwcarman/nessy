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
package org.jwcarman.nessy.engine;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * The one property the brief said would break silently if it broke at all: the trace carrier riding
 * {@link EffectWorker#perform} out through {@link Dispatcher#dispatch}'s {@code observability}
 * argument.
 *
 * <p>Neither {@code DispatcherSeamTest} (a bare lambda, no {@code EffectWorker} involved) nor
 * {@code EffectWorkerEdgeCasesTest} (only ever calls the 4-arg overload, whose carrier is always
 * {@code Map.of()}) touches this. A {@link Dispatcher} that ignores {@code observability} entirely
 * would pass every other test in this module — which is exactly what both of the module's own
 * {@code Dispatcher} adapters, {@code PekkoHarnessFactory.dispatcherFor} and {@code Engines}'
 * default, do. This test drives {@link EffectWorker} directly against a CAPTURING {@link
 * Dispatcher} instead, so the serialized carrier is a value someone actually asserted on rather
 * than a parameter nothing reads.
 *
 * <p><b>Two boundaries, not one.</b> The first two cases drive {@code AskApprover} for a call whose
 * asking message was never claimed, which answers SYNCHRONOUSLY through {@code completed()} --
 * proof that {@code tell()} and {@code observabilityOf} serialize correctly, but not proof that
 * {@code carried} survives the actual asynchronous hop. {@code run()} -- used by {@code takeWork},
 * {@code callModel}, {@code runTool}, and {@code askApprover}'s binding-present branch -- is where
 * {@code CompletableFuture.supplyAsync(work, blocking).whenComplete(...)} crosses onto another
 * thread, and it was measured (by a reviewer, not assumed) that swapping {@code run()}'s own {@code
 * tell(..., carried)} for {@code tell(..., Map.of())} left every test in the tree green. The third
 * case below closes exactly that gap, through {@code CallModel}.
 */
@DisplayName("The trace carrier riding an effect's outcome out")
class ObservabilityCarrierTest {

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private record Captured(
      AgentId agentId, Input input, EffectId completing, String observability) {}

  /**
   * Drives {@code AskApprover} for a call whose asking message was never claimed -- {@code callOf}
   * returns null, so {@code completed()} fires {@code tell()} SYNCHRONOUSLY, with no async hop to
   * race. That is what makes this test able to assert on the dispatch without an {@code await()}.
   */
  @Test
  @DisplayName("a non-empty carrier reaches the dispatcher as the serialized JSON object")
  void a_non_empty_carrier_is_serialized_onto_the_dispatch() throws JsonProcessingException {
    List<Captured> seen = new ArrayList<>();
    Dispatcher capturing =
        (agentId, input, completing, observability) ->
            seen.add(new Captured(agentId, input, completing, observability));
    AgentType type = AgentType.of("carrier-present");
    Engines.Parts parts =
        Engines.of(testKit.system(), type, Engines.stalled(), List.of(), Runnable::run, capturing);
    AgentId agentId = AgentId.of("house-carrier-present");
    AgentState state = AgentState.idle().taking(TurnId.of("turn-carrier"), "obs-claim");
    CallId callId = CallId.of("missing-call");
    Map<String, String> carried = Map.of("traceparent", "00-4bf92f-1-01");

    parts
        .effectWorker()
        .perform(
            agentId, state, new Effect.AskApprover(callId, "some_tool"), EffectId.next(), carried);

    assertThat(seen).hasSize(1);
    assertThat(seen.getFirst().observability())
        .isEqualTo(EngineMapper.INSTANCE.writeValueAsString(carried));
  }

  /**
   * The other half of the same property: an EMPTY carrier is legitimate work with no ambient trace,
   * not a serialization failure, so it must reach the dispatcher as {@code null} rather than as
   * {@code "{}"} or any other stand-in.
   */
  @Test
  @DisplayName("an empty carrier reaches the dispatcher as null, not as an empty object")
  void an_empty_carrier_reaches_the_dispatcher_as_null() {
    List<Captured> seen = new ArrayList<>();
    Dispatcher capturing =
        (agentId, input, completing, observability) ->
            seen.add(new Captured(agentId, input, completing, observability));
    AgentType type = AgentType.of("carrier-absent");
    Engines.Parts parts =
        Engines.of(testKit.system(), type, Engines.stalled(), List.of(), Runnable::run, capturing);
    AgentId agentId = AgentId.of("house-carrier-absent");
    AgentState state = AgentState.idle().taking(TurnId.of("turn-no-carrier"), "obs-claim");
    CallId callId = CallId.of("missing-call");

    parts
        .effectWorker()
        .perform(agentId, state, new Effect.AskApprover(callId, "some_tool"), EffectId.next());

    assertThat(seen).hasSize(1);
    assertThat(seen.getFirst().observability()).isNull();
  }

  /**
   * The boundary the first two cases cannot reach: {@code CallModel} always goes through {@code
   * run()}, so its dispatch is the answer to {@code CompletableFuture.supplyAsync(work,
   * blocking).whenComplete(...)} rather than a call made straight out of {@code perform()}.
   *
   * <p><b>How this synchronizes.</b> {@code blocking} here is a REAL virtual-thread-per-task
   * executor, not a synchronous stand-in, so the dispatch genuinely happens on another thread and
   * this test cannot read {@code seen} the instant {@code perform()} returns -- {@code perform()}
   * returns as soon as the work is SUBMITTED, before the model has even been asked. {@code
   * await().untilAsserted} polls until the capturing dispatcher has actually been called, which is
   * the only honest way to observe an asynchronous answer; reading {@code seen} immediately would
   * either race (usually empty, flaky rather than reliably red) or -- if it happened to win the
   * race by chance -- silently stop testing the asynchronous path at all.
   */
  @Test
  @DisplayName("a carrier crossing the async hop in run() reaches the dispatcher intact")
  void a_carrier_crossing_run_reaches_the_dispatcher() throws JsonProcessingException {
    List<Captured> seen = new ArrayList<>();
    Dispatcher capturing =
        (agentId, input, completing, observability) ->
            seen.add(new Captured(agentId, input, completing, observability));
    AgentType type = AgentType.of("carrier-across-run");
    Executor realBlocking = Executors.newVirtualThreadPerTaskExecutor();
    Engines.Parts parts =
        Engines.of(
            testKit.system(),
            type,
            Engines.saying(
                List.of(
                    new ModelResult.Refused(
                        "harassment", "will not help with that", Usage.unreported()))),
            List.of(),
            realBlocking,
            capturing);
    AgentId agentId = AgentId.of("house-carrier-across-run");
    AgentState state = AgentState.idle().taking(TurnId.of("turn-across-run"), "obs-claim");
    Map<String, String> carried = Map.of("traceparent", "00-1234ef-2-01");

    parts.effectWorker().perform(agentId, state, new Effect.CallModel(), EffectId.next(), carried);

    await()
        .atMost(15, SECONDS)
        .untilAsserted(
            () -> {
              assertThat(seen).hasSize(1);
              assertThat(seen.getFirst().observability())
                  .isEqualTo(EngineMapper.INSTANCE.writeValueAsString(carried));
            });
  }
}
