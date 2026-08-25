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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
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
 * own javadoc: "neither runs a tool"). A regression that made the fold BLOCK until the tool
 * finished would risk the approval delivery's own lease (30s) elapsing mid-fold, inviting Continuum
 * to treat the delivery as unacknowledged and hand it to a second claim — which would dispatch
 * {@code RunTool} a second time and run the (slow) tool twice.
 *
 * <p>Driven deterministically with {@link PumpedExecutor}: the tool sleeps a small, real amount of
 * wall-clock time (representing "slow" without literally waiting out the 30s lease — the resolution
 * this task shipped under says the wall-clock must stay small), and the test pumps the executor
 * several times after approving, polling for the task to land rather than assuming a single pump
 * catches a fold that runs on the harness's own background {@code ComputationScheduler} thread.
 */
class SlowApprovedToolRunsOnceTest {

  record NoInput() {}

  /** Sleeps a little on every invocation and counts how many times it actually ran. */
  static final class SlowTool implements Tool<NoInput> {
    private final AtomicInteger invocations = new AtomicInteger();

    @Override
    public String name() {
      return "slow_op";
    }

    @Override
    public String description() {
      return "a granted tool that takes a moment to run";
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
      sleepQuietly();
      return Awaited.ready(ToolResult.ok("done"));
    }

    int invocations() {
      return invocations.get();
    }

    private static void sleepQuietly() {
      try {
        Thread.sleep(200);
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
        new SubstrateAgentStateStore(
            substrate, "svc", Clock.systemUTC(), TestMappers.plainlyPinned());
    var call = new ToolCall("c1", "slow_op", JsonNodeFactory.instance.objectNode());
    var tool = new SlowTool();
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
      assertThat(state.load().phase()).isInstanceOf(Phase.AwaitingTools.class);

      // approve() only submits the drain (continuum-adoption spec §7): the fold, and its RunTool
      // dispatch onto `pump`, happen on the harness's own background scheduler thread.
      harness.approvals().approve(AgentId.of("svc"), "c1", "ops-desk", "");

      long deadline = System.currentTimeMillis() + 5000;
      while (pump.isQuiet() && System.currentTimeMillis() < deadline) {
        Thread.sleep(5);
      }
      // The tool's own invocation happens synchronously inside this pump call and blocks the
      // pumping thread for its ~200ms "slowness" — the point under test: nothing else got to
      // dispatch RunTool a second time while that was in flight.
      pump.pumpUntilQuiet();

      // Pump twice more: if a regression ever caused the approval delivery's lease to be missed
      // and the answer redelivered, a second dispatch of RunTool would land on `pump` here and a
      // second SlowTool invocation would follow.
      pump.pumpUntilQuiet();
      pump.pumpUntilQuiet();

      assertThat(tool.invocations()).isEqualTo(1);

      deadline = System.currentTimeMillis() + 5000;
      while (!(state.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < deadline) {
        pump.pumpUntilQuiet();
        Thread.sleep(20);
      }
      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
      assertThat(tool.invocations()).isEqualTo(1); // still exactly once after the turn completed
    } finally {
      harness.shutdown();
    }
  }
}
