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
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The tail API's dogfood (design §4): a {@link Memory} that keeps only a bounded tail of the
 * transcript verbatim, folding everything older into a running summary once that tail grows past a
 * threshold.
 *
 * <p>{@code remember} appends to the transcript, same as {@link TranscriptMemory} — retention
 * policy lives entirely on the {@code recall} side. {@code recall} loads the current {@link
 * SummaryStore.Summary} — absent means nothing has ever been folded, so the whole transcript is the
 * tail — then loads {@link Transcript#tail(ConversationId, long) transcript.tail(id, watermark)},
 * and, only once that tail's size exceeds {@code tailThreshold}, asks the model to fold the summary
 * and the tail's pair-safe prefix into a new summary. (Transcript versions start at {@code 0}
 * (design §2), so "nothing folded yet" is tracked internally as watermark {@code -1} — one below
 * the first real version — never as a stored {@code Summary}; a persisted watermark is always a
 * real transcript version.) The boundary it folds up to is chosen the same way {@link
 * Context#pairSafeCut} chooses one — a genuine user turn, never between a tool call and its answer
 * — so a tool exchange straddling the threshold is always kept whole, whichever side of the cut it
 * lands on. The new summary is saved watermarked at the last folded transcript version, and the
 * tail is reloaded from there.
 *
 * <p>The watermark is the bookkeeping: a crash at any point between summarizing and saving simply
 * means the next recall re-summarizes the same tail and lands on the same watermark. A lost {@link
 * SummaryStore#save} is re-done work, never lost words — the words still live in the transcript,
 * which {@link SummaryStore} never fences against (design §10).
 *
 * <p>Its model spend never touches {@link
 * org.jwcarman.nessy.api.conversation.ConversationState#usage}: this class has no access to a
 * {@code ConversationState} at all — {@code Memory} is keyed by {@link ConversationId} alone — so
 * the existing usage jurisdiction ruling (design §10.6) is upheld by construction, not by
 * discipline.
 *
 * <p>The rendered context is the summary, when non-empty, as one opening user message, followed by
 * the tail's messages, open-tail-trimmed exactly as {@link TranscriptMemory} trims it — the same
 * border law applies to every transcript-backed memory.
 *
 * <p>A thin face now: the fold/summarize/render mechanism above lives in package-private {@link
 * SummarizingHydrator}, this class only holds the transcript and a hydrator built from its
 * constructor arguments, and {@code recall} delegates.
 */
public final class SummarizingMemory implements Memory {

  private final Transcript transcript;
  private final ContextHydrator hydrator;

  public SummarizingMemory(
      Transcript transcript,
      SummaryStore summaries,
      ModelProvider provider,
      String model,
      String prompt,
      int tailThreshold) {
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    this.hydrator = ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold);
  }

  @Override
  public void remember(ConversationId id, Message message) {
    transcript.append(id, message); // idempotency is the transcript's own no-stutter rule
  }

  @Override
  public Context recall(ConversationId id) {
    return hydrator.hydrate(id, transcript);
  }
}
