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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.JsonNode;

/**
 * The question an approval was decided on, kept with the decision, through real Postgres.
 *
 * <p>A decision made at once keeps the question the approver was shown. An answer that arrives
 * after a deferral does not: the deferral kept the question when it was asked, and that one is the
 * decision's.
 */
@Tag("container")
class ApprovalQuestionTest {

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));

  record Query(String q) {}

  private final ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

  private static Tool<Query> lookup() {
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
        return Awaited.ready(ToolResult.ok(new Block.Text("the answer to " + request.input().q())));
      }
    };
  }

  private QueuedHarness<String> harness(AgentType type, Approver approver) {
    return engine
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
                                .approver(approver, a -> a.timeout(Duration.ofMinutes(30))))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private static void settled(AgentType type, AgentId agentId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(engine.stateOf(type, agentId).getClass().getSimpleName())
                    .isEqualTo("Idle"));
  }

  private static ActionRequest.ToolCall requestedCall(List<AgentEvent> story) {
    AgentEvent.ActionsRequested requested = (AgentEvent.ActionsRequested) story.get(1);
    return (ActionRequest.ToolCall) requested.actions().getFirst();
  }

  private static void assertNamesTheCall(
      AgentId agentId, PayloadRef question, ActionRequest.ToolCall call) {
    JsonNode document = engine.payloads().forAgent(agentId).getDocument(question);
    assertThat(document.path("callId").asString()).isEqualTo("call_1");
    assertThat(document.path("toolName").asString()).isEqualTo("lookup");
    assertThat(document.path("action").asString()).isEqualTo("look up loch ness");
    assertThat(document.path("idempotencyKey").asString())
        .isEqualTo(call.idempotencyKey().value().toString());
  }

  @Test
  void an_approval_decided_at_once_is_stored_with_its_question() {
    AgentType type = new AgentType("question-approved");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, _ -> Awaited.ready(ApprovalResult.approvedBy("u_carol")))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolApproved.class);
    AgentEvent.ToolApproved approved = (AgentEvent.ToolApproved) story.get(2);
    assertThat(approved.question()).isPresent();
    assertNamesTheCall(agentId, approved.question().get(), requestedCall(story));
  }

  @Test
  void a_denial_decided_at_once_is_stored_with_its_question() {
    AgentType type = new AgentType("question-denied");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, _ -> Awaited.ready(ApprovalResult.deniedBy("out of hours", "u_dave")))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolDenied.class);
    AgentEvent.ToolDenied denied = (AgentEvent.ToolDenied) story.get(2);
    assertThat(denied.question()).isPresent();
    assertNamesTheCall(agentId, denied.question().get(), requestedCall(story));
  }

  @Test
  void an_answer_after_a_deferral_carries_no_question_and_the_deferral_has_one() {
    AgentType type = new AgentType("question-deferred");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(
            type,
            request -> {
              handed.add(request.replyToken());
              return Awaited.deferred();
            })
        .tell(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));

    assertThat(engine.replies().approve(handed.peek(), ApprovalResult.approvedBy("u_carol")))
        .isInstanceOf(ReplyOutcome.Settled.class);
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(2)).isInstanceOf(AgentEvent.ApprovalDeferred.class);
    assertThat(story.get(3)).isInstanceOf(AgentEvent.ToolApproved.class);
    AgentEvent.ApprovalDeferred deferred = (AgentEvent.ApprovalDeferred) story.get(2);
    AgentEvent.ToolApproved approved = (AgentEvent.ToolApproved) story.get(3);
    assertThat(approved.question()).as("the deferral's question is the decision's").isEmpty();
    assertNamesTheCall(agentId, deferred.question(), requestedCall(story));
  }
}
