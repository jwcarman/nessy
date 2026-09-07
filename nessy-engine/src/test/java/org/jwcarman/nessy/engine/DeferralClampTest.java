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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * A tool or an approver that asks to park a call farther into the future than {@code
 * EngineConfig#maxDeferral} allows.
 *
 * <p>{@code actionable_at} for a parked effect IS its deadline (design of record 2026-09-04, Task
 * 7) -- an unbounded deferral is now an unbounded row nothing will ever revisit, not merely an
 * inert far-future timer. Both call sites -- {@code askApprover} and {@code runTool} -- go through
 * the SAME private {@code EffectWorker#clampDeferral}, which is what these two nested groups
 * together prove: the same input (a request past the ceiling) produces the same output (a clamp to
 * the ceiling, with a warning) whichever kind of deferral it came from.
 */
@DisplayName("A deferral is clamped against EngineConfig#maxDeferral")
class DeferralClampTest {

  record Args() {}

  // Matches Engines' own hardcoded default -- see Engines#of's EffectWorker.Dependencies
  // construction -- rather than adding a maxDeferral parameter to that shared test fixture just
  // for this one test class.
  private static final Duration MAX_DEFERRAL = Duration.ofDays(30);

  private static ActorTestKit testKit;

  @BeforeAll
  static void start() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stop() {
    testKit.shutdownTestKit();
  }

  private record Captured(AgentId agentId, Input input) {}

  private Logger workerLogger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureLogs() {
    workerLogger = (Logger) org.slf4j.LoggerFactory.getLogger(EffectWorker.class);
    logs = new ListAppender<>();
    logs.start();
    workerLogger.addAppender(logs);
  }

  @AfterEach
  void stopCapturingLogs() {
    workerLogger.detachAppender(logs);
  }

  @Test
  @DisplayName("a tool's deferral past the ceiling is clamped to it, with a warning naming both")
  void a_tool_deferral_past_the_ceiling_is_clamped() {
    Instant requested = Instant.now().plus(Duration.ofDays(365));
    CallId callId = CallId.of("call-tool-over");
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        toolParts(
            "defer-tool-over",
            requested,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-tool-over");
    TurnId turnId = TurnId.of("turn-tool-over");
    askAsking(parts, agentId, turnId, callId, "defer_tool");

    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            new Effect.RunTool(callId, "defer_tool"),
            EffectId.next());

    assertThat(seen).hasSize(1);
    Input.ToolParked parked = (Input.ToolParked) seen.get(0).input();
    // Granted at most MAX_DEFERRAL from "now" -- checked as a window, not an exact instant,
    // since the clamp reads its own Instant.now() independently of this assertion's.
    assertThat(parked.expiresAt())
        .as("clamped to the ceiling, not the 365-day request")
        .isBeforeOrEqualTo(Instant.now().plus(MAX_DEFERRAL).plusSeconds(5))
        .isAfter(Instant.now().plus(MAX_DEFERRAL).minusSeconds(5));
    assertWarned(true);
  }

  @Test
  @DisplayName("a tool's deferral within the ceiling is granted as asked, with no warning")
  void a_tool_deferral_within_the_ceiling_is_granted_unchanged() {
    Instant requested = Instant.now().plus(Duration.ofHours(1));
    CallId callId = CallId.of("call-tool-under");
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        toolParts(
            "defer-tool-under",
            requested,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-tool-under");
    TurnId turnId = TurnId.of("turn-tool-under");
    askAsking(parts, agentId, turnId, callId, "defer_tool");

    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            new Effect.RunTool(callId, "defer_tool"),
            EffectId.next());

    assertThat(seen).hasSize(1);
    Input.ToolParked parked = (Input.ToolParked) seen.get(0).input();
    assertThat(parked.expiresAt()).isEqualTo(requested);
    assertWarned(false);
  }

  @Test
  @DisplayName(
      "an approver's deferral past the ceiling is clamped identically -- the same private method")
  void an_approval_deferral_past_the_ceiling_is_clamped() {
    Instant requested = Instant.now().plus(Duration.ofDays(365));
    CallId callId = CallId.of("call-approval-over");
    List<Captured> seen = new ArrayList<>();
    Engines.Parts parts =
        approverParts(
            "gated-over",
            requested,
            (agentId, input, completing, observability) -> seen.add(new Captured(agentId, input)));
    AgentId agentId = AgentId.of("house-approval-over");
    TurnId turnId = TurnId.of("turn-approval-over");
    askAsking(parts, agentId, turnId, callId, "gated_tool");

    parts
        .effectWorker()
        .perform(
            agentId,
            AgentState.idle().taking(turnId, "obs"),
            new Effect.AskApprover(callId, "gated_tool"),
            EffectId.next());

    assertThat(seen).hasSize(1);
    Input.ToolParked parked = (Input.ToolParked) seen.get(0).input();
    assertThat(parked.expiresAt())
        .as("clamped to the ceiling, not the 365-day request")
        .isBeforeOrEqualTo(Instant.now().plus(MAX_DEFERRAL).plusSeconds(5))
        .isAfter(Instant.now().plus(MAX_DEFERRAL).minusSeconds(5));
    assertWarned(true);
  }

  private void assertWarned(boolean expected) {
    boolean warned =
        logs.list.stream()
            .anyMatch(
                event ->
                    event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("clamped"));
    assertThat(warned).isEqualTo(expected);
  }

  private Engines.Parts toolParts(String agentTypeName, Instant expiresAt, Dispatcher dispatcher) {
    Tool<Args> tool = deferringTool(expiresAt);
    ToolBinding<Args> binding =
        new ToolBinding<>(tool, Approver.always(), ActionRenderer.byToString());
    return partsWith(agentTypeName, binding, dispatcher);
  }

  private Engines.Parts approverParts(
      String agentTypeName, Instant expiresAt, Dispatcher dispatcher) {
    Tool<Args> tool = neverRunTool();
    ToolBinding<Args> binding =
        new ToolBinding<>(tool, deferringApprover(expiresAt), ActionRenderer.byToString());
    return partsWith(agentTypeName, binding, dispatcher);
  }

  private Engines.Parts partsWith(
      String agentTypeName, ToolBinding<Args> binding, Dispatcher dispatcher) {
    AgentType type = AgentType.of(agentTypeName);
    return Engines.of(
        testKit.system(), type, Engines.stalled(), List.of(binding), Runnable::run, dispatcher);
  }

  /** Writes the claim {@code callOf} reads back, so a real call is found rather than "gone". */
  private void askAsking(
      Engines.Parts parts, AgentId agentId, TurnId turnId, CallId callId, String toolName) {
    ToolCall call = new ToolCall(callId, toolName, JsonNodeFactory.instance.objectNode());
    org.jwcarman.codec.spi.Codec<List<ExchangeContentBlock>> askedCodec =
        JsonCodec.ofList(EngineMapper.INSTANCE, ExchangeContentBlock.class);
    parts
        .claims()
        .put(agentId, turnId, "asked", askedCodec.encode(List.of(new ToolCallBlock(call))));
  }

  private static Tool<Args> deferringTool(Instant expiresAt) {
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
        return "defers every call";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
        return new Awaited.Deferred<>(expiresAt);
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
        return "never runs -- the approver defers first";
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
        throw new AssertionError("the approver should have parked this call before it ran");
      }
    };
  }

  private static Approver deferringApprover(Instant expiresAt) {
    return request -> new Awaited.Deferred<>(expiresAt);
  }
}
