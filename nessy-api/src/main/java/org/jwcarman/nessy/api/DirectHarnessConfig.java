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

import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolConfig;

/**
 * How a {@link DirectHarness} is built, in the same words a {@link QueuedHarnessConfig} uses.
 *
 * <p>Almost everything means what it means on the other door: an agent type, a system prompt, what
 * a model is shown, which tools it may reach for and who guards them. Assembling a context and
 * binding a tool are one job each, and which door will start the turn is not part of either.
 *
 * <p><b>What is absent is absent because nothing here can produce it.</b> There is no poll
 * interval, because there is no poller; the turn runs on the thread that asked for it. There is no
 * policy, because coalescing answers "what if more work arrives while this runs", and the only
 * thing that can put work here is the caller, who is blocked. There IS an in-flight cap ({@link
 * #maxInFlight(int)}) -- the effects a harness's turns owe at once are real work this door fans out
 * concurrently, and it is worth bounding even though nothing here polls for it.
 *
 * @param <I> what a caller hands in
 */
public interface DirectHarnessConfig<I> extends HarnessConfig<DirectHarnessConfig<I>> {

  /**
   * What this agent type is told about itself. Fixed for the life of the harness: set once, here,
   * and never asked again.
   *
   * <p>It is the head of every request, and a provider caches a request's leading text, so a change
   * in it invalidates everything cached for every agent of the type. What varies by agent belongs
   * in a {@link StateSource}; what varies by the moment, in an {@link AmbientSource}.
   */
  DirectHarnessConfig<I> systemPrompt(String prompt);

  /** How what a caller hands in becomes what the model reads. */
  DirectHarnessConfig<I> inputRenderer(InputRenderer<I> renderer);

  /** The model, the budget, and what it is shown. */
  DirectHarnessConfig<I> inference(Customizer<InferenceConfig> customizer);

  /**
   * How many effects this harness may have running at once, across every {@link DirectHarness#ask}
   * call it is serving. Defaults to 64.
   *
   * <p><b>Harness-wide, not scoped to one turn's own batch.</b> A bound scoped to a single {@code
   * ask} call would let twenty concurrent turns each mint their own full set of permits, which
   * bounds nothing that matters -- the thing worth bounding is how much load this harness, as a
   * whole, puts on a provider or a downstream service, and only a shared bound does that.
   *
   * <p><b>Sized very differently than the queued door's {@link EffectsConfig#maxInFlight(int)}
   * (default 4), despite the shared name, and deliberately so.</b> That one bounds a poller
   * draining a durable queue, where nothing blocks and work simply waits its turn a little longer
   * -- four is plenty. This one bounds callers who are synchronously blocked on {@link
   * DirectHarness#ask} waiting for their own answer, and an inference is admitted through it too,
   * so the very first batch of every turn takes a permit before that turn can even begin. A
   * harness-wide cap of four would therefore limit this whole harness to four concurrent turns and
   * make a fifth caller wait before its own turn could even start -- a surprising thing to discover
   * behind a web endpoint. 64 is a real ceiling against a harness gone runaway -- "the model
   * requested fifty tool calls at once" -- while staying far above ordinary concurrency, and on
   * virtual threads an unused permit costs nothing.
   */
  DirectHarnessConfig<I> maxInFlight(int maxInFlight);

  /**
   * Who hears what happens while the turn runs.
   *
   * <p>Worth having even though the answer is returned: a caller waiting on a model wants to watch
   * it arrive, and the deltas are the only part that can be shown before the end.
   */
  DirectHarnessConfig<I> listener(NarrationListener listener);

  <T> DirectHarnessConfig<I> tool(Tool<T> tool, Customizer<ToolConfig<T>> customizer);

  default <T> DirectHarnessConfig<I> tool(Tool<T> tool) {
    return tool(tool, Customizer.withDefaults());
  }
}
