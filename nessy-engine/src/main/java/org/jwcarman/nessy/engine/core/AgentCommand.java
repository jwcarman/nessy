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
package org.jwcarman.nessy.engine.core;

import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.tool.CallId;

/**
 * What the harness asks of an {@link AgentState}. Five of them, and the same five whatever the
 * harness is.
 *
 * <p><b>Imperative name, past-tense payload.</b> {@code CompleteToolCall} is a request that the
 * state may decline; the {@code ToolSucceeded} inside it is a fact that already happened. Both
 * words are correct and both are in the right position.
 *
 * <p><b>Grouped by the effect they complete</b>, one apiece, rather than one command per outcome or
 * one envelope over all of them. An arm therefore knows from the command alone whether it is
 * concerned -- only the one that is looks inside -- and the switch it then does is real logic
 * rather than routing.
 *
 * <p><b>No payloads.</b> Content is claim-checked by the harness before it gets here.
 */
public sealed interface AgentCommand {

  /**
   * Begin a turn on this input.
   *
   * <p>Accepted only by {@link AgentState.Idle}. Queuing is the harness's business: it holds work
   * while the agent is busy and asks when the agent is not. The state refusing when busy is not
   * redundant with the harness not asking -- two harnesses can both read an idle state and both
   * ask, and this is what makes the loser harmless.
   */
  record StartTurn(PayloadRef input) implements AgentCommand {}

  /** Accept nothing further. Work already in flight is still owed its outcome. */
  record Terminate() implements AgentCommand {}

  /** An inference came back. */
  record CompleteInference(InferenceOutcome outcome) implements AgentCommand {}

  /** An approval decision came back for one call. */
  record CompleteApproval(CallId callId, ApprovalOutcome outcome) implements AgentCommand {}

  /** A tool call came back. */
  record CompleteToolCall(CallId callId, ToolOutcome outcome) implements AgentCommand {}

  /** What an inference produced. */
  sealed interface InferenceOutcome {
    record Answered(PayloadRef answer) implements InferenceOutcome {}

    record Refused(String category) implements InferenceOutcome {}

    record Failed(Failure failure) implements InferenceOutcome {}

    record RequestedActions(PayloadRef request, List<ActionRequest> actions)
        implements InferenceOutcome {
      public RequestedActions {
        actions = List.copyOf(actions);
      }
    }
  }

  /** What an approver decided. */
  sealed interface ApprovalOutcome {
    record Approved(Optional<String> reference) implements ApprovalOutcome {}

    record Denied(String reason, Optional<String> reference) implements ApprovalOutcome {}
  }

  /** What a tool produced. */
  sealed interface ToolOutcome {
    record Succeeded(PayloadRef result) implements ToolOutcome {}

    /** Names what went wrong, never the values involved. See {@link AgentEvent.ToolFailed}. */
    record Failed(String message) implements ToolOutcome {}
  }
}
