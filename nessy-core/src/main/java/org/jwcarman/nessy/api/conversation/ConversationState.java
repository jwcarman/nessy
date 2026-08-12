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
package org.jwcarman.nessy.api.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Everything the loop knows, as data.
 *
 * <p>This record is the control block: the debt lane, the dials, and the markers the fold needs to
 * decide what happens next. It holds no connections, no threads, and no callbacks, which is what
 * makes the fold pure, the loop testable without a network, and durable resume a storage concern
 * rather than an assembly change. The settled transcript itself is not here — that is {@link
 * org.jwcarman.nessy.spi.memory.Memory}'s custody, not the control block's.
 *
 * @param id the session this state belongs to
 * @param pendingCalls tool calls the model asked for and we have not finished
 * @param pendingResults results collected so far, flushed as one user message when the last pending
 *     call resolves
 * @param consecutiveErrors errored tool results in a row; any success resets it
 * @param modelCalls model calls completed so far — the termination policy's dial. It counts across
 *     the conversation's whole life, not within one turn: {@code told()} never resets it, so it is
 *     a lifetime total the way {@link #consecutiveErrors()} is a streak — the unit {@link
 *     TerminationPolicy} actually bounds.
 * @param usage tokens spent so far, accumulated across every completed model call — the loop's own
 *     spend, reported by {@link ConversationEvent.ModelResponded}. This is the jurisdiction rule
 *     (design §10.6, ruled 2026-08-10): the ledger bills only the loop's own conversational calls;
 *     auxiliary spend — a summarizing {@code Memory}'s own call today, a tool's internal model
 *     calls tomorrow — is telemetry's jurisdiction, instrumented on its own span, and never reaches
 *     this field
 * @param failureReason why the session failed, or {@code null} if it has not failed. This is the
 *     one sanctioned nullable field on this record: most sessions never fail, and forcing every
 *     caller to thread an empty string through the happy path would be worse than the null check
 *     the few failure sites already need.
 * @param told words interjected — the drained-tell accumulator: each entry is one tell's content,
 *     kept in arrival order for a durable resume to replay against the transcript.
 * @param parkedCalls homework waiting on the world — tool calls that yielded rather than settled,
 *     each named by the {@link org.jwcarman.nessy.api.ParkToken} a later resume must present.
 * @param version the fence's token — bumped on every durable write so a store can reject a stale
 *     save out from under a concurrent resume.
 * @param status lifecycle position
 */
