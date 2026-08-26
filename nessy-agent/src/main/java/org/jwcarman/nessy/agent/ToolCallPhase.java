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
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Where one call's lifecycle stands (approval-lifecycle spec §2). States are named for what they
 * await; the acts that put a call there have their own past-tense names in {@link AgentEvent}. Two
 * states wait on Continuum and are one mechanism used twice: the state records the computation's
 * id, the delivery is recognised by it, and the call is never re-fired.
 *
 * <p>Not a "status" (deferral-by-callback spec §2.1): a status is a scalar label, and this carries
 * data AND behaviour — each state decides what its own call makes of a {@link ToolCallEvent}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ToolCallPhase.SeekingApproval.class, name = "seeking-approval"),
  @JsonSubTypes.Type(value = ToolCallPhase.AwaitingApproval.class, name = "awaiting-approval"),
  @JsonSubTypes.Type(value = ToolCallPhase.RunningTool.class, name = "running-tool"),
  @JsonSubTypes.Type(value = ToolCallPhase.AwaitingResult.class, name = "awaiting-result"),
  @JsonSubTypes.Type(value = ToolCallPhase.Completed.class, name = "completed"),
  @JsonSubTypes.Type(value = ToolCallPhase.Denied.class, name = "denied"),
  @JsonSubTypes.Type(value = ToolCallPhase.Failed.class, name = "failed")
})
public sealed interface ToolCallPhase {

  /**
   * This call's outcome, if it has one — the single answer to "is this call done?", stated once
   * here instead of once per {@code instanceof} at every site that asked.
   *
   * <p>{@link JsonIgnore} because it is derived (deferral-by-callback spec §8). Belt and braces,
   * measured 2026-08-26: these states are records, and Jackson's record support builds properties
   * from record COMPONENTS, so a no-arg method that is not one is already invisible — removing the
   * annotation changes no byte of the wire. What is NOT invisible is a derived method named like a
   * bean getter: a {@code getResult()} would emit a phantom {@code result} on every state. The
   * annotation is what makes that safe whatever the method is called.
   *
   * <p>Named {@code result()} (spec §6): each terminal's own record component is {@code block}, not
   * {@code result}, so the interface method and a record's own accessor no longer collide.
   */
  @JsonIgnore
  Optional<ToolResultBlock> result();

  /**
   * The effects recovery must re-fire for a call in this state (spec §6.1) — the re-fire rule,
   * stated ONCE. A state that has handed its work to Continuum, or that is done, owes nothing.
   *
   * <p>Takes the call because the effects carry it: the arguments live in the assistant turn's
   * tool-use block, never in the state.
   */
  @JsonIgnore
  List<Effect> outstanding(ToolCall call);

  /**
   * What this state makes of one fact about its own call (deferral-by-callback spec §6) — the one
   * exhaustive switch over {@link ToolCallEvent}, so adding an event breaks exactly one place, and
   * that place is where "what is the default for this?" is the right question.
   */
  default ToolCallTransition handle(ToolCallEvent event) {
    return switch (event) {
      case AgentEvent.ApprovalDeferred e -> onApprovalDeferred(e);
      case AgentEvent.ApprovalAnswered e -> onApprovalAnswered(e);
      case AgentEvent.ToolDeferred e -> onToolDeferred(e);
      case AgentEvent.ToolFinished e -> onToolFinished(e);
    };
  }

