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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * One remembered fact (remembrance spec §2): the vocabulary a {@link Memory#remember(Remembrance)}
 * call carries. Every member arrives with its opaque {@link #key()} already minted at the fold site
 * — the turn identity {@link Memory}'s idempotence law converges on.
 *
 * <p>The members map one-to-one onto the three fold moments (spec §2): an observation folds into a
 * {@link UserMessage} (keyed by an opaque, per-fold identity); a model turn folds into an {@link
 * AssistantMessage} (keyed by its committed {@code ModelResponseId}) — whether or not it carries
 * {@code tool_use} blocks; a completed tool call folds into a {@link ToolExchange} (keyed by its
 * execution {@code ComputationId}) — one exchange per call id, carrying the call and its result
 * together so the pairing invariant survives even when the result folds long after the call, and
 * even when a memory implementation never sees the two arrive in the same batch, or in the same
 * order: {@link AssistantMessage}'s own javadoc spells out the ordering {@link Memory#recall()}
 * owes.
 *
 * <p>Benign redundancy is deliberate: an {@link AssistantMessage} may already carry {@code
 * tool_use} blocks naming the same calls a later {@link ToolExchange} answers — a memory
 * implementation may treat every {@code Remembrance} as self-contained rather than reaching back
 * into an earlier fact to reassemble one.
 *
 * <p>Sealed, house etiquette (spec §2): implementors switch on this exhaustively, no {@code
 * default} arm — a new member breaks every custom memory loudly at compile time, not silently at
 * runtime.
 *
 * <p>Carries a {@code "type"} discriminator naming the record on the wire, matching house
 * convention ({@code Phase}, {@code ContentBlock}): {@code user-message}, {@code
 * assistant-message}, {@code tool-exchange}. The values are a compatibility surface and must never
 * change.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Remembrance.UserMessage.class, name = "user-message"),
  @JsonSubTypes.Type(value = Remembrance.AssistantMessage.class, name = "assistant-message"),
  @JsonSubTypes.Type(value = Remembrance.ToolExchange.class, name = "tool-exchange")
})
public sealed interface Remembrance {

  /**
   * The opaque turn identity — the idempotency key {@link Memory#remember(Remembrance)} keys on.
   */
  String key();

  /** An observation delivery's rendered content, absorbed at {@code Idle} (spec §2). */
  record UserMessage(String key, Message message) implements Remembrance {
    public UserMessage {
      requireKey(key);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * A model turn — the assistant's word, whether it ends the turn or opens a fan-out. {@code
   * message} may carry {@code tool_use} blocks: when it does, its {@link ToolExchange}s may
   * remember AFTER this fact does (an earlier-answering sibling call folds — and is remembered —
   * before every pending call has finished, so this message and some of its own exchanges can
   * arrive in either order). {@link Memory#recall()} withholds an {@code AssistantMessage} that
   * names {@code tool_use} call ids until every one of them has a matching {@link ToolExchange}
   * remembered somewhere, then emits the two together (spec §3).
   */
  record AssistantMessage(String key, Message message) implements Remembrance {
    public AssistantMessage {
      requireKey(key);
      Objects.requireNonNull(message, "message must not be null");
    }
  }

  /**
   * One tool call and the result it came back with, paired — never split. {@code call} is the
   * model's request; {@code result} is what came back, already resolved out of any in-band failure.
   */
  record ToolExchange(String key, ToolCall call, ToolResult result) implements Remembrance {
    public ToolExchange {
      requireKey(key);
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key must not be blank");
    }
  }
}
