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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The approvals waiting on a person, rebuilt from what is stored, through real Postgres.
 *
 * <p>Each test is its own agent types, whose system prompt is the type's name; the one model reads
 * the name to know which story to play. Reads that name no type are filtered to the test's own
 * agents, because the other tests' approvals are waiting in the same tables.
 */
@Tag("container")
@DisplayName("The approvals waiting on a person")
class WaitingApprovalsTest {

  private static final Duration PATIENT = Duration.ofSeconds(20);

  record Query(String q) {}

  /**
   * A story named "repeat..." asks twice with the same call id and different arguments; "ping..."
   * asks for a call that is put aside with no approval; any other asks once for an approved call.
   */
  private static final InferenceProvider MODEL =
      (request, _) -> {
        String story = request.systemPrompt().value();
        int exchanges =
            request.context().turns().stream().mapToInt(turn -> turn.exchanges().size()).sum();
        if (story.startsWith("repeat")) {
          return exchanges < 2
              ? actions(
                  "call_1", "lookup", "{\"q\":\"" + (exchanges == 0 ? "first" : "second") + "\"}")
              : answer();
        }
        if (exchanges > 0) {
          return answer();
        }
        if (story.startsWith("twice")) {
          return new InferenceResult.Actions(
              List.of(
                  new Block.ToolCall("call_1", "lookup", "{\"q\":\"one\"}"),
                  new Block.ToolCall("call_1", "lookup", "{\"q\":\"two\"}")));
        }
        return story.startsWith("ping")
            ? actions("call_1", "ping", "{\"q\":\"nothing\"}")
            : actions("call_1", "lookup", "{\"q\":\"loch ness\"}");
      };

  private static InferenceResult answer() {
    return new InferenceResult.Answer(List.of(new Block.Text("all done")));
  }

  private static InferenceResult actions(String id, String tool, String arguments) {
    return new InferenceResult.Actions(List.of(new Block.ToolCall(id, tool, arguments)));
  }

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  /** What the approvers were handed, as they left it: a copy taken at the moment they deferred. */
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

  private Approver deferring() {
    return request -> {
      shown.add(copyOf(request));
      return Awaited.deferred();
    };
  }

  /** Adds a fact of its own to what an enricher already added, then defers. */
  private Approver deferringWithAFact() {
    return request -> {
      request.fact("approver", "added this");
      shown.add(copyOf(request));
      return Awaited.deferred();
    };
  }

  private static Tool<Query> tool(String name) {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName(name);
      }

