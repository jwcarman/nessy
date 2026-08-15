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

/**
 * One stage of the context pipeline: takes the context as built so far, returns the context as it
 * should continue. May trim, redact, elide, reorder-within-law, or append.
 *
 * <p>Legality is enforced by the type, not by trust: a {@link Context} can only be built through
 * {@code Context.of}, which rejects illegal shapes (a split tool exchange, an unanswered tool-use
 * mid-history). A transformer therefore cannot hand the model a corrupted dialogue — the worst it
 * can do is throw. To the pipeline, every stage is required: a stage that throws propagates out of
 * {@code recall} rather than being papered over, so the turn fails, the durable machinery retries
 * it later, and the model never sees a context the stage did not bless. Eliding a tool exchange
 * means removing the pair atomically; the border check makes a half-elision fail loud rather than
 * handing out a corrupted context.
 *
 * <p>Stage output is synthesized at recall and never remembered: not told to the transcript, not
 * folded into any summary. One fresh pass per model call, no accumulation, no drift.
 */
public interface ContextTransformer {

  /**
   * Transforms {@code context} for conversation {@code id}, returning the context to continue with.
   */
  Context transform(ConversationId id, Context context);

  /**
   * Wraps {@code delegate} so its failures cannot fail the pipeline: fail-closed is the pipeline's
   * only behavior at the seam (every stage is required, a throw propagates), so optionality is not
   * a pipeline concept — a stage optionalizes itself by opting into this decorator instead. On any
   * {@link RuntimeException} thrown by {@code delegate}, logs exactly one {@code WARN} line naming
   * the delegate and the conversation id, then returns the input {@code context} unchanged — this
   * call behaves as if the stage were absent. A partial output from a failed stage is never used:
   * the delegate either finishes cleanly or its output is discarded whole.
   *
   * @param delegate the stage to make best-effort; must not be null
   */
  static ContextTransformer optional(ContextTransformer delegate) {
    return new OptionalTransformer(delegate);
  }
}
