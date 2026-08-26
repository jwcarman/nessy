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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentPhase;
import org.jwcarman.nessy.agent.ToolCallPhase;
import org.jwcarman.nessy.agent.store.SubstrateAgentPhaseStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * Spec §10, proof 1: the delivery worker only ever FOLDS an approval answer — it dispatches the
 * granted tool's own execution ({@code RegistryToolCallExecutor#runTool}) onto the harness's
 * executor and returns immediately, never blocking on the tool itself (see {@code DeliveryWorker}'s
 * own javadoc: "neither runs a tool"). A regression that ran the tool INLINE on the fold thread
 * instead of dispatching it through the executor would risk the approval delivery's own lease (30s)
 * elapsing mid-fold, inviting Continuum to treat the delivery as unacknowledged and hand it to a
 * second claim — which would dispatch {@code RunTool} a second time and run the tool twice.
 *
 * <p>Driven with a {@link PumpedExecutor} and a tool that blocks on a gate, rather than a
 * wall-clock sleep (fix round 1, finding 1): {@code RunTool}'s dispatch happens on the harness's
 * own background {@code ComputationScheduler} thread, and this test asserts the task lands on
 * {@code pump}'s queue — i.e. is dispatched THROUGH the executor rather than run inline — before
 * ever draining it. A tool merely sleeping proves nothing: an inline-executed tool would also
 * finish and leave {@code invocations == 1}, so the old version of this test could not fail against
 * the very regression it named (see the fix-round report for the red/green proof).
 */
class SlowApprovedToolRunsOnceTest {

  record NoInput() {}

  /** Counts invocations; blocks on {@code gate} once entered, after signalling {@code started}. */
  static final class GatedTool implements Tool<NoInput> {
    private final AtomicInteger invocations = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch gate = new CountDownLatch(1);

    @Override
    public String name() {
      return "slow_op";
    }

    @Override
    public String description() {
      return "a granted tool that blocks until released";
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
      invocations.incrementAndGet();
      started.countDown();
      awaitGate();
      return Awaited.ready(ToolResult.ok("done"));
    }

    int invocations() {
      return invocations.get();
    }

    /** Blocks up to 5s for {@link #execute} to have been entered; true once it has. */
    boolean awaitStarted() throws InterruptedException {
      return started.await(5, TimeUnit.SECONDS);
    }

    void release() {
      gate.countDown();
    }

    private void awaitGate() {
      try {
        gate.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final ActionContributor<NoInput, String> SLOW_ACTION = input -> "slow op";

  @Test
  void aSlowApprovedToolRunsExactlyOnce() throws InterruptedException {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var state =
        new SubstrateAgentPhaseStore(
            substrate, "svc", Clock.systemUTC(), TestMappers.plainlyPinned());
    var call = new ToolCall("c1", "slow_op", JsonNodeFactory.instance.objectNode());
    var tool = new GatedTool();
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("all done."))));

    var harness =
        Nessy.harness(
            h ->
                h.type("svc")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(tool, SLOW_ACTION, Approvers.defer()))
                    .substrate(substrate)
                    .executor(pump));
    try {
      harness.bind(AgentId.of("svc")).tell("please do the slow op");
      pump.pumpUntilQuiet();
      assertThat(state.load().value()).isInstanceOf(AgentPhase.AwaitingTools.class);

      // approve() only submits the drain (continuum-adoption spec §7): the fold, its commit of
      // ToolCallPhase.RunningTool, and its RunTool dispatch onto `pump` all happen on the harness's
      // own
      // background scheduler thread, asynchronous to this one.
      harness.approvals().approve(AgentId.of("svc"), "c1", "ops-desk", "");

      // (a) The task must land on `pump`'s queue — i.e. be dispatched THROUGH the executor —
      // before this test ever drains it. If RunTool were ever run inline on the fold thread
      // instead, nothing would land here and this would time out still quiet.
      long deadline = System.currentTimeMillis() + 5000;
      while (pump.isQuiet() && System.currentTimeMillis() < deadline) {
        Thread.sleep(5);
      }
      assertThat(pump.isQuiet())
          .as("RunTool must be dispatched onto the executor, not run inline on the fold thread")
          .isFalse();

      // (b) The fold already committed ToolCallPhase.RunningTool for c1 as part of THAT same state
      // write, strictly before the tool has been given any chance to run — Running is part of
      // the AgentTransition the fold produces, and dispatchEffects (which merely calls
      // executor.execute(...) here) only runs after the CAS write already succeeded.
      assertThat(callStatus(state, "c1")).isInstanceOf(ToolCallPhase.RunningTool.class);
      assertThat(tool.invocations()).isEqualTo(0); // not yet run — only dispatched

      // Now actually drain it, on a thread of its own: the tool blocks on its gate once entered,
      // so pumping it here would otherwise block this test thread indefinitely.
      Thread pumping = new Thread(pump::pumpUntilQuiet, "test-pump");
      pumping.start();
      assertThat(tool.awaitStarted()).isTrue();

      // Still exactly one invocation while blocked, and the phase is unchanged — nothing
      // re-dispatched RunTool a second time while the first was in flight.
      assertThat(tool.invocations()).isEqualTo(1);
      assertThat(callStatus(state, "c1")).isInstanceOf(ToolCallPhase.RunningTool.class);

      tool.release();
      pumping.join(5000);
      assertThat(pumping.isAlive()).isFalse();

      deadline = System.currentTimeMillis() + 5000;
      while (!(state.load().value() instanceof AgentPhase.Idle)
          && System.currentTimeMillis() < deadline) {
        pump.pumpUntilQuiet();
        Thread.sleep(20);
      }
      assertThat(state.load().value()).isEqualTo(new AgentPhase.Idle());
      assertThat(tool.invocations()).isEqualTo(1); // still exactly once after the turn completed
    } finally {
      tool.release(); // in case an assertion above failed before the release, so nothing hangs
      harness.shutdown();
    }
  }

  private static ToolCallPhase callStatus(SubstrateAgentPhaseStore state, String toolCallId) {
    AgentPhase phase = state.load().value();
    if (phase instanceof AgentPhase.AwaitingTools awaiting) {
      return awaiting.calls().get(toolCallId);
    }
    throw new IllegalStateException("expected AwaitingTools, was " + phase);
  }
}
