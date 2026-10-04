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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ToolEventKeyTest {

  private static final Seq SEQ = new Seq(4);
  private static final TurnId TURN = new TurnId(1);
  private static final CallId CALL = new CallId("c1");

  @Test
  void an_approval_refuses_a_null_key() {
    assertThatThrownBy(() -> new AgentEvent.ToolApproved(SEQ, TURN, CALL, Optional.empty(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("idempotencyKey must not be null");
  }

  @Test
  void a_denial_refuses_a_null_key() {
    assertThatThrownBy(
            () -> new AgentEvent.ToolDenied(SEQ, TURN, CALL, "no", Optional.empty(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("idempotencyKey must not be null");
  }

  @Test
  void a_result_refuses_a_null_key() {
    PayloadRef result = PayloadRef.of("a3d9f0b1");

    assertThatThrownBy(() -> new AgentEvent.ToolSucceeded(SEQ, TURN, CALL, result, "ok", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("idempotencyKey must not be null");
  }

  @Test
  void a_failure_refuses_a_null_key() {
    assertThatThrownBy(
            () -> new AgentEvent.ToolFailed(SEQ, TURN, CALL, CallFailure.FAILED, "boom", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("idempotencyKey must not be null");
  }

  @Test
  void a_failure_refuses_a_null_kind() {
    IdempotencyKey key = IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

    assertThatThrownBy(() -> new AgentEvent.ToolFailed(SEQ, TURN, CALL, null, "boom", key))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("kind must not be null");
  }
}
