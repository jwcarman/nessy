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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.SummaryStore.Summary;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

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
 */
public final class SummarizingMemory implements Memory {

  private static final int SUMMARY_MAX_TOKENS = 4096;

  /**
   * The watermark of "nothing has ever been folded" — one below the first real transcript version
   * (versions start at {@code 0}, design §2), so {@code transcript.tail(id, NOTHING_FOLDED)} yields
   * the whole transcript. Never itself persisted: a saved {@link Summary}'s watermark is always a
   * real transcript version.
   */
  private static final long NOTHING_FOLDED = -1L;

  private final Transcript transcript;
  private final SummaryStore summaries;
  private final ModelProvider provider;
  private final String model;
  private final String prompt;
  private final int tailThreshold;

  public SummarizingMemory(
      Transcript transcript,
      SummaryStore summaries,
      ModelProvider provider,
      String model,
      String prompt,
      int tailThreshold) {
    this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    if (tailThreshold < 0) {
      throw new IllegalArgumentException("tailThreshold must be at least 0");
    }
    this.tailThreshold = tailThreshold;
  }

  @Override
  public void remember(ConversationId id, Message message) {
    transcript.append(id, message); // idempotency is the transcript's own no-stutter rule
  }

  @Override
  public Context recall(ConversationId id) {
    Summary current = summaries.find(id).orElse(new Summary(NOTHING_FOLDED, ""));
    List<Transcript.Entry> tail = transcript.tail(id, current.watermark());
    Summary folded = tail.size() > tailThreshold ? fold(id, current, tail) : current;
    List<Transcript.Entry> finalTail =
        folded.watermark() == current.watermark() ? tail : transcript.tail(id, folded.watermark());
    return render(folded, finalTail);
  }

  /**
   * One model call, folding {@code current}'s text and as much of {@code tail} as a pair-safe
   * boundary allows into a new, saved {@link Summary}. Returns {@code current} unchanged — no model
   * call ever having happened this round — when the tail carries no pair-safe boundary at all (an
   * all-open-tool-exchange tail, in practice vanishingly rare): there is nothing safe to fold, so
   * nothing is folded.
   *
   * <p>Also returns {@code current} unchanged, with no save and no watermark advance, when the
   * model does call but its folded text comes back {@link String#isBlank()}: a blank summary is not
   * a legitimately empty one — the words it should have folded would be silently dropped from every
   * future recall the moment the watermark moved past them, since the transcript's own tail window
   * would no longer include the folded messages. Leaving the watermark where it was means the next
   * recall simply retries the same fold over the same tail.
   */
  private Summary fold(ConversationId id, Summary current, List<Transcript.Entry> tail) {
    List<Message> tailMessages =
        TranscriptTrim.withoutOpenTail(tail.stream().map(Transcript.Entry::message).toList());
    Context tailContext = Context.of(tailMessages);
    int cut = tailContext.pairSafeCut(0);
    if (cut == 0) {
      return current;
    }
    List<Message> toFold = tailMessages.subList(0, cut);
    long newWatermark = tail.get(cut - 1).version();
    String newText = summarize(current.text(), toFold);
    if (newText.isBlank()) {
      return current;
    }
    Summary folded = new Summary(newWatermark, newText);
    summaries.save(id, folded);
    return folded;
  }

  /** One model call: prior summary text (if any) plus the messages it folds, in, plain text out. */
  private String summarize(String priorText, List<Message> toFold) {
    List<Message> foldMessages = new ArrayList<>(toFold.size() + 1);
    if (!priorText.isBlank()) {
      foldMessages.add(Message.user(priorText));
    }
    foldMessages.addAll(toFold);
    ModelRequest request =
        new ModelRequest(
            Context.of(foldMessages), prompt, model, SUMMARY_MAX_TOKENS, List.of(), Set.of(), null);
    StringBuilder text = new StringBuilder();
    try (ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        if (event instanceof ModelEvent.TextChunk(String chunk)) {
          text.append(chunk);
        }
      }
    }
    return text.toString();
  }

  private static Context render(Summary summary, List<Transcript.Entry> tail) {
    List<Message> tailMessages =
        TranscriptTrim.withoutOpenTail(tail.stream().map(Transcript.Entry::message).toList());
    List<Message> rendered = new ArrayList<>(tailMessages.size() + 1);
    if (!summary.text().isBlank()) {
      rendered.add(Message.user(summary.text()));
    }
    rendered.addAll(tailMessages);
    return Context.of(rendered);
  }
}
