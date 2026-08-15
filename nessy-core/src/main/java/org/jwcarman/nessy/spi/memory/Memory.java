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

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.transcript.Transcript;

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
 * That rule carries one shared exception, an open tail: the loop remembers a tool-use message the
 * moment its fold settles, before it knows whether the call will park, so a parked conversation's
 * raw telling can legitimately end in an unanswered assistant tool-use message. {@code recall}
 * trims that trailing open tail so the returned {@code Context} stays legal — the tool-exchange
 * atomic-unit rule above is otherwise unchanged. Halting mid-turn while a call is parked remains a
 * recorded open case.
 *
 * <p>Tellings are at-least-once: a crash between telling and persisting re-tells the same message
 * on recovery, so {@link #remember} must be idempotent. The implementation is wired per agent —
 * different agents carry different memory systems — while the contract is keyed by conversation,
 * one instance serving all of that agent's conversations.
 */
public interface Memory {

  void remember(ConversationId id, Message message);

  Context recall(ConversationId id);

  /**
   * The bounded default for a long-lived agent: {@code remember} delegates to {@code delegate}
   * unchanged, while {@code recall} clips whatever {@code delegate} returns to its last {@code n}
   * messages via {@link Context#keepRecent(int)} — a pair-safe trim, never splitting a tool-use
   * message from the results answering it. A one-line factory for the shape most agents actually
   * want in production: unlimited retention underneath, a bounded window at the border.
   *
   * @param delegate the {@code Memory} whose full history is retained and clipped on the way out
   * @param n how many of the most recent messages {@code recall} keeps intact; must be at least 1
   *     (the deleted {@code WindowedMemory} this factory replaces enforced the same floor at
   *     construction, so a misconfigured window fails loud at startup — e.g. night-watchman's
   *     {@code watchman.window} property — rather than quietly booting with a near-empty context);
   *     forwarded verbatim to {@link Context#keepRecent(int)}, so the same {@code n} constraints
   *     apply beyond that floor
   * @throws IllegalArgumentException if {@code n} is less than 1
   */
  static Memory windowed(Memory delegate, int n) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    if (n < 1) {
      throw new IllegalArgumentException("window must be at least 1");
    }
    return new Memory() {
      @Override
      public void remember(ConversationId id, Message message) {
        delegate.remember(id, message);
      }

      @Override
      public Context recall(ConversationId id) {
        return delegate.recall(id).keepRecent(n);
      }
    };
  }

  /**
   * The documented front door for composing transcript-backed memory: hydrate, then run an ordered
   * list of {@link ContextTransformer} stages. {@code transcript} is the one required ingredient —
   * {@code remember} always appends to it, whatever hydration chooses to re-read.
   *
   * <p>The degenerate case is the floor, not a special case: {@code Memory.pipeline(transcript)
   * .build()} — no hydrator named, no stages — hydrates with {@link ContextHydrator#full()} and
   * transforms nothing, the whole history every time, behaviorally identical to {@link
   * TranscriptMemory}. Every addition to the chain from there is strictly opt-in.
   *
   * @param transcript the log {@code remember} appends to and the default hydrator reads whole
   */
  static PipelineMemory.Builder pipeline(Transcript transcript) {
    return new PipelineMemory.Builder(transcript);
  }
}
