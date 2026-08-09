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
package org.jwcarman.nessy.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.ContentBlock;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.SessionStatus;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.TerminationPolicy;
import org.jwcarman.nessy.api.TextBlock;
import org.jwcarman.nessy.api.ThinkingBlock;
import org.jwcarman.nessy.api.ToolCall;
import org.jwcarman.nessy.api.ToolResult;
import org.jwcarman.nessy.api.ToolResultBlock;
import org.jwcarman.nessy.api.ToolUseBlock;

/**
 * The whole of the agent's semantics, as a pure function.
 *
 * <p>Performs no I/O and holds no state beyond its own configuration. Given the same state and
 * event it always produces the same step, which is what makes the loop testable without a model and
 * resumable on another machine.
 *
 * @param termination decides when the loop must stop calling the model rather than burning tokens
 *     in a loop
 */
public record Reducer(TerminationPolicy termination) {

  public Reducer {
    Objects.requireNonNull(termination, "termination must not be null");
  }

  public static Reducer defaults() {
    return new Reducer(TerminationPolicy.defaults());
  }

  public Step reduce(SessionState state, Event event) {
    return switch (event) {
      case Event.UserSaid e -> userSaid(state, e);
      case Event.TextDelta e -> textDelta(state, e);
      case Event.ThinkingDelta e -> thinkingDelta(state, e);
      case Event.ModelTurnEnded e -> modelTurnEnded(state, e);
      case Event.ToolCallRequested e -> toolCallRequested(state, e);
      case Event.ApprovalDecided e -> approvalDecided(state, e);
      case Event.ToolFinished(ToolCall call, ToolResult result) ->
          toolFinished(state, call, result);
    };
  }

  /**
   * A new human turn starts a fresh error streak.
   *
   * <p>The circuit breaker counts <em>consecutive</em> errored tool results. A person typing again
   * is by definition not part of the previous streak, so resuming a session that tripped the
   * breaker must not re-fail on its very first errored result.
   */
  private Step userSaid(SessionState state, Event.UserSaid event) {
    SessionState next =
        state
            .withMessageAppended(Message.user(event.content()))
            .withConsecutiveErrors(0)
            .withFailureReason(null)
            .with(SessionStatus.AWAITING_MODEL);
    Optional<String> halt = termination.shouldHalt(next);
    if (halt.isPresent()) {
      return Step.of(
          flushResults(abandonPendingCalls(next))
              .withFailureReason(halt.get())
              .with(SessionStatus.FAILED));
    }
    return Step.of(next, Effect.callModel());
  }

