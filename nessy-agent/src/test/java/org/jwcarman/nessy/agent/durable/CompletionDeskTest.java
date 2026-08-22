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
package org.jwcarman.nessy.agent.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/** The result door: completes {@code tool:} computations with a {@code ToolResult} (spec §5). */
class CompletionDeskTest {

  private final SubstrateComputations backend =
      new SubstrateComputations(new InMemorySubstrate(), TestMappers.plainlyPinned());
  private int nudges;
  private final CompletionDesk desk = new CompletionDesk(backend, () -> nudges++);

  private static final ComputationId COMPUTATION = ComputationId.of("tool:t:a:c1");
  private static final ToolInvocationId INVOCATION = new ToolInvocationId("response-1", "c1");
  private static final Continuation RETURN_ADDRESS = new Continuation("SCOPE_RESUME", "{}");

  private void park() {
    backend.create(COMPUTATION, INVOCATION, RETURN_ADDRESS, Optional.empty());
  }

  @Test
  void completingTransfersOwnershipAndNudgesTheWorker() {
    park();

    desk.complete(COMPUTATION, ToolResult.ok("approved"));

    assertThat(backend.find(COMPUTATION)).isEmpty();
    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void failingTransfersOwnershipAndNudgesTheWorker() {
    park();

    desk.fail(COMPUTATION, "too risky");

    assertThat(backend.find(COMPUTATION)).isEmpty();
    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void completingAnUnknownIdIsBenignAndStillNudges() {
    var ghost = ComputationId.of("ghost");

    desk.complete(ghost, ToolResult.ok("x"));

    assertThat(backend.find(ghost)).isEmpty();
    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void aSecondCompletionIsBenignNotAThrow() {
    park();
    desk.complete(COMPUTATION, ToolResult.ok("approved"));

    assertThatCode(() -> desk.complete(COMPUTATION, ToolResult.ok("again")))
        .doesNotThrowAnyException();
  }
}
