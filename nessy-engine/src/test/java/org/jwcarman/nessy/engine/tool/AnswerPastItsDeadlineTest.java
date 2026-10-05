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
package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * An answer that arrives after its approval's deadline, before the dispatcher has folded the
 * expiry. The spec's outcome table says such an answer is ignored; the waiting list already leaves
 * the approval out at that moment.
 */
@Tag("container")
class AnswerPastItsDeadlineTest {

  record Query(String q) {}

  private final InferenceProvider model =
      (request, _) -> {
        boolean answered =
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty());
        return answered
            ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
            : new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
      };

  private final ConcurrentLinkedQueue<ApprovalRequest> asks = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> ran = new ConcurrentLinkedQueue<>();
  private EngineFixture engine;

  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        ran.add(request.input().q());
        return Awaited.ready(ToolResult.ok(new Block.Text("found")));
      }
    };
  }

  @Test
  void an_approval_answered_after_its_deadline_is_ignored_even_before_the_expiry_is_folded() {
    AgentType type = new AgentType("past-deadline-" + UUID.randomUUID());
    Approver deferring =
        request -> {
          asks.add(request);
          return Awaited.deferred();
        };
    var harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(),
                            t ->
                                t.action(query -> "look up " + query.q())
                                    .approver(deferring, a -> a.timeout(Duration.ofSeconds(2))))
                        .inference(in -> in.model("a-model"))
                        // A poll long enough that nothing folds the expiry during the test.
                        .effects(e -> e.pollInterval(Duration.ofMinutes(10))));
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.tell(agent, "what lake?");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                engine.story(type, agent).stream()
                    .anyMatch(AgentEvent.ApprovalDeferred.class::isInstance));
    ApprovalRequest request = asks.peek();
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> Instant.now().isAfter(request.deadline().plusMillis(200)));
    assertThat(engine.work().waitingApprovals(type))
        .as("the waiting list already says the approval is not waiting")
        .isEmpty();

    ReplyOutcome outcome =
        engine
            .replies()
            .approve(
                request.agentType(),
                request.agentId(),
                request.idempotencyKey(),
                ApprovalResult.approved());

    assertThat(outcome)
        .as("an answer past the deadline is ignored (spec 6c)")
        .isEqualTo(new ReplyOutcome.Ignored());
    assertThat(
            engine.story(type, agent).stream()
                .filter(AgentEvent.ToolApproved.class::isInstance)
                .count())
        .as("an expired request authorises nothing")
        .isZero();
  }
}
