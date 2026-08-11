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
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Everything the loop knows, as data.
 *
 * <p>This record is the control block: the debt lane, the dials, and the markers the reducer needs
 * to decide what happens next. It holds no connections, no threads, and no callbacks, which is what
 * makes the reducer pure, the loop testable without a network, and durable resume a storage concern
 * rather than an engine change. The settled transcript itself is not here — that is {@link
 * org.jwcarman.nessy.spi.memory.Memory}'s custody, not the control block's.
 *
 * @param id the session this state belongs to
 * @param pendingCalls tool calls the model asked for and we have not finished
 * @param pendingResults results collected so far, flushed as one user message when the last pending
 *     call resolves
 * @param consecutiveErrors errored tool results in a row; any success resets it
 * @param modelCalls model calls completed so far — the termination policy's dial. A turn is the
 *     whole tell-to-clean-response episode; this field counts the model calls within it, which is
 *     the unit {@link TerminationPolicy} actually bounds.
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
    ConversationStatus status) {

  public ConversationState {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(status, "status must not be null");
    pendingCalls = List.copyOf(pendingCalls);
    pendingResults = List.copyOf(pendingResults);
  }

  public static ConversationState newConversation(ConversationId id) {
    return new ConversationState(
        id, List.of(), List.of(), 0, 0, Usage.zero(), null, ConversationStatus.IDLE);
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
        newStatus);
  }

  public ConversationState withPendingCalls(List<ToolCall> calls) {
    return new ConversationState(
        id, calls, pendingResults, consecutiveErrors, modelCalls, usage, failureReason, status);
  }

  public ConversationState withPendingResults(List<ToolResultBlock> results) {
    return new ConversationState(
        id, pendingCalls, results, consecutiveErrors, modelCalls, usage, failureReason, status);
  }

  public ConversationState withConsecutiveErrors(int errors) {
    return new ConversationState(
        id, pendingCalls, pendingResults, errors, modelCalls, usage, failureReason, status);
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
        status);
  }

  public ConversationState withFailureReason(String reason) {
    return new ConversationState(
        id, pendingCalls, pendingResults, consecutiveErrors, modelCalls, usage, reason, status);
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
      case ConversationEvent.AgentTold e -> told(e);
      case ConversationEvent.ModelResponded e -> modelResponded(e);
      case ConversationEvent.ModelCallFailed e -> modelCallFailed(e);
      case ConversationEvent.ToolFinished e -> toolFinished(e);
    };
  }

  /** A tell starts a fresh error streak: a new instruction is not part of the previous failure. */
  private Step told(ConversationEvent.AgentTold event) {
    ConversationState next =
        withConsecutiveErrors(0).withFailureReason(null).with(ConversationStatus.AWAITING_MODEL);
    return Step.of(next, List.of(Message.user(event.content())), Effect.callModel());
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
      return Step.of(accounted.with(ConversationStatus.COMPLETE), List.of(event.message()));
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
   * One piece of homework settles. Results arrive in any order; the flush waits for the last one,
   * because providers require every result for a turn to arrive together in the following message.
   */
  private Step toolFinished(ConversationEvent.ToolFinished event) {
    List<ToolResultBlock> results = new ArrayList<>(pendingResults);
    results.add(
        new ToolResultBlock(event.call().id(), event.result().content(), event.result().isError()));
    List<ToolCall> remaining = new ArrayList<>(pendingCalls);
    removeFirstMatch(remaining, event.call().id());
    int errors = event.result().isError() ? consecutiveErrors + 1 : 0;
    ConversationState next =
        withPendingResults(results).withPendingCalls(remaining).withConsecutiveErrors(errors);
    if (!remaining.isEmpty()) {
      return Step.of(next);
    }
    Message flush = Message.toolResults(List.copyOf(results));
    return Step.of(
        next.withPendingResults(List.of()).with(ConversationStatus.AWAITING_MODEL),
        List.of(flush),
        Effect.callModel());
  }

  /**
   * The closure every failing path owes: answer outstanding homework with abandoned-error results
   * and flush, so the record never holds a tool_use without its tool_result, then fail with the
   * reason. Consulted by the loop when the termination policy halts, and reused by fatal stop
   * reasons.
   */
  public Step halted(String reason) {
    ConversationState failed = withFailureReason(reason).with(ConversationStatus.FAILED);
    if (pendingCalls.isEmpty() && pendingResults.isEmpty()) {
      return Step.of(failed);
    }
    List<ToolResultBlock> results = new ArrayList<>(pendingResults);
    for (ToolCall pending : pendingCalls) {
      results.add(
          new ToolResultBlock(
              pending.id(), "Abandoned: the conversation failed before this tool ran.", true));
    }
    Message flush = Message.toolResults(List.copyOf(results));
    return Step.of(
        failed.withPendingCalls(List.of()).withPendingResults(List.of()), List.of(flush));
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
}
