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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.CallStatus;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * Spec §10, proof 2: two approvals whose granted tools block do not starve the harness's shared
 * {@code ComputationScheduler} (two platform threads, pumping every scope's deliveries). The fold
 * that dispatches {@code RunTool} is fire-and-forget (see {@link SlowApprovedToolRunsOnceTest}'s
 * javadoc) — a regression that made it block on the tool itself would, with only two pool threads,
 * let two blocked tools occupy both and starve every other scope's own drain, including one with
 * nothing to do with either blocked call.
 *
 * <p>No {@link org.jwcarman.nessy.agent.support.PumpedExecutor} here on purpose: the two blocking
 * tools must actually be in flight — occupying real threads — while a third, wholly unrelated
 * scope's deferred tool is completed and folds to a finished turn, which requires the default
 * (unbounded, virtual-thread) executor so the blocked tool bodies do not themselves prevent the
 * unrelated scope's own tool invocation from starting.
 */
class PumpsAreNeverStarvedTest {

  record NoInput() {}

  /** Approved, then blocks on {@code gate} until the test releases it. */
  static final class BlockingTool implements Tool<NoInput> {
    private final String name;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);

    BlockingTool(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "blocks until released";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      started.countDown();
      await(gate);
      return Awaited.ready(ToolResult.ok("released"));
    }

    void release() {
      gate.countDown();
    }

    /** Blocks up to 5s for {@link #execute} to have been entered; true once it has. */
    boolean awaitStarted() throws InterruptedException {
      return started.await(5, TimeUnit.SECONDS);
    }

    /** Whether {@link #execute} has been entered — the gate itself may still be held. */
    boolean hasStarted() {
      return started.getCount() == 0;
    }

    private static void await(CountDownLatch latch) {
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Auto-approved, defers immediately, and is answered out of band through {@code completions()}.
   */
  static final class UnrelatedDeferringTool implements Tool<NoInput> {
    @Override
    public String name() {
      return "unrelated_op";
    }

    @Override
    public String description() {
      return "defers durably; answered out of band";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      context.defer();
      return Awaited.deferred();
    }
  }

  private static final ActionContributor<NoInput, String> ACTION = input -> "op";

  @Test
  void anUnrelatedDeferredCompletionFoldsWhileTwoApprovedToolsAreStillBlocked()
      throws InterruptedException {
    var substrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var callA = new ToolCall("c1", "block_a", JsonNodeFactory.instance.objectNode());
    var callB = new ToolCall("c1", "block_b", JsonNodeFactory.instance.objectNode());
    var callC = new ToolCall("c1", "unrelated_op", JsonNodeFactory.instance.objectNode());
    var toolA = new BlockingTool("block_a");
    var toolB = new BlockingTool("block_b");
    var toolC = new UnrelatedDeferringTool();

    var modelA =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(callA, null)),
                List.of(new ModelEvent.TextChunk("a done."))));
    var modelB =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(callB, null)),
                List.of(new ModelEvent.TextChunk("b done."))));
    var modelC =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(callC, null)),
                List.of(new ModelEvent.TextChunk("c done."))));

    var harnessA =
        harness("starve-a", modelA, substrate, ToolGrant.grant(toolA, ACTION, Approvers.defer()));
    var harnessB =
        harness("starve-b", modelB, substrate, ToolGrant.grant(toolB, ACTION, Approvers.defer()));
    var harnessC =
        harness("starve-c", modelC, substrate, ToolGrant.grant(toolC, ACTION, Approvers.allow()));
    try {
      var stateA = new SubstrateAgentStateStore(substrate, "svc-a", Clock.systemUTC(), mapper);
      var stateB = new SubstrateAgentStateStore(substrate, "svc-b", Clock.systemUTC(), mapper);
      var stateC = new SubstrateAgentStateStore(substrate, "svc-c", Clock.systemUTC(), mapper);

      harnessA.bind(AgentId.of("svc-a")).tell("please block a");
      harnessB.bind(AgentId.of("svc-b")).tell("please block b");
      harnessC.bind(AgentId.of("svc-c")).tell("please run the unrelated op");

      awaitAwaitingApproval(stateA, "c1");
      awaitAwaitingApproval(stateB, "c1");
      ComputationId execution = awaitAwaitingResult(stateC, "c1");

      // Approve both — each dispatches RunTool, and each tool immediately blocks on its own gate.
      harnessA.approvals().approve(AgentId.of("svc-a"), "c1", "ops", "");
      harnessB.approvals().approve(AgentId.of("svc-b"), "c1", "ops", "");
      assertThat(toolA.awaitStarted()).isTrue();
      assertThat(toolB.awaitStarted()).isTrue();

      // While both blocking tools are still in flight, complete the wholly unrelated deferred
      // tool. If the shared ComputationScheduler pool were starved by the two blocked folds, this
      // would never fold — svc-c's phase would sit at AwaitingResult(execution) forever.
      harnessC.completions().complete(execution, ToolResult.ok("unrelated done"));
      awaitPhase(stateC, Phase.Idle.class);

      // The blocked tools genuinely had not finished yet when svc-c completed.
      assertThat(toolA.hasStarted()).isTrue();
      assertThat(toolB.hasStarted()).isTrue();

      toolA.release();
      toolB.release();
      awaitPhase(stateA, Phase.Idle.class);
      awaitPhase(stateB, Phase.Idle.class);
    } finally {
      toolA.release();
      toolB.release();
      harnessA.shutdown();
      harnessB.shutdown();
      harnessC.shutdown();
    }
  }

  private static Harness<String> harness(
      String type, ScriptedModel model, InMemorySubstrate substrate, ToolGrant grant) {
    return Nessy.harness(
        h ->
            h.type(type)
                .model(model)
                .systemPrompt(TestSettings.SYSTEM_PROMPT)
                .settings(TestSettings.settings())
                .grants(grant)
                .substrate(substrate));
  }

  private static void awaitPhase(SubstrateAgentStateStore state, Class<? extends Phase> expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (!expected.isInstance(state.load().phase()) && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertThat(state.load().phase()).isInstanceOf(expected);
  }

  /**
   * Polls until {@code callId} is {@code AwaitingApproval}. Waiting for the PHASE to be {@code
   * AwaitingTools} is not enough: the call inside it is {@code Pending} until the {@code
   * SeekApproval} effect folds {@code ApprovalDeferred}, and the desk's by-coordinates door refuses
   * a {@code Pending} call loudly. Under a loaded suite that gap is wide enough to lose, which is
   * exactly how this test failed once on a full reactor build and never in isolation.
   */
  private static void awaitAwaitingApproval(SubstrateAgentStateStore state, String callId)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      if (state.load().phase() instanceof Phase.AwaitingTools awaiting
          && awaiting.calls().get(callId) instanceof CallStatus.AwaitingApproval) {
        return;
      }
      Thread.sleep(10);
    }
    throw new IllegalStateException("call " + callId + " never reached AwaitingApproval");
  }

  /** Polls until {@code callId} is {@code AwaitingResult} and returns the computation it names. */
  private static ComputationId awaitAwaitingResult(SubstrateAgentStateStore state, String callId)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      if (state.load().phase() instanceof Phase.AwaitingTools awaiting
          && awaiting.calls().get(callId) instanceof CallStatus.AwaitingResult awaitingResult) {
        return awaitingResult.tool();
      }
      Thread.sleep(10);
    }
    throw new IllegalStateException("call " + callId + " never reached AwaitingResult");
  }
}
