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
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ActionRenderer;
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
 * One property, tested from every angle that could break it: <b>every path out of this handler
 * discharges the call.</b>
 *
 * <p>Not defensiveness. A call with no result makes the conversation unsendable to any provider, so
 * an agent that loses one is not degraded -- it is stuck, permanently, with no way back. Each test
 * here is one realistic way to lose a result: the tool does not exist, the arguments do not parse,
 * the story does not have the call. All of them are things a live model will cause.
 */
class ToolCallHandlerTest {

  private static final AgentType TYPE = new AgentType("tools");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final ReplyTokens TOKENS = ReplyTokens.ephemeral();
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
    return new Tools(
        List.of(
            new ToolBinding<>(
                tool,
                JsonMapper.builder().build(),
                new InputSchema("{}"),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                ActionRenderer.byToString(),
                List.of(),
                approver,
                Duration.ofMinutes(10),
                new RetryPolicy.Never())));
  }

  /** A story that holds exactly one call, at seq 2. */
  private static ToolCalls story(Block.ToolCall call) {
    return (agentId, requestSeq, callId) ->
        requestSeq.equals(new Seq(2)) && call.id().equals(callId)
            ? Optional.of(new ToolCalls.ResolvedCall(new TurnId(1), call))
            : Optional.empty();
  }

  private static ToolCalls nothing() {
    return (agentId, requestSeq, callId) -> Optional.empty();
  }

  private Awaited<EffectOutcome> handled(Tools tools, ToolCalls calls) {
    return new ToolCallHandler(
            TYPE,
            tools,
            calls,
            TOKENS,
            Narrator.silent(),
            Duration.ofSeconds(30),
            new RetryPolicy.Never(),
            CLOCK)
        .handle(
            AGENT, new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));
  }

  private EffectOutcome handle(Tools tools, ToolCalls calls) {
    Awaited<EffectOutcome> awaited = handled(tools, calls);
    assertThat(awaited).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<EffectOutcome>) awaited).value();
  }

  @Test
  void aCallThatRunsComesBackAsItsResult() {
    EffectOutcome outcome =
        handle(bound(echo()), story(new Block.ToolCall("c1", "lookup", "{\"q\":\"loch ness\"}")));

    assertThat(outcome)
        .isEqualTo(
            new EffectOutcome.ToolSucceeded(
                new CallId("c1"), List.of(new Block.Text("you said loch ness"))));
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

  /** The deadline the tool is handed is the harness's budget, counted from now. */
  @Test
  void theToolIsToldWhenItsAnswerStopsBeingWanted() {
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

    handle(bound(records), story(new Block.ToolCall("c1", "lookup", "{\"q\":\"x\"}")));

    assertThat(seen[0]).isEqualTo(Instant.parse("2026-09-08T12:00:30Z"));
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
                    new InputSchema("{}"),
                    Duration.ofSeconds(90),
                    new RetryPolicy.FixedDelay(3, Duration.ofSeconds(1), Duration.ZERO),
                    ActionRenderer.byToString(),
                    List.of(),
                    Approver.allow(),
                    Duration.ofMinutes(10),
                    new RetryPolicy.Never())));

    EffectTerms terms =
        new ToolCallHandler(
                TYPE,
                tools,
                nothing(),
                TOKENS,
                Narrator.silent(),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                CLOCK)
            .termsFor(
                new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));

    assertThat(terms.timeout()).isEqualTo(Duration.ofSeconds(90));
    assertThat(terms.retryPolicy()).isInstanceOf(RetryPolicy.FixedDelay.class);
  }

  /**
   * A call for a tool that is not bound still needs terms: it is about to be discharged with a
   * failure, and that discharge has to be written somewhere.
   */
  @Test
  void anUnboundToolFallsBackToTheHarnessTerms() {
    EffectTerms terms =
        new ToolCallHandler(
                TYPE,
                Tools.none(),
                nothing(),
                TOKENS,
                Narrator.silent(),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                CLOCK)
            .termsFor(new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("gone")));

    assertThat(terms.timeout()).isEqualTo(Duration.ofSeconds(30));
  }

  /**
   * Both stored failures name the call. An outcome that could not say which call it answers
   * discharges nothing -- the fold matches on the id, so the call stays outstanding and the turn
   * can never close.
   */
  @Test
  void bothStoredFailuresNameTheCallTheyDischarge() {
    EffectTerms terms =
        new ToolCallHandler(
                TYPE,
                Tools.none(),
                nothing(),
                TOKENS,
                Narrator.silent(),
                Duration.ofSeconds(30),
                new RetryPolicy.Never(),
                CLOCK)
            .termsFor(
                new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));

    assertThat(terms.undispatchable())
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
    assertThat(terms.failed(new IllegalStateException("boom")))
        .asInstanceOf(type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::callId)
        .isEqualTo(new CallId("c1"));
  }
}
