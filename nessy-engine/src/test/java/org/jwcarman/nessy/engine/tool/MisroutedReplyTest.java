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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.store.Outbox;
import tools.jackson.databind.json.JsonMapper;

/**
 * An answer that is authentic but is not for anything still waiting.
 *
 * <p>A token is only proof of what it says: this agent type, this agent, this call. It is not proof
 * that the call is still outstanding, that this process serves that agent type, or that the row it
 * names is the row the answer is allowed to settle. Every one of those is checked separately, and
 * the interesting thing about all of them is that they are indistinguishable to the caller -- one
 * answer, {@code NotAwaiting}, because "answered a moment ago", "expired" and "never existed here"
 * are the same news to whoever is holding the token.
 *
 * <p>The one that has to be checked rather than assumed is the kind: a verdict may not settle a
 * call that is already running, and a result may not settle one still awaiting permission. Either
 * would take a tool past its gate instead of through it.
 */
class MisroutedReplyTest {

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  private static final AgentType TYPE = new AgentType("replying");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Seq REQUEST = new Seq(42);
  private static final TurnId TURN = new TurnId(7);
  private static final CallId CALL = new CallId("c1");
  private static final ToolName TOOL = new ToolName("lookup");

  private final ReplyTokens tokens = ReplyTokens.withKeys(new byte[32]);
  private final Rows rows = new Rows();
  private final Deliveries delivered = new Deliveries();
  private final Payloads payloads =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));
  private final DefaultReplies replies = new DefaultReplies(tokens);

  private ReplyToken token() {
    return tokens.mint(TYPE, AGENT, REQUEST, CALL);
  }

  private void serving() {
    replies.register(TYPE, rows, delivered, payloads, Tools.none());
  }

  /** A token minted before a rename, or a deployment that no longer builds that harness. */
  @Test
  void anAnswerForAnAgentTypeThisProcessDoesNotServeIsNotAwaiting() {
    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * The fence lost, which means the row moved on while the answer was being folded. Harmless: the
   * call is discharged either way, and whatever holds the row now will find the fold already
   * ignoring what it delivers.
   */
  @Test
  void anAnswerDeliveredAsItsRowWasTakenOverIsStillSettled() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.Approve(TURN, REQUEST, CALL, TOOL, KEY);
    rows.completeWins = false;

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.outcomes).as("the agent was told before the row was let go").hasSize(1);
  }

  /** Right kind, right agent, different call. */
  @Test
  void anAnswerForAnotherCallOfTheSameRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.Approve(TURN, REQUEST, new CallId("c2"), TOOL, KEY);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /** Same call id, but from a request two turns ago -- ids are only unique within a request. */
  @Test
  void anAnswerForTheSameCallOfAnEarlierRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.Approve(TURN, new Seq(7), CALL, TOOL, KEY);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /** The same two mistakes on the other door, where a tool result is what arrives. */
  @Test
  void aResultForAnotherCallOfTheSameRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, new CallId("c2"), TOOL, KEY);

    assertThat(replies.complete(token(), ToolResult.ok(new Block.Text("done"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  @Test
  void aResultForTheSameCallOfAnEarlierRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, new Seq(7), CALL, TOOL, KEY);

    assertThat(replies.complete(token(), ToolResult.ok(new Block.Text("done"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  @Test
  void a_result_for_a_tool_that_is_no_longer_bound_still_gets_its_line() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);
    PayloadRef ref = payloads.put(List.of(new Block.Text("done")));

    assertThat(replies.complete(token(), ToolResult.ok(new Block.Text("done"))))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.outcomes)
        .containsExactly(new EffectOutcome.ToolSucceeded(CALL, ref, "done"));
  }

  @Test
  void a_reply_for_the_current_request_is_delivered_with_that_request() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);

    assertThat(replies.complete(token(), ToolResult.ok(new Block.Text("done"))))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.requests).containsExactly(Optional.of(REQUEST));
  }

  @Test
  void a_verdict_for_the_current_request_is_delivered_with_that_request() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.Approve(TURN, REQUEST, CALL, TOOL, KEY);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.requests).containsExactly(Optional.of(REQUEST));
  }

  @Test
  void a_deferred_failure_without_a_message_is_recorded_with_one() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);

    assertThat(replies.complete(token(), new ToolResult.Failure(null)))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.outcomes)
        .containsExactly(
            new EffectOutcome.ToolFailed(
                CALL, CallFailure.FAILED, "the tool failed and gave no message"));
  }

  @Test
  void a_deferred_failure_is_recorded_as_a_failed_call() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);

    assertThat(replies.complete(token(), new ToolResult.Failure("the index is locked")))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(delivered.outcomes)
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  /**
   * A verdict may not settle a call that is already running. Letting it through would approve a
   * tool that has already gone past the gate, which is a grant recorded for work already done.
   */
  @Test
  void aVerdictCannotSettleACallThatIsAlreadyRunning() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(TURN, REQUEST, CALL, TOOL, KEY);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * A row nobody can decode is simply not a match. Its own deadline deals with it -- skipping it
   * here is not the same as giving up on it.
   */
  @Test
  void aRowWhoseEffectCannotBeReadIsLeftToItsDeadline() {
    serving();
    rows.running = List.of(attempt());
    rows.effectFails = new IllegalStateException("an effect from a build that was rolled back");

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  private static Attempt attempt() {
    return new Attempt(
        UUID.randomUUID(),
        AGENT,
        new byte[0],
        new byte[0],
        1,
        Instant.parse("2026-09-18T12:00:00Z"),
        null,
        null);
  }

  /** One agent type's rows, said rather than stored. */
  private static final class Rows extends Outbox {

    private List<Attempt> running = List.of();
    private AgentEffect effect = new AgentEffect.Infer(TURN);
    private RuntimeException effectFails;
    private boolean completeWins = true;
    private final List<UUID> parked = new CopyOnWriteArrayList<>();

    private Rows() {
      super(TYPE, null, null);
    }

    @Override
    public List<Attempt> runningFor(AgentId agentId) {
      return running;
    }

    @Override
    public AgentEffect effectOf(Attempt attempt) {
      if (effectFails != null) {
        throw effectFails;
      }
      return effect;
    }

    @Override
    public boolean complete(UUID effectId, int attemptsMade) {
      return completeWins;
    }

    @Override
    public boolean park(UUID effectId, int attemptsMade, Instant at) {
      parked.add(effectId);
      return true;
    }
  }

  private static final class Deliveries implements AgentEffectCallback {

    private final List<EffectOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final List<Optional<Seq>> requests = new CopyOnWriteArrayList<>();

    @Override
    public void deliverOutcome(
        AgentId agentId,
        Optional<TurnId> turn,
        Optional<Seq> request,
        EffectOutcome outcome,
        String traceContext,
        List<FailedAttempt> priorAttempts) {
      outcomes.add(outcome);
      requests.add(request);
    }
  }
}
