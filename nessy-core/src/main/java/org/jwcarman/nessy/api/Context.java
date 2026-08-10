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
package org.jwcarman.nessy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A wire-safe slice of the conversation: a list of {@link Message}s that a provider will always
 * accept, because the tool-pairing invariant is enforced on the way in rather than hoped for on the
 * way out.
 *
 * <p>The pairing invariant: for every {@link Role#ASSISTANT} message containing {@link
 * ToolUseBlock}s, the message immediately following it must be a {@link Role#USER} message whose
 * {@link ToolResultBlock}s answer exactly that set of ids — every id answered, no unknown ids, and
 * nothing in between. A {@link ToolResultBlock} may appear only in such an answering message. A
 * trailing assistant message with unanswered tool-use ids is rejected: a {@code Context} is
 * wire-bound, so an open tail belongs in {@code SessionState}, never here.
 */
public record Context(List<Message> messages) {

  public Context {
    Objects.requireNonNull(messages, "messages must not be null");
    messages = List.copyOf(messages);
    int i = 0;
    while (i < messages.size()) {
      List<String> callIds = toolUseIdsOf(messages.get(i));
      if (callIds.isEmpty()) {
        List<String> strayResultIds = toolResultIdsOf(messages.get(i));
        if (!strayResultIds.isEmpty()) {
          throw new IllegalArgumentException(
              "tool_result outside an answering message: " + strayResultIds.getFirst());
        }
        i++;
        continue;
      }
      if (i + 1 >= messages.size()) {
        throw new IllegalArgumentException("unanswered tool_use: " + callIds.getFirst());
      }
      Message next = messages.get(i + 1);
      if (next.role() != Role.USER) {
        throw new IllegalArgumentException("unanswered tool_use: " + callIds.getFirst());
      }
      List<String> resultIds = toolResultIdsOf(next);
      for (String callId : callIds) {
        if (!resultIds.contains(callId)) {
          throw new IllegalArgumentException("unanswered tool_use: " + callId);
        }
      }
      for (String resultId : resultIds) {
        if (!callIds.contains(resultId)) {
          throw new IllegalArgumentException("tool_result for an unknown id: " + resultId);
        }
      }
      i += 2;
    }
  }

  public static Context of(List<Message> messages) {
    return new Context(messages);
  }

  /**
   * The largest index {@code cut <= messages.size() - keepRecentMessages} at which {@code
   * messages.get(cut)} is a genuine user turn — a {@link Role#USER} message whose blocks are all
   * {@link TextBlock}s, never a spot between an assistant {@code tool_use} and the message carrying
   * its results. Walks downward from the limit (clamped to {@code messages.size() - 1} so a {@code
   * keepRecentMessages} of {@code 0} still indexes a real message); {@code 0} when no index
   * qualifies, which tells the caller nothing is safe to compact away.
   */
  public int pairSafeCut(int keepRecentMessages) {
    int limit = Math.min(messages.size() - keepRecentMessages, messages.size() - 1);
    for (int cut = limit; cut > 0; cut--) {
      if (isGenuineUserTurn(messages.get(cut))) {
        return cut;
      }
    }
    return 0;
  }

  /**
   * The prefix {@code [0, cut)} as a new {@code Context}. {@code cut} must come from {@link
   * #pairSafeCut}.
   */
  public Context head(int cut) {
    return new Context(messages.subList(0, cut));
  }

  private static boolean isGenuineUserTurn(Message message) {
    return message.role() == Role.USER
        && !message.content().isEmpty()
        && message.content().stream().allMatch(TextBlock.class::isInstance);
  }

  private static List<String> toolUseIdsOf(Message message) {
    if (message.role() != Role.ASSISTANT) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      if (block instanceof ToolUseBlock toolUse) {
        ids.add(toolUse.call().id());
      }
    }
    return ids;
  }

  private static List<String> toolResultIdsOf(Message message) {
    List<String> ids = new ArrayList<>();
    for (ContentBlock block : message.content()) {
      if (block instanceof ToolResultBlock toolResult) {
        ids.add(toolResult.toolUseId());
      }
    }
    return ids;
  }
}
