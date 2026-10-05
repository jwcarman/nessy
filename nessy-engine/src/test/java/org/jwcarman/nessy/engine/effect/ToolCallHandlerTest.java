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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.jwcarman.nessy.engine.tool.Tools;
import tools.jackson.databind.json.JsonMapper;

/**
 * One property, tested from every angle that could break it: <b>every path out of this handler
 * discharges the call.</b>
 *
 * <p>Not defensiveness. A call with no result makes the conversation unsendable to any provider, so
 * an agent that loses one is not degraded -- it is stuck, permanently, with no way back. Each test
 * here is one realistic way to lose a result: the tool does not exist, the arguments do not parse,
 * the story does not have the call. All of them are things a live model will cause.
 */
class ToolCallHandlerTest {

  /** Any key: the tests here are not about which one a call gets. */
  private static final IdempotencyKey KEY =
      IdempotencyKey.of(UUID.fromString("01999999-0000-7000-8000-000000000001"));

  private static final AgentType TYPE = new AgentType("tools");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Payloads PAYLOADS =
      new InMemoryPayloads(new JacksonCodecFactory(JsonMapper.builder().build()));

  /**
   * When the effect's row says the call stands until, whatever the clock reads when it is handled.
   */
  private static final Instant WRITTEN_DEADLINE = Instant.parse("2026-09-08T12:07:30Z");

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

  record Query(String q) {}

