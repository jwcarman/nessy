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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * I2: {@code AgentRuntime.perform}'s {@code parked()} branch -- the re-run / second-reply-token
 * defect {@code ReminderExpiryTest} used to prove before it was deleted with the reminder
 * machinery. A call's own TERM lapsing (not its watchdog) must dispatch {@code DeadlinePassed}
 * directly; re-attempting the underlying {@code RunTool}/{@code AskApprover} payload would re-run a
 * tool a person or a webhook may still answer.
 */
@DisplayName("A parked call whose term lapses")
class ParkedTermTest {

  record Args() {}

  private record Captured(AgentId agentId, Input input) {}

  @Test
  @DisplayName(
      "a parked row past its term dispatches DeadlinePassed -- the tool is NOT run a second time"
          + " and NO second reply token is minted")
  void a_lapsed_term_dispatches_deadline_passed_without_rerunning_the_tool() {
    AgentType type = AgentType.of("parked-term");
    AtomicInteger toolInvocations = new AtomicInteger();
    AtomicInteger tokensMinted = new AtomicInteger();
    Instant term = Instant.now().plus(Duration.ofMillis(50));
    ToolBinding<Args> binding =
        new ToolBinding<>(
            deferringTool(term, toolInvocations, tokensMinted),
            Approver.always(),
            ActionRenderer.byToString());
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        Engines.of(
            type,
            Engines.stalled(),
            List.of(binding),
            Runnable::run,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-parked-term");
    TurnId turnId = TurnId.of("turn-parked-term");
    CallId callId = CallId.of("call-parked-term");
    askAsking(parts, agentId, turnId, callId, "defer_tool");
    EffectId effectId =
        parts
            .effects()
            .insert(
                type,
                agentId,
                turnId,
                callId,
                0,
                EffectStore.PAYLOADS.encode(new Effect.RunTool(callId, "defer_tool")),
                null);
    parts.effects().attempt(type, 100, Instant.now(), Duration.ofMinutes(1));

    // Attempt #1: a real RunTool. The tool defers, and this test parks the row exactly as
    // Transition does on Input.ToolParked (see EffectStore#park's own javadoc).
    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            turnId,
            new Effect.RunTool(callId, "defer_tool"),
            effectId,
            0);
    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).input()).isInstanceOf(Input.ToolParked.class);
    boolean parked = parts.effects().park(type, agentId, turnId, callId, term);
    assertThat(parked).as("the row was RUNNING, so park() actually moved it").isTrue();
    assertThat(toolInvocations).hasValue(1);
    assertThat(tokensMinted).hasValue(1);
    seen.clear();

    // The term lapses. A later pass finds the row PARKED and due -- attempt()'s Attempted#parked()
    // is what tells AgentRuntime#perform apart a lapsed TERM from an ordinary watchdog timeout.
    List<EffectStore.Attempted> due =
        parts.effects().attempt(type, 100, term.plus(Duration.ofSeconds(1)), Duration.ofMinutes(1));
    assertThat(due).extracting(EffectStore.Attempted::id).containsExactly(effectId);
    assertThat(due.get(0).parked()).isTrue();

    // Wired against the REAL EffectWorker::perform, exactly as AgentRuntime is in production --
    // this is what AgentRuntime.perform's parked() branch has to prove it never reaches. A fresh
    // AgentRuntime with its own (unrelated) dispatcher is deliberate: the parked() branch's
    // dispatch is AgentRuntime's OWN direct call, not a hop through EffectWorker's dispatcher, so
    // this test's real evidence is the counters below, not what this second runtime's dispatch
    // happens to do with an agent this test never fully constructed a real awaiting state for.
    AgentRuntime runtime =
        new AgentRuntime(
            type,
            transitionFor(parts, type),
            parts.effectWorker()::perform,
            Runnable::run,
            Traces.noop());

    runtime.perform(agentId, AgentState.idle().taking(turnId, "obs"), due.get(0));

    assertThat(toolInvocations)
        .as(
            "the tool was NOT invoked a second time -- perform's parked() branch never reached"
                + " EffectWorker#runTool at all")
        .hasValue(1);
    assertThat(tokensMinted).as("NO second reply token was minted").hasValue(1);
  }

  private Transition transitionFor(Engines.Parts parts, AgentType type) {
    return new Transition(
        type,
        parts.store(),
        parts.effects(),
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                parts.dataSource())));
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

  private static Tool<Args> deferringTool(
      Instant expiresAt, AtomicInteger invocations, AtomicInteger tokensMinted) {
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
        return "defer_tool";
      }

      @Override
      public String description() {
        return "defers, and hands its reply token to a pretend webhook";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
        invocations.incrementAndGet();
        call.replyToken();
        tokensMinted.incrementAndGet();
        return new Awaited.Deferred<>(expiresAt);
      }
    };
  }
}