      @Override
      public String description() {
        return "does " + name;
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return name.equals("ping")
            ? Awaited.deferred()
            : Awaited.ready(ToolResult.ok(new Block.Text("the answer to " + request.input().q())));
      }
    };
  }

  private final Map<AgentType, QueuedHarness<String>> harnesses = new HashMap<>();

  private void harness(AgentType type, Approver approver, boolean enriched) {
    harnesses.put(
        type,
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt(type.value())
                        .tool(
                            tool("lookup"),
                            t -> {
                              t.action(query -> "look up " + query.q())
                                  .approver(approver, a -> a.timeout(Duration.ofMinutes(30)));
                              if (enriched) {
                                t.enrich(request -> request.fact("enricher", "found this"));
                              }
                            })
                        .tool(
                            tool("ping"),
                            t ->
                                t.action(query -> "ping " + query.q())
                                    .timeout(Duration.ofMinutes(30)))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50)))));
  }

  private void harness(AgentType type) {
    harness(type, deferring(), false);
  }

  /** Tells a new agent of {@code type} and waits until its approver has deferred one more. */
  private AgentId parkAnother(AgentType type) {
    int before = shown.size();
    AgentId agent = AgentId.random();
    harnesses.get(type).tell(agent, "go");
    await().atMost(PATIENT).untilAsserted(() -> assertThat(shown).hasSize(before + 1));
    awaitParked(type, agent);
    return agent;
  }

  private void awaitParked(AgentType type, AgentId agent) {
    await()
        .atMost(PATIENT)
        .untilAsserted(
            () -> assertThat(engine.work().status(type, agent).waitingApprovals()).isNotEmpty());
  }

  private static List<AgentId> agentsOf(List<ApprovalRequest> requests, List<AgentId> mine) {
    return requests.stream().map(ApprovalRequest::agentId).filter(mine::contains).toList();
  }

  private static Instant parkedAt(AgentType type, AgentId agent) {
    return engine
        .jdbc()
        .sql("SELECT parked_at FROM nessy_agent_effect WHERE agent_type = ? AND agent_id = ?")
        .params(type.value(), agent.value())
        .query(OffsetDateTime.class)
        .single()
        .toInstant();
  }

  @Nested
  @DisplayName("One approval")
  class One_approval {

    @Test
    void a_waiting_approval_is_the_request_the_approver_was_shown() {
      AgentType type = new AgentType("waiting-shown");
      harness(type);
      AgentId agent = parkAnother(type);

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(type);

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
              parkedAt(type, agent),
              given.deadline(),
              given.facts());
      assertThat(given.arguments()).isEqualTo("{\"q\":\"loch ness\"}");
      assertThat(given.action()).isEqualTo("look up loch ness");
      assertThat(waiting).containsExactly(expected);
    }

    @Test
    void its_facts_are_the_ones_the_approver_left() {
      AgentType type = new AgentType("waiting-facts");
      harness(type, deferringWithAFact(), true);
      parkAnother(type);

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(type);

      assertThat(waiting).hasSize(1);
      assertThat(waiting.getFirst().facts())
          .isEqualTo(
              JsonNodeFactory.instance
                  .objectNode()
                  .put("enricher", "found this")
                  .put("approver", "added this"));
      assertThat(waiting.getFirst().facts()).isEqualTo(shown.peek().facts());
    }

    @Test
    void an_answered_approval_is_gone_from_the_next_read() {
      AgentType type = new AgentType("waiting-answered");
      harness(type);
      parkAnother(type);
      ApprovalRequest request = engine.work().waitingApprovals(type).getFirst();

      ReplyOutcome outcome =
          engine
              .replies()
              .approve(
                  request.agentType(),
                  request.agentId(),
                  request.idempotencyKey(),
                  ApprovalResult.approvedBy("u_carol"));

      assertThat(outcome).isInstanceOf(ReplyOutcome.Applied.class);
      await()
          .atMost(PATIENT)
          .untilAsserted(() -> assertThat(engine.work().waitingApprovals(type)).isEmpty());
    }

    @Test
    void an_approval_past_its_deadline_is_not_waiting() {
      AgentType type = new AgentType("waiting-past-deadline");
      harness(type);
      parkAnother(type);
      Clock afterTheDeadline = Clock.fixed(Instant.now().plus(Duration.ofDays(1)), ZoneOffset.UTC);
      AgentWork later = engine.workAt(afterTheDeadline);

      assertThat(engine.work().waitingApprovals(type)).as("waiting until then").hasSize(1);

      assertThat(later.waitingApprovals(type)).isEmpty();
    }

    @Test
    void a_deferred_tool_call_is_not_listed() {
      AgentType type = new AgentType("ping-deferred-call");
      harness(type);
      AgentId agent = AgentId.random();
      harnesses.get(type).tell(agent, "go");

      await()
          .atMost(PATIENT)
          .untilAsserted(
              () -> assertThat(engine.work().status(type, agent).waitingToolCalls()).isEqualTo(1));

      assertThat(engine.work().waitingApprovals(type)).isEmpty();
      assertThat(engine.work().status(type, agent).waitingApprovals()).isEmpty();
    }

    @Test
    void a_call_id_repeated_in_a_later_request_gets_its_own_action_and_arguments() {
      AgentType type = new AgentType("repeat-call-id");
      harness(type);
      parkAnother(type);
      ApprovalRequest first = engine.work().waitingApprovals(type).getFirst();
      assertThat(first.arguments()).isEqualTo("{\"q\":\"first\"}");
      engine
          .replies()
          .approve(
              first.agentType(),
              first.agentId(),
              first.idempotencyKey(),
              ApprovalResult.approvedBy("u_carol"));
      await().atMost(PATIENT).untilAsserted(() -> assertThat(shown).hasSize(2));
      awaitReplacedBy(type, first);

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(type);

      ApprovalRequest second = List.copyOf(shown).get(1);
      assertThat(waiting).hasSize(1);
      assertThat(waiting.getFirst().callId()).isEqualTo(first.callId());
      assertThat(waiting.getFirst().idempotencyKey()).isNotEqualTo(first.idempotencyKey());
      assertThat(waiting.getFirst().idempotencyKey()).isEqualTo(second.idempotencyKey());
      assertThat(waiting.getFirst().arguments()).isEqualTo("{\"q\":\"second\"}");
      assertThat(waiting.getFirst().action()).isEqualTo("look up second");
    }

    private void awaitReplacedBy(AgentType type, ApprovalRequest answered) {
      await()
          .atMost(PATIENT)
          .untilAsserted(
              () ->
                  assertThat(engine.work().waitingApprovals(type))
                      .extracting(ApprovalRequest::idempotencyKey)
                      .isNotEmpty()
                      .doesNotContain(answered.idempotencyKey()));
    }

    @Test
    void two_calls_with_one_call_id_in_one_response_each_get_their_own_action_and_arguments() {
      AgentType type = new AgentType("twice-one-call-id");
      harness(type);
      AgentId agent = AgentId.random();
      harnesses.get(type).tell(agent, "go");
      await().atMost(PATIENT).until(() -> engine.work().waitingApprovals(type).size() == 2);

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(type);

      assertThat(waiting)
          .extracting(ApprovalRequest::arguments)
          .containsExactly("{\"q\":\"one\"}", "{\"q\":\"two\"}");
    }

    @Test
    void an_agents_status_lists_its_waiting_approvals() {
      AgentType type = new AgentType("waiting-in-status");
      harness(type);
      AgentId agent = parkAnother(type);

      List<ApprovalRequest> status = engine.work().status(type, agent).waitingApprovals();

      assertThat(status).isEqualTo(engine.work().waitingApprovals(type));
      assertThat(status).hasSize(1);
      assertThat(status.getFirst().agentId()).isEqualTo(agent);
    }
  }

  @Nested
  @DisplayName("Several approvals")
  class Several_approvals {

    @Test
    void waiting_approvals_come_oldest_first() {
      AgentType type = new AgentType("waiting-ordered");
      harness(type);
      List<AgentId> parked = List.of(parkAnother(type), parkAnother(type), parkAnother(type));

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(type);

      assertThat(agentsOf(waiting, parked)).containsExactlyElementsOf(parked);
    }

    @Test
    void only_one_agent_types_when_one_is_named() {
      AgentType wanted = new AgentType("waiting-named-one");
      AgentType other = new AgentType("waiting-named-other");
      harness(wanted);
      harness(other);
      AgentId first = parkAnother(wanted);
      parkAnother(other);
      AgentId third = parkAnother(wanted);

      List<ApprovalRequest> waiting = engine.work().waitingApprovals(wanted);

      assertThat(waiting).extracting(ApprovalRequest::agentId).containsExactly(first, third);
      assertThat(waiting).extracting(ApprovalRequest::agentType).containsOnly(wanted);
    }

    @Test
    void every_types_when_none_is_named() {
      AgentType one = new AgentType("waiting-every-one");
      AgentType two = new AgentType("waiting-every-two");
      harness(one);
      harness(two);
      List<AgentId> parked = List.of(parkAnother(one), parkAnother(two), parkAnother(one));

      List<ApprovalRequest> waiting = engine.work().waitingApprovals();

      assertThat(agentsOf(waiting, parked)).containsExactlyElementsOf(parked);
    }

    @Test
    void no_more_than_the_cap_come_back_and_they_are_the_oldest() {
      AgentType type = new AgentType("waiting-capped");
      harness(type);
      List<AgentId> parked = List.of(parkAnother(type), parkAnother(type), parkAnother(type));
      AgentWork capped = StoredAgentWork.queued(engine.backend(), Clock.systemUTC(), 2);

      List<ApprovalRequest> waiting = capped.waitingApprovals(type);

      assertThat(waiting)
          .extracting(ApprovalRequest::agentId)
          .containsExactlyElementsOf(parked.subList(0, 2));
      assertThat(StoredAgentWork.MAXIMUM_WAITING).isEqualTo(500);
    }
  }

  @Nested
  @DisplayName("The direct door")
  class The_direct_door {

    @Test
    void a_direct_door_agent_has_none() {
      AgentWork direct = StoredAgentWork.direct(engine.events(), Clock.systemUTC());

      assertThat(direct.waitingApprovals()).isEmpty();
      assertThat(direct.waitingApprovals(new AgentType("waiting-direct"))).isEmpty();
    }
  }
}
