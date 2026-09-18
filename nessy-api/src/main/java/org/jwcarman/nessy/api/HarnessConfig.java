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

import java.util.function.Consumer;
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
 * @param <O> the observation type this agent takes
 */
public interface HarnessConfig<O> {

  /** What this agent type is called. Names its rows and scopes its dispatcher's polling. */
  HarnessConfig<O> agentType(AgentType agentType);

  /** What this agent is, in the same words for every agent of the type. */
  HarnessConfig<O> systemPrompt(String prompt);

  /** What this agent is, worked out per agent. May do I/O; it runs off the row lock. */
  HarnessConfig<O> systemPrompt(SystemPromptSource source);

  /**
   * How an observation becomes something a model can read.
   *
   * <p>Defaults to {@link ObservationRenderer#asString()}, which is right for records and for
   * anything with a considered {@code toString}, and quietly wrong for a class without one -- that
   * sends {@code com.acme.Order@1a2b3c} to a model, and you pay for it.
   */
  HarnessConfig<O> observationRenderer(ObservationRenderer<O> renderer);

  /**
   * What the backlog becomes when an observation arrives while the agent is busy.
   *
   * <p>Defaults to {@link ObservationCoalescer#keepAll()} -- right for anything a person said,
   * wrong for a sensor, and only the application knows which it has.
   */
  HarnessConfig<O> observationCoalescer(ObservationCoalescer<O> coalescer);

  /** Adjusts how this agent type infers, using the factory's provider. */
  HarnessConfig<O> inference(Consumer<InferenceConfig> customizer);

  /** Adjusts how this agent type performs the work it owes itself. */
  HarnessConfig<O> effects(Consumer<EffectsConfig> customizer);

  /**
   * Somebody who hears what this harness's agents do, in addition to whoever the engine already
   * tells. Repeatable; every listener hears every event.
   */
  HarnessConfig<O> listener(AgentEventListener listener);

  /** Offers a tool, and says what a call of it is worth. */
  <I> HarnessConfig<O> tool(Tool<I> tool, Consumer<ToolConfig<I>> customizer);

  default <I> HarnessConfig<O> tool(Tool<I> tool) {
    return tool(tool, Customizers.withDefaults());
  }
}
