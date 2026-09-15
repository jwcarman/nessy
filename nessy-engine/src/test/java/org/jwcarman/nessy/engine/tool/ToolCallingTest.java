package org.jwcarman.nessy.engine.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
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
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.jwcarman.nessy.spi.inference.ToolOffer;
import org.jwcarman.nessy.spi.narration.AgentNarrator;

/**
 * A whole round, through real Postgres: the model asks for work, the work is dispatched as its own
 * durable effect, the result is written down, and the model is asked again in the same turn.
 *
 * <p>The unit tests assert each hop; this asserts that they join up. What it is really watching for
 * is the failure that no component test can see -- a turn closing with a call still owed, or an
 * effect that never becomes a result, either of which leaves an agent that looks healthy and can
 * never speak again.
 */
class ToolCallingTest {

  private EngineFixture engine;

  /**
   * One engine per test, and each built around the model that test needs.
   *
   * <p>The provider is a factory-level setting -- one model serves every harness an engine hands
   * out -- so a class that varies what the model asks for varies the engine, not the harness.
   */
  private void running(InferenceProvider model) {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    if (engine != null) {
      engine.close();
    }
  }

  /** What a tool binds its arguments to. */
  record Query(String q) {}

  /** Answers once with a call, then with an answer -- the shape of every tool-using turn. */
  static final class ScriptedModel implements InferenceProvider {

    private final ConcurrentLinkedQueue<InferenceResult> script;
    private final ConcurrentLinkedQueue<List<ToolOffer>> offered = new ConcurrentLinkedQueue<>();

    ScriptedModel(InferenceResult... results) {
      this.script = new ConcurrentLinkedQueue<>(List.of(results));
    }

    @Override
    public InferenceResult infer(InferenceRequest request, AgentNarrator narrator) {
      offered.add(request.tools());
      InferenceResult next = script.poll();
      return next != null
          ? next
          : new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"));
    }
  }

