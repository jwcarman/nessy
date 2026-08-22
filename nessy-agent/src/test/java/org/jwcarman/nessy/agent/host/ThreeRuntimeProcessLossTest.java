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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.durable.CompletionDesk;
import org.jwcarman.nessy.agent.durable.SubstrateComputations;
import org.jwcarman.nessy.agent.memory.SubstrateMemory;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The full spec §9 process-loss sentence, all three legs: "create+dispatch in one runtime, complete
 * in a fresh one, fold advances in a third." {@link GrantSurvivalTest} exercises two runtimes
 * (grant, then drain); this test adds the missing middle leg — the completer is neither the
 * creating host nor the delivering host, and is not a host at all: a bare {@link
 * SubstrateComputations} + {@link CompletionDesk} pair built fresh over the shared substrate, with
 * no delivery worker anywhere near it. A wrong implementation that smuggled any in-process state (a
 * live callback, a cached continuation, a worker reference) across these hand-offs — rather than
 * reading everything back from the substrate — would fail this test: host A is closed and garbage
 * before completion happens, and the completer that drives completion is never wired to a worker at
 * all, so nothing but durable state can carry the result to host C.
 */
class ThreeRuntimeProcessLossTest {

  record NoInput() {}

  /** Durable and always defers: forces a computation record, never resolves inline. */
  static final class DeferringTool implements Tool<NoInput> {
    @Override
    public String name() {
      return "central_op";
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
      return Awaited.deferred();
    }
  }

  @Test
  @Timeout(30)
  void createDispatchOnHostACompleteOnABareRuntimeBAndDeliverOnHostC() throws InterruptedException {
    var substrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var call = new ToolCall("c1", "central_op", JsonNodeFactory.instance.objectNode());

    // Runtime A: create the computation and dispatch (the tool defers), then die.
    var pumpA = new PumpedExecutor();
    var providerA =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    try (var hostA =
        Nessy.autonomous()
            .type("ops")
            .provider(providerA)
            .settings(TestSettings.settings())
            .grants(ToolGrant.grant(new DeferringTool(), UsagePolicy.allow()))
            .substrate(substrate)
            .executor(pumpA)
            .build()) {
      hostA.post("central-scope", "please run the central op");
      pumpA.pumpUntilQuiet();
    }
    // hostA is now closed: its heartbeat thread stopped. The one pending computation exists only
    // as durable state in `substrate`.
    List<String> computationKeys = substrate.keys("computation", 10);
    assertThat(computationKeys).hasSize(1);
    var computationId = ComputationId.of(computationKeys.getFirst());

    // Runtime B: not a host at all — a bare backend + desk, wired to no worker, built fresh over
    // the same substrate. This is the "fresh SubstrateComputations+desk" leg of the §9 sentence.
    var backendB = new SubstrateComputations(substrate, mapper);
    var deskB = new CompletionDesk(backendB, () -> {});
    deskB.complete(computationId, ToolResult.ok("central op done"));

    assertThat(substrate.read("computation", computationId.value())).isEmpty();
    assertThat(substrate.keys("outbox", 10)).hasSize(1); // the completion survives as a delivery

    // Runtime C: a fresh host, knowing nothing of A or B, whose own heartbeat is the only thing
    // that ever drains the delivery.
    var pumpC = new PumpedExecutor();
    var providerC =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("all done."))));
    try (var hostC =
        Nessy.autonomous()
            .type("ops")
            .provider(providerC)
            .settings(TestSettings.settings())
            .grants(ToolGrant.grant(new DeferringTool(), UsagePolicy.allow()))
            .substrate(substrate)
            .executor(pumpC)
            .build()) {
      long deadline = System.currentTimeMillis() + 20_000;
      while (!substrate.keys("outbox", 10).isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
        pumpC.pumpUntilQuiet();
      }
      assertThat(substrate.keys("outbox", 10)).isEmpty();
    }

    var state = new SubstrateAgentStateStore(substrate, "central-scope", Clock.systemUTC(), mapper);
    assertThat(state.load().phase()).isEqualTo(new Phase.Idle());

    var memory = new SubstrateMemory(substrate, "central-scope", mapper);
    List<Message> transcript = memory.recall().messages();
    assertThat(transcript).hasSizeGreaterThanOrEqualTo(3);
    assertThat(transcript.get(2).content())
        .contains(new ToolResultBlock("c1", "central op done", false));
  }
}
