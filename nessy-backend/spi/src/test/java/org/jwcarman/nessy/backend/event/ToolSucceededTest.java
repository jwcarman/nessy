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
package org.jwcarman.nessy.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.EffectOutcome;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ToolSucceededTest {

  private static final CallId CALL = new CallId("c1");
  private static final PayloadRef RESULT = PayloadRef.of("a3d9f0b1");

  @Test
  void an_event_refuses_a_null_rendered_line() {
    Seq seq = new Seq(4);
    TurnId turn = new TurnId(1);

    assertThatThrownBy(() -> new AgentEvent.ToolSucceeded(seq, turn, CALL, RESULT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("rendered must not be null");
  }

  @Test
  void an_event_accepts_an_empty_rendered_line() {
    assertThat(new AgentEvent.ToolSucceeded(new Seq(4), new TurnId(1), CALL, RESULT, "").rendered())
        .isEmpty();
  }

  @Test
  void an_outcome_refuses_a_null_rendered_line() {
    assertThatThrownBy(() -> new EffectOutcome.ToolSucceeded(CALL, RESULT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("rendered must not be null");
  }

  @Test
  void an_outcome_accepts_an_empty_rendered_line() {
    assertThat(new EffectOutcome.ToolSucceeded(CALL, RESULT, "").rendered()).isEmpty();
  }
}
