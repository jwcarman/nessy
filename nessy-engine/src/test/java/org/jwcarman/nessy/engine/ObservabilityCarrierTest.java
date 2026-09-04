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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
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
}
