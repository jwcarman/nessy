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
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * {@link ToolCallActor} in isolation, below the whole-round harness {@link RoundFlowTest} uses --
 * this test drives the denial path ({@code settleAsDenied}) against a substrate whose {@code
 * remember} always fails, the branch a full round can never force on demand.
 */
@DisplayName("A tool call actor")
class ToolCallActorTest {

  private final ActorTestKit testKit = ActorTestKit.create();
  private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void stop() {
    testKit.shutdownTestKit();
    blocking.shutdown();
  }

  @Test
  @DisplayName(
      "when remember throws, a denied call is never told it settled -- nothing was recorded to"
          + " settle it against")
  void a_remember_that_throws_never_settles_a_denied_call() {
    String agentId = "agent-" + UUID.randomUUID();
    String turnId = "turn-" + UUID.randomUUID();
    Substrate substrate = new BatchThrowsSubstrate(new InMemorySubstrate(Clock.systemUTC()));
    Claims claims = new Claims(substrate);
    Memories memories = new Memories(substrate, 8000);
    String claimId = claims.put(agentId, turnId, "{}".getBytes(StandardCharsets.UTF_8));
    ToolCallRecord call =
        ToolCallRecord.asked(
                "call-prune-1",
                "prune_images",
                claimId,
                WatchmanTools.action("prune_images", "{}"),
                Instant.now())
            .decidedBy(
                new ToolCallRecord.Decision(false, "james", "not on a Friday", Instant.now()));

    TestProbe<AgentActor.NessyMessage> agent = testKit.createTestProbe();
    TestProbe<ToolWorker.RunTool> tools = testKit.createTestProbe();

    testKit.spawn(
        ToolCallActor.create(
            agentId,
            turnId,
            call,
            agent.getRef(),
            tools.getRef(),
            Duration.ofMinutes(10),
            Map.of(),
            Clock.systemUTC(),
            memories,
            blocking,
            claims,
            WatchmanTools.boundTo(new FakeRunner())));

    // The bug this guards against: telling ToolCallSettled here even though remember() blew up
    // would settle a denied call whose exchange was never recorded -- the assistant turn naming
    // it then hangs withheld from recall() forever, the same bug ToolWorker had, reached through
    // the denial door instead of the run door.
    agent.expectNoMessage(Duration.ofSeconds(1));
    tools.expectNoMessage(Duration.ofMillis(100));
    assertThat(memories.everything(agentId).messages()).isEmpty();
  }

  @Test
  @DisplayName(
      "when the claim cannot be resolved, a denied call still settles -- the failure is recorded"
          + " rather than the round stalling in WorkingTools forever")
  void a_denied_call_whose_claim_cannot_be_resolved_still_settles() {
    String agentId = "agent-" + UUID.randomUUID();
    String turnId = "turn-" + UUID.randomUUID();
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Claims claims = new Claims(substrate);
    Memories memories = new Memories(substrate, 8000);
    // Never written by claims.put -- exactly what ToolWorker's orElseThrow guards against on the
    // run path; settleAsDenied must guard against it on the denial path the same way.
    String missingClaimId = Identifiers.next();
    ToolCallRecord call =
        ToolCallRecord.asked(
                "call-prune-2",
                "prune_images",
                missingClaimId,
                WatchmanTools.action("prune_images", "{}"),
                Instant.now())
            .decidedBy(
                new ToolCallRecord.Decision(false, "james", "not on a Friday", Instant.now()));

    TestProbe<AgentActor.NessyMessage> agent = testKit.createTestProbe();
    TestProbe<ToolWorker.RunTool> tools = testKit.createTestProbe();

    testKit.spawn(
        ToolCallActor.create(
            agentId,
            turnId,
            call,
            agent.getRef(),
            tools.getRef(),
            Duration.ofMinutes(10),
            Map.of(),
            Clock.systemUTC(),
            memories,
            blocking,
            claims,
            WatchmanTools.boundTo(new FakeRunner())));

    // Unlike a throwing remember (above), an unresolved claim IS caught -- the round must not
    // stall on the watchman's most common path (a denial). The agent is told the call settled.
    agent.expectMessage(new AgentActor.ToolCallSettled(call.id(), Map.of()));
    tools.expectNoMessage(Duration.ofMillis(100));
  }

  @Test
  @DisplayName(
      "when the blocking executor rejects the submission, the actor is not killed -- the"
          + " rejection is logged, not left to propagate out of the message handler")
  void a_denied_call_survives_a_rejected_executor_submission() {
    String agentId = "agent-" + UUID.randomUUID();
    String turnId = "turn-" + UUID.randomUUID();
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Claims claims = new Claims(substrate);
    Memories memories = new Memories(substrate, 8000);
    String claimId = claims.put(agentId, turnId, "{}".getBytes(StandardCharsets.UTF_8));
    ToolCallRecord call =
        ToolCallRecord.asked(
                "call-prune-3",
                "prune_images",
                claimId,
                WatchmanTools.action("prune_images", "{}"),
                Instant.now())
            .decidedBy(
                new ToolCallRecord.Decision(false, "james", "not on a Friday", Instant.now()));

    TestProbe<AgentActor.NessyMessage> agent = testKit.createTestProbe();
    TestProbe<ToolWorker.RunTool> tools = testKit.createTestProbe();
    Executor rejecting =
        runnable -> {
          throw new RejectedExecutionException("executor mid-shutdown");
        };

    // The bug this guards against: ToolWorker#create wraps its submission in try/catch, but
    // settleAsDenied used CompletableFuture.runAsync bare -- a rejection propagated synchronously
    // out of Behaviors.setup and killed this actor (and, unhandled, would have killed AgentActor
    // too, since this is spawned as its child).
    assertThatCode(
            () ->
                testKit.spawn(
                    ToolCallActor.create(
                        agentId,
                        turnId,
                        call,
                        agent.getRef(),
                        tools.getRef(),
                        Duration.ofMinutes(10),
                        Map.of(),
                        Clock.systemUTC(),
                        memories,
                        rejecting,
                        claims,
                        WatchmanTools.boundTo(new FakeRunner()))))
        .doesNotThrowAnyException();

    agent.expectNoMessage(Duration.ofSeconds(1));
    tools.expectNoMessage(Duration.ofMillis(100));
    assertThat(memories.everything(agentId).messages()).isEmpty();
  }
}
