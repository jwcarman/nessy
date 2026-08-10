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
 * <p>Events are the only input to {@code Reducer}. Streaming text arrives as ordinary events, which
 * is why the loop streams natively instead of growing a second code path for it.
 *
 * <p>Every variant is self-attributing: {@link #conversationId()} names the conversation the event
 * belongs to, carried as each variant's first component. This is what lets {@code Reducer.reduce}
 * reject a fact addressed to one conversation but folded into another's state, and what lets the
 * engine publish the grammar event itself rather than wrapping it in an envelope.
 */
public sealed interface ConversationEvent extends ConversationScoped {

  @Override
  ConversationId conversationId();

  /** A human said something, as arbitrary content blocks rather than plain text. */
  record UserSaid(ConversationId conversationId, List<ContentBlock> content)
      implements ConversationEvent {

    public UserSaid {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      content = List.copyOf(content);
    }

    /** The common case: a single block of prose. */
    public static UserSaid of(ConversationId conversationId, String text) {
      return new UserSaid(conversationId, List.of(new TextBlock(text)));
    }
  }

  /** A chunk of assistant prose arrived from the stream. */
  record TextDelta(ConversationId conversationId, String text) implements ConversationEvent {

    public TextDelta {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
    }
  }

  /** A chunk of the model's visible reasoning arrived from the stream. */
  record ThinkingDelta(ConversationId conversationId, String text) implements ConversationEvent {

    public ThinkingDelta {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
    }
  }

  /** The provider finished a thinking block and delivered its signature. */
  record ThinkingSigned(ConversationId conversationId, String signature)
      implements ConversationEvent {

    public ThinkingSigned {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(signature, "signature must not be null");
    }
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  record RedactedThinkingArrived(ConversationId conversationId, String data)
      implements ConversationEvent {

    public RedactedThinkingArrived {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** The model finished emitting one complete tool call. */
  record ToolCallRequested(ConversationId conversationId, ToolCall call)
      implements ConversationEvent {

    public ToolCallRequested {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
    }
  }

  /** The model's turn is over. */
  record ModelTurnEnded(ConversationId conversationId, StopReason reason, Usage usage)
      implements ConversationEvent {

    public ModelTurnEnded {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /** The approval question for one call has been answered. */
  record ApprovalDecided(ConversationId conversationId, ToolCall call, Decision decision)
      implements ConversationEvent {

    public ApprovalDecided {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
    }
  }

  /** A tool ran to completion, successfully or not. */
  record ToolFinished(ConversationId conversationId, ToolCall call, ToolResult result)
      implements ConversationEvent {

    public ToolFinished {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
    }
  }

  /**
   * A compaction attempt finished. {@code workingSet} is the strategy's result: smaller than the
   * working set that went in means the reducer replaces its messages wholesale; the same size or
   * larger is a skip with no other change. Carries no spend — the jurisdiction rule (design §10.6)
   * reserves the ledger for the loop's own spend; whatever a compactor's own call cost is
   * telemetry's business, not this event's.
   */
  record Compacted(ConversationId conversationId, List<Message> workingSet)
      implements ConversationEvent {

    public Compacted {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(workingSet, "workingSet must not be null");
      workingSet = List.copyOf(workingSet);
    }
  }

  /** Compaction was attempted but did not happen; the turn proceeds uncompacted. */
  record CompactionSkipped(ConversationId conversationId, String reason)
      implements ConversationEvent {

    public CompactionSkipped {
      Objects.requireNonNull(conversationId, "conversationId must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
