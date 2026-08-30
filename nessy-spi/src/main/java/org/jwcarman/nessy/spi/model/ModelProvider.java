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
package org.jwcarman.nessy.spi.model;

import org.jwcarman.nessy.api.model.ModelId;

/**
 * Turns a name into something callable.
 *
 * <p>The seam that keeps model choice a configuration decision: an application names a model, an
 * adapter module resolves it. Handed to a harness factory once, alongside the substrate, because it
 * is infrastructure rather than something that differs between two kinds of agent.
 */
@FunctionalInterface
public interface ModelProvider {

  /**
   * @throws IllegalArgumentException if this provider serves no model by that name — better a loud
   *     failure at harness creation than a mystery at the first turn
   */
  Model model(ModelId id);
}
