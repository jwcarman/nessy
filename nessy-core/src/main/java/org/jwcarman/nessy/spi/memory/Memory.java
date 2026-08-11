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
package org.jwcarman.nessy.spi.memory;

import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * The content jurisdiction: told everything that was said, and decides what the model is reminded
 * of.
 *
 * <p>Two duties. It is <em>told</em> — every message-grade happening, in order, for the
 * conversation's whole life: the user message when {@code AgentTold} folds, the assistant message
 * when {@code ModelResponded} folds, the batched results message when the tool debt clears. That
 * list is closed: the wire dialogue has exactly three message producers. And it is <em>asked</em> —
 * {@link #recall} builds the finished context for the next model call.
 *
 * <p>Freedom of retention, rule of law at the border: inside, an implementation may transcribe,
 * summarize, checkpoint, embed, or discard — the harness never audits how it thinks. At the border,
 * {@code recall} must return a legal {@code Context}; the unit of retention is the
 * <em>transaction</em> (an assistant message carrying tool-use blocks and the results message
 * answering it are one atomic unit — keep both or drop both, never split, never reorder across).
 *
 * <p>Tellings are at-least-once: a crash between telling and persisting re-tells the same message
 * on recovery, so {@link #remember} must be idempotent. The implementation is wired per agent —
 * different agents carry different memory systems — while the contract is keyed by conversation,
 * one instance serving all of that agent's conversations.
 */
public interface Memory {

  void remember(ConversationId id, Message message);

  Context recall(ConversationId id);
}
