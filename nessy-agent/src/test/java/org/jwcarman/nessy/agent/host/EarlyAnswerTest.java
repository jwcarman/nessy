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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.CallStatus;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ActionContributor;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * Spec §10, proof 3: an approver free to answer the id it was just handed BEFORE {@code defer()}
 * returns to it — because {@code ComputationApprovalContext#defer()} folds {@code ApprovalDeferred}
 * synchronously, on the approver's own thread, before minting the outcome it returns (see that
 * class's own javadoc: "nobody can be told about a question the scope has not recorded, because
 * nobody has the id yet"). By the time the approver's early answer reaches the desk, the phase
 * already names the call {@code AwaitingApproval} — never {@code Pending} — so the delivery
 * worker's own early-delivery guard ({@code DeliveryWorker#isEarly}) never fires and the answer
 * resolves on the very first drain, rather than being thrown away and waiting out the approval
 * kind's 5s backoff.
 *
 * <p>Deliberately NOT built from {@code ScriptedApprover} (the resolution this task shipped under):
 * the kit's approver never touches a desk from inside {@code approve()}, so this is a one-off,
 * test-local approver instead.
 */
class EarlyAnswerTest {

  record NoInput() {}

  static final class NoopTool implements Tool<NoInput> {
    @Override
    public String name() {
      return "noop";
    }

    @Override
    public String description() {
      return "does nothing";
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
      return Awaited.ready(ToolResult.ok("done"));
    }
  }

  private static final ActionContributor<NoInput, String> ACTION = input -> "op";

  @Test
  void anAnswerFromInsideApproveBeforeDeferReturnsStillResolvesTheCall()
      throws InterruptedException {
    var pump = new PumpedExecutor();
    var substrate = new InMemorySubstrate();
    var state =
        new SubstrateAgentStateStore(
            substrate, "svc", Clock.systemUTC(), TestMappers.plainlyPinned());
    var call = new ToolCall("c1", "noop", JsonNodeFactory.instance.objectNode());
    var provider =
        new ScriptedModel(
            List.of(
                List.of(new ModelEvent.ToolUseEmitted(call, null)),
                List.of(new ModelEvent.TextChunk("all done."))));

    AtomicInteger earlyAnswers = new AtomicInteger();
    AtomicReference<Harness<String>> harnessRef = new AtomicReference<>();
    Approver earlyAnswering =
        context -> {
          ApprovalOutcome outcome = context.defer();
          if (outcome instanceof ApprovalOutcome.Deferred(ComputationId id)) {
            // defer() has already folded ApprovalDeferred and returned — the phase names this
            // call AwaitingApproval before this line runs.
            earlyAnswers.incrementAndGet();
            harnessRef.get().approvals().approve(id, "test", "");
          }
          return outcome;
        };

    var harness =
        Nessy.harness(
            h ->
                h.type("svc")
                    .model(provider)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(new NoopTool(), ACTION, earlyAnswering))
                    .substrate(substrate)
                    .executor(pump));
    harnessRef.set(harness);
    try {
      harness.bind(AgentId.of("svc")).tell("please do the op");

      long deadline = System.currentTimeMillis() + 5000;
      while (!(state.load().phase() instanceof Phase.Idle)
          && System.currentTimeMillis() < deadline) {
        pump.pumpUntilQuiet();
        Thread.sleep(20);
      }

      assertThat(state.load().phase()).isEqualTo(new Phase.Idle());
      assertThat(earlyAnswers.get()).isEqualTo(1);
    } finally {
      harness.shutdown();
    }
  }

  @Test
  void theByCoordinatesDoorAgainstAPendingCallThrows() {
    var substrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var state = new SubstrateAgentStateStore(substrate, "svc", Clock.systemUTC(), mapper);
    var call = new ToolCall("c1", "noop", JsonNodeFactory.instance.objectNode());
    Message turn = Message.assistant(List.<ContentBlock>of(new ToolUseBlock(call, null)));
    Phase phase =
        new Phase.AwaitingTools(
            turn, Map.of("c1", new CallStatus.Pending()), ModelResponseId.of("response-1"));
    state.save(new State(phase, state.load().version()));

    var harness =
        Nessy.harness(
            h ->
                h.type("svc")
                    .model(new ScriptedModel(List.of()))
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .grants(ToolGrant.grant(new NoopTool(), ACTION, Approvers.defer()))
                    .substrate(substrate));
    try {
      assertThatThrownBy(() -> harness.approvals().approve(AgentId.of("svc"), "c1", "ops", ""))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not awaiting approval");
    } finally {
      harness.shutdown();
    }
  }
}
