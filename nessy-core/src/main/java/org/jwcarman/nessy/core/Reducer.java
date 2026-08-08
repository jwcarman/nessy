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
package org.jwcarman.nessy.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole of the agent's semantics, as a pure function.
 *
 * <p>Performs no I/O and holds no state beyond its own configuration. Given the same state and
 * event it always produces the same step, which is what makes the loop testable without a model and
 * resumable on another machine.
 *
 * @param maxConsecutiveErrors how many errored tool results in a row before the session fails
 *     rather than burning tokens in a loop
 */
public record Reducer(int maxConsecutiveErrors) {

  public static final int DEFAULT_MAX_CONSECUTIVE_ERRORS = 3;

  public Reducer {
    if (maxConsecutiveErrors < 1) {
      throw new IllegalArgumentException("maxConsecutiveErrors must be at least 1");
    }
  }

  public static Reducer withDefaults() {
    return new Reducer(DEFAULT_MAX_CONSECUTIVE_ERRORS);
  }

  public Step reduce(SessionState state, Event event) {
    return switch (event) {
      case Event.UserSaid e -> userSaid(state, e);
      case Event.TextDelta e -> textDelta(state, e);
      case Event.ModelTurnEnded e -> modelTurnEnded(state, e);
      case Event.ToolCallRequested e -> toolCallRequested(state, e);
      case Event.ApprovalDecided e -> approvalDecided(state, e);
      case Event.ToolFinished e -> toolFinished(state, e.call(), e.result());
    };
  }

  private Step userSaid(SessionState state, Event.UserSaid event) {
    return Step.of(
        state.withMessageAppended(Message.user(event.text())).with(SessionStatus.AWAITING_MODEL),
        Effect.callModel());
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

  private Step modelTurnEnded(SessionState state, Event.ModelTurnEnded event) {
    SessionState settled = settleAssistantMessage(state);
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
      case Decision.Deny deny ->
          toolFinished(state, event.call(), ToolResult.error("Denied by user: " + deny.reason()));
    };
  }

  private Step toolFinished(SessionState state, ToolCall call, ToolResult result) {
    List<ContentBlock> results = new ArrayList<>(state.pendingResults());
    results.add(new ToolResultBlock(call.id(), result.content(), result.isError()));

    List<ToolCall> remaining = new ArrayList<>(state.pendingCalls());
    remaining.removeIf(pending -> pending.id().equals(call.id()));

    int errors = result.isError() ? state.consecutiveErrors() + 1 : 0;

    SessionState next =
        state.withPendingResults(results).withPendingCalls(remaining).withConsecutiveErrors(errors);

    if (errors >= maxConsecutiveErrors) {
      return Step.of(flushResults(next).with(SessionStatus.FAILED));
    }
    if (!remaining.isEmpty()) {
      return Step.of(
          next.with(SessionStatus.AWAITING_APPROVAL),
          new Effect.RequestApproval(remaining.getFirst()));
    }
    return Step.of(flushResults(next).with(SessionStatus.AWAITING_MODEL), Effect.callModel());
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
