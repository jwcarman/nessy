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
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.jwcarman.nessy.engine.tool.Tools;
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

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  private static final AgentType TYPE = new AgentType("gated");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final ReplyTokens TOKENS = ReplyTokens.ephemeral();
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

  /**
   * When the effect's row says the call stands until, whatever the clock reads when it is handled.
   */
  private static final Instant WRITTEN_DEADLINE = Instant.parse("2026-09-08T12:07:30Z");

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
    return bound(approver, approvalTimeout, onAsking, Optional.empty());
  }

  private static Tools bound(
      Approver approver,
      Duration approvalTimeout,
      RetryPolicy onAsking,
      Optional<Stringifier<Query>> action) {
    return new Tools(
        List.of(
            new ToolBinding<>(
                tool(),
                JsonMapper.builder().build(),
                new JsonSchema("{\"type\":\"object\"}"),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                action,
                Optional.empty(),
                List.of(),
                approver,
                approvalTimeout,
                onAsking)));
  }

  /** A story holding one call, at seq 2, in turn 1. */
  private static ToolCalls story(String arguments) {
    return story(arguments, "Query[q=loch ness]");
  }

  /** The same story, with the action that was stored when the model asked. */
  private static ToolCalls story(String arguments, String storedAction) {
    Block.ToolCall call = new Block.ToolCall("c1", "lookup", arguments);
    return (agentId, requestSeq, callId) ->
        requestSeq.equals(new Seq(2)) && new CallId("c1").equals(callId)
            ? Optional.of(new ToolCalls.ResolvedCall(new TurnId(1), call, storedAction))
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
    return handler(tools, calls, Narrator.silent());
  }

  private ApprovalHandler handler(Tools tools, ToolCalls calls, Narrator narrator) {
    EffectTermsSource terms =
        new EffectTermsSource(
            tools,
            Duration.ofSeconds(30),
            new RetryPolicy.Never(),
            Duration.ofMinutes(10),
            new RetryPolicy.Never(),
            Duration.ofMinutes(5),
            new RetryPolicy.Never());
    return new ApprovalHandler(TYPE, tools, calls, TOKENS, narrator, terms, CLOCK);
  }

  private EffectOutcome ask(Tools tools) {
    return ask(tools, story());
  }

  private Awaited<EffectOutcome> asked(Tools tools, ToolCalls calls) {
    return asked(handler(tools, calls), CLOCK.instant().plus(Duration.ofMinutes(10)));
  }

  private Awaited<EffectOutcome> asked(ApprovalHandler handler, Instant deadline) {
    return handler.handle(
        AGENT,
        new AgentEffect.Approve(
            new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY),
        deadline);
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

  // ---- the deadline shown is the deadline kept ---------------------------------------------

  /**
   * The row was written before this clock reading and says when the call stands until. What the
   * approver is shown is that instant, not the clock plus the configured timeout.
   */
  @Test
  void the_approver_is_shown_the_deadline_the_effect_was_written_with() {
    ApprovalRequest[] seen = new ApprovalRequest[1];
    ApprovalHandler handler =
        handler(
            bound(
                request -> {
                  seen[0] = request;
                  return Awaited.ready(ApprovalResult.approved());
                }),
            story());

    asked(handler, WRITTEN_DEADLINE);

    assertThat(seen[0].deadline()).isEqualTo(WRITTEN_DEADLINE);
    assertThat(seen[0].askedAt()).isEqualTo(CLOCK.instant());
  }

  @Test
  void an_approver_that_defers_leaves_the_call_deferred() {
    ApprovalHandler handler = handler(bound(_ -> new Awaited.Deferred<>()), story());

    Awaited<EffectOutcome> awaited = asked(handler, WRITTEN_DEADLINE);

    assertThat(awaited).isInstanceOf(Awaited.Deferred.class);
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
   * The sentence a person consents to was written when the model asked, and is read back here,
   * never worked out again -- so what the binding's stringifier would say today is beside the
   * point.
   */
  @Test
  void the_question_carries_the_action_stored_with_the_call() {
    String[] seen = new String[1];
    ask(
        bound(
            request -> {
              seen[0] = request.action();
              return Awaited.ready(ApprovalResult.approved());
            },
            Duration.ofMinutes(10),
            new RetryPolicy.Never(),
            Optional.of(query -> "look up " + query.q() + " in the register")),
        story("{\"q\":\"loch ness\"}", "stored at request time"));

    assertThat(seen[0]).isEqualTo("stored at request time");
  }

  /** An enricher runs after the question is built, so it reads the stored sentence. */
  @Test
  void an_enricher_reads_the_stored_action() {
    String[] seen = new String[1];
    Tools tools =
        new Tools(
            List.of(
                new ToolBinding<>(
                    tool(),
                    JsonMapper.builder().build(),
                    new JsonSchema("{\"type\":\"object\"}"),
                    Duration.ofSeconds(30),
                    new RetryPolicy.Never(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(request -> seen[0] = request.action()),
                    Approver.allow(),
                    Duration.ofMinutes(10),
                    new RetryPolicy.Never())));

    ask(tools, story("{\"q\":\"loch ness\"}", "stored at request time"));

    assertThat(seen[0]).isEqualTo("stored at request time");
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

  /**
   * A gate exists to put what a call would do in front of somebody. When the action could not be
   * said there is no sentence to consent to, and a yes would run a call nobody could see, so nobody
   * is asked.
   */
  @Test
  void a_gated_call_whose_action_could_not_be_said_is_discharged_without_asking() {
    boolean[] asked = {false};
    Stringifier<Query> throwing =
        query -> {
          throw new IllegalStateException("boom");
        };

    EffectOutcome outcome =
        ask(
            bound(
                _ -> {
                  asked[0] = true;
                  return Awaited.ready(ApprovalResult.approved());
                },
                Duration.ofMinutes(10),
                new RetryPolicy.Never(),
                Optional.of(throwing)),
            story("{\"q\":\"loch ness\"}", "lookup (what it would do could not be said)"));

    assertThat(asked[0]).as("nothing to consent to").isFalse();
    assertThat(outcome)
        .isEqualTo(
            new EffectOutcome.ToolFailed(
                new CallId("c1"),
                CallFailure.FAILED,
                "what the call would do could not be described, so it was not put to an approver"));
  }

  /**
   * A gatherer that broke without saying why is still a gatherer that broke: the call is discharged
   * with a message, nobody is asked, and so nothing is approved to run.
   */
  @Test
  void an_enricher_that_throws_without_a_message_still_discharges_the_call() {
    boolean[] asked = {false};
    Tools tools =
        new Tools(
            List.of(
                new ToolBinding<>(
                    tool(),
                    JsonMapper.builder().build(),
                    new JsonSchema("{\"type\":\"object\"}"),
                    Duration.ofSeconds(30),
                    new RetryPolicy.Never(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(
                        _ -> {
                          throw new IllegalStateException();
                        }),
                    _ -> {
                      asked[0] = true;
                      return Awaited.ready(ApprovalResult.approved());
                    },
                    Duration.ofMinutes(10),
                    new RetryPolicy.Never())));

    EffectOutcome outcome = ask(tools);

    assertThat(asked[0]).as("nobody was asked, so nothing was approved").isFalse();
    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .satisfies(
            failed -> {
              assertThat(failed.callId()).isEqualTo(new CallId("c1"));
              assertThat(failed.message()).isNotNull().isNotBlank();
            });
  }

  /**
   * Saying nothing is not failing to say: the name is a sentence, and a person can decide on it.
   */
  @Test
  void a_gated_call_whose_stringifier_said_nothing_is_asked_with_the_tools_name() {
    String[] seen = new String[1];

    EffectOutcome outcome =
        ask(
            bound(
                request -> {
                  seen[0] = request.action();
                  return Awaited.ready(ApprovalResult.approved());
                },
                Duration.ofMinutes(10),
                new RetryPolicy.Never(),
                Optional.of(query -> null)),
            story("{\"q\":\"loch ness\"}", "lookup"));

    assertThat(seen[0]).isEqualTo("lookup");
    assertThat(outcome).isEqualTo(new EffectOutcome.ToolApproved(new CallId("c1")));
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

  /**
   * The key was made when the call was recorded, and the question carries it unchanged, so the tool
   * that later runs the call can be matched to what was decided about it.
   */
  @Test
  void theQuestionCarriesTheCallsIdempotencyKey() {
    ApprovalRequest[] seen = new ApprovalRequest[1];
    ask(
        bound(
            request -> {
              seen[0] = request;
              return Awaited.ready(ApprovalResult.approved());
            }));

    assertThat(seen[0].idempotencyKey()).isEqualTo(KEY);
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
                new AgentEffect.Approve(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY));

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
                new AgentEffect.Approve(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY));

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

  /** Every way the gate discharges a call without asking is a failure of the call itself. */
  @Test
  void a_call_for_a_tool_that_is_not_bound_is_discharged_as_failed() {
    assertThat(ask(Tools.none()))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  @Test
  void a_call_missing_from_the_story_is_discharged_as_failed() {
    assertThat(ask(bound(Approver.allow()), (agentId, requestSeq, callId) -> Optional.empty()))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  @Test
  void a_call_with_unreadable_arguments_is_discharged_as_failed() {
    assertThat(ask(bound(Approver.allow()), story("{\"q\": ")))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  @Test
  void a_call_whose_action_could_not_be_said_is_discharged_as_failed() {
    Stringifier<Query> throwing =
        query -> {
          throw new IllegalStateException("boom");
        };

    EffectOutcome outcome =
        ask(
            bound(
                Approver.allow(),
                Duration.ofMinutes(10),
                new RetryPolicy.Never(),
                Optional.of(throwing)),
            story("{\"q\":\"loch ness\"}", "lookup (what it would do could not be said)"));

    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }
}
