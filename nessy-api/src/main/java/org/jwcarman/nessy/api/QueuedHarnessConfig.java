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
 * How one agent type is set up.
 *
 * <p>A CONFIG, not a builder: fluent setters and no public {@code build()}. What produces a harness
 * is the factory, which holds the machinery a caller has no business assembling -- the stores, the
 * codecs, the transaction manager, the scheduler, the provider. A caller says what is different
 * about their agent type and nothing else.
 *
 * <p><b>Two things are required</b>, and they are the two that define an agent: what it is called
 * and what it is. Everything else has a default, either here or from the factory. Forgetting a poll
 * interval gets you 250ms; forgetting a system prompt would get you a generic assistant wearing
 * your agent type's name, working perfectly and doing the wrong job, with nothing in the logs to
 * say so.
 *
 * <p>The agent type, settled when the harness was asked for rather than here, does more work on
 * this door than on the direct one: besides naming the rows, it is what the dispatcher polls by, so
 * two types share a database without either one seeing the other's queue.
 *
 * @param <I> the input type this agent takes
 */
public interface QueuedHarnessConfig<I> extends HarnessConfig<QueuedHarnessConfig<I>> {

  /**
   * What this agent type is told about itself. Fixed for the life of the harness: set once, here,
   * and never asked again.
   *
   * <p>It is the head of every request, and a provider caches a request's leading text, so a change
   * in it invalidates everything cached for every agent of the type. What varies by agent belongs
   * in a {@link StateSource}; what varies by the moment, in an {@link AmbientSource}.
   */
  QueuedHarnessConfig<I> systemPrompt(String prompt);

  /**
   * How an input becomes something a model can read.
   *
   * <p>Defaults to {@link InputRenderer#asString()}, which is right for records and for anything
   * with a considered {@code toString}, and quietly wrong for a class without one -- that sends
   * {@code com.acme.Order@1a2b3c} to a model, and you pay for it.
   */
  QueuedHarnessConfig<I> inputRenderer(InputRenderer<I> renderer);

  /**
   * A short label for each input, written on the start of the turn that takes it up and told in the
   * story, so a reader of the story can see what started a turn without opening its input.
   *
   * <p><b>A label is a category, never content.</b> Name the kind of work the input asks for, one
   * of a small set of values such as {@code invoice:PRICE_VARIANCE}, not what the input says: it is
   * also written to the turn's row in plain text, so a query can group turns by it, and the row is
   * not encrypted as the story is. It is not part of the trajectory, so one behaviour under two
   * labels is one trajectory.
   *
   * <p>The label is made one line and cut to 256 characters, as an action line is. Defaults to the
   * input's simple class name, which is also what is written when this label throws, returns null
   * or returns a blank string. A label that fails never fails the turn; a warning names the agent
   * type.
   */
  QueuedHarnessConfig<I> inputLabel(Stringifier<I> label);

  /**
   * What the backlog becomes when an input arrives while the agent is busy.
   *
   * <p>Defaults to {@link BacklogPolicy#keepAll()} -- right for anything a person said, wrong for a
   * sensor, and only the application knows which it has.
   */
  QueuedHarnessConfig<I> backlogPolicy(BacklogPolicy<I> policy);

  /** Adjusts how this agent type infers, using the factory's provider. */
  QueuedHarnessConfig<I> inference(Customizer<InferenceConfig> customizer);

  /** Adjusts how this agent type performs the work it owes itself. */
  QueuedHarnessConfig<I> effects(Customizer<EffectsConfig> customizer);

  /**
   * Somebody who hears what this harness's agents do, in addition to whoever the engine already
   * tells. Repeatable; every listener hears every event.
   */
  QueuedHarnessConfig<I> listener(NarrationListener listener);

  /** Offers a tool, and says what a call of it is worth. */
  <T> QueuedHarnessConfig<I> tool(Tool<T> tool, Customizer<ToolConfig<T>> customizer);

  default <T> QueuedHarnessConfig<I> tool(Tool<T> tool) {
    return tool(tool, Customizer.withDefaults());
  }
}