  /** Answers with whatever it was asked, so a decode can be observed from the outside. */
  private static Tool<Query> echo() {
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
        return Awaited.ready(ToolResult.ok(new Block.Text("you said " + request.input().q())));
      }
    };
  }

  private static Tools bound(Tool<Query> tool) {
    return bound(tool, Approver.allow());
  }

  private static Tools bound(Tool<Query> tool, Approver approver) {
    return bound(tool, approver, Optional.empty());
  }

  private static Tools bound(
      Tool<Query> tool, Approver approver, Optional<Stringifier<ToolResult.Success>> result) {
    return new Tools(
        List.of(
            new ToolBinding<>(
                tool,
                JsonMapper.builder().build(),
                new JsonSchema("{\"type\":\"object\"}"),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                Optional.empty(),
                result,
                List.of(),
                approver,
                Duration.ofMinutes(10),
                new RetryPolicy.Never())));
  }

  /** A story that holds exactly one call, at seq 2. */
  private static ToolCalls story(Block.ToolCall call) {
    return (agentId, requestSeq, callId) ->
        requestSeq.equals(new Seq(2)) && call.id().equals(callId)
            ? Optional.of(new ToolCalls.ResolvedCall(new TurnId(1), call, "lookup"))
            : Optional.empty();
  }

  private static ToolCalls nothing() {
    return (agentId, requestSeq, callId) -> Optional.empty();
  }

  private static EffectTermsSource terms(Tools tools) {
    return new EffectTermsSource(
        tools,
        Duration.ofSeconds(30),
        new RetryPolicy.Never(),
        Duration.ofMinutes(10),
        new RetryPolicy.Never(),
        Duration.ofMinutes(5),
        new RetryPolicy.Never());
  }

  private Handled handled(Tools tools, ToolCalls calls) {
    return handled(tools, calls, CLOCK.instant().plus(Duration.ofSeconds(30)));
  }

  private Handled handled(Tools tools, ToolCalls calls, Instant deadline) {
    return new ToolCallHandler(TYPE, tools, calls, terms(tools), PAYLOADS)
        .handle(
            AGENT,
            new AgentEffect.CallTool(
                new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY),
            deadline);
  }

  private EffectOutcome handle(Tools tools, ToolCalls calls) {
    Handled handled = handled(tools, calls);
    assertThat(handled).isInstanceOf(Handled.Settled.class);
    return ((Handled.Settled) handled).outcome();
  }

  @Test
  void aCallThatRunsComesBackAsItsResult() {
    EffectOutcome outcome =
        handle(bound(echo()), story(new Block.ToolCall("c1", "lookup", "{\"q\":\"loch ness\"}")));

    assertThat(outcome)
        .isEqualTo(
            new EffectOutcome.ToolSucceeded(
                new CallId("c1"),
                PAYLOADS.forAgent(AGENT).put(List.of(new Block.Text("you said loch ness"))),
                "you said loch ness"));
  }

  @Nested
  class The_line_a_success_carries {

    @Test
    void a_success_carries_what_the_binding_says_it_returned() {
      Tools tools = bound(echo(), Approver.allow(), Optional.of(success -> "80 days"));

      EffectOutcome outcome =
          handle(tools, story(new Block.ToolCall("c1", "lookup", "{\"q\":\"loch ness\"}")));

      assertThat(outcome)
          .asInstanceOf(type(EffectOutcome.ToolSucceeded.class))
          .extracting(EffectOutcome.ToolSucceeded::rendered)
          .isEqualTo("80 days");
    }

    @Test
    void a_result_stringifier_that_throws_leaves_an_empty_line_and_the_call_still_succeeds() {
      Tools tools =
          bound(
              echo(),
              Approver.allow(),
              Optional.of(
                  success -> {
                    throw new IllegalStateException("no words for this");
                  }));

      EffectOutcome outcome =
          handle(tools, story(new Block.ToolCall("c1", "lookup", "{\"q\":\"loch ness\"}")));

      assertThat(outcome)
          .asInstanceOf(type(EffectOutcome.ToolSucceeded.class))
          .extracting(EffectOutcome.ToolSucceeded::rendered)
          .isEqualTo("");
    }
  }

  /**
   * Models ask for tools that do not exist -- a misremembered name, or configuration that changed
   * between the offer and the call. Telling one so is how it picks a different one.
   */
  @Test
  void anUnknownToolIsAFailureTheModelCanRead() {
    EffectOutcome outcome = handle(Tools.none(), story(new Block.ToolCall("c1", "lookup", "{}")));

    assertThat(outcome).isInstanceOf(EffectOutcome.ToolFailed.class);
    assertThat(((EffectOutcome.ToolFailed) outcome).callId())
        .as("named, so the fold can take it off the outstanding list")
        .isEqualTo(new CallId("c1"));
    assertThat(((EffectOutcome.ToolFailed) outcome).message()).contains("lookup");
  }

  /**
   * Arguments that do not match the schema are ordinary, and the useful answer is the complaint
   * itself. Throwing would route it through retry, which would run the identical bad arguments
   * again and reach the identical failure.
   */
  @Test
  void argumentsThatWillNotDecodeAreAFailureRatherThanARetry() {
    EffectOutcome outcome =
        handle(bound(echo()), story(new Block.ToolCall("c1", "lookup", "{\"q\": ")));

    assertThat(outcome).isInstanceOf(EffectOutcome.ToolFailed.class);
    assertThat(((EffectOutcome.ToolFailed) outcome).callId()).isEqualTo(new CallId("c1"));
  }

  /** A tool that fails and says nothing is still a failure the model can read, not a throw. */
  @Test
  void a_failure_without_a_message_comes_back_as_a_failure_and_does_not_throw() {
    Tool<Query> failsSilently =
        new Tool<>() {
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
            return "fails without a word";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            return Awaited.ready(new ToolResult.Failure(null));
          }
        };

    EffectOutcome outcome =
        handle(bound(failsSilently), story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")));

    assertThat(outcome)
        .isEqualTo(
            new EffectOutcome.ToolFailed(
                new CallId("c1"), CallFailure.FAILED, "the tool failed and gave no message"));
  }

  /**
   * An effect row and the story disagreeing is unrepairable, but the obligation is still real.
   * Dying here would leave the call outstanding forever, which is the one outcome with no way back.
   */
  @Test
  void aCallMissingFromTheStoryIsStillDischarged() {
    EffectOutcome outcome = handle(bound(echo()), nothing());

    assertThat(outcome).isInstanceOf(EffectOutcome.ToolFailed.class);
    assertThat(((EffectOutcome.ToolFailed) outcome).callId()).isEqualTo(new CallId("c1"));
  }

  /**
   * The row was written before this clock reading and says when the call stands until. The tool is
   * shown that instant, not the clock plus the binding's timeout.
   */
  @Test
  void the_tool_is_shown_the_deadline_the_effect_was_written_with() {
    Instant[] seen = new Instant[1];
    Tool<Query> records =
        new Tool<>() {
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
            return "records its deadline";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            seen[0] = request.deadline();
            return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
          }
        };

    handled(
        bound(records),
        story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")),
        WRITTEN_DEADLINE);

    assertThat(seen[0]).isEqualTo(WRITTEN_DEADLINE);
  }

  @Test
  void a_tool_that_defers_leaves_the_call_deferred() {
    Tool<Query> defers =
        new Tool<>() {
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
            return "will answer later";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            return new Awaited.Deferred<>();
          }
        };

    Handled handled =
        handled(
            bound(defers),
            story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")),
            WRITTEN_DEADLINE);

    assertThat(handled).isInstanceOf(Handled.Deferred.class);
  }

  /** A tool has no facts to keep: what is waited for is the tool's own answer. */
  @Test
  void a_deferred_tool_call_hands_back_a_deferral() {
    Tool<Query> defers =
        new Tool<>() {
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
            return "will answer later";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            return new Awaited.Deferred<>();
          }
        };

    Handled handled =
        handled(
            bound(defers),
            story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")),
            WRITTEN_DEADLINE);

    assertThat(handled).isEqualTo(Handled.deferred());
  }

  // ---- terms ---------------------------------------------------------------------------

  /**
   * The terms are read while the effect row is being written, before anything has been decoded or
   * looked up -- which is the whole reason the effect carries the tool's name. A handler that
   * answered with its own defaults would make {@code ToolConfig.timeout} decorative.
   */
  @Test
  void aBoundToolsOwnTermsAreWhatTheRowIsWrittenWith() {
    Tools tools =
        new Tools(
            List.of(
                new ToolBinding<>(
                    echo(),
                    JsonMapper.builder().build(),
                    new JsonSchema("{\"type\":\"object\"}"),
                    Duration.ofSeconds(90),
                    new RetryPolicy.FixedDelay(3, Duration.ofSeconds(1), Duration.ZERO),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Approver.allow(),
                    Duration.ofMinutes(10),
                    new RetryPolicy.Never())));

    EffectTerms resolved =
        new ToolCallHandler(TYPE, tools, nothing(), terms(tools), PAYLOADS)
            .termsFor(
                new AgentEffect.CallTool(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY));

    assertThat(resolved.timeout()).isEqualTo(Duration.ofSeconds(90));
    assertThat(resolved.retryPolicy()).isInstanceOf(RetryPolicy.FixedDelay.class);
  }

  /**
   * A call for a tool that is not bound still needs terms: it is about to be discharged with a
   * failure, and that discharge has to be written somewhere.
   */
  @Test
  void anUnboundToolFallsBackToTheHarnessTerms() {
    EffectTerms resolved =
        new ToolCallHandler(TYPE, Tools.none(), nothing(), terms(Tools.none()), PAYLOADS)
            .termsFor(
                new AgentEffect.CallTool(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("gone"), KEY));

    assertThat(resolved.timeout()).isEqualTo(Duration.ofSeconds(30));
  }

  /**
   * Both stored failures name the call. An outcome that could not say which call it answers
   * discharges nothing -- the fold matches on the id, so the call stays outstanding and the turn
   * can never close.
   */
  @Test
  void bothStoredFailuresNameTheCallTheyDischarge() {
    EffectTerms resolved =
        new ToolCallHandler(TYPE, Tools.none(), nothing(), terms(Tools.none()), PAYLOADS)
            .termsFor(
                new AgentEffect.CallTool(
                    new TurnId(1), new Seq(2), new CallId("c1"), new ToolName("lookup"), KEY));

    assertThat(resolved.undispatchable())
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
    assertThat(resolved.failed(new IllegalStateException("boom")))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
  }

  @Test
  void an_unknown_tool_is_reported_as_failed() {
    EffectOutcome outcome = handle(Tools.none(), story(new Block.ToolCall("c1", "lookup", "{}")));

    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  @Test
  void a_call_missing_from_the_story_is_reported_as_failed() {
    EffectOutcome outcome = handle(bound(echo()), nothing());

    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }

  @Test
  void a_tool_that_returns_a_failure_is_reported_as_failed() {
    Tool<Query> failing =
        new Tool<>() {
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
            return "fails with a reason";
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
            return Awaited.ready(new ToolResult.Failure("no such buyer"));
          }
        };

    EffectOutcome outcome =
        handle(bound(failing), story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")));

    assertThat(outcome)
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.FAILED);
  }
}
