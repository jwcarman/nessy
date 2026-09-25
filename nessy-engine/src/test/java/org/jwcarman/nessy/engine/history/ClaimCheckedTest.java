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

package org.jwcarman.nessy.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.direct.InMemoryPayloads;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.ToolName;
import org.jwcarman.nessy.spi.store.PayloadStore;

/** Where content stops and the fold begins. */
@DisplayName("An outcome on its way into the fold")
class ClaimCheckedTest {

  private static final CallId CALL = new CallId("c1");

  private final PayloadStore payloads = new InMemoryPayloads();
  private final ClaimChecked checked = new ClaimChecked(payloads);

  private List<Block> behind(org.jwcarman.nessy.api.PayloadRef ref) {
    return switch (payloads.get(ref)) {
      case PayloadStore.Resolved.Found(List<Block> content) -> content;
      case PayloadStore.Resolved.Missing _ -> List.of();
    };
  }

  @Test
  @DisplayName("an answer goes behind a reference, and the command carries only that")
  void an_answer_is_put_away() {
    AgentCommand command =
        checked.command(new EffectOutcome.InferenceAnswered(List.of(new Block.Text("done"))));

    assertThat(command)
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(
                AgentCommand.CompleteInference.class))
        .extracting(AgentCommand.CompleteInference::outcome)
        .asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(
                AgentCommand.InferenceOutcome.Answered.class))
        .satisfies(
            answered ->
                assertThat(behind(answered.answer())).containsExactly(new Block.Text("done")));
  }

  /** A refusal and a failure carry no content, so there is nothing to put away. */
  @Test
  @DisplayName("a refusal and a failure cross as they are")
  void refusals_and_failures_are_words() {
    assertThat(checked.command(new EffectOutcome.InferenceRefused("self-harm")))
        .isEqualTo(
            new AgentCommand.CompleteInference(
                new AgentCommand.InferenceOutcome.Refused("self-harm")));

    Failure failure = new Failure.Rejected("too long");
    assertThat(checked.command(new EffectOutcome.InferenceFailed(failure)))
        .isEqualTo(
            new AgentCommand.CompleteInference(new AgentCommand.InferenceOutcome.Failed(failure)));
  }

  /**
   * The one thing a reference cannot stand in for: the fold has to know which calls it is waiting
   * for, so those come out beside it.
   */
  @Test
  @DisplayName("requested calls come out alongside the reference, named and in order")
  void requested_calls_are_named() {
    List<Block.ActionRequestContent> blocks =
        List.of(
            new Block.Commentary("thinking"),
            new Block.ToolCall(CALL, new ToolName("lookup"), "{}"),
            new Block.ToolCall(new CallId("c2"), new ToolName("send"), "{}"));

    AgentCommand command = checked.command(new EffectOutcome.InferenceRequestedActions(blocks));

    AgentCommand.InferenceOutcome.RequestedActions asked =
        (AgentCommand.InferenceOutcome.RequestedActions)
            ((AgentCommand.CompleteInference) command).outcome();
    assertThat(asked.calls())
        .as("prose is not a call, and order is the model's")
        .extracting(call -> call.toolName().value())
        .containsExactly("lookup", "send");
    assertThat(behind(asked.request())).as("all of it, prose included").hasSize(3);
  }

  @Test
  @DisplayName("a tool result goes behind a reference; a tool failure stays a sentence")
  void tool_outcomes() {
    AgentCommand succeeded =
        checked.command(new EffectOutcome.ToolSucceeded(CALL, List.of(new Block.Text("42 days"))));
    assertThat(((AgentCommand.CompleteToolCall) succeeded).outcome())
        .isInstanceOf(AgentCommand.ToolOutcome.Succeeded.class);

    // Not a reference: the model reads this one.
    assertThat(checked.command(new EffectOutcome.ToolFailed(CALL, "the ledger is down")))
        .isEqualTo(
            new AgentCommand.CompleteToolCall(
                CALL, new AgentCommand.ToolOutcome.Failed("the ledger is down")));
  }

  @Test
  @DisplayName("approvals cross with whatever record the desk kept")
  void approvals() {
    assertThat(checked.command(new EffectOutcome.ToolApproved(CALL, Optional.of("ticket-9"))))
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                CALL, new AgentCommand.ApprovalOutcome.Approved(Optional.of("ticket-9"))));

    assertThat(checked.command(new EffectOutcome.ToolDenied(CALL, "not today", Optional.empty())))
        .isEqualTo(
            new AgentCommand.CompleteApproval(
                CALL, new AgentCommand.ApprovalOutcome.Denied("not today", Optional.empty())));
  }
}
