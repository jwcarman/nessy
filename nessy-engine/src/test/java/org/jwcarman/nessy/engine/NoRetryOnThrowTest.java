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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.tool.ActionRenderer;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * R-AB: never retry a user's tool or approver -- retry only what the engine owns.
 *
 * <p>A tool (or an approver) that throws has RUN. Its outcome is a failure RESULT the model is
 * entitled to see and reason about, not an unfinished obligation -- and neither is idempotent in
 * general, so re-invoking one that may already have had a side effect is worse than not retrying at
 * all. Driven with a REAL {@code attempts} count (0, not the -1 sentinel), through the exact
 * overload {@code AgentRuntime.Performer} calls, because the defect this proves the absence of only
 * shows up once a policy is actually consulted: at the -1 sentinel, {@code retryDelay} is always
 * null already, and the two code paths (before and after this fix) are indistinguishable.
 */
@DisplayName("A tool or approver that throws never retries")
class NoRetryOnThrowTest {

  record Args() {}

  private record Captured(AgentId agentId, Input input) {}

  @Test
  @DisplayName(
      "a throwing tool fails once and folds -- no retry, no backoff, no policy, exactly one"
          + " attempt")
  void a_throwing_tool_fails_once_and_folds() {
    AgentType type = AgentType.of("throwing-tool");
    ToolBinding<Args> binding =
        new ToolBinding<>(throwingTool(), Approver.always(), ActionRenderer.byToString());
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        Engines.of(
            type,
            Engines.stalled(),
            List.of(binding),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-throwing-tool");
    TurnId turnId = TurnId.of("turn-throwing-tool");
    CallId callId = CallId.of("call-throwing-tool");
    askAsking(parts, agentId, turnId, callId, "boom_tool");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                turnId,
                callId,
                0,
                EffectStore.PAYLOADS.encode(new Effect.RunTool(callId, "boom_tool")),
                null);
    List<EffectStore.Attempted> attempted =
        parts
            .effects()
            .attempt(type, 100, java.time.Instant.now(), java.time.Duration.ofMinutes(1));
    assertThat(attempted).extracting(EffectStore.Attempted::id).containsExactly(effectId);

    // The overload AgentRuntime.Performer actually calls -- attempts=0, a REAL count, not the -1
    // sentinel that turns RetryPolicy consultation off (see the class javadoc).
    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            turnId,
            new Effect.RunTool(callId, "boom_tool"),
            effectId,
            0);

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).input()).isInstanceOf(Input.ToolCompleted.class);
    ToolResult result = decodedResult(parts, agentId, turnId, callId);
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) result).message()).contains("the tool exploded");

    // The regression this guards against: a throw that went through RetryPolicy would have called
    // EffectStore#retry, moving the row to PENDING and incrementing attempts to 1. Untouched here
    // -- still RUNNING, still attempts=0 -- is the proof it was never retried.
    assertThat(statusOf(parts, effectId)).isEqualTo("RUNNING");
    assertThat(attemptsOf(parts, effectId)).isZero();
  }

  @Test
  @DisplayName("a throwing approver fails the call -- it is NOT synthesized as a denial")
  void a_throwing_approver_fails_without_synthesizing_a_denial() {
    AgentType type = AgentType.of("throwing-approver");
    ToolBinding<Args> binding =
        new ToolBinding<>(neverRunTool(), throwingApprover(), ActionRenderer.byToString());
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        Engines.of(
            type,
            Engines.stalled(),
            List.of(binding),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-throwing-approver");
    TurnId turnId = TurnId.of("turn-throwing-approver");
    CallId callId = CallId.of("call-throwing-approver");
    askAsking(parts, agentId, turnId, callId, "gated_tool");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                turnId,
                callId,
                0,
                EffectStore.PAYLOADS.encode(new Effect.AskApprover(callId, "gated_tool")),
                null);
    parts.effects().attempt(type, 100, java.time.Instant.now(), java.time.Duration.ofMinutes(1));

    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            turnId,
            new Effect.AskApprover(callId, "gated_tool"),
            effectId,
            0);

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).input())
        .as("a ToolCompleted, never an ApprovalGiven synthesizing a denial nobody decided")
        .isInstanceOf(Input.ToolCompleted.class);
    ToolResult result = decodedResult(parts, agentId, turnId, callId);
    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) result).message()).contains("the approver exploded");
    assertThat(statusOf(parts, effectId)).isEqualTo("RUNNING");
    assertThat(attemptsOf(parts, effectId)).isZero();
  }

  private void askAsking(
      Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId, String toolName) {
    ToolCall call = new ToolCall(callId, toolName, JsonNodeFactory.instance.objectNode());
    org.jwcarman.codec.spi.Codec<List<ExchangeContentBlock>> askedCodec =
        JsonCodec.ofList(EngineMapper.INSTANCE, ExchangeContentBlock.class);
    parts
        .claims()
        .put(agentId, turnId, "asked", askedCodec.encode(List.of(new ToolCallBlock(call))));
  }

  private static ToolResult decodedResult(
      Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId) {
    byte[] payload =
        parts.claims().get(agentId, turnId, EffectWorker.resultKey(callId)).orElseThrow();
    return JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class).decode(payload);
  }

  private String statusOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(String.class)
        .single();
  }

  private int attemptsOf(Engines.Parts parts, EffectId effectId) {
    return JdbcClient.create(parts.dataSource())
        .sql("SELECT attempts FROM nessy_effect WHERE effect_id = ?")
        .param(effectId.value())
        .query(Integer.class)
        .single();
  }

  private static Tool<Args> throwingTool() {
    return new Tool<>() {
      @Override
      public Class<Args> inputType() {
        return Args.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return JsonNodeFactory.instance.objectNode();
      }

      @Override
      public String name() {
        return "boom_tool";
      }

      @Override
      public String description() {
        return "always throws";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
        throw new IllegalStateException("the tool exploded");
      }
    };
  }

  private static Tool<Args> neverRunTool() {
    return new Tool<>() {
      @Override
      public Class<Args> inputType() {
        return Args.class;
      }

      @Override
      public ObjectNode inputSchema() {
        return JsonNodeFactory.instance.objectNode();
      }

      @Override
      public String name() {
        return "gated_tool";
      }

      @Override
      public String description() {
        return "never runs -- the approver throws first";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
        throw new AssertionError("the approver should have failed this call before it ran");
      }
    };
  }

  private static Approver throwingApprover() {
    return request -> {
      throw new IllegalStateException("the approver exploded");
    };
  }
}
