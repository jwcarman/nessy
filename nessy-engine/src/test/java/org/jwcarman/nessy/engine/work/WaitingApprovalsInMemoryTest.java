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
package org.jwcarman.nessy.engine.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The waiting approvals through the in-memory queued backend: the rebuild, the order, the deadline,
 * the answer and the cap, with no database. The reads of a row's own fields that Postgres rounds
 * are not what this proves; the container test does that.
 */
@DisplayName("The approvals waiting on a person, in memory")
class WaitingApprovalsInMemoryTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);

  record Query(String q) {}

  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));

  private final InMemoryQueuedBackend memory =
      new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final ConcurrentLinkedQueue<ApprovalRequest> shown = new ConcurrentLinkedQueue<>();

  private static ApprovalRequest copyOf(ApprovalRequest request) {
    return new ApprovalRequest(
        request.agentType(),
        request.agentId(),
        request.turn(),
        request.callId(),
        request.idempotencyKey(),
        request.toolName(),
        request.arguments(),
        request.action(),
        request.askedAt(),
        request.deadline(),
        request.facts().deepCopy());
  }

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

  private DefaultQueuedHarnessFactory factory() {
    return DefaultQueuedHarnessFactory.of(
        engine ->
            engine
                .backend(memory)
                .provider(ProviderId.of("test"), MODEL)
                .inference(ProviderId.of("test"), InferenceOptions.of("a-model")));
  }

  private QueuedHarness<String> harness(DefaultQueuedHarnessFactory factory, AgentType type) {
    return factory.create(
        type,
        String.class,
        config ->
            config
                .systemPrompt("You are a test assistant.")
                .tool(
                    lookup(),
                    t ->
                        t.action(query -> "look up " + query.q())
                            .enrich(request -> request.fact("enricher", "found this"))
                            .approver(
                                request -> {
                                  request.fact("approver", "added this");
                                  shown.add(copyOf(request));
                                  return Awaited.deferred();
                                },
                                a -> a.timeout(Duration.ofMinutes(30))))
                .inference(in -> in.model("a-model"))
                .effects(e -> e.pollInterval(Duration.ofMillis(20))));
  }

  private AgentId parkAnother(QueuedHarness<String> harness) {
    int before = shown.size();
    AgentId agent = AgentId.random();
    harness.tell(agent, "go");
    await().atMost(PATIENT).untilAsserted(() -> assertThat(shown).hasSize(before + 1));
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () ->
                assertThat(memory.effects().liveFor(shown.peek().agentType(), agent))
                    .allMatch(row -> row.parkedAt().isPresent()));
    return agent;
  }

  @Test
  void a_waiting_approval_is_the_request_the_approver_was_shown_with_the_facts_it_left() {
    AgentType type = new AgentType("memory-shown");
    try (DefaultQueuedHarnessFactory factory = factory()) {
      AgentId agent = parkAnother(harness(factory, type));
      Instant parkedAt = memory.effects().liveFor(type, agent).getFirst().parkedAt().orElseThrow();

      List<ApprovalRequest> waiting = factory.work().waitingApprovals(type);

      ApprovalRequest given = shown.peek();
      ApprovalRequest expected =
          new ApprovalRequest(
              given.agentType(),
              given.agentId(),
              given.turn(),
              given.callId(),
              given.idempotencyKey(),
              given.toolName(),
              given.arguments(),
              given.action(),
              parkedAt,
              given.deadline(),
              given.facts());
      assertThat(given.facts())
          .isEqualTo(
              JsonNodeFactory.instance
                  .objectNode()
                  .put("enricher", "found this")
                  .put("approver", "added this"));
      assertThat(waiting).containsExactly(expected);
    }
  }

  @Test
  void waiting_approvals_come_oldest_first_and_one_type_can_be_named() {
    AgentType wanted = new AgentType("memory-ordered-wanted");
    AgentType other = new AgentType("memory-ordered-other");
    try (DefaultQueuedHarnessFactory factory = factory()) {
      QueuedHarness<String> wantedHarness = harness(factory, wanted);
      QueuedHarness<String> otherHarness = harness(factory, other);
      AgentId first = parkAnother(wantedHarness);
      AgentId second = parkAnother(otherHarness);
      AgentId third = parkAnother(wantedHarness);

      List<ApprovalRequest> everything = factory.work().waitingApprovals();
      List<ApprovalRequest> named = factory.work().waitingApprovals(wanted);

      assertThat(everything)
          .extracting(ApprovalRequest::agentId)
          .containsExactly(first, second, third);
      assertThat(named).extracting(ApprovalRequest::agentId).containsExactly(first, third);
    }
  }

  @Test
  void an_answered_approval_is_gone_and_a_late_clock_finds_none() {
    AgentType type = new AgentType("memory-answered");
    try (DefaultQueuedHarnessFactory factory = factory()) {
      parkAnother(harness(factory, type));
      AgentWork later =
          StoredAgentWork.queued(
              memory, Clock.fixed(Instant.now().plus(Duration.ofDays(1)), ZoneOffset.UTC));
      ApprovalRequest request = factory.work().waitingApprovals(type).getFirst();
      assertThat(later.waitingApprovals(type)).as("past the deadline").isEmpty();

      ReplyOutcome outcome =
          factory
              .replies()
              .approve(
                  request.agentType(),
                  request.agentId(),
                  request.idempotencyKey(),
                  ApprovalResult.approvedBy("u_carol"));

      assertThat(outcome).isInstanceOf(ReplyOutcome.Applied.class);
      await()
          .atMost(PATIENT)
          .untilAsserted(() -> assertThat(factory.work().waitingApprovals(type)).isEmpty());
    }
  }

  @Test
  void no_more_than_the_cap_come_back_and_an_agents_status_lists_its_own() {
    AgentType type = new AgentType("memory-capped");
    try (DefaultQueuedHarnessFactory factory = factory()) {
      QueuedHarness<String> harness = harness(factory, type);
      AgentId first = parkAnother(harness);
      AgentId second = parkAnother(harness);
      parkAnother(harness);
      AgentWork capped = StoredAgentWork.queued(memory, Clock.systemUTC(), 2);

      List<ApprovalRequest> waiting = capped.waitingApprovals(type);

      assertThat(waiting).extracting(ApprovalRequest::agentId).containsExactly(first, second);
      assertThat(factory.work().status(type, first).waitingApprovals())
          .extracting(ApprovalRequest::agentId)
          .containsExactly(first);
    }
  }
}
