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
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.api.session.SessionStatus;
import org.jwcarman.nessy.api.session.TerminationPolicy;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.compaction.Compactor;

/**
 * The whole of the agent's semantics, as a pure function.
 *
 * <p>Performs no I/O and holds no state beyond its own configuration. Given the same state and
 * event it always produces the same step, which is what makes the loop testable without a model and
 * resumable on another machine.
 *
 * @param termination decides when the loop must stop calling the model rather than burning tokens
 *     in a loop
 * @param compaction decides when the settled conversation needs shrinking and shrinks it, keeping
 *     it inside the model's context window
 */
public record Reducer(TerminationPolicy termination, Compactor compaction) {

  public Reducer {
    Objects.requireNonNull(termination, "termination must not be null");
    Objects.requireNonNull(compaction, "compaction must not be null");
  }

  /**
   * Compaction disabled. Every other default in this codebase that needs a provider is assembled by
   * {@code AgentBuilder}, which has one to hand to {@code Summarizer.usingProvider(...)}; this
   * factory has no provider available to it, so it cannot build the summarizing default the way
   * {@code AgentBuilder.build()} does. Call {@code AgentBuilder} for a compacting agent.
   */
  public static Reducer defaults() {
    return new Reducer(TerminationPolicy.defaults(), Compactor.disabled());
  }

  public Step reduce(SessionState state, Event event) {
    return switch (event) {
      case Event.UserSaid e -> userSaid(state, e);
      case Event.TextDelta e -> textDelta(state, e);
      case Event.ThinkingDelta e -> thinkingDelta(state, e);
      case Event.ThinkingSigned(String signature) -> thinkingSigned(state, signature);
      case Event.RedactedThinkingArrived(String data) -> redactedThinkingArrived(state, data);
      case Event.ModelTurnEnded e -> modelTurnEnded(state, e);
      case Event.ToolCallRequested e -> toolCallRequested(state, e);
      case Event.ApprovalDecided e -> approvalDecided(state, e);
      case Event.ToolFinished(ToolCall call, ToolResult result) ->
          toolFinished(state, call, result);
      case Event.Compacted e -> compacted(state, e);
      case Event.CompactionSkipped e -> compactionSkipped(state, e);
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
    return proceedOrCompact(next);
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
   *
   * <p>A signed block is closed: its signature covers its exact text, so a later delta must start a
   * fresh block rather than growing text the signature no longer matches. The empty-signature check
   * is what tells an unsigned (still-open) block apart from a signed (closed) one.
   */
  private Step thinkingDelta(SessionState state, Event.ThinkingDelta event) {
    List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
    if (!blocks.isEmpty()
        && blocks.getLast() instanceof ThinkingBlock last
        && last.signature().isEmpty()) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(last.text() + event.text(), ""));
    } else {
      blocks.add(new ThinkingBlock(event.text(), ""));
    }
    return Step.of(state.withPendingBlocks(blocks));
  }

  /**
   * Lands a signature on the trailing thinking block once the provider has finished it.
   *
   * <p>A no-op when the trailing block is not a {@link ThinkingBlock}: the provider contract always
   * emits a {@code ThinkingChunk} before a signature, so a signature with nothing trailing it to
   * sign is not a shape this reducer needs to guard against loudly.
   */
  private Step thinkingSigned(SessionState state, String signature) {
    List<ContentBlock> blocks = state.pendingBlocks();
    if (blocks.isEmpty() || !(blocks.getLast() instanceof ThinkingBlock last)) {
      return Step.of(state);
    }
    List<ContentBlock> next = new ArrayList<>(blocks);
    next.set(next.size() - 1, new ThinkingBlock(last.text(), signature));
    return Step.of(state.withPendingBlocks(next));
  }

  /** Appends a complete redacted-thinking block; its contents stay opaque by design. */
  private Step redactedThinkingArrived(SessionState state, String data) {
    List<ContentBlock> blocks = new ArrayList<>(state.pendingBlocks());
    blocks.add(new RedactedThinkingBlock(data));
    return Step.of(state.withPendingBlocks(blocks));
  }

