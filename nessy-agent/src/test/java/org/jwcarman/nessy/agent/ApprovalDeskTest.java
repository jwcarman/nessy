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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The approve/deny door: two decisions, one vocabulary — {@code Decision} (durable-deliveries spec
 * §5).
 */
class ApprovalDeskTest {

  private final SubstrateComputations backend =
      new SubstrateComputations(
          new InMemorySubstrate(), TestMappers.plainlyPinned(), "approval", "outbox");
  private int nudges;
  private final ApprovalDesk desk = new ApprovalDesk(backend, () -> nudges++);

  private static final ComputationId COMPUTATION = ComputationId.of("approval:t:a:c1");
  private static final ToolInvocationId INVOCATION = new ToolInvocationId("response-1", "c1");
  private static final Continuation RETURN_ADDRESS = new Continuation("SCOPE_RESUME", "{}");

  private void park() {
    backend.create(COMPUTATION, INVOCATION, RETURN_ADDRESS, Optional.empty());
  }

  @Test
  void approvingTransfersOwnershipAndNudgesTheWorker() {
    park();

    desk.approve(COMPUTATION);

    assertThat(backend.find(COMPUTATION)).isEmpty();
    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void denyingTransfersOwnershipAndNudgesTheWorker() {
    park();

    desk.deny(COMPUTATION, "no");

    assertThat(backend.find(COMPUTATION)).isEmpty();
    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void aSecondDecisionOnAnAlreadyTransferredComputationIsBenignNotAThrow() {
    park();
    desk.approve(COMPUTATION);

    assertThatCode(() -> desk.deny(COMPUTATION, "too late")).doesNotThrowAnyException();

    assertThat(nudges).isEqualTo(2); // both decisions nudge; the worker's own drain is idempotent
  }
}
