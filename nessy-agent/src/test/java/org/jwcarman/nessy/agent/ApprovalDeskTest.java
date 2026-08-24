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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.support.TestApprovalClients;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;

/** The approve/deny door: completes approval computations with a {@code Decision} (spec §3, §7). */
class ApprovalDeskTest {

  private final ContinuumClient<Decision, Routing> client =
      TestApprovalClients.client("approval", TestMappers.plainlyPinned());
  private int nudges;
  private final ApprovalDesk desk = new ApprovalDesk(client, () -> nudges++);

  private static Routing routing() {
    return new Routing(
        "t",
        "a",
        "response-1",
        new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode()));
  }

  private ComputationId park() {
    var created = client.create(routing());
    return ComputationId.of(created.id().value().toString());
  }

  @Test
  void approvingNudgesTheWorker() {
    ComputationId id = park();

    desk.approve(id);

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void denyingNudgesTheWorker() {
    ComputationId id = park();

    desk.deny(id, "not on a Friday");

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void denyingWithANullReasonIsRejected() {
    ComputationId id = park();

    assertThatThrownBy(() -> desk.deny(id, null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void denyingWithABlankReasonIsRejected() {
    ComputationId id = park();

    assertThatThrownBy(() -> desk.deny(id, "   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decidingAGenuinelyAbsentIdIsBenignAndStillNudges() {
    var ghost = ComputationId.of(UUID.randomUUID().toString());

    desk.approve(ghost);

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void aSecondDecisionOnAnAlreadyResolvedIdIsBenignNotAThrow() {
    ComputationId id = park();
    desk.approve(id);

    assertThatCode(() -> desk.deny(id, "too late")).doesNotThrowAnyException();
  }
}
