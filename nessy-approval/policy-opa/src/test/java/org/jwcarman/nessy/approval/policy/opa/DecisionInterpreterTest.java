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
package org.jwcarman.nessy.approval.policy.opa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.approval.policy.Verdict;

/**
 * Reading an answer, for both conventions this ships with.
 *
 * <p>No container: an interpreter is a pure function over a document, and the documents here are
 * the ones a real OPA produced — captured, not invented.
 */
@DisplayName("Reading what a policy answered")
class DecisionInterpreterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (Exception notJson) {
      throw new IllegalArgumentException(notJson);
    }
  }

  @Nested
  @DisplayName("Nessy's own effect convention")
  class EffectStyle {

    private final DecisionInterpreter interpreter = DecisionInterpreter.effectStyle();

    @Test
    void allow_approves() {
      assertThat(interpreter.interpret(json("{\"effect\":\"allow\"}")))
          .isEqualTo(Verdict.approve());
    }

    @Test
    void deny_keeps_the_policys_reason() {
      assertThat(interpreter.interpret(json("{\"effect\":\"deny\",\"reason\":\"not here\"}")))
          .isEqualTo(Verdict.deny("not here"));
    }

    @Test
    void a_denial_with_no_reason_still_says_something() {
      assertThat(interpreter.interpret(json("{\"effect\":\"deny\"}")))
          .isEqualTo(Verdict.deny("denied by policy"));
    }

    @Test
    @DisplayName("everything the policy attached rides along, namespaced for the delegate")
    void delegate_carries_the_rest_as_facts() {
      Verdict verdict =
          interpreter.interpret(
              json(
                  "{\"effect\":\"delegate\",\"to\":\"humans\",\"term\":\"PT72H\",\"ticket\":\"OPS-1\"}"));

      Verdict.Delegate delegate = (Verdict.Delegate) verdict;
      assertThat(delegate.to()).isEqualTo("humans");
      assertThat(delegate.facts().path("policy.term").asText()).isEqualTo("PT72H");
      assertThat(delegate.facts().path("policy.ticket").asText()).isEqualTo("OPS-1");
      assertThat(delegate.facts().has("policy.to")).as("routing is not a fact").isFalse();
      assertThat(delegate.facts().has("policy.effect")).isFalse();
    }

    @Test
    @DisplayName("a typo like \"alow\" must never read as a yes")
    void an_unknown_effect_throws() {
      JsonNode decision = json("{\"effect\":\"alow\"}");

      assertThatThrownBy(() -> interpreter.interpret(decision))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("alow");
    }

    @Test
    void delegating_nowhere_throws() {
      JsonNode decision = json("{\"effect\":\"delegate\"}");

      assertThatThrownBy(() -> interpreter.interpret(decision))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must name a");
    }

    @Test
    @DisplayName("a bare boolean is not a decision this convention understands")
    void a_document_of_the_wrong_shape_throws() {
      JsonNode decision = json("true");

      assertThatThrownBy(() -> interpreter.interpret(decision))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("AuthZEN, which standardizes only two of the three")
  class Authzen {

    private final DecisionInterpreter interpreter = DecisionInterpreter.authzen();

    @Test
    void a_true_decision_approves() {
      assertThat(interpreter.interpret(json("{\"decision\":true}"))).isEqualTo(Verdict.approve());
    }

    @Test
    void a_false_decision_denies_with_the_reason_meant_for_a_person() {
      assertThat(
              interpreter.interpret(
                  json("{\"decision\":false,\"context\":{\"reason_user\":\"out of hours\"}}")))
          .isEqualTo(Verdict.deny("out of hours"));
    }

    @Test
    void a_false_decision_with_no_context_still_says_something() {
      assertThat(interpreter.interpret(json("{\"decision\":false}")))
          .isEqualTo(Verdict.deny("denied by policy"));
    }

    @Test
    @DisplayName("a response with no decision at all is a broken gate, not a no")
    void a_missing_decision_throws() {
      JsonNode decision = json("{\"context\":{}}");

      assertThatThrownBy(() -> interpreter.interpret(decision))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("boolean");
    }
  }
}
