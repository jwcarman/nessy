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
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Where one call's lifecycle stands (approval-lifecycle spec §2). States are named for what they
 * await; the acts that put a call there have their own past-tense names in {@link AgentEvent}. Two
 * statuses wait on Continuum and are one mechanism used twice: the status records the computation's
 * id, the delivery is recognised by it, and the call is never re-fired.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CallStatus.Pending.class, name = "pending"),
  @JsonSubTypes.Type(value = CallStatus.AwaitingApproval.class, name = "awaiting-approval"),
  @JsonSubTypes.Type(value = CallStatus.Running.class, name = "running"),
  @JsonSubTypes.Type(value = CallStatus.AwaitingResult.class, name = "awaiting-result"),
  @JsonSubTypes.Type(value = CallStatus.Finished.class, name = "finished")
})
public sealed interface CallStatus {

  /**
   * This call's outcome, if it has one — the single answer to "is this call done?", stated once
   * here instead of once per {@code instanceof} at every site that asked.
   *
   * <p>{@link JsonIgnore} because it is derived: without it Jackson would invent a {@code
   * resultBlock} property on every state (deferral-by-callback spec §8).
   *
   * <p>Named for the type it returns rather than {@code result()} (spec §6) because {@link
   * Finished}'s own record component is {@code result}, and a record accessor cannot be widened to
   * {@code Optional<ToolResultBlock>}. Keeping the component preserves the persisted field name.
   */
  @JsonIgnore
  Optional<ToolResultBlock> resultBlock();

  /**
   * The effects recovery must re-fire for a call in this state (spec §6.1) — the re-fire rule,
   * stated ONCE. A state that has handed its work to Continuum, or that is done, owes nothing.
   *
   * <p>Takes the call because the effects carry it: the arguments live in the assistant turn's
   * tool-use block, never in the state.
   */
  @JsonIgnore
  List<Effect> outstanding(ToolCall call);

  /** Approval sought; no answer recorded. Re-fire re-seeks. */
  record Pending() implements CallStatus {

    @Override
    public Optional<ToolResultBlock> resultBlock() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.SeekApproval(call));
    }
  }

  /** The approver deferred; Continuum holds the ask. Never re-fired. */
  record AwaitingApproval(ComputationId approval, ApprovalRequest request) implements CallStatus {
    public AwaitingApproval {
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
    }

    @Override
    public Optional<ToolResultBlock> resultBlock() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // Continuum holds the ask
    }
  }

  /** Approved; the tool is executing. Re-fire re-runs. */
  record Running() implements CallStatus {

    @Override
    public Optional<ToolResultBlock> resultBlock() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.RunTool(call));
    }
  }

  /** The tool deferred; Continuum holds the result. Never re-fired. */
  record AwaitingResult(ComputationId tool) implements CallStatus {
    public AwaitingResult {
      Objects.requireNonNull(tool, "tool must not be null");
    }

    @Override
    public Optional<ToolResultBlock> resultBlock() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // Continuum holds the result
    }
  }

  /** An outcome exists — success, denial or failure. */
  record Finished(ToolResultBlock result) implements CallStatus {
    public Finished {
      Objects.requireNonNull(result, "result must not be null");
    }

    @Override
    public Optional<ToolResultBlock> resultBlock() {
      return Optional.of(result);
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // done
    }
  }
}
