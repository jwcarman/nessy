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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.ConversationScoped;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Something that happened to a conversation.
 *
 * <p>Events are the only input to {@link org.jwcarman.nessy.api.conversation.ConversationState#fold
 * ConversationState.fold}. Four facts, settled rather than streamed — the loop's own executors
 * narrate texture as it arrives via {@code TurnObserver} instead.
 *
 * <p>Every variant is self-attributing: {@link #conversationId()} names the conversation the event
 * belongs to, carried as each variant's first component. This is what lets {@code fold} reject a
 * fact addressed to one conversation but folded into another's state, and what lets the loop
 * publish the grammar event itself rather than wrapping it in an envelope.
 */
public sealed interface ConversationEvent extends ConversationScoped {

  String CONVERSATION_ID_MUST_NOT_BE_NULL = "conversationId must not be null";

  @Override
  ConversationId conversationId();

  /**
   * The agent was told something, as arbitrary content blocks rather than plain text.
   *
   * <p>The name matches the verb: you {@code tell} the agent, and this is the fact that it was
   * told. The teller need not be human — triggers include webhooks and crons.
   */
  record AgentTold(ConversationId conversationId, List<ContentBlock> content)
      implements ConversationEvent {

    public AgentTold {
      Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
      content = List.copyOf(content);
    }

    /** The common case: a single block of prose. */
    public static AgentTold of(ConversationId conversationId, String text) {
      return new AgentTold(conversationId, List.of(new TextBlock(text)));
    }
  }

  /**
   * The model's settled contribution: one assistant message — text, thinking, and any tool-use
   * blocks (the homework) as its content — plus how the call stopped and what it cost. One fact per
   * call; the fold unpacks the homework into effects.
   */
  record ModelResponded(
      ConversationId conversationId, Message message, StopReason reason, Usage usage)
      implements ConversationEvent {

    public ModelResponded {
      Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(message, "message must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The model call failed in a way re-performing cannot fix — canonically, the context outgrew the
   * window. There is no party left in the dialogue to show this to (the model is the party that
   * failed), so it is fate, not data: the fold answers it with {@code FAILED}. Transient failures
   * (socket resets, retries exhausted) are exceptions, not facts — status still points at the work
   * and re-driving is the recovery.
   */
  record ModelCallFailed(ConversationId conversationId, String reason)
      implements ConversationEvent {

    public ModelCallFailed {
      Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }

  /** A tool ran to completion, successfully or not. */
  record ToolFinished(ConversationId conversationId, ToolCall call, ToolResult result)
      implements ConversationEvent {

    public ToolFinished {
      Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
