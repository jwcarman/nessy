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
package org.jwcarman.nessy.engine.history;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.tool.ToolCalls;

/**
 * One call of a request the model made, read out of the event that recorded it.
 *
 * <p>The one place that knows how a call's arguments and action are stored: the call's entry in the
 * event holds the action sentence, and the event's request content holds the tool-use block with
 * the arguments. What an approver is handed and what a waiting approval is rebuilt from both come
 * from here, so they cannot differ.
 */
public final class RequestedCalls {

  private RequestedCalls() {}

  /**
   * The call whose entry in {@code asked} satisfies {@code entry}, with its arguments read from the
   * stored request content of {@code agentPayloads}.
   *
   * <p>Empty when the entry is not there, when the content is gone, or when the content holds no
   * block at the entry's position that is the entry's call.
   */
  public static Optional<ToolCalls.ResolvedCall> resolve(
      Payloads agentPayloads,
      AgentEvent.ActionsRequested asked,
      Predicate<ActionRequest.ToolCall> entry) {
    List<ActionRequest.ToolCall> entries =
        asked.actions().stream()
            .filter(ActionRequest.ToolCall.class::isInstance)
            .map(ActionRequest.ToolCall.class::cast)
            .toList();
    for (int position = 0; position < entries.size(); position++) {
      if (entry.test(entries.get(position))) {
        return blockAt(agentPayloads, asked, entries.get(position), position);
      }
    }
    return Optional.empty();
  }

  /**
   * The entries were made one for each tool-use block, in order, so the entry at a position and the
   * block at that position are the same call. The call id is no key: a response may repeat one.
   * When there is no block at the position, or the block there is not the entry's call, nothing is
   * guessed. (A request with more blocks than entries still resolves the entries it has: an entry
   * is never recorded for a block that was not asked for, and the handlers rely on that leniency.)
   */
  private static Optional<ToolCalls.ResolvedCall> blockAt(
      Payloads agentPayloads,
      AgentEvent.ActionsRequested asked,
      ActionRequest.ToolCall stored,
      int position) {
    return switch (agentPayloads.get(asked.request())) {
      case Payloads.Resolved.Found(List<Block> blocks) -> {
        List<Block.ToolCall> calls =
            blocks.stream()
                .filter(Block.ToolCall.class::isInstance)
                .map(Block.ToolCall.class::cast)
                .toList();
        if (position >= calls.size()) {
          yield Optional.empty();
        }
        Block.ToolCall call = calls.get(position);
        yield call.id().equals(stored.id()) && call.name().equals(stored.name())
            ? Optional.of(new ToolCalls.ResolvedCall(asked.turn(), call, stored.action()))
            : Optional.empty();
      }
      // A request whose content is gone is not a call that can be performed, and saying so is
      // better than performing one with arguments nobody can see.
      case Payloads.Resolved.Missing _ -> Optional.empty();
    };
  }
}
