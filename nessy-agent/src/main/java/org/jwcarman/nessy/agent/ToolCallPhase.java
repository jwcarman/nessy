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
  @JsonSubTypes.Type(value = ToolCallPhase.DeferringApproval.class, name = "deferring-approval"),
  @JsonSubTypes.Type(value = ToolCallPhase.AwaitingApproval.class, name = "awaiting-approval"),
  @JsonSubTypes.Type(value = ToolCallPhase.RunningTool.class, name = "running-tool"),
  @JsonSubTypes.Type(value = ToolCallPhase.DeferringResult.class, name = "deferring-result"),
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
      case AgentEvent.ApprovalDeferralRequested e -> onApprovalDeferralRequested(e);
      case AgentEvent.ApprovalDeferred e -> onApprovalDeferred(e);
      case AgentEvent.ApprovalAnswered e -> onApprovalAnswered(e);
      case AgentEvent.ToolCallDeferralRequested e -> onToolCallDeferralRequested(e);
      case AgentEvent.ToolCallDeferred e -> onToolCallDeferred(e);
      case AgentEvent.ToolFinished e -> onToolFinished(e);
    };
  }

  /**
   * The default for every event a state does not name: DROP. Safe as a blanket default only because
   * {@link ToolCallEvent} is a sub-hierarchy — a state can never be handed an observation or a
   * model completion, so everything reaching here genuinely is unexpected for this state.
   */
  default ToolCallTransition onApprovalDeferralRequested(
      AgentEvent.ApprovalDeferralRequested event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferralRequested}: dropped unless this state names it. */
  default ToolCallTransition onApprovalDeferred(AgentEvent.ApprovalDeferred event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferralRequested}: dropped unless this state names it. */
  default ToolCallTransition onApprovalAnswered(AgentEvent.ApprovalAnswered event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferralRequested}: dropped unless this state names it. */
  default ToolCallTransition onToolCallDeferralRequested(
      AgentEvent.ToolCallDeferralRequested event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferralRequested}: dropped unless this state names it. */
  default ToolCallTransition onToolCallDeferred(AgentEvent.ToolCallDeferred event) {
    return ToolCallTransition.dropped();
  }

  /** See {@link #onApprovalDeferralRequested}: dropped unless this state names it. */
  default ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
    return ToolCallTransition.dropped();
  }

  /**
   * An admitted approval deferral request, from either of the two states that may take one — the
   * state that asked, and the state that is already mid-handoff and is asking again (spec §9a).
   * Both produce the same thing: a fresh {@link DeferringApproval} and a fresh effect carrying this
   * request's callback and term.
   */
  private static ToolCallTransition deferringApproval(AgentEvent.ApprovalDeferralRequested event) {
    return ToolCallTransition.to(
        new DeferringApproval(),
        new Effect.DeferApproval(event.call(), event.request(), event.callback(), event.term()));
  }

  /** The tool side's {@link #deferringApproval}. */
  private static ToolCallTransition deferringResult(AgentEvent.ToolCallDeferralRequested event) {
    return ToolCallTransition.to(
        new DeferringResult(),
        new Effect.DeferToolCall(event.call(), event.callback(), event.term()));
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

    /**
     * The approver returned a deferral. Nothing is parked yet — no computation exists and no id
     * does either (spec §9a) — so the call moves to {@link DeferringApproval} and the handoff rides
     * an effect, which is the only thing that may carry the callback.
     */
    @Override
    public ToolCallTransition onApprovalDeferralRequested(
        AgentEvent.ApprovalDeferralRequested event) {
      return deferringApproval(event);
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

  /**
   * The approver deferred and the handoff is in flight: the {@code DeferApproval} effect is
   * creating the computation and running the callback, and until it folds {@code ApprovalDeferred}
   * NOBODY OUTSIDE KNOWS ANYTHING — not the id, because it may not exist yet, and not the question,
   * because the callback may not have run.
   *
   * <p><b>Carries nothing</b> (James, 2026-08-26). The callback cannot be written to state, and the
   * term need not be: after a restart the re-ask produces a fresh callback and a fresh term, so a
   * persisted one would only be a stale copy of something about to be replaced. What the variant
   * itself says — "this call is mid-approval-handoff" — is the whole of the recoverable fact.
   */
  record DeferringApproval() implements ToolCallPhase {

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    /**
     * <b>The ORIGINATING effect, never this state's own.</b> Recovery re-fires {@code SeekApproval}
     * — the ask runs again from the top — rather than re-firing the {@code DeferApproval} that was
     * in flight.
     *
     * <p>The reason is that {@code outstanding()} rebuilds instructions from PERSISTED state, and
     * {@code DeferApproval} carries a {@link org.jwcarman.nessy.api.tool.ComputationCallback} — a
     * closure. A closure is not persisted and cannot be reconstructed from anything that is, so
     * there is no honest way to re-issue that instruction. Re-asking is honest and it is also SAFE,
     * for the reason this state exists at all: until the callback has run, nothing outside has been
     * told anything, so doing it again tells nobody twice. The moment that stops being true is the
     * moment the call leaves for {@link AwaitingApproval}, which owes nothing.
     *
     * <p>Its cost is the at-least-once caveat a crash always carried (spec §9a): if the process
     * died AFTER the callback ran but before {@code ApprovalDeferred} folded, the world holds an id
     * this scope has forgotten, and the re-ask leaves it holding two. A crash tells us nothing at
     * all — not even that anything failed — so that is the honest trade. A callback that THREW is a
     * different case entirely and does not come here: it fails the call outright, because "it
     * threw" and "it never reached the world" are not the same knowledge.
     */
    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.SeekApproval(call));
    }

    /**
     * <b>Asked again</b> (spec §9a, the first mandatory cell). Recovery re-fired {@code
     * SeekApproval}, the approver ran again, and it returned a NEW deferral — a new closure, a new
     * term. This event is naturally admitted only from {@link SeekingApproval}; unless this state
     * admits it too and REPLACES itself, the re-ask can never land, and the call sits here forever
     * while every staleness tick re-fires an ask whose answer is thrown away. The new request wins:
     * its callback and its term are the ones the handoff carries.
     */
    @Override
    public ToolCallTransition onApprovalDeferralRequested(
        AgentEvent.ApprovalDeferralRequested event) {
      return deferringApproval(event);
    }

    /** The handoff succeeded: the callback ran, so the world may now know the id. */
    @Override
    public ToolCallTransition onApprovalDeferred(AgentEvent.ApprovalDeferred event) {
      return ToolCallTransition.to(new AwaitingApproval(event.approval(), event.request()));
    }

    /**
     * <b>The handoff never got a computation</b>: {@code create} threw, so nothing exists and
     * nobody was told. The failure is id-less, and must be — this state recorded no id — and it is
     * admitted here so the call goes terminal instead of sitting in a handoff that will never be
     * attempted again.
     *
     * <p>A callback that THREW does not arrive here: by then the park has folded and the call has
     * moved on to {@link AwaitingApproval}, which takes that failure riding the id it recorded
     * (§9a, the second mandatory cell).
     */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().isEmpty() ? finished(event) : ToolCallTransition.dropped();
    }
  }

  /**
   * The approver deferred; Continuum holds the ask. Never re-fired.
   *
   * <p><b>The agreed deadline rides the EVENT, not this state</b> — a deliberate narrowing of spec
   * §5's "the deadline rides the event into the state". The consumer §5 names is the
   * pending-approvals projection, and a projection reads the fact, not the phase; putting an {@link
   * java.time.Instant} on the wire here would need a JSR-310 Jackson module the pinned mapper does
   * not carry, and adding one is a new dependency. Nothing else reads it, so it stays on the fact.
   */
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

    /**
     * <b>The handoff's callback threw after the park committed</b> (spec §9a, the second mandatory
     * cell, under the 2026-08-26 ordering ruling). The effect folds the park BEFORE it tells
     * anyone, so by the time a callback can fail, the call is already here — and the failure rides
     * the id this state recorded, which is the only shape §3 lets it admit.
     *
     * <p>{@code DeferApproval}'s own catch is the sole producer: nothing else ever sends a tool
     * completion to a call awaiting approval, and one carrying an id this state did not record is
     * dropped like any other mismatch. It folds to {@code Failed} rather than {@code Denied}
     * because nobody decided anything — the machinery broke, which is what {@code Failed} means.
     * Relying instead on Continuum's delivery of the failed computation would fold a DENIAL, since
     * the worker maps a failed approval computation to one; that would tell the model a human said
     * no, which nobody did.
     */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().filter(approval::equals).isPresent()
          ? finished(event)
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

    /** The tool side's mirror of {@link SeekingApproval#onApprovalDeferralRequested}. */
    @Override
    public ToolCallTransition onToolCallDeferralRequested(
        AgentEvent.ToolCallDeferralRequested event) {
      return deferringResult(event);
    }

    /**
     * Only an in-process result: a {@code RunningTool} call names no computation, so a delivered id
     * is by definition one the scope knows nothing of. There is no timing gap to rescue — a tool
     * cannot hand out an id, because when it returns its deferral no id exists
     * (deferral-by-callback spec §9a). On the crash path the re-fired {@code RunTool} defers again,
     * minting a SECOND computation; the orphan's expiry then meets {@code AwaitingResult(id2)} — a
     * mismatch, correctly dropped there.
     */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().isEmpty() ? finished(event) : ToolCallTransition.dropped();
    }
  }

  /** The tool side's {@link DeferringApproval}, carrying nothing for the same reasons. */
  record DeferringResult() implements ToolCallPhase {

    @Override
    public Optional<ToolResultBlock> result() {
      return Optional.empty();
    }

    /**
     * The ORIGINATING effect — {@code RunTool} — never this state's own {@code DeferToolCall}. See
     * {@link DeferringApproval#outstanding(ToolCall)} for the whole argument; it is the same one,
     * about a closure that cannot be rebuilt from persisted state and a re-run that is safe
     * precisely because nobody outside has been told anything yet.
     */
    @Override
    public List<Effect> outstanding(ToolCall call) {
      return List.of(new Effect.RunTool(call));
    }

    /** Asked again — see {@link DeferringApproval#onApprovalDeferralRequested}. */
    @Override
    public ToolCallTransition onToolCallDeferralRequested(
        AgentEvent.ToolCallDeferralRequested event) {
      return deferringResult(event);
    }

    /** The handoff succeeded: the callback ran, so the world may now know the id. */
    @Override
    public ToolCallTransition onToolCallDeferred(AgentEvent.ToolCallDeferred event) {
      return ToolCallTransition.to(new AwaitingResult(event.tool()));
    }

    /**
     * The handoff never got a computation — see {@link DeferringApproval#onToolFinished}. The
     * id-carrying case belongs to {@link AwaitingResult}, which this call reaches the moment the
     * park folds.
     */
    @Override
    public ToolCallTransition onToolFinished(AgentEvent.ToolFinished event) {
      return event.tool().isEmpty() ? finished(event) : ToolCallTransition.dropped();
    }
  }

  /**
   * The tool deferred; Continuum holds the result. Never re-fired; see {@link AwaitingApproval} for
   * why the agreed deadline stays on the fact rather than on the state.
   */
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
