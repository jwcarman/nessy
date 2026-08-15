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
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * Recall-side context production as a named pipeline with exactly two seams: a {@link
 * ContextHydrator} produces the initial context from durable history, and an ordered list of {@link
 * ContextTransformer} stages reshapes it — clamping, redacting, eliding, amending — before it goes
 * out the door. Both seams are open; {@code remember} always appends to the pipeline's own {@link
 * Transcript} (idempotency stays the transcript's own no-stutter rule), whatever hydration chooses
 * to re-read.
 *
 * <p><b>The degenerate floor.</b> {@code Memory.pipeline(transcript).build()} — no hydrator named,
 * no stages — hydrates with {@link ContextHydrator#full()} and transforms nothing: the whole
 * history, every time, behaviorally identical to {@link TranscriptMemory}. Every addition to the
 * chain from there is strictly opt-in.
 *
 * <p><b>Retention stays delegated.</b> The kernel knows only {@code Memory} — {@code
 * remember}/{@code recall} is the whole retention contract, and nothing in the loop or the api
 * package references {@code Transcript}. {@code PipelineMemory} does not change that: it is one
 * {@code Memory} implementation family that <em>chooses</em> transcript backing, declared in its
 * own constructor; {@link ContextHydrator}'s {@code Transcript} parameter is that family's internal
 * seam, not a kernel contract. A {@code Memory} that wants different retention implements the
 * two-method interface directly and owes the pipeline nothing.
 */
public final class PipelineMemory implements Memory {

  private final Transcript transcript;
  private final ContextHydrator hydrator;
  private final List<ContextTransformer> stages;

  private PipelineMemory(
      Transcript transcript, ContextHydrator hydrator, List<ContextTransformer> stages) {
    this.transcript = transcript;
    this.hydrator = hydrator;
    this.stages = List.copyOf(stages);
  }

  @Override
  public void remember(ConversationId id, Message message) {
    transcript.append(id, message); // idempotency is the transcript's own no-stutter rule
  }

  @Override
  public Context recall(ConversationId id) {
    Context context = hydrator.hydrate(id, transcript);
    for (ContextTransformer stage : stages) {
      context = stage.transform(id, context);
    }
    return context;
  }

  /**
   * Assembles a {@link PipelineMemory}: names a hydration strategy (default {@link
   * ContextHydrator#full()}) and an ordered list of {@link ContextTransformer} stages, every one of
   * them required — optional behavior arrives pre-wrapped via {@link
   * ContextTransformer#optional(ContextTransformer)}.
   */
  public static final class Builder {

    private final Transcript transcript;
    private ContextHydrator hydrator;
    private final List<ContextTransformer> stages = new ArrayList<>();

    Builder(Transcript transcript) {
      this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    }

    /**
     * Sets the hydration strategy. Setting a hydrator twice — by this verb or by {@link
     * #summarizing}, in either order — is an {@link IllegalStateException}: one hydration strategy
     * per pipeline.
     */
    public Builder hydrator(ContextHydrator hydrator) {
      Objects.requireNonNull(hydrator, "hydrator must not be null");
      if (this.hydrator != null) {
        throw new IllegalStateException("one hydration strategy per pipeline");
      }
      this.hydrator = hydrator;
      return this;
    }

    /**
     * Sugar for {@code hydrator(ContextHydrator.summarizing(summaries, provider, model, prompt,
     * tailThreshold))}; parameters mirror {@link SummarizingMemory}'s constructor exactly.
     */
    public Builder summarizing(
        SummaryStore summaries,
        ModelProvider provider,
        String model,
        String prompt,
        int tailThreshold) {
      return hydrator(
          ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold));
    }

    /**
     * Registers the pair-safe trim ({@link Context#keepRecent(int)}) as a required stage at its
     * call position; same {@code n >= 1} floor as {@link Memory#windowed(Memory, int)}.
     *
     * @throws IllegalArgumentException if {@code n} is less than 1
     */
    public Builder keepRecent(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("window must be at least 1");
      }
      stages.add((id, context) -> context.keepRecent(n));
      return this;
    }

    /** Registers {@code stage} as a required stage, appended after any already registered. */
    public Builder transform(ContextTransformer stage) {
      stages.add(Objects.requireNonNull(stage, "stage must not be null"));
      return this;
    }

    /**
     * Builds the {@link PipelineMemory}: {@code remember} appends to the transcript, {@code recall}
     * runs the named (or defaulted) hydrator, then folds the stage list in registration order.
     */
    public PipelineMemory build() {
      return new PipelineMemory(
          transcript, hydrator != null ? hydrator : ContextHydrator.full(), stages);
    }
  }
}
