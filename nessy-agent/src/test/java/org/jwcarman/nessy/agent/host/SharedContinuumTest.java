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
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
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
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@link HarnessConfig#continuum} seam, without a database: two harnesses handed the SAME
 * Continuum and the same substrate see each other's parked calls. A parks an approval-gated call
 * and is shut down, never having decided it; B — fresh objects, shared stores — approves it, claims
 * the delivery through its own pumps, and finishes the turn A started. This is the property the
 * since-deleted {@code ThreeRuntimeProcessLossTest} lost when each {@code finish()} began minting a
 * private Continuum, restored: computation state is now as shared as the caller makes it.
 */
class SharedContinuumTest {

  record RestartInput(String target) {}

  static final class RestartTool implements Tool<RestartInput> {

    @Override
    public String name() {
      return "restart_prod";
    }

    @Override
    public String description() {
      return "restarts a production target; requires human approval";
    }

    @Override
    public Class<RestartInput> inputType() {
      return RestartInput.class;
    }

    @Override
    public CompletionPolicy requiredCompletion() {
      return CompletionPolicy.DURABLE;
    }

    @Override
    public Awaited<ToolResult> execute(RestartInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted " + input.target()));
    }
  }

  private static final ActionContributor<RestartInput, String> RESTART_ACTION =
      input -> "restart " + input.target();

  private static final String SCOPE = "prod-eu";

  @Test
  void aCallParkedOnOneHarnessIsDeliveredByAnotherSharingItsContinuum() throws Exception {
    Substrate substrate = new InMemorySubstrate();
    Continuum shared =
        new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system());
    var state =
        new SubstrateAgentStateStore(
            substrate, SCOPE, Clock.systemUTC(), TestMappers.plainlyPinned());
    var requests = new CopyOnWriteArrayList<ApprovalRequest>();
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", SCOPE));

    // Harness A: asks, parks, and is gone before anyone decides.
    var pumpA = new PumpedExecutor();
    var modelA = new ScriptedModel(List.of(List.of(new ModelEvent.ToolUseEmitted(call, null))));
    var harnessA =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(modelA)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .continuum(shared)
                    .approvalNotifier(requests::add)
                    .executor(pumpA));
    harnessA.bind(AgentId.of(SCOPE)).tell("please restart " + SCOPE);
    pumpA.pumpUntilQuiet();
    assertThat(state.load().phase()).isInstanceOf(Phase.AwaitingTools.class);
    assertThat(requests).hasSize(1);
    harnessA.shutdown();

    // Harness B: fresh objects over the same two stores. Its model only ever sees the resumed turn.
    var pumpB = new PumpedExecutor();
    var modelB =
        new ScriptedModel(List.of(List.of(new ModelEvent.TextChunk("Done — prod-eu restarted."))));
    var harnessB =
        Nessy.harness(
            h ->
                h.type("ops")
                    .model(modelB)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(
                        ToolGrant.grant(
                            new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                    .substrate(substrate)
                    .continuum(shared)
                    .executor(pumpB));
    try {
      harnessB.approvals().approve(requests.getFirst().id());

      long deadline = System.currentTimeMillis() + 5000;
      while (!(state.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < deadline) {
        pumpB.pumpUntilQuiet();
        Thread.sleep(20);
      }

      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
      assertThat(modelB.requests()).hasSize(1);
    } finally {
      harnessB.shutdown();
    }
  }
}
