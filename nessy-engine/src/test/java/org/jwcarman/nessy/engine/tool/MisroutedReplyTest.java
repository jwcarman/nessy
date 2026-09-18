package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.engine.store.Attempt;
import org.jwcarman.nessy.engine.store.EffectStore;

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

  private static final AgentType TYPE = new AgentType("replying");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Seq REQUEST = new Seq(42);
  private static final CallId CALL = new CallId("c1");
  private static final ToolName TOOL = new ToolName("lookup");

  private final ReplyTokens tokens = ReplyTokens.withKeys(new byte[32]);
  private final Rows rows = new Rows();
  private final Deliveries delivered = new Deliveries();
  private final DefaultReplies replies = new DefaultReplies(tokens);

  private ReplyToken token() {
    return tokens.mint(TYPE, AGENT, REQUEST, CALL);
  }

  private void serving() {
    replies.register(TYPE, rows, delivered);
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
    rows.effect = new AgentEffect.Approve(REQUEST, CALL, TOOL);
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
    rows.effect = new AgentEffect.Approve(REQUEST, new CallId("c2"), TOOL);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /** Same call id, but from a request two turns ago -- ids are only unique within a request. */
  @Test
  void anAnswerForTheSameCallOfAnEarlierRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.Approve(new Seq(7), CALL, TOOL);

    assertThat(replies.approve(token(), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(delivered.outcomes).isEmpty();
  }

  /** The same two mistakes on the other door, where a tool result is what arrives. */
  @Test
  void aResultForAnotherCallOfTheSameRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(REQUEST, new CallId("c2"), TOOL);

    assertThat(replies.complete(token(), ToolResult.ok(HistoryEntry.ToolSucceeded.text("done"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  @Test
  void aResultForTheSameCallOfAnEarlierRequestMatchesNothing() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(new Seq(7), CALL, TOOL);

    assertThat(replies.complete(token(), ToolResult.ok(HistoryEntry.ToolSucceeded.text("done"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  /**
   * A verdict may not settle a call that is already running. Letting it through would approve a
   * tool that has already gone past the gate, which is a grant recorded for work already done.
   */
  @Test
  void aVerdictCannotSettleACallThatIsAlreadyRunning() {
    serving();
    rows.running = List.of(attempt());
    rows.effect = new AgentEffect.CallTool(REQUEST, CALL, TOOL);

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
        null);
  }

  /** One agent type's rows, said rather than stored. */
  private static final class Rows extends EffectStore {

    private List<Attempt> running = List.of();
    private AgentEffect effect = new AgentEffect.Infer();
    private RuntimeException effectFails;
    private boolean completeWins = true;

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
  }

  private static final class Deliveries implements AgentEffectCallback {

    private final List<EffectOutcome> outcomes = new CopyOnWriteArrayList<>();

    @Override
    public void deliverOutcome(AgentId agentId, EffectOutcome outcome, String traceContext) {
      outcomes.add(outcome);
    }
  }
}
