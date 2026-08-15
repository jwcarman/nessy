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
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.transcript.Transcript;
import org.jwcarman.nessy.spi.transcript.TranscriptTrim;

/**
 * Bootstraps the context from durable history. The transcript arrives as a parameter — the pipeline
 * passes the same {@link Transcript} it remembers into, so told-history and re-read-history can
 * never disagree — and a hydrator reads as much or as little of it as its strategy requires,
 * consulting whatever companion stores it holds. Duty at the border: apply the open-tail trim
 * ({@link TranscriptTrim#withoutOpenTail}) before {@link Context#of} — a parked conversation's raw
 * telling can legitimately end in an unanswered tool-use message, and {@code Context}'s validating
 * constructor rejects that shape.
 *
 * <p>The seam is open: a custom hydrator (bootstrap from a vector store, a checkpoint, an external
 * system of record) is a legitimate implementation, which is why {@link TranscriptTrim} is public —
 * the border duty must be dischargeable from outside this package.
 */
public interface ContextHydrator {

  /** Produces the initial context for {@code id} from {@code transcript}. */
  Context hydrate(ConversationId id, Transcript transcript);

  /** The floor: the whole telling, open-tail-trimmed. */
  static ContextHydrator full() {
    return (id, transcript) ->
        Context.of(
            TranscriptTrim.withoutOpenTail(
                transcript.all(id).stream().map(Transcript.Entry::message).toList()));
  }

  /** {@link SummarizingHydrator}: summary head plus tail-since-watermark. */
  static ContextHydrator summarizing(
      SummaryStore summaries,
      ModelProvider provider,
      String model,
      String prompt,
      int tailThreshold) {
    return new SummarizingHydrator(summaries, provider, model, prompt, tailThreshold);
  }
}
