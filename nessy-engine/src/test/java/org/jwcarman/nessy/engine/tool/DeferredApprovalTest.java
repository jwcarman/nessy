package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * An answer that arrives long after the question, through real Postgres.
 *
 * <p>The happy path is one test. The rest are the ways an answer can arrive at the wrong moment,
 * because that is what a days-long gap guarantees will happen: twice, too late, out of order, on a
 * forged address. Every one of them must be refused out loud -- a dropped answer strands the agent
 * <em>and</em> the person who answered and believes they are done.
 */
class DeferredApprovalTest {

  private static EngineFixture engine;

  @BeforeAll
  static void startEngine() {
    engine = new EngineFixture(MODEL);
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  /**
   * The provider belongs to the factory, not to a harness. It asks for the lookup on the first pass
   * and answers once the call has been settled, which is the shape a deferred approval has to
   * survive.
   */
  private static final InferenceProvider MODEL =
      (request, _) ->
          request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
              ? new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("all done"))
              : new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));

  record Query(String q) {}

  private final ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> ran = new ConcurrentLinkedQueue<>();

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
        return Awaited.ready(ToolResult.ok(new Block.Text("the answer to " + request.input().q())));
      }
    };
  }

  /** Asks once, keeps the address, says nothing -- the whole of a deferring approver. */
  private Harness<String> harness(AgentType type, Duration questionStands) {
    return engine
        .harnesses()
        .create(
            String.class,
            config ->
                config
                    .agentType(type)
                    .systemPrompt("You are a test assistant.")
                    .tool(
                        lookup(),
                        t ->
                            t.action(query -> "look up " + query.q())
                                .approver(
                                    request -> {
                                      handed.add(request.replyToken());
                                      return Awaited.deferred();
                                    },
                                    a -> a.timeout(questionStands)))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private String agentStateOf(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT state_type FROM nessy_agent_state WHERE agent_id = ?")
        .params(agentId.value())
        .query(String.class)
        .single();
  }

  private int outstandingEffects(AgentId agentId) {
    return engine
        .jdbc()
        .sql("SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?")
        .params(agentId.value())
        .query(Integer.class)
        .single();
  }

  private ReplyToken parkOne(AgentType type, Duration questionStands) {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, questionStands).observe(agentId, "what lake?");
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).isNotEmpty());
    return handed.peek();
  }

  /**
   * The whole point: a question parked, answered later by somebody else entirely, and the turn
   * carries on from exactly where it stopped -- the tool runs, the model is asked again, the agent
   * finishes.
   */
  @Test
  void anAnswerThatArrivesLaterRunsTheCallAndFinishesTheTurn() {
    AgentType type = new AgentType("deferred-approved");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, Duration.ofMinutes(30)).observe(agentId, "what lake?");

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    assertThat(ran).as("nothing ran while permission was outstanding").isEmpty();

    assertThat(engine.replies().approve(handed.peek(), ApprovalResult.approvedBy("u_carol")))
        .as("the agent has been told; the tool has not necessarily run yet")
        .isInstanceOf(ReplyOutcome.Settled.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(ran).containsExactly("loch ness");
    List<HistoryEntry> story = engine.history().entriesFrom(type, agentId, 0);
    assertThat(story.get(2))
        .as("the grant carries the join to whoever actually said yes")
        .isEqualTo(
            new HistoryEntry.ToolApproved(
                new Seq(3), new TurnId(1), new CallId("call_1"), Optional.of("u_carol")));
    assertThat(story.get(3)).isInstanceOf(HistoryEntry.ToolSucceeded.class);
    assertThat(story.get(4)).isInstanceOf(HistoryEntry.InferenceAnswered.class);
  }

  /** A late denial discharges the call and never reaches the tool. */
  @Test
  void aLateDenialStopsTheCallWithoutRunningIt() {
    AgentType type = new AgentType("deferred-denied");
    ReplyToken token = parkOne(type, Duration.ofMinutes(30));

    assertThat(engine.replies().approve(token, ApprovalResult.deniedBy("out of hours", "u_dave")))
        .isInstanceOf(ReplyOutcome.Settled.class);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(ran).as("never authorised").isEmpty());
  }

  // ---- answers at the wrong moment ---------------------------------------------------------

  /**
   * Somebody clicks twice, or a webhook is redelivered. The second answer must not fold a second
   * outcome into a call that is already running.
   */
  @Test
  void thesameAnswerTwiceIsRefusedTheSecondTime() {
    AgentType type = new AgentType("deferred-twice");
    ReplyToken token = parkOne(type, Duration.ofMinutes(30));

    assertThat(engine.replies().approve(token, ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Settled.class);
    assertThat(engine.replies().approve(token, ApprovalResult.approved()))
        .as("nothing is awaiting it any more, and saying so beats folding it twice")
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
  }

  /**
   * The agent stopped waiting and was told so. An answer arriving now cannot be honoured -- the
   * turn it belonged to has closed -- and the person who answered deserves to be told that rather
   * than to believe it landed.
   */
  @Test
  void anAnswerAfterTheQuestionExpiredIsRefused() {
    AgentType type = new AgentType("deferred-expired");
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness(type, Duration.ofSeconds(2)).observe(agentId, "what lake?");

    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(handed).hasSize(1));
    ReplyToken token = handed.peek();

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(engine.replies().approve(token, ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(ran).as("an expired question cannot authorise anything").isEmpty();
  }

  /** A forged or edited address is refused, and told apart from a stale one. */
  @Test
  void anAddressThisEngineDidNotIssueIsRefused() {
    assertThat(
            engine
                .replies()
                .approve(new ReplyToken("clearly-not-a-token"), ApprovalResult.approved()))
        .isInstanceOf(ReplyOutcome.Unreadable.class);

    ReplyToken elsewhere =
        ReplyTokens.ephemeral()
            .mint(
                new AgentType("chat"),
                new AgentId(UUID.randomUUID()),
                new Seq(2),
                new CallId("call_1"));
    assertThat(engine.replies().approve(elsewhere, ApprovalResult.approved()))
        .as("authentic under somebody else's key is still not ours")
        .isInstanceOf(ReplyOutcome.Unreadable.class);
  }

  /**
   * A verdict cannot settle a call that is past the gate, and a result cannot settle one still
   * waiting at it. The token names which effect is parked, so answering the wrong kind finds
   * nothing rather than running past the gate.
   */
  @Test
  void aToolResultCannotAnswerAQuestionAboutPermission() {
    AgentType type = new AgentType("deferred-wrong-kind");
    ReplyToken token = parkOne(type, Duration.ofMinutes(30));

    assertThat(engine.replies().complete(token, ToolResult.ok(new Block.Text("I said so"))))
        .isInstanceOf(ReplyOutcome.NotAwaiting.class);
    assertThat(ran).isEmpty();
    assertThat(engine.replies().approve(token, ApprovalResult.approved()))
        .as("and the real answer still works afterwards")
        .isInstanceOf(ReplyOutcome.Settled.class);
  }
}
