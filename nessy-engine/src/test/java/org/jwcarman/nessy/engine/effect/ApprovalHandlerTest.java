package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.spi.narration.Narrator;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gate, on its own.
 *
 * <p>Two properties worth keeping apart. <b>Every call is asked about</b>, including the ones
 * nothing is gating -- one path into a running tool rather than two, of which the unexercised one
 * is what a misconfiguration would take. And <b>a verdict is never retried</b>: a denial is an
 * answer, and re-asking until somebody relents would eventually produce the answer the engine
 * wanted. Only the question failing to arrive is worth another attempt.
 */
class ApprovalHandlerTest {

  private static final AgentType TYPE = new AgentType("gated");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final ReplyTokens TOKENS = ReplyTokens.ephemeral();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

  record Query(String q) {}

  private static Tool<Query> tool() {
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
        return "looks things up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("never reached")));
      }
    };
  }

  private static Tools bound(Approver approver, Duration approvalTimeout, RetryPolicy onAsking) {
    return bound(approver, approvalTimeout, onAsking, ActionRenderer.byToString());
  }

  private static Tools bound(
      Approver approver,
      Duration approvalTimeout,
      RetryPolicy onAsking,
      ActionRenderer<Query> action) {
    return new Tools(
        List.of(
            new ToolBinding<>(
                tool(),
                JsonMapper.builder().build(),
                new InputSchema("{}"),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                action,
                List.of(),
                approver,
                approvalTimeout,
                onAsking)));
  }

  /** A story holding one call, at seq 2, in turn 1. */
  private static ToolCalls story(String arguments) {
    Block.ToolCall call = new Block.ToolCall("c1", "lookup", arguments);
    return (agentId, requestSeq, callId) ->
        requestSeq.equals(new Seq(2)) && new CallId("c1").equals(callId)
            ? Optional.of(new ToolCalls.ResolvedCall(new TurnId(1), call))
            : Optional.empty();
  }

  private static ToolCalls story() {
    return story("{\"q\":\"loch ness\"}");
  }

  private static Tools bound(Approver approver) {
    return bound(approver, Duration.ofMinutes(10), new RetryPolicy.Never());
  }

  private ApprovalHandler handler(Tools tools) {
    return handler(tools, story());
  }

  private ApprovalHandler handler(Tools tools, ToolCalls calls) {
    return new ApprovalHandler(
        TYPE,
        tools,
        calls,
        TOKENS,
        Narrator.silent(),
        Duration.ofMinutes(10),
        new RetryPolicy.Never(),
        CLOCK);
  }

  private EffectOutcome ask(Tools tools) {
    return ask(tools, story());
  }

  private Awaited<EffectOutcome> asked(Tools tools, ToolCalls calls) {
    return handler(tools, calls)
        .handle(
            AGENT, new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup")));
  }

  /** The answer, for the tests that expect one now. */
  private EffectOutcome ask(Tools tools, ToolCalls calls) {
    Awaited<EffectOutcome> awaited = asked(tools, calls);
    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<EffectOutcome>) awaited).value();
  }

  @Test
  void anApprovedCallComesBackAsPermissionRatherThanAResult() {
    assertThat(ask(bound(_ -> Awaited.ready(ApprovalResult.approved()))))
        .isEqualTo(new EffectOutcome.ToolApproved(new CallId("c1")));
  }

  @Test
  void aDeniedCallComesBackAsADenialCarryingTheReason() {
    assertThat(ask(bound(_ -> Awaited.ready(ApprovalResult.denied("out of hours")))))
        .isEqualTo(new EffectOutcome.ToolDenied(new CallId("c1"), "out of hours"));
  }

  /** The default is a real approver that says yes, not an absence to check for. */
  @Test
  void aToolWithNothingGatingItIsStillAsked() {
    assertThat(ask(bound(Approver.allow())))
        .isEqualTo(new EffectOutcome.ToolApproved(new CallId("c1")));
  }

  /**
   * Discharged here rather than waved through to fail one hop later, which would run the whole
   * lifecycle to reach a conclusion already in hand.
   */
  @Test
  void approvingACallForAToolThatIsNotBoundDischargesItInstead() {
    assertThat(ask(Tools.none()))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("c1"));
              assertThat(failed.message()).contains("lookup");
            });
  }

  /** Generous by nature, and unrelated to what the tool itself is worth waiting for. */
  @Test
  void theApproverIsToldWhenTheQuestionStopsStanding() {
    Instant[] seen = new Instant[1];
    ask(
        bound(
            request -> {
              seen[0] = request.deadline();
              return Awaited.ready(ApprovalResult.approved());
            },
            Duration.ofHours(2),
            new RetryPolicy.Never()));

    assertThat(seen[0]).isEqualTo(Instant.parse("2026-09-08T14:00:00Z"));
  }

  // ---- the question ----------------------------------------------------------------------

  /**
   * An approver that could not see what it was approving could only make a blanket yes or no.
   * Everything here is what a page renders and a person reads.
   */
  @Test
  void theApproverIsToldWhatItIsBeingAskedAbout() {
    ApprovalRequest[] seen = new ApprovalRequest[1];
    ask(
        bound(
            request -> {
              seen[0] = request;
              return Awaited.ready(ApprovalResult.approved());
            }));

    assertThat(seen[0].agentType()).isEqualTo(TYPE);
    assertThat(seen[0].agentId()).isEqualTo(AGENT);
    assertThat(seen[0].turn()).isEqualTo(new TurnId(1));
    assertThat(seen[0].callId()).isEqualTo(new CallId("c1"));
    assertThat(seen[0].toolName()).isEqualTo(new ToolName("lookup"));
    assertThat(seen[0].arguments()).isEqualTo("{\"q\":\"loch ness\"}");
    assertThat(seen[0].askedAt()).isEqualTo(Instant.parse("2026-09-08T12:00:00Z"));
    assertThat(seen[0].deadline()).isEqualTo(Instant.parse("2026-09-08T12:10:00Z"));
  }

  /**
   * The sentence a person consents to, rendered from the tool's own input type rather than the
   * model's JSON -- which is why the arguments have to be read before anybody is asked.
   */
  @Test
  void theActionSaysWhatTheCallWouldDoRatherThanWhatTheToolIs() {
    String[] seen = new String[1];
    ask(
        bound(
            request -> {
              seen[0] = request.action();
              return Awaited.ready(ApprovalResult.approved());
            },
            Duration.ofMinutes(10),
            new RetryPolicy.Never(),
            query -> "look up " + query.q() + " in the register"));

    assertThat(seen[0]).isEqualTo("look up loch ness in the register");
  }

  /** The default reads well for a record, which is what most inputs are. */
  @Test
  void theDefaultActionIsTheInputsOwnToString() {
    String[] seen = new String[1];
    ask(
        bound(
            request -> {
              seen[0] = request.action();
              return Awaited.ready(ApprovalResult.approved());
            }));

    assertThat(seen[0]).isEqualTo("Query[q=loch ness]");
  }

  /**
   * A gate exists to stop execution, and a call whose arguments will not read has no execution to
   * stop -- so there is nothing to ask about and nobody is asked.
   */
  @Test
  void aCallWithUnreadableArgumentsIsDischargedWithoutAskingAnyone() {
    boolean[] asked = {false};
    EffectOutcome outcome =
        ask(
            bound(
                _ -> {
                  asked[0] = true;
                  return Awaited.ready(ApprovalResult.approved());
                }),
            story("{\"q\": "));

    assertThat(asked[0]).as("nothing to consent to").isFalse();
    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("c1"));
              assertThat(failed.message()).contains("could not be read");
            });
  }

  /** The story and an effect row disagreeing is unrepairable, but the call is still owed one. */
  @Test
  void aCallMissingFromTheStoryIsStillDischarged() {
    assertThat(ask(bound(Approver.allow()), (agentId, requestSeq, callId) -> Optional.empty()))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
  }

  /** The reply address is a credential, and a log is not where one belongs. */
  @Test
  void theReplyAddressIsNotInTheRequestsOwnToString() {
    ApprovalRequest[] seen = new ApprovalRequest[1];
    ask(
        bound(
            request -> {
              seen[0] = request;
              return Awaited.ready(ApprovalResult.approved());
            }));

    assertThat(seen[0].replyToken()).isNotNull();
    assertThat(seen[0].toString()).doesNotContain(seen[0].replyToken().value()).contains("lookup");
  }

  /** Two turns can each produce a "call_1", so the turn is part of what names a call. */
  @Test
  void theCallKeyNamesTheTurnAsWellAsTheCall() {
    ApprovalRequest[] seen = new ApprovalRequest[1];
    ask(
        bound(
            request -> {
              seen[0] = request;
              return Awaited.ready(ApprovalResult.approved());
            }));

    assertThat(seen[0].callKey()).isEqualTo("1/c1");
  }

  // ---- terms ---------------------------------------------------------------------------

  /**
   * Asking has its own budget and its own policy, both separate from the tool's. A build that takes
   * five minutes may be waved through instantly; a one-second lookup may wait an hour for somebody
   * to read the question.
   */
  @Test
  void askingIsGovernedSeparatelyFromCalling() {
    EffectTerms terms =
        handler(
                bound(
                    Approver.allow(),
                    Duration.ofHours(1),
                    new RetryPolicy.FixedDelay(3, Duration.ofSeconds(1), Duration.ZERO)))
            .termsFor(
                new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup")));

    assertThat(terms.timeout())
        .as("an hour to answer, though the tool itself gets thirty seconds to run")
        .isEqualTo(Duration.ofHours(1));
    assertThat(terms.retryPolicy())
        .as("re-asking changes nothing in the world, so it may be widened")
        .isInstanceOf(RetryPolicy.FixedDelay.class);
  }

  /**
   * Both stored failures say the call could not be <em>authorised</em>, never that it was denied.
   * Nobody said no -- claiming otherwise would tell the model it was refused by someone who never
   * saw the question.
   */
  @Test
  void aQuestionThatNeverArrivedIsAFailureAndNotADenial() {
    EffectTerms terms =
        handler(bound(Approver.allow()))
            .termsFor(
                new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup")));

    assertThat(terms.undispatchable())
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("c1"));
              assertThat(failed.message()).contains("authorised");
            });
    assertThat(terms.failed(new IllegalStateException("unreachable")))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
  }
}
