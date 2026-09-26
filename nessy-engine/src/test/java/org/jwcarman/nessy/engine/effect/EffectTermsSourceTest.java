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

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.InputSchema;
import org.jwcarman.nessy.inference.tool.ToolName;
import tools.jackson.databind.json.JsonMapper;

/**
 * What this resolves is exactly what the three handlers used to resolve themselves, and this is the
 * one place it is asked for now: {@link ToolCallHandlerTest} and {@link ApprovalHandlerTest}
 * already prove a handler answers correctly through it, so what is worth proving here is the
 * source's own behaviour in isolation -- per tool from the binding, falling back to the
 * harness-wide defaults, and uniform for an inference.
 */
class EffectTermsSourceTest {

  private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);
  private static final RetryPolicy TOOL_RETRY = new RetryPolicy.Never();
  private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(10);
  private static final RetryPolicy APPROVAL_RETRY = new RetryPolicy.Never();
  private static final Duration INFERENCE_TIMEOUT = Duration.ofMinutes(5);
  private static final RetryPolicy INFERENCE_RETRY =
      new RetryPolicy.FixedDelay(3, Duration.ZERO, Duration.ZERO);

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
        return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
      }
    };
  }

  private static ToolBinding<Query> binding(Duration timeout, RetryPolicy retryPolicy) {
    return binding(timeout, retryPolicy, Duration.ofHours(1), new RetryPolicy.Never());
  }

  private static ToolBinding<Query> binding(
      Duration timeout, RetryPolicy retryPolicy, Duration approvalTimeout, RetryPolicy onAsking) {
    return new ToolBinding<>(
        tool(),
        JsonMapper.builder().build(),
        new InputSchema("{}"),
        timeout,
        retryPolicy,
        ActionRenderer.byToString(),
        List.of(),
        Approver.allow(),
        approvalTimeout,
        onAsking);
  }

  private static EffectTermsSource source(Tools tools) {
    return new EffectTermsSource(
        tools,
        TOOL_TIMEOUT,
        TOOL_RETRY,
        APPROVAL_TIMEOUT,
        APPROVAL_RETRY,
        INFERENCE_TIMEOUT,
        INFERENCE_RETRY);
  }

  @Nested
  class CallingATool {

    @Test
    void aBoundToolsOwnTermsWinOverTheHarnessDefaults() {
      Tools tools =
          new Tools(
              List.of(
                  binding(
                      Duration.ofSeconds(90),
                      new RetryPolicy.FixedDelay(3, Duration.ZERO, Duration.ZERO))));

      EffectTerms terms =
          source(tools)
              .termsFor(
                  new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("lookup")));

      assertThat(terms.timeout()).isEqualTo(Duration.ofSeconds(90));
      assertThat(terms.retryPolicy()).isInstanceOf(RetryPolicy.FixedDelay.class);
    }

    @Test
    void aNameWithNoBindingFallsBackToTheHarnessDefaults() {
      EffectTerms terms =
          source(Tools.none())
              .termsFor(
                  new AgentEffect.CallTool(new Seq(2), new CallId("c1"), new ToolName("gone")));

      assertThat(terms.timeout()).isEqualTo(TOOL_TIMEOUT);
      assertThat(terms.retryPolicy()).isEqualTo(TOOL_RETRY);
    }
  }

  @Nested
  class AskingForApproval {

    @Test
    void aBoundToolsOwnApprovalTermsWinOverTheHarnessDefaults() {
      Tools tools =
          new Tools(
              List.of(
                  binding(
                      TOOL_TIMEOUT,
                      TOOL_RETRY,
                      Duration.ofHours(2),
                      new RetryPolicy.FixedDelay(3, Duration.ZERO, Duration.ZERO))));

      EffectTerms terms =
          source(tools)
              .termsFor(
                  new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("lookup")));

      assertThat(terms.timeout()).isEqualTo(Duration.ofHours(2));
      assertThat(terms.retryPolicy()).isInstanceOf(RetryPolicy.FixedDelay.class);
    }

    @Test
    void aNameWithNoBindingFallsBackToTheHarnessApprovalDefaults() {
      EffectTerms terms =
          source(Tools.none())
              .termsFor(
                  new AgentEffect.Approve(new Seq(2), new CallId("c1"), new ToolName("gone")));

      assertThat(terms.timeout()).isEqualTo(APPROVAL_TIMEOUT);
      assertThat(terms.retryPolicy()).isEqualTo(APPROVAL_RETRY);
    }
  }

  @Nested
  class Inferring {

    @Test
    void oneAgentTypeCallsOneModelOnOneSetOfTerms() {
      EffectTerms terms = source(Tools.none()).termsFor(new AgentEffect.Infer());

      assertThat(terms.timeout()).isEqualTo(INFERENCE_TIMEOUT);
      assertThat(terms.retryPolicy()).isEqualTo(INFERENCE_RETRY);
    }

    /**
     * {@code Unknown}, not {@code Permanent}. A row reaches this blob by passing its deadline, and
     * the dispatcher does not ask whether anything was attempted first -- a crash mid-call leaves
     * exactly such a row, with the attempt already counted and a provider that may well have
     * answered. Permanent would assert the work did not happen; nobody knows that.
     */
    @Test
    void undispatchableSaysNobodyKnowsWhetherTheInferenceRan() {
      EffectOutcome outcome =
          source(Tools.none()).termsFor(new AgentEffect.Infer()).undispatchable();

      assertThat(outcome).isInstanceOf(EffectOutcome.InferenceFailed.class);
      assertThat(((EffectOutcome.InferenceFailed) outcome).failure())
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void aThrowThatEscapedIsAnUnknownFailureRatherThanAPermanentOne() {
      EffectOutcome outcome =
          source(Tools.none())
              .termsFor(new AgentEffect.Infer())
              .failed(new IllegalStateException("boom"));

      assertThat(outcome).isInstanceOf(EffectOutcome.InferenceFailed.class);
      assertThat(((EffectOutcome.InferenceFailed) outcome).failure())
          .isInstanceOf(Failure.Unknown.class);
    }
  }
}
