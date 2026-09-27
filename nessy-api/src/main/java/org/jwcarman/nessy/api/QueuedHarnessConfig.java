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
 * @param <I> the input type this agent takes
 */
public interface QueuedHarnessConfig<I> extends HarnessConfig<QueuedHarnessConfig<I>> {

  // What this agent type is called. Names its rows and scopes its dispatcher's polling.

  /** What this agent is, in the same words for every agent of the type. */
  QueuedHarnessConfig<I> systemPrompt(String prompt);

  /** What this agent is, worked out per agent. May do I/O; it runs off the row lock. */
  QueuedHarnessConfig<I> systemPrompt(SystemPromptSource source);

  /**
   * How an input becomes something a model can read.
   *
   * <p>Defaults to {@link InputRenderer#asString()}, which is right for records and for anything
   * with a considered {@code toString}, and quietly wrong for a class without one -- that sends
   * {@code com.acme.Order@1a2b3c} to a model, and you pay for it.
   */
  QueuedHarnessConfig<I> inputRenderer(InputRenderer<I> renderer);

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
