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
package org.jwcarman.nessy.examples.watchman.pekko;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/** A watchman round with no tokens and no network. Deterministic on the context it is given. */
public final class ScriptedWatchmanModel implements WatchmanModel {

  private static final ObjectMapper JSON = new ObjectMapper();

  // Plausible, non-zero on purpose: a script that always reports Usage.zero() would let a chat
  // span's token attributes go missing without any test noticing.
  private static final Usage USAGE = new Usage(606, 142, 0, 0);

  private final Duration latency;

  public ScriptedWatchmanModel(Duration latency) {
    this.latency = latency;
  }

  @Override
  public ModelReply reply(Context context) {
    sleep(latency);

    List<Message> messages = context.messages();
    int lastUser = lastPlainUser(messages);
    boolean answeredThisRound =
        messages.subList(lastUser + 1, messages.size()).stream()
            .anyMatch(ScriptedWatchmanModel::carriesToolResults);

    if (answeredThisRound) {
      return new ModelReply.Said(
          Message.assistant(
              List.of(
                  new TextBlock(
                      "Rounds complete. Disk is filling and there are unused images to reclaim."))),
          USAGE);
    }

    // Unique per CALL, never derived from the context -- which is a property real models have and
    // which this fake briefly did not. Deriving ids from what is visible looks fine until the
    // context is budget-clipped: the count drops back, an id gets reissued, and Memory's
    // idempotence-by-key silently swallows the second use because a Remembrance key is unique for
    // the life of the agent, not for the life of the prompt. The round then hangs forever waiting
    // for a result that was never recorded.
    String suffix = "-" + Identifiers.next();
    List<ToolCall> calls =
        List.of(
            new ToolCall("call-disk" + suffix, "disk_usage", JSON.createObjectNode()),
            new ToolCall("call-containers" + suffix, "containers", JSON.createObjectNode()),
            new ToolCall("call-prune" + suffix, "prune_images", JSON.createObjectNode()));

    List<ContentBlock> blocks =
        List.of(
            new TextBlock("Looking at the box."),
            new ToolUseBlock(calls.get(0), null),
            new ToolUseBlock(calls.get(1), null),
            new ToolUseBlock(calls.get(2), null));
    return new ModelReply.AskedForTools(Message.assistant(blocks), calls, USAGE);
  }

  /** A user message that is a person talking, not a batch of tool results. */
  private static int lastPlainUser(List<Message> messages) {
    int last = -1;
    for (int i = 0; i < messages.size(); i++) {
      Message message = messages.get(i);
      if (message.role() == Role.USER && !carriesToolResults(message)) {
        last = i;
      }
    }
    return last;
  }

  private static boolean carriesToolResults(Message message) {
    return message.content().stream()
        .anyMatch(org.jwcarman.nessy.api.message.ToolResultBlock.class::isInstance);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted in the scripted model", e);
    }
  }
}