  private static Tool<Query> lookup(ConcurrentLinkedQueue<String> seen) {
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
        seen.add(request.input().q());
        return Awaited.ready(ToolResult.ok(new Block.Text("the answer to " + request.input().q())));
      }
    };
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

  @Test
  void aToolCallIsRunAndTheModelIsAskedAgainInTheSameTurn() {
    AgentType type = new AgentType("tool-round");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Actions(
                List.of(
                    new Block.Commentary("Let me look."),
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))),
            new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("It is Loch Ness.")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(lookup(seen))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "what lake?");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(seen).as("the tool ran, on arguments the model wrote").containsExactly("loch ness");
    assertThat(model.offered)
        .as("offered on every call of the turn, so the second sees what the first did")
        .allSatisfy(
            offers ->
                assertThat(offers)
                    .extracting(ToolOffer::name)
                    .containsExactly(new ToolName("lookup")));

    List<HistoryEntry> story = engine.history().entriesFrom(type, agentId, 0);
    assertThat(story).hasSize(5);
    assertThat(story.get(0)).isInstanceOf(HistoryEntry.ObservationReceived.class);
    assertThat(story.get(1))
        .isEqualTo(
            new HistoryEntry.InferenceRequestedActions(
                new Seq(2),
                new TurnId(1),
                List.of(
                    new Block.Commentary("Let me look."),
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))));
    assertThat(story.get(2))
        .as("the grant, written before the call was dispatched")
        .isEqualTo(
            new HistoryEntry.ToolApproved(
                new Seq(3), new TurnId(1), new CallId("call_1"), Optional.empty()));
    assertThat(story.get(3))
        .isEqualTo(
            new HistoryEntry.ToolSucceeded(
                new Seq(4),
                new TurnId(1),
                new CallId("call_1"),
                List.of(new Block.Text("the answer to loch ness"))));
    assertThat(story.get(4)).isEqualTo(HistoryEntry.InferenceAnswered.of(5, 1, "It is Loch Ness."));
    assertThat(story)
        .allSatisfy(
            entry ->
                assertThat(entry.turn())
                    .as("one turn throughout: asking for work does not start a new one")
                    .isEqualTo(new TurnId(1)));
  }

  /**
   * The generated schema is what the model is shown, so a tool that was bound has to arrive
   * describing its own arguments rather than an empty object.
   */
  @Test
  void aBoundToolIsOfferedWithASchemaGeneratedFromItsInputType() {
    AgentType type = new AgentType("tool-offer");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("no need")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(lookup(new ConcurrentLinkedQueue<>()))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "hello");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(agentId)).isEqualTo("Idle"));

    assertThat(model.offered.peek())
        .singleElement()
        .satisfies(
            offer -> {
              assertThat(offer.name()).isEqualTo(new ToolName("lookup"));
              assertThat(offer.description()).isEqualTo("looks a thing up");
              assertThat(offer.schema().json())
                  .as("generated from Query, so it names the property the model must write")
                  .contains("\"q\"");
            });
  }

  /**
   * A denied call still closes its turn. The whole risk of gating a tool is that saying no leaves
   * the agent holding an obligation nobody discharges -- so the thing worth proving is not that the
   * tool was skipped, but that the conversation carried on without it.
   */
  @Test
  void aDeniedCallDischargesTheTurnAndIsWrittenDownAsADenial() {
    AgentType type = new AgentType("tool-denied");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> asked = new ConcurrentLinkedQueue<>();
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))),
            new InferenceResult.Answer(
                HistoryEntry.InferenceAnswered.text("I was not allowed to look.")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(seen),
                            t ->
                                t.action(query -> "look up " + query.q() + " in the register")
                                    .approver(
                                        request -> {
                                          asked.add(request.action());
                                          return Awaited.ready(
                                              ApprovalResult.denied("out of hours"));
                                        }))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "what lake?");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(seen).as("denied means never attempted").isEmpty();
    assertThat(asked)
        .as("the approver was shown what the call would do, not what the tool is")
        .containsExactly("look up loch ness in the register");
    List<HistoryEntry> story = engine.history().entriesFrom(type, agentId, 0);
    assertThat(story.get(2))
        .as("a denial is written and no grant ever was")
        .isEqualTo(
            new HistoryEntry.ToolDenied(
                new Seq(3), new TurnId(1), new CallId("call_1"), "out of hours"));
    assertThat(story).noneMatch(HistoryEntry.ToolApproved.class::isInstance);
    assertThat(story.get(3))
        .isEqualTo(HistoryEntry.InferenceAnswered.of(4, 1, "I was not allowed to look."));
  }

  /** What an application configures per tool is what the tool is actually told. */
  @Test
  void aToolsOwnTimeoutIsWhatReachesIt() {
    AgentType type = new AgentType("tool-timeout");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<Duration> budget = new ConcurrentLinkedQueue<>();
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "slow", "{\"q\":\"x\"}"))));

    Tool<Query> slow =
        new Tool<>() {
          @Override
          public Class<Query> inputType() {
            return Query.class;
          }

          @Override
          public ToolName name() {
            return new ToolName("slow");
          }

          @Override
          public String description() {
            return "takes its time";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            budget.add(Duration.between(java.time.Instant.now(), request.deadline()));
            return Awaited.ready(ToolResult.ok(new Block.Text("eventually")));
          }
        };

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(slow, t -> t.timeout(Duration.ofMinutes(4)))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "go");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(agentStateOf(agentId)).isEqualTo("Idle"));

    assertThat(budget.peek())
        .as("four minutes, not the harness-wide thirty seconds")
        .isBetween(Duration.ofMinutes(3), Duration.ofMinutes(4));
  }

  /**
   * The property the grant entry exists to give: <b>every call that ran was approved, provable from
   * the rows alone.</b>
   *
   * <p>Not a restatement of the fold's logic. This reads the story the way an auditor would -- with
   * no access to the state machine, months later, after the agent is gone -- and asks whether
   * anything ran that nothing allowed. Without the grant written down the question is unanswerable,
   * and "the code would never do that" is the only assurance on offer.
   */
  @Test
  void everyCallThatRanHasAGrantBeforeItInTheStory() {
    AgentType type = new AgentType("tool-audit");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Actions(
                List.of(
                    new Block.ToolCall("call_1", "lookup", "{\"q\":\"one\"}"),
                    new Block.ToolCall("call_2", "lookup", "{\"q\":\"two\"}"))),
            new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("both done")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(lookup(new ConcurrentLinkedQueue<>()))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "two things");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    List<HistoryEntry> story = engine.history().entriesFrom(type, agentId, 0);
    for (HistoryEntry entry : story) {
      if (entry instanceof HistoryEntry.ToolSucceeded ran) {
        assertThat(
                story.stream()
                    .anyMatch(
                        before ->
                            before instanceof HistoryEntry.ToolApproved grant
                                && grant.callId().equals(ran.callId())
                                && grant.turn().equals(ran.turn())
                                && grant.seq().compareTo(ran.seq()) < 0))
            .as("call %s ran with nothing in the story allowing it", ran.callId())
            .isTrue();
      }
    }
    assertThat(story)
        .filteredOn(HistoryEntry.ToolApproved.class::isInstance)
        .as("one grant per call, and no more")
        .hasSize(2);
  }

  /**
   * A deferred approval parks: nothing is delivered, nothing is written, and the row stays.
   *
   * <p>The whole of parking, and there is deliberately no mechanism behind it. {@code
   * actionable_at} was pinned to the deadline when the row was claimed, so the effect comes due
   * exactly once more -- at the moment the agent stops being willing to wait -- and the failure
   * stored beside it discharges the call then. An unanswered question becomes a denial the model
   * reads, which is the only safe reading of silence.
   *
   * <p>What this pins is that the row is neither retired nor retried. Retiring would strand the
   * agent; retrying would ask a person the same question twice.
   */
  @Test
  void aDeferredApprovalParksTheCallAndExpiresIntoAFailure() {
    AgentType type = new AgentType("tool-deferred");
    AgentId agentId = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();
    ScriptedModel model =
        new ScriptedModel(
            new InferenceResult.Actions(
                List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}"))),
            new InferenceResult.Answer(
                HistoryEntry.InferenceAnswered.text("Nobody got back to me.")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(seen),
                            t ->
                                t.approver(
                                    request -> {
                                      // What a real one does: keep the address, go and ask
                                      // somebody, say nothing.
                                      handed.add(request.replyToken());
                                      return new Awaited.Deferred<>();
                                    },
                                    a -> a.timeout(Duration.ofSeconds(3))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));

    harness.observe(agentId, "what lake?");

    // Parked: the question was asked once, the row is still there, and the agent is waiting.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(handed)
                    .as("the approver was asked, and given somewhere to reply")
                    .hasSize(1));
    assertThat(agentStateOf(agentId))
        .as("still mid-turn, owing a call nobody has answered")
        .isEqualTo("AwaitingActions");
    assertThat(outstandingEffects(agentId))
        .as("the row stays: parking is the absence of retiring, not a new state")
        .isEqualTo(1);
    assertThat(seen).as("nothing ran while permission was outstanding").isEmpty();

    // And at the deadline it expires into the failure stored beside it.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(agentId)).isEqualTo("Idle");
              assertThat(outstandingEffects(agentId)).isZero();
            });

    assertThat(handed).as("asked once, never again -- a deferral is not a retry").hasSize(1);
    assertThat(seen).as("never authorised, so never run").isEmpty();
    List<HistoryEntry> story = engine.history().entriesFrom(type, agentId, 0);
    assertThat(story).noneMatch(HistoryEntry.ToolApproved.class::isInstance);
    assertThat(story.get(2))
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(HistoryEntry.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("call_1"));
              assertThat(failed.message()).contains("could not be authorised");
            });
  }

  /**
   * A parked call holds nothing this node needs.
   *
   * <p>The question worth answering before anyone ships this: if a handful of calls are sitting on
   * people, is the node finished? It is not, for two reasons that are independent of each other --
   * and the dispatcher is configured here with a single permit so that either one failing would
   * wedge this test rather than merely slow it.
   *
   * <p>The permit is released when the attempt returns, not when the row retires, and a deferral
   * returns immediately. And a parked row is {@code RUNNING} with {@code actionable_at} pinned to
   * its deadline, so the claim query -- which takes only rows already due -- cannot see it at all.
   * Neither threads nor claim slots are held by a question somebody is thinking about.
   */
  @Test
  void aParkedCallHoldsNeitherAPermitNorAClaimSlot() {
    AgentType type = new AgentType("tool-parked-capacity");
    AgentId waiting = new AgentId(UUID.randomUUID());
    AgentId working = new AgentId(UUID.randomUUID());
    ConcurrentLinkedQueue<ReplyToken> handed = new ConcurrentLinkedQueue<>();

    // Every inference asks for the one tool; whether the call parks is the approver's doing.
    InferenceProvider model =
        (request, _) ->
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
                ? new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"))
                : new InferenceResult.Actions(
                    List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"x\"}")));

    running(model);
    Harness<String> harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(type)
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(new ConcurrentLinkedQueue<>()),
                            t ->
                                t.approver(
                                    request -> {
                                      // Only the agent that is meant to wait actually waits. The
                                      // other is
                                      // answered on the spot, so the two share one permit and one
                                      // poller.
                                      if (request.agentId().equals(waiting)) {
                                        handed.add(request.replyToken());
                                        return new Awaited.Deferred<>();
                                      }
                                      return Awaited.ready(ApprovalResult.approved());
                                    },
                                    a -> a.timeout(Duration.ofMinutes(30))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.maxInFlight(1).pollInterval(Duration.ofMillis(50))));

    harness.observe(waiting, "park this one");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(handed).hasSize(1));

    // The parked question stands for half an hour. If it held the only permit, or if its row
    // were still claimable, nothing below would ever finish.
    harness.observe(working, "and answer this one");

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              assertThat(agentStateOf(working))
                  .as("a second agent ran to completion while the first sat parked")
                  .isEqualTo("Idle");
              assertThat(outstandingEffects(working)).isZero();
            });

    assertThat(agentStateOf(waiting))
        .as("and the parked one is exactly where it was")
        .isEqualTo("AwaitingActions");
    assertThat(outstandingEffects(waiting)).isEqualTo(1);
    assertThat(handed).as("asked once; never re-claimed and never re-asked").hasSize(1);
  }
}
