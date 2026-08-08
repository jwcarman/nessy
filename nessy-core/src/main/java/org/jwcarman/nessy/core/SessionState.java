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
 * @param status lifecycle position
 */
public record SessionState(
    SessionId id,
    List<Message> messages,
    List<ContentBlock> pendingBlocks,
    List<ToolCall> pendingCalls,
    List<ContentBlock> pendingResults,
    int consecutiveErrors,
    SessionStatus status) {

  public SessionState {
    messages = List.copyOf(messages);
    pendingBlocks = List.copyOf(pendingBlocks);
    pendingCalls = List.copyOf(pendingCalls);
    pendingResults = List.copyOf(pendingResults);
  }

  public static SessionState newSession(SessionId id) {
    return new SessionState(id, List.of(), List.of(), List.of(), List.of(), 0, SessionStatus.IDLE);
  }

  public SessionState with(SessionStatus newStatus) {
    return new SessionState(
        id, messages, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, newStatus);
  }

  public SessionState withMessageAppended(Message message) {
    List<Message> appended = new ArrayList<>(messages);
    appended.add(message);
    return new SessionState(
        id, appended, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, status);
  }

  public SessionState withPendingBlocks(List<ContentBlock> blocks) {
    return new SessionState(
        id, messages, blocks, pendingCalls, pendingResults, consecutiveErrors, status);
  }

  public SessionState withPendingCalls(List<ToolCall> calls) {
    return new SessionState(
        id, messages, pendingBlocks, calls, pendingResults, consecutiveErrors, status);
  }

  public SessionState withPendingResults(List<ContentBlock> results) {
    return new SessionState(
        id, messages, pendingBlocks, pendingCalls, results, consecutiveErrors, status);
  }

  public SessionState withConsecutiveErrors(int errors) {
    return new SessionState(
        id, messages, pendingBlocks, pendingCalls, pendingResults, errors, status);
  }
}
