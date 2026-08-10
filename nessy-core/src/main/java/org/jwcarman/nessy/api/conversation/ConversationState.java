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
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Everything the loop knows, as data.
 *
 * <p>This record is the whole of the agent's memory. It holds no connections, no threads, and no
 * callbacks, which is what makes the reducer pure, the loop testable without a network, and durable
 * resume a storage concern rather than an engine change.
 *
 * @param id the session this state belongs to
 * @param messages the settled conversation
 * @param pendingBlocks the assistant message currently being streamed in
 * @param pendingCalls tool calls the model asked for and we have not finished
 * @param pendingResults results collected so far, flushed as one user message when the last pending
 *     call resolves
 * @param consecutiveErrors errored tool results in a row; any success resets it
 * @param turns model turns completed so far
 * @param usage tokens spent so far, accumulated across every completed turn — the loop's own spend,
 *     reported by {@code ModelTurnEnded}. This is the jurisdiction rule (design §10.6, ruled
 *     2026-08-10): the ledger bills only the loop's own conversational turns; auxiliary spend — a
 *     {@code Compactor}'s own summarization call today, a tool's internal model calls tomorrow — is
 *     telemetry's jurisdiction, instrumented on its own span, and never reaches this field
 * @param lastInputTokens the provider's own measurement of what the most recent model call cost;
 *     read by the reducer's {@code Compactor} to decide when to compact. This reads the provider's
 *     reported input token count as-is; a future message-level prompt-cache breakpoint that
 *     excludes cached tokens from that count would weaken the trigger, since a large cached prefix
 *     would then read as cheap even while still counting toward the model's context window
 * @param generation bumped whenever compaction rewrites the settled conversation; the store's
 *     signal that it must rewrite rather than append
 * @param failureReason why the session failed, or {@code null} if it has not failed. This is the
 *     one sanctioned nullable field on this record: most sessions never fail, and forcing every
 *     caller to thread an empty string through the happy path would be worse than the null check
 *     the few failure sites already need.
 * @param status lifecycle position
 */
public record ConversationState(
    ConversationId id,
    List<Message> messages,
    List<ContentBlock> pendingBlocks,
    List<ToolCall> pendingCalls,
    List<ToolResultBlock> pendingResults,
    int consecutiveErrors,
    int turns,
    Usage usage,
    long lastInputTokens,
    int generation,
    String failureReason,
    ConversationStatus status) {

  public ConversationState {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (lastInputTokens < 0) {
      throw new IllegalArgumentException("lastInputTokens must be at least 0");
    }
    if (generation < 0) {
      throw new IllegalArgumentException("generation must be at least 0");
    }
    messages = List.copyOf(messages);
    pendingBlocks = List.copyOf(pendingBlocks);
    pendingCalls = List.copyOf(pendingCalls);
    pendingResults = List.copyOf(pendingResults);
  }

  public static ConversationState newConversation(ConversationId id) {
    return new ConversationState(
        id,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0,
        0,
        Usage.zero(),
        0,
        0,
        null,
        ConversationStatus.IDLE);
  }

  public ConversationState with(ConversationStatus newStatus) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        newStatus);
  }

  public ConversationState withMessageAppended(Message message) {
    List<Message> appended = new ArrayList<>(messages);
    appended.add(message);
    return new ConversationState(
        id,
        appended,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  /** Replaces the settled conversation wholesale, as compaction does. */
  public ConversationState withMessages(List<Message> newMessages) {
    return new ConversationState(
        id,
        newMessages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withPendingBlocks(List<ContentBlock> blocks) {
    return new ConversationState(
        id,
        messages,
        blocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withPendingCalls(List<ToolCall> calls) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        calls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withPendingResults(List<ToolResultBlock> results) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        results,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withConsecutiveErrors(int errors) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        errors,
        turns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withTurns(int newTurns) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        newTurns,
        usage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withUsage(Usage newUsage) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        newUsage,
        lastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withLastInputTokens(long newLastInputTokens) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        newLastInputTokens,
        generation,
        failureReason,
        status);
  }

  public ConversationState withGeneration(int newGeneration) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        newGeneration,
        failureReason,
        status);
  }

  public ConversationState withFailureReason(String reason) {
    return new ConversationState(
        id,
        messages,
        pendingBlocks,
        pendingCalls,
        pendingResults,
        consecutiveErrors,
        turns,
        usage,
        lastInputTokens,
        generation,
        reason,
        status);
  }
}