  /**
   * Ends the model's turn.
   *
   * <p>A turn cut off at the token ceiling, or one the model refused to continue, fails the session
   * rather than reporting {@link SessionStatus#COMPLETE}. Compaction guards against reaching the
   * ceiling by shrinking the transcript <em>before</em> the next call, but it cannot rescue a turn
   * that has already overflowed mid-stream, so overflow still has to fail loudly: a half-finished
   * sentence reported as a finished answer is the worse outcome, and it is silent. A refusal gets
   * the same treatment for the same reason: nothing downstream can tell a refused turn from a
   * finished one except this reducer.
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
        state
            .withTurns(state.turns() + 1)
            .withUsage(state.usage().plus(event.usage()))
            .withLastInputTokens(event.usage().inputTokens());
    SessionState settled = settleAssistantMessage(accounted);
    Optional<String> halt = haltReason(event.reason());
    if (halt.isPresent()) {
      return Step.of(
          flushResults(abandonPendingCalls(settled))
              .withFailureReason(halt.get())
              .with(SessionStatus.FAILED));
    }
    if (settled.pendingCalls().isEmpty()) {
      return Step.of(settled.with(SessionStatus.COMPLETE));
    }
    return Step.of(
        settled.with(SessionStatus.AWAITING_APPROVAL),
        new Effect.RequestApproval(settled.pendingCalls().getFirst()));
  }

  /**
   * The failure reason for a stop reason that must halt the session, or empty for one that lets the
   * turn conclude normally. Keeping each reason string beside the constant it explains avoids a
   * chain of equality checks drifting out of sync with the strings they produce.
   */
  private static Optional<String> haltReason(StopReason reason) {
    return switch (reason) {
      case MAX_TOKENS -> Optional.of("model hit the token ceiling (MAX_TOKENS)");
      case REFUSAL -> Optional.of("model refused to continue (REFUSAL)");
      case END_TURN, TOOL_USE -> Optional.empty();
    };
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
      case Decision.Allow _ ->
          Step.of(state.with(SessionStatus.EXECUTING_TOOL), new Effect.ExecuteTool(event.call()));
      // A denial is not a special path: it is a result the model can read and
      // adapt to, exactly like a tool that failed.
      case Decision.Deny(String reason) ->
          toolFinished(state, event.call(), ToolResult.error("Denied by user: " + reason));
    };
  }

  private Step toolFinished(SessionState state, ToolCall call, ToolResult result) {
    List<ToolResultBlock> results = new ArrayList<>(state.pendingResults());
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
    return proceedOrCompact(flushResults(next).with(SessionStatus.AWAITING_MODEL));
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
    List<ToolResultBlock> results = new ArrayList<>(state.pendingResults());
    for (ToolCall pending : state.pendingCalls()) {
      results.add(
          new ToolResultBlock(
              pending.id(), "Abandoned: the session failed before this tool ran.", true));
    }
    return state.withPendingResults(results).withPendingCalls(List.of());
  }

  /**
   * The decision made at every point the loop is about to ask the model to continue: call it, or —
   * when {@link Compactor#requiresCompaction} says the settled conversation has grown enough — hand
   * the whole working set to the compactor first. Termination has already been checked by the
   * caller; this is the second half of that same decision point, tried at most once per point (a
   * skipped or completed compaction does not loop back through here).
   *
   * <p>The reducer no longer computes what to keep versus summarize away: the compactor sees the
   * whole ledger and decides for itself.
   */
  private Step proceedOrCompact(SessionState state) {
    if (!compaction.requiresCompaction(state)) {
      return Step.of(state, Effect.callModel());
    }
    return Step.of(state.with(SessionStatus.COMPACTING), Effect.compact());
  }

  /**
   * The compactor's result lands. A working set smaller than what went in is a shrink: it replaces
   * the messages wholesale, bumps the generation (a compacted transcript is a new shape the rest of
   * the system — transcripts, resumed sessions — must be able to tell apart from the one before
   * it), and resets {@code lastInputTokens} since the next call's measured usage will reflect the
   * new, smaller transcript. A result no smaller than what went in is a skip in every way that
   * matters: the spend still happened and is still accounted for, but the messages are untouched
   * and the generation does not bump.
   *
   * <p>Compaction only ever applies against a settled transcript, but that is a claim about the
   * state at emission time, not at apply time — durable replay makes the two different moments.
   * {@code Event.Compacted} can land here with tool debt outstanding: a durably replayed run can
   * replay a stale {@code Compacted} against a state that has since moved on, and nothing stops a
   * hostile or buggy {@code Compactor} from answering late. {@link #proceedOrCompact} is itself
   * reached from more than one caller — including {@link #userSaid}, which performs no pending-lane
   * check of its own — so this belt cannot lean on an apply-time guarantee that was never actually
   * enforced at every call site. Splicing a rewritten working set underneath an assistant message
   * that still has unanswered {@code tool_use} blocks would strand the pending lane — the tail the
   * compactor dropped might be exactly the messages those calls belong to. So a {@code Compacted}
   * arriving with {@code pendingCalls} or {@code pendingResults} non-empty is always treated as a
   * skip, regardless of what the working set's size says.
   *
   * <p>No usage accounting happens here, shrink or skip: the jurisdiction rule (design §10.6) bills
   * the ledger only for the loop's own spend, reported via {@code ModelTurnEnded}. Whatever a
   * compactor's own call cost is telemetry's jurisdiction, instrumented on its own span — never
   * folded into {@code SessionState.usage()}.
   */
  private Step compacted(SessionState state, Event.Compacted event) {
    boolean settled = state.pendingCalls().isEmpty() && state.pendingResults().isEmpty();
    if (settled && event.workingSet().size() < state.messages().size()) {
      SessionState next =
          state
              .withMessages(event.workingSet())
              .withGeneration(state.generation() + 1)
              .withLastInputTokens(0)
              .with(SessionStatus.AWAITING_MODEL);
      return Step.of(next, Effect.callModel());
    }
    return Step.of(state.with(SessionStatus.AWAITING_MODEL), Effect.callModel());
  }

  /**
   * One attempt per decision point: a skipped compaction proceeds to the model as-is rather than
   * re-checking here, so {@code lastInputTokens} is left untouched and simply retriggers the same
   * decision naturally at the next {@code CallModel} site.
   */
  private Step compactionSkipped(SessionState state, Event.CompactionSkipped event) {
    return Step.of(state.with(SessionStatus.AWAITING_MODEL), Effect.callModel());
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
        .withMessageAppended(Message.toolResults(List.copyOf(state.pendingResults())))
        .withPendingResults(List.of());
  }
}