  /**
   * The default for every event a state does not name: DROP. Safe as a blanket default only because
   * {@link ToolCallEvent} is a sub-hierarchy — a state can never be handed an observation or a
   * model completion, so everything reaching here genuinely is unexpected for this state.
   */
  default ToolCallTransition onApprovalDeferred(AgentEvent.ApprovalDeferred event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferred}: dropped unless this state names it. */
  default ToolCallTransition onApprovalAnswered(AgentEvent.ApprovalAnswered event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferred}: dropped unless this state names it. */
  default ToolCallTransition onToolDeferred(AgentEvent.ToolDeferred event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferred}: dropped unless this state names it. */
  default ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
    return ToolCallTransition.dropped();
  }

  /** An admitted answer, from either of the two states that may take one. */
  private static ToolCallTransition answered(AgentEvent.ApprovalAnswered event) {
    ToolCall call = event.call();
    return switch (event.answer()) {
      case Approval.Approved _ ->
          ToolCallTransition.to(new RunningTool(), new Effect.RunTool(call));
      case Approval.Denied(var reason, var _) ->
          ToolCallTransition.to(new Denied(new ToolResultBlock(call.id(), reason, true)));
    };
  }

  /** An admitted result, from either of the two states that may take one. */
  private static ToolCallTransition finished(AgentEvent.ToolFinished event) {
    ToolCall call = event.call();
    return switch (event.outcome()) {
      case ToolOutcome.Returned(var result) when !result.isError() ->
          ToolCallTransition.to(
              new Completed(new ToolResultBlock(call.id(), result.content(), false)));
      case ToolOutcome.Returned(var result) ->
          ToolCallTransition.to(new Failed(new ToolResultBlock(call.id(), result.content(), true)));
      case ToolOutcome.Failed(var error) ->
          ToolCallTransition.to(new Failed(new ToolResultBlock(call.id(), error.message(), true)));
    };
  }

  /** Approval sought; no answer recorded. Re-fire re-seeks. */
  record SeekingApproval() implements ToolCallPhase {

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.SeekApproval(call));
    }

    @Override
    public ToolCallTransition onApprovalDeferred(AgentEvent.ApprovalDeferred event) {
      return ToolCallTransition.to(new AwaitingApproval(event.approval(), event.request()));
    }

    /**
     * Only an in-process answer: nothing has been parked, so a delivered id is one this call never
     * recorded — an orphan or a duplicate, and no amount of backoff makes it fold (spec §4).
     */
    @Override
    public ToolCallTransition onApprovalAnswered(AgentEvent.ApprovalAnswered event) {
      return event.approval().isEmpty() ? answered(event) : ToolCallTransition.dropped();
    }
  }

  /** The approver deferred; Continuum holds the ask. Never re-fired. */
  record AwaitingApproval(ComputationId approval, ApprovalRequest request)
      implements ToolCallPhase {
    public AwaitingApproval {
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
    }

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // Continuum holds the ask
    }

    /** This call admits the id it recorded and nothing else (spec §3). */
    @Override
    public ToolCallTransition onApprovalAnswered(AgentEvent.ApprovalAnswered event) {
      return event.approval().filter(approval::equals).isPresent()
          ? answered(event)
          : ToolCallTransition.dropped();
    }
  }

  /** Approved; the tool is executing. Re-fire re-runs. */
  record RunningTool() implements ToolCallPhase {

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.RunTool(call));
    }

    @Override
    public ToolCallTransition onToolDeferred(AgentEvent.ToolDeferred event) {
      return ToolCallTransition.to(new AwaitingResult(event.tool()));
    }

    /**
     * Only an in-process result: a {@code RunningTool} call names no computation, so a delivered id
     * is by definition one the scope knows nothing of. There is no timing gap to rescue — the door
     * ({@code ToolContext#defer}, tool-context-defer spec §2) folds {@code ToolDeferred} and
     * commits BEFORE it hands the id back, so nothing outside can hold an id this scope does not
     * already name. On the crash path the re-fired {@code RunTool} defers again, minting a SECOND
     * computation; the orphan's expiry then meets {@code AwaitingResult(id2)} — a mismatch,
     * correctly dropped there.
     */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().isEmpty() ? finished(event) : ToolCallTransition.dropped();
    }
  }

  /** The tool deferred; Continuum holds the result. Never re-fired. */
  record AwaitingResult(ComputationId tool) implements ToolCallPhase {
    public AwaitingResult {
      Objects.requireNonNull(tool, "tool must not be null");
    }

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // Continuum holds the result
    }

    /** This call admits the id it recorded and nothing else (spec §3). */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().filter(tool::equals).isPresent()
          ? finished(event)
          : ToolCallTransition.dropped();
    }
  }

  /**
   * The tool ran and returned successfully. Absorbing: every event for this call is dropped, which
   * is every default on this interface, so this record overrides none of them.
   */
  record Completed(ToolResultBlock block) implements ToolCallPhase {
    public Completed {
      Objects.requireNonNull(block, "block must not be null");
    }

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.of(block);
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // done
    }
  }

  /**
   * The approver denied this call. Absorbing, like {@link Completed}; {@code block} carries the
   * denial's reason as an error result, exactly as {@code Finished} once did.
   */
  record Denied(ToolResultBlock block) implements ToolCallPhase {
    public Denied {
      Objects.requireNonNull(block, "block must not be null");
    }

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.of(block);
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // done
    }
  }

  /** The tool ran and failed, or returned an error result. Absorbing, like {@link Completed}. */
  record Failed(ToolResultBlock block) implements ToolCallPhase {
    public Failed {
      Objects.requireNonNull(block, "block must not be null");
    }

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.of(block);
    }

    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(); // done
    }
  }
}