public record ConversationState(
    ConversationId id,
    List<ToolCall> pendingCalls,
    List<ToolResultBlock> pendingResults,
    int consecutiveErrors,
    int modelCalls,
    Usage usage,
    String failureReason,
    List<List<ContentBlock>> told,
    List<ParkedCall> parkedCalls,
    long version,
    ConversationStatus status) {

  public ConversationState {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    pendingCalls = List.copyOf(pendingCalls);
    pendingResults = List.copyOf(pendingResults);
    told = List.copyOf(told);
    parkedCalls = List.copyOf(parkedCalls);
  }

  public static ConversationState newConversation(ConversationId id) {
    return new ConversationState(
        id,
        List.of(),
        List.of(),
        0,
        0,
        Usage.zero(),
        null,
        List.of(),
        List.of(),
        0L,
        ConversationStatus.IDLE);
  }

  public ConversationState with(ConversationStatus newStatus) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        version,
        newStatus);
  }

  public ConversationState withPendingCalls(List<ToolCall> calls) {
    return new ConversationState(
        id,
        calls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withPendingResults(List<ToolResultBlock> results) {
    return new ConversationState(
        id,
        pendingCalls,
        results,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withConsecutiveErrors(int errors) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        errors,
        modelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withModelCalls(int newModelCalls) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        newModelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withUsage(Usage newUsage) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        newUsage,
        failureReason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withFailureReason(String reason) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        reason,
        told,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withTold(List<List<ContentBlock>> newTold) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        newTold,
        parkedCalls,
        version,
        status);
  }

  public ConversationState withParkedCalls(List<ParkedCall> newParkedCalls) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        told,
        newParkedCalls,
        version,
        status);
  }

  public ConversationState withVersion(long newVersion) {
    return new ConversationState(
        id,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        modelCalls,
        usage,
        failureReason,
        told,
        parkedCalls,
        newVersion,
        status);
  }

  /**
   * The fold: one fact in, the next state plus its message births and effects out. The whole of the
   * agent's semantics — pure, deterministic, exhaustive over the fact grammar. The misdelivery
   * guard runs first: a fact addressed to one conversation can never fold into another's state.
   */
  public Step fold(ConversationEvent event) {
    if (!event.conversationId().equals(id)) {
      throw new IllegalArgumentException(
          "misdelivered fact: event for " + event.conversationId() + " folded into " + id);
    }
    return switch (event) {
      case ConversationEvent.AgentTold e -> noted(e);
      case ConversationEvent.ModelResponded e -> modelResponded(e);
      case ConversationEvent.ModelCallFailed e -> modelCallFailed(e);
      case ConversationEvent.ToolFinished e -> toolFinished(e);
    };
  }

  /**
   * A tell is a pure note: it joins the {@link #told} accumulator and nothing else changes — no
   * remember, no effect, no status move, no streak reset. Turn-opening is a separate, deliberate
   * act ({@link #openTurn()}), invoked by the loop once the fold's quiescent.
   */
  private Step noted(ConversationEvent.AgentTold event) {
    List<List<ContentBlock>> newTold = new ArrayList<>(told);
    newTold.add(event.content());
    return Step.of(withTold(newTold));
  }

  private static final List<ConversationStatus> QUIESCENT_STATUSES =
      List.of(ConversationStatus.IDLE, ConversationStatus.COMPLETE, ConversationStatus.FAILED);

  /**
   * Whether this conversation is at rest — {@code IDLE}, {@code COMPLETE}, or {@code FAILED} — the
   * one source of truth {@link #openTurn()}'s own precondition and the loop's continuation pointer
   * both consult, so the two never drift into two different ideas of "resumable."
   */
  public boolean isQuiescent() {
    return QUIESCENT_STATUSES.contains(status);
  }

  /**
   * The closure transition that turns accumulated notes into an open turn: every {@link #told}
   * entry merges into one {@link Message#user(List) user} message, in arrival order, the streak and
   * any prior failure reason are cleared — a new turn owes nothing to the last one — and a model
   * call is asked for. Precondition: the conversation is quiescent ({@code IDLE}, {@code COMPLETE},
   * or {@code FAILED}) and {@link #told} is non-empty; violating either is a loop bug, so it fails
   * loud rather than folding something nonsensical.
   */
  public Step openTurn() {
    if (!isQuiescent()) {
      throw new IllegalStateException(
          "openTurn refuses to open over an open turn: status is " + status);
    }
    if (told.isEmpty()) {
      throw new IllegalStateException("openTurn called with no notes to open a turn with");
    }
    Message merged = Message.user(mergedTold());
    ConversationState next =
        withTold(List.of())
            .withConsecutiveErrors(0)
            .withFailureReason(null)
            .with(ConversationStatus.AWAITING_MODEL);
    return Step.of(next, List.of(merged), Effect.callModel());
  }

  /**
   * The model's settled contribution folds in: account the call, remember the message, then decide
   * — fatal stop reason fails (answering any homework the truncated message opened), no homework
   * completes, homework fans out one effect per call.
   */
  private Step modelResponded(ConversationEvent.ModelResponded event) {
    List<ToolCall> calls =
        event.message().content().stream()
            .filter(ToolUseBlock.class::isInstance)
            .map(block -> ((ToolUseBlock) block).call())
            .toList();
    ConversationState accounted =
        withModelCalls(modelCalls + 1).withUsage(usage.plus(event.usage())).withPendingCalls(calls);
    Optional<String> fatal = fatalStop(event.reason());
    if (fatal.isPresent()) {
      Step closed = accounted.halted(fatal.get());
      List<Message> remember = new ArrayList<>();
      remember.add(event.message());
      remember.addAll(closed.remember());
      return new Step(closed.state(), remember, List.of());
    }
    if (calls.isEmpty()) {
      if (told.isEmpty()) {
        return Step.of(accounted.with(ConversationStatus.COMPLETE), List.of(event.message()));
      }
      Message merged = Message.user(mergedTold());
      ConversationState continued =
          accounted.withTold(List.of()).with(ConversationStatus.AWAITING_MODEL);
      return Step.of(continued, List.of(event.message(), merged), Effect.callModel());
    }
    List<Effect> effects =
        calls.stream().map(call -> (Effect) new Effect.ExecuteTool(call)).toList();
    return new Step(
        accounted.with(ConversationStatus.EXECUTING_TOOL), List.of(event.message()), effects);
  }

  /** Fate, not data: no party remains in the dialogue to read a failed call. */
  private Step modelCallFailed(ConversationEvent.ModelCallFailed event) {
    return Step.of(withFailureReason(event.reason()).with(ConversationStatus.FAILED));
  }

  /**
   * One piece of homework settles. Results arrive in any order; the flush waits for every sibling —
   * pending <em>and</em> parked — because providers require every result for a turn to arrive
   * together in the following message: flushing {@code [result:c2]} while {@code c1} is still
   * parked would answer the wire with a {@code tool_use} nobody ever settled.
   *
   * <p>When the last <em>pending</em> sibling settles but a parked one remains outstanding, this
   * fold does not flush — it moves straight to {@link ConversationStatus#PARKED}, holding the
   * settled results in {@link #pendingResults} for the parked sibling's own eventual {@code
   * toolFinished} fold to gather and flush together, riders included. That later fold is this same
   * method: once it removes the resuming call and finds neither pending nor parked siblings left,
   * it takes the flush branch below exactly as if nothing had ever waited.
   */
  private Step toolFinished(ConversationEvent.ToolFinished event) {
    List<ToolResultBlock> results = new ArrayList<>(pendingResults);
    results.add(
        new ToolResultBlock(event.call().id(), event.result().content(), event.result().isError()));
    List<ToolCall> remaining = new ArrayList<>(pendingCalls);
    removeFirstMatch(remaining, event.call().id());
    List<ParkedCall> remainingParked = new ArrayList<>(parkedCalls);
    removeFirstMatchParked(remainingParked, event.call().id());
    int errors = event.result().isError() ? consecutiveErrors + 1 : 0;
    ConversationState next =
        withPendingResults(results)
            .withPendingCalls(remaining)
            .withParkedCalls(remainingParked)
            .withConsecutiveErrors(errors);
    if (!remaining.isEmpty()) {
      return Step.of(next);
    }
    if (!remainingParked.isEmpty()) {
      return Step.of(next.with(ConversationStatus.PARKED));
    }
    List<ContentBlock> flushContent = new ArrayList<>(results);
    flushContent.addAll(next.mergedTold());
    Message flush = Message.toolResults(List.copyOf(flushContent));
    return Step.of(
        next.withPendingResults(List.of())
            .withTold(List.of())
            .with(ConversationStatus.AWAITING_MODEL),
        List.of(flush),
        Effect.callModel());
  }

  /**
   * The closure every failing path owes: answer outstanding homework with abandoned-error results,
   * ride any unread notes beside them — a dying conversation still delivers the world's words to
   * the record — flush, so the record never holds a tool_use without its tool_result, then fail
   * with the reason. Consulted by the loop when the termination policy halts, and reused by fatal
   * stop reasons.
   */
  public Step halted(String reason) {
    ConversationState failed = withFailureReason(reason).with(ConversationStatus.FAILED);
    List<ContentBlock> notes = mergedTold();
    if (pendingCalls.isEmpty() && pendingResults.isEmpty() && notes.isEmpty()) {
      return Step.of(failed);
    }
    List<ContentBlock> content = new ArrayList<>(pendingResults);
    for (ToolCall pending : pendingCalls) {
      content.add(
          new ToolResultBlock(
              pending.id(), "Abandoned: the conversation failed before this tool ran.", true));
    }
    content.addAll(notes);
    Message flush = Message.toolResults(List.copyOf(content));
    return Step.of(
        failed.withPendingCalls(List.of()).withPendingResults(List.of()).withTold(List.of()),
        List.of(flush));
  }

  /**
   * The closure transition a park applies: {@code call} moves from {@link #pendingCalls} to {@link
   * #parkedCalls}, named by {@code token} for the resume that must later present it. Status becomes
   * {@link ConversationStatus#PARKED} iff no {@link #pendingCalls} remain un-parked once {@code
   * call} is moved — a sibling call still running keeps the conversation {@code EXECUTING_TOOL}
   * until it too settles or parks. Fold-free and loop-applied, like {@link #halted(String)}: no
   * message is born, so unlike {@code halted} there is nothing for the loop to remember, only to
   * save.
   */
  public ConversationState parked(ToolCall call, ParkToken token) {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(token, "token must not be null");
    List<ToolCall> remaining = new ArrayList<>(pendingCalls);
    removeFirstMatch(remaining, call.id());
    List<ParkedCall> newParkedCalls = new ArrayList<>(parkedCalls);
    newParkedCalls.add(new ParkedCall(token, call));
    ConversationStatus newStatus =
        remaining.isEmpty() ? ConversationStatus.PARKED : ConversationStatus.EXECUTING_TOOL;
    return withPendingCalls(remaining).withParkedCalls(newParkedCalls).with(newStatus);
  }

  /**
   * Every {@link #told} entry concatenated in arrival order, block boundaries preserved — no
   * joining, no separators. The shape the open-turn message, the clean-continue message, and every
   * flush's riders are built from.
   */
  private List<ContentBlock> mergedTold() {
    List<ContentBlock> merged = new ArrayList<>();
    for (List<ContentBlock> entry : told) {
      merged.addAll(entry);
    }
    return merged;
  }

  private static Optional<String> fatalStop(StopReason reason) {
    return switch (reason) {
      case MAX_TOKENS -> Optional.of("model hit the token ceiling (MAX_TOKENS)");
      case REFUSAL -> Optional.of("model refused to continue (REFUSAL)");
      case END_TURN, TOOL_USE -> Optional.empty();
    };
  }

  private static void removeFirstMatch(List<ToolCall> calls, String callId) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).id().equals(callId)) {
        calls.remove(i);
        return;
      }
    }
  }

  private static void removeFirstMatchParked(List<ParkedCall> calls, String callId) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).call().id().equals(callId)) {
        calls.remove(i);
        return;
      }
    }
  }
}
