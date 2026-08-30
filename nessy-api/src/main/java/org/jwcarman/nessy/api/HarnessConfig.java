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
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolBindingConfig;

/**
 * What one kind of agent IS.
 *
 * <p>Infrastructure is deliberately absent — no substrate, no model provider, no actor system.
 * Those are handed to the {@link HarnessFactory} once and shared by every harness it makes. What
 * lands here is only what could differ between two kinds of agent running side by side.
 *
 * <p>The model is named, not supplied: a {@link ModelId} is a value an application can put in a
 * properties file, where handing over a bound model would force the choice into code.
 *
 * @param <O> the observation type
 */
public interface HarnessConfig<O> {

  /**
   * What kind of agent this is. Required: it namespaces every agent id this harness serves, and
   * qualifies what they persist.
   */
  HarnessConfig<O> type(AgentType type);

  /** How a waiting backlog is groomed as observations arrive. */
  HarnessConfig<O> coalescer(BacklogCoalescer<O> coalescer);

  /** The standing instruction every turn carries. */
  HarnessConfig<O> systemPrompt(String systemPrompt);

  /** Which model these agents talk to. */
  HarnessConfig<O> model(ModelId modelId);

  /**
   * Grants one tool, ungated and described by its input's {@code toString()}.
   *
   * <p>Sugar over {@link #tool(Tool, Consumer)} for the common case.
   */
  <I> HarnessConfig<O> tool(Tool<I> tool);

  /**
   * Grants one tool, and says how it is governed and explained.
   *
   * <p>The type parameter is on the METHOD rather than on this interface, so one kind of agent can
   * grant tools whose inputs differ — and so the compiler still ties a tool to its own describer,
   * which a {@code ToolBinding<?>} list could not.
   */
  <I> HarnessConfig<O> tool(Tool<I> tool, Consumer<ToolBindingConfig<I>> customizer);

  /**
   * How an observation becomes inference content. Required for any {@code O} the factory cannot
   * render on its own.
   */
  HarnessConfig<O> renderer(ObservationRenderer<O> renderer);
}
