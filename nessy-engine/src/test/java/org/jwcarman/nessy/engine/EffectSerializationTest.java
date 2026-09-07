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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.agent.Effect;

/**
 * An effect is a row now, so it has to come back the same effect it went in as.
 *
 * <p>{@code @JsonSubTypes} compiles whether or not it can actually deserialize -- a typo'd class
 * reference or a missing no-args-friendly shape is a wiring mistake Jackson only reports at
 * runtime, on the first row that hits it. This is that check, run once per arm rather than left for
 * Task 6 to discover against a live effect row.
 */
@DisplayName("An effect round-trips through the wire")
class EffectSerializationTest {

  /** One arm of the grammar, paired with the wire name a committed row depends on. */
  record Case(Effect effect, String wireName) {}

  static Stream<Case> effects() {
    return Stream.of(
        new Case(new Effect.TakeWork(), "take-work"),
        new Case(new Effect.CallModel(), "call-model"),
        new Case(new Effect.AskApprover(CallId.of("c1"), "look_up"), "ask-approver"),
        new Case(new Effect.RunTool(CallId.of("c1"), "look_up"), "run-tool"),
        new Case(new Effect.Remember.Input(), "remember-input"),
        new Case(new Effect.Remember.Answer(), "remember-answer"),
        new Case(new Effect.Remember.Exchange(), "remember-exchange"),
        new Case(new Effect.Release(), "release"),
        new Case(new Effect.Forget(), "forget"),
        new Case(new Effect.Narrate.TurnStarted(TurnId.of("t1")), "narrate-turn-started"),
        new Case(new Effect.Narrate.Answered(), "narrate-answered"),
        new Case(
            new Effect.Narrate.TurnEnded(new TurnResult.Completed(), Usage.unreported()),
            "narrate-turn-ended"),
        new Case(
            new Effect.Narrate.ApprovalDecided(CallId.of("c1"), new ApprovalResult.Approved()),
            "narrate-approval-decided"),
        new Case(
            new Effect.Narrate.ToolCallCompleted(CallId.of("c1")), "narrate-tool-call-completed"));
  }

  @ParameterizedTest
  @MethodSource("effects")
  @DisplayName("comes back equal to itself, as its own concrete type")
  void round_trips_through_the_configured_mapper(Case testCase) throws Exception {
    String written = EngineMapper.INSTANCE.writeValueAsString(testCase.effect());

    Effect read = EngineMapper.INSTANCE.readValue(written, Effect.class);

    assertThat(read).isEqualTo(testCase.effect());
  }

  @ParameterizedTest
  @MethodSource("effects")
  @DisplayName("names its concrete type on the wire, not the sealed interface")
  void carries_a_discriminator(Case testCase) throws Exception {
    String written = EngineMapper.INSTANCE.writeValueAsString(testCase.effect());

    JsonNode node = EngineMapper.INSTANCE.readTree(written);

    assertThat(node.has("do")).isTrue();
    assertThat(node.get("do").asText()).isEqualTo(testCase.wireName());
  }

  @Test
  @DisplayName("every arm of the sealed grammar is covered by the fixture above")
  void the_fixture_is_not_missing_an_arm() {
    List<Class<?>> fixtureClasses =
        effects().<Class<?>>map(testCase -> testCase.effect().getClass()).toList();
    Set<Class<?>> declaredClasses = leafClasses(Effect.class);

    assertThat(fixtureClasses).isNotEmpty();
    assertThat(fixtureClasses).doesNotHaveDuplicates();
    assertThat(new HashSet<>(fixtureClasses)).isEqualTo(declaredClasses);
  }

  /**
   * Every concrete class a sealed hierarchy can produce, recursing into nested sealed interfaces
   * ({@code Remember}, {@code Narrate}) rather than stopping at their un-instantiable selves.
   *
   * <p>Derived from the grammar itself rather than hand-counted, so a new arm added to {@link
   * Effect} without a matching fixture entry fails this test instead of silently under-covering
   * {@code @JsonSubTypes}.
   */
  private static Set<Class<?>> leafClasses(Class<?> sealedType) {
    Set<Class<?>> leaves = new HashSet<>();
    for (Class<?> permitted : sealedType.getPermittedSubclasses()) {
      if (permitted.isSealed()) {
        leaves.addAll(leafClasses(permitted));
      } else {
        leaves.add(permitted);
      }
    }
    return leaves;
  }
}
