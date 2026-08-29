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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.engine.BatchThrowsSubstrate;
import org.jwcarman.nessy.engine.Claims;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.ToolCallActor;
import org.jwcarman.nessy.engine.ToolCallRecord;
import org.jwcarman.nessy.engine.ToolWorker;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * {@link ToolWorker} in isolation, below the whole-round harness {@link RoundFlowTest} uses --
 * these tests exercise the one branch a full round can never force on demand: {@code remember}
 * itself throwing.
 */
@DisplayName("A tool worker")
class ToolWorkerTest {

  private final ActorTestKit testKit = ActorTestKit.create();
  private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void stop() {
    testKit.shutdownTestKit();
    blocking.shutdown();
  }

  @Test
  @DisplayName(
      "when remember throws, the call is never told it ran -- nothing was recorded to settle it against")
  void a_remember_that_throws_never_settles_the_call() {
    String agentId = "agent-" + UUID.randomUUID();
    String turnId = "turn-" + UUID.randomUUID();
    Substrate substrate = new BatchThrowsSubstrate(new InMemorySubstrate(Clock.systemUTC()));
    Claims claims = new Claims(substrate);
    Memories memories = new Memories(substrate, 8000);
    String claimId = claims.put(agentId, turnId, "{}".getBytes(StandardCharsets.UTF_8));
    ToolCallRecord call =
        ToolCallRecord.asked(
            "call-disk-1",
            "disk_usage",
            claimId,
            WatchmanTools.action("disk_usage", "{}"),
            Instant.now());

    ActorRef<ToolWorker.RunTool> worker =
        testKit.spawn(
            ToolWorker.create(
                WatchmanTools.boundTo(new FakeRunner()),
                memories,
                blocking,
                MicrometerTracing.noop(),
                claims));
    TestProbe<ToolCallActor.Command> replyTo = testKit.createTestProbe();

    worker.tell(new ToolWorker.RunTool(agentId, turnId, call, claimId, replyTo.getRef(), Map.of()));

    // The bug this guards against: telling Ran() here even though remember() blew up would
    // settle a call whose exchange was never recorded -- the assistant turn naming it then hangs
    // withheld from recall() forever, exactly like the original bug, just reached through a
    // different door (a substrate failure rather than a thrown tool).
    replyTo.expectNoMessage(Duration.ofSeconds(1));
    assertThat(memories.everything(agentId).messages()).isEmpty();
  }
}
