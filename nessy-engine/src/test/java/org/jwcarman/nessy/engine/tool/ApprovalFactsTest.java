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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.RequestManifest;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.story.EventAgentStories;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The facts an approval was decided on, kept on the event that records the decision, through real
 * Postgres.
 *
 * <p>A decision made at once keeps the facts the approver was shown. An answer that arrives after a
 * deferral does not: the deferral kept the facts when it put the call aside, and those are the
 * decision's. Nothing about an approval is written to the payload table.
 */
@Tag("container")
class ApprovalFactsTest {

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  /** Not a cipher; enough to make a row unreadable to anyone who does not undo it. */
  private static final Codec<byte[]> REVERSED =
      new Codec<>() {
        @Override
        public byte[] encode(byte[] bytes) {
          return reverse(bytes);
        }

        @Override
        public byte[] decode(byte[] bytes) {
          return reverse(bytes);
        }
      };

  private static byte[] reverse(byte[] bytes) {
    byte[] reversed = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      reversed[i] = bytes[bytes.length - 1 - i];
    }
    return reversed;
  }

  /** What the model was told about calls that failed, whichever agent it was told it for. */
  private static final ConcurrentLinkedQueue<String> TOLD_OF_FAILURES =
      new ConcurrentLinkedQueue<>();

  private static final InferenceProvider MODEL =
      (request, _) -> {
        request.context().turns().stream()
            .flatMap(turn -> turn.exchanges().stream())
            .flatMap(exchange -> exchange.outcomes().stream())
            .forEach(
                outcome -> {
                  if (outcome instanceof ToolOutcome.Failed failed) {
                    TOLD_OF_FAILURES.add(failed.message());
                  }
                });
        return request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
            ? new InferenceResult.Answer(List.of(new Block.Text("all done")))
            : new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
      };

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
    return harness(type, approver, new RetryPolicy.Never());
  }

  private QueuedHarness<String> harness(AgentType type, Approver approver, RetryPolicy onAsking) {
    return harness(engine, type, approver, onAsking);
  }

  private QueuedHarness<String> harness(
      EngineFixture on, AgentType type, Approver approver, RetryPolicy onAsking) {
    return on.harnesses()
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
                                .approver(
                                    approver,
                                    a -> a.timeout(Duration.ofMinutes(30)).retryPolicy(onAsking)))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private static void settled(AgentType type, AgentId agentId) {
    settled(engine, type, agentId);
  }

  private static void settled(EngineFixture on, AgentType type, AgentId agentId) {
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(on.stateOf(type, agentId).getClass().getSimpleName()).isEqualTo("Idle"));
  }

  private static StoryContent contentOf(EngineFixture on, AgentType type, AgentId agentId) {
    return new EventAgentStories(on.events(), on.payloads()).of(type, agentId).content();
  }

  private static IdempotencyKey keyOfTheCall(EngineFixture on, AgentType type, AgentId agentId) {
    return requestedCall(on.story(type, agentId)).idempotencyKey();
  }

  private static ActionRequest.ToolCall requestedCall(List<AgentEvent> story) {
    AgentEvent.ActionsRequested requested = (AgentEvent.ActionsRequested) story.get(1);
    return (ActionRequest.ToolCall) requested.actions().getFirst();
  }

  private static ObjectNode facts() {
    return JsonNodeFactory.instance.objectNode().put("risk", "low").put("depth", 2);
  }

  private static ObjectNode none() {
    return JsonNodeFactory.instance.objectNode();
  }

  /** An approver that adds the facts of {@link #facts()}, then answers. */
  private static Approver adding(ApprovalResult answer) {
    return request -> {
      request.fact("risk", "low").fact("depth", JsonNodeFactory.instance.numberNode(2));
      return Awaited.ready(answer);
    };
  }

  /** An approver that adds the facts of {@link #facts()}, then puts the call aside. */
  private Approver deferringWithFacts() {
    return request -> {
      request.fact("risk", "low").fact("depth", JsonNodeFactory.instance.numberNode(2));
      handed.add(request.replyToken());
      return Awaited.deferred();
    };
  }

  /** The parts of the requests the model was shown, which are stored as documents. */
  private static Set<String> manifestDocuments(List<AgentEvent> story) {
    Set<String> refs = new HashSet<>();
    for (AgentEvent event : story) {
      Optional<RequestManifest> manifest =
          switch (event) {
            case AgentEvent.ActionsRequested requested -> requested.manifest();
            case AgentEvent.InferenceAnswered answered -> answered.request();
            case AgentEvent.InferenceRefused refused -> refused.request();
            case AgentEvent.InferenceFailed failed -> failed.request();
            case AgentEvent.InferenceAttempted attempted -> attempted.request();
            default -> Optional.empty();
          };
      manifest.ifPresent(
          m -> {
            refs.add(m.tools().value());
            refs.add(m.options().value());
            m.answerShape().ifPresent(shape -> refs.add(shape.value()));
          });
    }
    return refs;
  }

  /**
   * Nothing about an approval is written to the payload store. The only documents an agent's rows
   * hold are the parts of the requests the model was shown, which its model-call events name.
   */
  private static void assertNoApprovalDocumentIsStored(AgentType type, AgentId agentId) {
    List<String> documents =
        engine
            .jdbc()
            .sql(
                "SELECT encode(hash, 'hex') FROM nessy_payload"
                    + " WHERE agent_id = ? AND kind = 'DOCUMENT'")
            .params(agentId.value())
            .query(String.class)
            .list();
    Set<String> named = manifestDocuments(engine.story(type, agentId));
    assertThat(documents).isNotEmpty();
    assertThat(named).isNotEmpty();
    assertThat(named).containsAll(documents);
  }

  @Test
  void an_approval_decided_at_once_is_stored_with_its_facts() {
    AgentType type = new AgentType("facts-approved");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, adding(ApprovalResult.approvedBy("u_carol"))).tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolApproved.class);
    AgentEvent.ToolApproved approved = (AgentEvent.ToolApproved) story.get(2);
    assertThat(approved.facts().toString()).isEqualTo(facts().toString());
    assertNoApprovalDocumentIsStored(type, agentId);
  }

  @Test
  void a_denial_decided_at_once_is_stored_with_its_facts() {
    AgentType type = new AgentType("facts-denied");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, adding(ApprovalResult.deniedBy("out of hours", "u_dave")))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolDenied.class);
    AgentEvent.ToolDenied denied = (AgentEvent.ToolDenied) story.get(2);
    assertThat(denied.facts().toString()).isEqualTo(facts().toString());
    assertNoApprovalDocumentIsStored(type, agentId);
  }

  @Test
  void an_answer_after_a_deferral_carries_no_facts_and_the_deferral_has_them() {
    AgentType type = new AgentType("facts-deferred");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, deferringWithFacts()).tell(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));

    assertThat(engine.replies().approve(handed.peek(), ApprovalResult.approvedBy("u_carol")))
        .isInstanceOf(ReplyOutcome.Settled.class);
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);
    assertThat(story.get(2)).isInstanceOf(AgentEvent.ApprovalDeferred.class);
    assertThat(story.get(3)).isInstanceOf(AgentEvent.ToolApproved.class);
    AgentEvent.ApprovalDeferred deferred = (AgentEvent.ApprovalDeferred) story.get(2);
    AgentEvent.ToolApproved approved = (AgentEvent.ToolApproved) story.get(3);
    assertThat(approved.facts()).as("the deferral's facts are the decision's").isEqualTo(none());
    assertThat(deferred.facts().toString()).isEqualTo(facts().toString());
    assertNoApprovalDocumentIsStored(type, agentId);
  }

  /** The deferral is the record that the call was put aside, with facts or without. */
  @Test
  void a_deferral_with_no_facts_is_still_recorded_and_its_row_is_parked() {
    AgentType type = new AgentType("facts-deferred-bare");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(
            type,
            request -> {
              handed.add(request.replyToken());
              return Awaited.deferred();
            })
        .tell(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              List<AgentEvent> story = engine.story(type, agentId);
              assertThat(story).anyMatch(event -> event instanceof AgentEvent.ApprovalDeferred);
            });
    List<AgentEvent> story = engine.story(type, agentId);
    AgentEvent.ApprovalDeferred deferred =
        story.stream()
            .filter(AgentEvent.ApprovalDeferred.class::isInstance)
            .map(AgentEvent.ApprovalDeferred.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(deferred.facts()).isEqualTo(none());
    Integer parked =
        engine
            .jdbc()
            .sql(
                "SELECT count(*) FROM nessy_agent_effect"
                    + " WHERE agent_id = ? AND parked_at IS NOT NULL")
            .params(agentId.value())
            .query(Integer.class)
            .single();
    assertThat(parked).as("the approval's row is parked").isEqualTo(1);
    assertNoApprovalDocumentIsStored(type, agentId);
  }

  /** An approver that records what it was shown, and fails the first {@code failures} times. */
  private static Approver failing(
      List<ApprovalRequest> shown, AtomicInteger asks, int failures, String message) {
    return request -> {
      int ask = asks.incrementAndGet();
      request.fact("ask", JsonNodeFactory.instance.numberNode(ask));
      shown.add(request);
      if (ask <= failures) {
        throw new IllegalStateException(message);
      }
      return Awaited.ready(ApprovalResult.approvedBy("u_carol"));
    };
  }

  /**
   * Retrying a failed ask is the dispatcher's, and it asks the policy, not the exception. The
   * failure that is finally recorded holds the facts of the ask that was last made.
   */
  @Test
  void an_approver_that_throws_twice_is_asked_twice_and_the_failure_holds_the_second_asks_facts() {
    AgentType type = new AgentType("facts-throws-twice");
    AgentId agentId = new AgentId(UUID.randomUUID());
    List<ApprovalRequest> shown = new CopyOnWriteArrayList<>();
    String message = "approval service down (twice)";
    harness(
            type,
            failing(shown, new AtomicInteger(), 2, message),
            new RetryPolicy.FixedDelay(2, Duration.ofMillis(100), Duration.ZERO))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(shown).as("asked exactly twice").hasSize(2);
    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolFailed.class);
    AgentEvent.ToolFailed failed = (AgentEvent.ToolFailed) story.get(2);
    assertThat(failed.kind()).isEqualTo(CallFailure.NOT_AUTHORISED);
    assertThat(failed.message()).isEqualTo("the call could not be authorised: " + message);
    assertThat(failed.facts()).isEqualTo(JsonNodeFactory.instance.objectNode().put("ask", 2));
    assertThat(TOLD_OF_FAILURES).contains("the call could not be authorised: " + message);
    assertThat(story).isNotEmpty();
    assertThat(story).noneMatch(event -> event instanceof AgentEvent.ToolApproved);
    assertNoApprovalDocumentIsStored(type, agentId);
  }

  @Test
  void an_approver_that_throws_once_then_approves_runs_the_call() {
    AgentType type = new AgentType("facts-throws-once");
    AgentId agentId = new AgentId(UUID.randomUUID());
    List<ApprovalRequest> shown = new CopyOnWriteArrayList<>();
    harness(
            type,
            failing(shown, new AtomicInteger(), 1, "approval service down (once)"),
            new RetryPolicy.FixedDelay(2, Duration.ofMillis(100), Duration.ZERO))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    List<AgentEvent> story = engine.story(type, agentId);

    assertThat(shown).as("asked twice").hasSize(2);
    assertThat(story).isNotEmpty();
    assertThat(story).noneMatch(event -> event instanceof AgentEvent.ToolFailed);
    assertThat(story.get(2)).isInstanceOf(AgentEvent.ToolApproved.class);
    AgentEvent.ToolApproved approved = (AgentEvent.ToolApproved) story.get(2);
    assertThat(approved.facts()).isEqualTo(JsonNodeFactory.instance.objectNode().put("ask", 2));
    assertThat(story.get(3)).isInstanceOf(AgentEvent.ToolSucceeded.class);
  }

  @Test
  void deferred_facts_read_the_same_while_the_call_waits_and_after_it_is_answered() {
    AgentType type = new AgentType("facts-read-deferred");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, deferringWithFacts()).tell(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    IdempotencyKey key = keyOfTheCall(engine, type, agentId);
    StoryContent content = contentOf(engine, type, agentId);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(() -> assertThat(content.approvalFacts(key)).isPresent());
    Optional<JsonNode> waiting = content.approvalFacts(key);

    assertThat(waiting).isPresent();
    assertThat(waiting.get().toString()).isEqualTo(facts().toString());
    assertThat(engine.replies().approve(handed.peek(), ApprovalResult.approvedBy("u_carol")))
        .isInstanceOf(ReplyOutcome.Settled.class);
    settled(type, agentId);
    Optional<JsonNode> answered = content.approvalFacts(key);
    assertThat(answered).isPresent();
    assertThat(answered.get().toString()).isEqualTo(waiting.get().toString());
  }

  @Test
  void an_approval_decided_at_once_reads_back_after_the_turn() {
    AgentType type = new AgentType("facts-read-approved");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, adding(ApprovalResult.approvedBy("u_carol"))).tell(agentId, "what lake?");
    settled(type, agentId);

    Optional<JsonNode> read =
        contentOf(engine, type, agentId).approvalFacts(keyOfTheCall(engine, type, agentId));

    assertThat(read).isPresent();
    assertThat(read.get().toString()).isEqualTo(facts().toString());
  }

  /** An approver that adds nothing still leaves a decision with an empty object, readable. */
  @Test
  void an_approval_with_no_facts_reads_back_as_an_empty_object() {
    AgentType type = new AgentType("facts-read-bare");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, _ -> Awaited.ready(ApprovalResult.approvedBy("u_carol")))
        .tell(agentId, "what lake?");
    settled(type, agentId);

    Optional<JsonNode> read =
        contentOf(engine, type, agentId).approvalFacts(keyOfTheCall(engine, type, agentId));

    assertThat(read).contains(none());
  }

  @Test
  void facts_still_read_back_when_the_storage_is_transformed() {
    AgentType type = new AgentType("facts-read-transformed");
    AgentId agentId = new AgentId(UUID.randomUUID());
    try (EngineFixture transformed = new EngineFixture(MODEL, REVERSED)) {
      harness(
              transformed,
              type,
              adding(ApprovalResult.deniedBy("out of hours", "u_dave")),
              new RetryPolicy.Never())
          .tell(agentId, "what lake?");
      settled(transformed, type, agentId);

      byte[] row =
          transformed
              .jdbc()
              .sql("SELECT payload FROM nessy_agent_event WHERE agent_id = ? AND seq = 3")
              .params(agentId.value())
              .query(byte[].class)
              .single();
      Optional<JsonNode> read =
          contentOf(transformed, type, agentId)
              .approvalFacts(keyOfTheCall(transformed, type, agentId));

      assertThat(new String(row, StandardCharsets.UTF_8))
          .doesNotContain("facts")
          .doesNotContain("risk");
      assertThat(read).isPresent();
      assertThat(read.get().toString()).isEqualTo(facts().toString());
    }
  }
}
