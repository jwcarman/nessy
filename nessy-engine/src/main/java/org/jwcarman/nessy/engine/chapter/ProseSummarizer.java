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
package org.jwcarman.nessy.engine.chapter;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;

/**
 * Writes the record of one chapter by showing a model its turns and asking for the record.
 *
 * <p>Reads the chapter's turns itself, whole, and calls the provider directly: this is not an
 * agent's conversation, so it goes through no assembler and no tool loop. No tools are offered and
 * nothing is streamed.
 *
 * <p><b>The chapter is sent as text, in one user message.</b> That message is {@link
 * Transcripts#render} of the chapter's turns, a blank line, and the closing ask; the request holds
 * no summaries, no tail, and no tool-call, tool-result or reasoning blocks. A model that is shown
 * tool history and offered no tools may answer nothing: measured on 2026-10-02 on
 * claude-sonnet-4-5, five empty replies of five for a chapter with tool calls. Written out as
 * lines, a call is something the model reads rather than something it is expected to continue.
 *
 * <p><b>It returns what the model wrote, blank included.</b> Judging whether that is good enough
 * belongs to the caller, which treats blank as a failure. What it does refuse to return is a record
 * the model never wrote: a chapter with no turns, or a call that came back as anything but an
 * answer, is an {@link IllegalStateException} naming the chapter's bounds.
 *
 * <p><b>A summary that was cut off at the output limit is refused,</b> not kept: a summary is
 * written once and then stands in for its chapter, so one that stops mid-sentence would lose the
 * rest of the chapter for good. The chapter stays unsummarised.
 *
 * <p>The options are validated when it is built, so a bad option fails at wiring rather than at the
 * first chapter that closes.
 */
public final class ProseSummarizer implements Summarizer {

  /** What the model is told it is doing. Written for a chapter that stands alone. */
  public static final String PROMPT =
      """
      You are writing the record of one part of a longer conversation. It will be shown in place of that
      part, and it is the only trace of it that is kept in view, so it must stand alone.

      Keep what a reader would need in order to continue:
      - who said or did what, with names, places, numbers, identifiers and dates exactly as given
      - decisions made, and what they were made for
      - commitments and obligations, in either direction, and how things turned out
      - questions raised that are still open

      Work out actual dates when someone says "yesterday" or "last week" and the date is known. Do not
      narrate, and do not describe the conversation as a conversation. Keep exact values: a name, a
      number or an identifier is worth more than a sentence about it.
      """;

  private static final String ASK = "Write the record of everything above now.";

  private final TurnHistories histories;
  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final String prompt;

  public ProseSummarizer(
      TurnHistories histories, InferenceProvider provider, InferenceOptions options) {
    this(histories, provider, options, PROMPT);
  }

  /**
   * As above, telling the model {@code prompt} in place of {@link #PROMPT}: the same reading of the
   * chapter's turns and the same closing ask, with a different idea of what the record is for.
   */
  public ProseSummarizer(
      TurnHistories histories,
      InferenceProvider provider,
      InferenceOptions options,
      String prompt) {
    this.histories = Objects.requireNonNull(histories, "histories must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.prompt = Objects.requireNonNull(prompt, "prompt must not be null");
    provider.validate(options);
  }

  @Override
  public String summarize(Chapter chapter) {
    List<Turn> turns =
        histories
            .forAgent(chapter.agentType(), chapter.agentId())
            .turnsBetween(chapter.from(), chapter.through());
    if (turns.isEmpty()) {
      throw new IllegalStateException(
          "no turns from %s through %s".formatted(chapter.from(), chapter.through()));
    }

    Turn asking =
        new Turn(
            new TurnId(turns.getLast().id().value() + 1),
            new Input(
                new Seq(turns.getLast().id().value() + 1),
                List.of(new Block.Text(Transcripts.render(turns) + "\n" + ASK))),
            List.of(),
            null,
            0);

    InferenceResult result =
        provider.infer(
            // A chapter is summarised once, so there is nothing here for a vendor's cache to keep.
            new InferenceRequest(
                    new SystemPrompt(prompt),
                    new InferenceContext(
                        List.of(), List.of(), List.of(), List.of(), asking, List.of()),
                    Toolset.none(),
                    options)
                .asOneOff());
    if (result instanceof InferenceResult.Truncated) {
      throw new IllegalStateException(
          "the summary of turns %s through %s was cut off at the output limit"
              .formatted(chapter.from(), chapter.through()));
    }
    if (!(result instanceof InferenceResult.Answer(var blocks, _))) {
      throw new IllegalStateException(
          "the model did not answer for turns %s through %s: %s"
              .formatted(chapter.from(), chapter.through(), result));
    }
    return Transcripts.text(blocks);
  }
}