  /**
   * Merges a chunk into the trailing text block rather than appending a new one, so a hundred
   * deltas become one block instead of a hundred.
   */
  private Step textDelta(SessionState state, Event.TextDelta event) {
    List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
    if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock last) {
      blocks.set(blocks.size() - 1, new TextBlock(last.text() + event.text()));
    } else {
      blocks.add(new TextBlock(event.text()));
    }
    return Step.of(state.withPendingBlocks(blocks));
  }

  /**
   * Merges a chunk into the trailing thinking block rather than appending a new one, mirroring
   * {@link #textDelta}.
   */
  private Step thinkingDelta(SessionState state, Event.ThinkingDelta event) {
    List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock last) {
      blocks.set(
          blocks.size() - 1, new ThinkingBlock(last.text() + event.text(), last.signature()));
    } else {
      blocks.add(new ThinkingBlock(event.text(), ""));
    }
    return Step.of(state.withPendingBlocks(blocks));
  }

  /**
   * Ends the model's turn.
   *
   * <p>A turn cut off at the token ceiling fails the session rather than reporting {@link
   * SessionStatus#COMPLETE}. Context compaction is deliberately deferred, so overflow has to fail
   * loudly: a half-finished sentence reported as a finished answer is the worse outcome, and it is
   * silent.
   *
   * <p>A turn can be truncated mid-{@code tool_use}, leaving the just-settled assistant message
   * with {@link ToolUseBlock}s that never got a matching result. Left alone, those pending calls
   * would leak into a resumed session and the next user message would follow a {@code tool_use}
   * with no {@code tool_result}, which providers reject outright. So this path closes the shape the
   * same way the error ceiling does: answer every pending call and flush the results before
   * failing.
   */
  private Step modelTurnEnded(SessionState state, Event.ModelTurnEnded event) {
    SessionState accounted =
        state.withTurns(state.turns() + 1).withUsage(state.usage().plus(event.usage()));
    SessionState settled = settleAssistantMessage(accounted);
    if (event.reason() == StopReason.MAX_TOKENS) {
      return Step.of(
          flushResults(abandonPendingCalls(settled))
              .withFailureReason("model hit the token ceiling (MAX_TOKENS)")
              .with(SessionStatus.FAILED));
    }
    if (settled.pendingCalls().isEmpty()) {
      return Step.of(settled.with(SessionStatus.COMPLETE));
    }
    return Step.of(
        settled.with(SessionStatus.AWAITING_APPROVAL),
        new Effect.RequestApproval(settled.pendingCalls().getFirst()));
  }

  private Step toolCallRequested(SessionState state, Event.ToolCallRequested event) {
    List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
    blocks.add(new ToolUseBlock(event.call()));

    List<ToolCall> calls = new ArrayList<>(state.pendingCalls());
    calls.add(event.call());

    return Step.of(state.withPendingBlocks(blocks).withPendingCalls(calls));
  }

  /** Moves the in-flight blocks into the settled conversation. */
  private SessionState settleAssistantMessage(SessionState state) {
    if (state.pendingBlocks().isEmpty()) {
      return state;
    }
    return state
        .withMessageAppended(Message.assistant(state.pendingBlocks()))
        .withPendingBlocks(List.of());
  }

  private Step approvalDecided(SessionState state, Event.ApprovalDecided event) {
    return switch (event.decision()) {
      case Decision.Allow ignored ->
          Step.of(state.with(SessionStatus.EXECUTING_TOOL), new Effect.ExecuteTool(event.call()));
      // A denial is not a special path: it is a result the model can read and
      // adapt to, exactly like a tool that failed.
      case Decision.Deny(String reason) ->
          toolFinished(state, event.call(), ToolResult.error("Denied by user: " + reason));
    };
  }

  private Step toolFinished(SessionState state, ToolCall call, ToolResult result) {
    List<ContentBlock> results = new ArrayList<>(state.pendingResults());
    results.add(new ToolResultBlock(call.id(), result.content(), result.isError()));

    List<ToolCall> remaining = new ArrayList<>(state.pendingCalls());
    removeFirstMatch(remaining, call.id());

    int errors = result.isError() ? state.consecutiveErrors() + 1 : 0;

    SessionState next =
        state.withPendingResults(results).withPendingCalls(remaining).withConsecutiveErrors(errors);

    Optional<String> halt = termination.shouldHalt(next);
    if (halt.isPresent()) {
      return Step.of(
          flushResults(abandonPendingCalls(next))
              .withFailureReason(halt.get())
              .with(SessionStatus.FAILED));
    }
    if (!remaining.isEmpty()) {
      return Step.of(
          next.with(SessionStatus.AWAITING_APPROVAL),
          new Effect.RequestApproval(remaining.getFirst()));
    }
    return Step.of(flushResults(next).with(SessionStatus.AWAITING_MODEL), Effect.callModel());
  }

  /**
   * Removes one pending call by id, not every call sharing it.
   *
   * <p>A provider that reuses an id would otherwise silently drop a second call's slot, leaving its
   * {@code tool_use} block without a matching result.
   */
  private static void removeFirstMatch(List<ToolCall> calls, String id) {
    for (int i = 0; i < calls.size(); i++) {
      if (calls.get(i).id().equals(id)) {
        calls.remove(i);
        return;
      }
    }
  }

  /**
   * Answers every still-pending call with an errored result before the session dies.
   *
   * <p>The settled assistant message already carries a {@code tool_use} block for each of them, and
   * providers reject a turn whose blocks are not all answered. Leaving them unanswered would make
   * the transcript permanently unusable — a 400 on every subsequent call — so whichever path is
   * failing the session closes the shape it opened, whether that's the error ceiling or a turn
   * truncated at the token limit.
   */
  private static SessionState abandonPendingCalls(SessionState state) {
    if (state.pendingCalls().isEmpty()) {
      return state;
    }
    List<ContentBlock> results = new ArrayList<>(state.pendingResults());
    for (ToolCall pending : state.pendingCalls()) {
      results.add(
          new ToolResultBlock(
              pending.id(), "Abandoned: the session failed before this tool ran.", true));
    }
    return state.withPendingResults(results).withPendingCalls(List.of());
  }

  /**
   * Collected results become one user message. They are batched rather than sent one at a time
   * because the providers we target require every result for a turn to arrive together in the
   * message that follows it.
   */
  private SessionState flushResults(SessionState state) {
    if (state.pendingResults().isEmpty()) {
      return state;
    }
    return state
        .withMessageAppended(Message.toolResults(state.pendingResults()))
        .withPendingResults(List.of());
  }
}
