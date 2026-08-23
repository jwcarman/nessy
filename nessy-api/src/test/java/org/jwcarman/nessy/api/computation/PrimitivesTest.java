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
package org.jwcarman.nessy.api.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PrimitivesTest {

  @Test
  void aComputationIdCarriesItsValueAndRejectsBlank() {
    assertThat(ComputationId.of("tool:a:b:c").value()).isEqualTo("tool:a:b:c");
    assertThatThrownBy(() -> ComputationId.of(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outcomesRejectNullPayloads() {
    assertThatThrownBy(() -> new Outcome.Success(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.Failure(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Outcome.Cancelled(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aContinuationIsOpaqueTypedData() {
    var c = new Continuation("RESUME_SCOPE", "{\"agentId\":\"x\"}");
    assertThat(c.type()).isEqualTo("RESUME_SCOPE");
    assertThat(c.data()).contains("agentId");
    assertThatThrownBy(() -> new Continuation(null, "d")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Continuation("t", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void equalContinuationsAreOneRegistration() {
    assertThat(new Continuation("T", "d")).isEqualTo(new Continuation("T", "d"));
  }

  @Test
  void aToolInvocationIdCarriesTheResponseAndCallIdsAndRejectsBlankOrNullComponents() {
    var id = new ToolInvocationId("response-1", "call-1");
    assertThat(id.responseId()).isEqualTo("response-1");
    assertThat(id.callId()).isEqualTo("call-1");
    assertThatThrownBy(() -> new ToolInvocationId(null, "call-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId(" ", "call-1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId("response-1", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolInvocationId("response-1", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void equalToolInvocationIdsAreOneIdentity() {
    assertThat(new ToolInvocationId("response-1", "call-1"))
        .isEqualTo(new ToolInvocationId("response-1", "call-1"));
  }

  @Test
  void aPendingComputationCarriesItsInvocationReturnAddressAndOptionalDeadlineAndRejectsNulls() {
    var id = ComputationId.of("tool:a:b:c");
    var invocation = new ToolInvocationId("response-1", "call-1");
    var returnAddress = new Continuation("RESUME_SCOPE", "{}");
    var pending = new PendingComputation(id, invocation, returnAddress, Optional.empty());

    assertThat(pending.id()).isEqualTo(id);
    assertThat(pending.invocation()).isEqualTo(invocation);
    assertThat(pending.returnAddress()).isEqualTo(returnAddress);
    assertThat(pending.deadline()).isEmpty();
    assertThatThrownBy(
            () -> new PendingComputation(null, invocation, returnAddress, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PendingComputation(id, null, returnAddress, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PendingComputation(id, invocation, null, Optional.empty()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new PendingComputation(id, invocation, returnAddress, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void createResultIsGetOrCreate() {
    var id = ComputationId.of("tool:a:b:c");
    assertThat(new CreateResult(id, true).created()).isTrue();
    assertThat(new CreateResult(id, false).created()).isFalse();
    assertThatThrownBy(() -> new CreateResult(null, true)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void completionResultHasExactlyTwoArms() {
    assertThat(CompletionResult.values())
        .containsExactly(CompletionResult.TRANSFERRED, CompletionResult.ALREADY_DONE);
  }
}
