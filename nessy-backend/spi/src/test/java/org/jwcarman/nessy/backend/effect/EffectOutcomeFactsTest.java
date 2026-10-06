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
package org.jwcarman.nessy.backend.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.tool.CallId;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EffectOutcomeFactsTest {

  private static final CallId CALL = new CallId("c1");

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  @Test
  void the_shorter_constructors_hold_an_empty_object() {
    List<ObjectNode> held =
        List.of(
            new EffectOutcome.ToolApproved(CALL).facts(),
            new EffectOutcome.ToolApproved(CALL, Optional.of("ann")).facts(),
            new EffectOutcome.ToolDenied(CALL, "no").facts(),
            new EffectOutcome.ToolDenied(CALL, "no", Optional.of("ann")).facts(),
            new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "boom").facts());

    assertThat(held).hasSize(5).allMatch(facts -> facts.equals(none()));
  }

  @Test
  void null_facts_are_an_empty_object() {
    List<ObjectNode> held =
        List.of(
            new EffectOutcome.ToolApproved(CALL, Optional.empty(), null).facts(),
            new EffectOutcome.ToolDenied(CALL, "no", Optional.empty(), null).facts(),
            new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "boom", null).facts());

    assertThat(held).hasSize(3).allMatch(facts -> facts.equals(none()));
  }

  @Test
  void an_outcome_keeps_its_own_copy_of_the_facts() {
    ObjectNode facts = JsonNodeFactory.instance.objectNode().put("risk", "low");
    List<ObjectNode> held =
        List.of(
            new EffectOutcome.ToolApproved(CALL, Optional.empty(), facts).facts(),
            new EffectOutcome.ToolDenied(CALL, "no", Optional.empty(), facts).facts(),
            new EffectOutcome.ToolFailed(CALL, CallFailure.FAILED, "boom", facts).facts());

    facts.put("late", "yes");

    assertThat(held)
        .hasSize(3)
        .allMatch(kept -> kept.equals(JsonNodeFactory.instance.objectNode().put("risk", "low")));
  }
}
