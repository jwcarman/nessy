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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.continuum.api.BatchSize;
import org.jwcarman.continuum.api.TypedOutcome;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestToolClients;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/** The result door: completes tool computations with a {@code ToolResult} (spec §5). */
class CompletionDeskTest {

  private final ContinuumClient<ToolResult, Routing> client =
      TestToolClients.client("tool", TestMappers.plainlyPinned());
  private int nudges;
  private final CompletionDesk desk = new CompletionDesk(client, () -> nudges++);

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
  void completingNudgesTheWorker() {
    ComputationId id = park();

    desk.complete(id, ToolResult.ok("approved"));

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void failingNudgesTheWorkerAndDeliversAFailureOutcome() {
    ComputationId id = park();

    desk.fail(id, "too risky");

    assertThat(nudges).isEqualTo(1);
    List<TypedOutcome<ToolResult>> delivered = new ArrayList<>();
    client.deliverResults(BatchSize.of(10), delivery -> delivered.add(delivery.outcome()));
    assertThat(delivered).isNotEmpty();
    assertThat(delivered)
        .singleElement()
        .isInstanceOfSatisfying(
            TypedOutcome.Failure.class,
            failure -> assertThat(failure.message()).isEqualTo("too risky"));
  }

  @Test
  void completingAGenuinelyAbsentIdIsBenignAndStillNudges() {
    var ghost = ComputationId.of(UUID.randomUUID().toString());

    desk.complete(ghost, ToolResult.ok("x"));

    assertThat(nudges).isEqualTo(1);
  }

  @Test
  void aSecondCompletionIsBenignNotAThrow() {
    ComputationId id = park();
    desk.complete(id, ToolResult.ok("approved"));

    assertThatCode(() -> desk.complete(id, ToolResult.ok("again"))).doesNotThrowAnyException();
  }
}
