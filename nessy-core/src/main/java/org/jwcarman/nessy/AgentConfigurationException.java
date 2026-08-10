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
package org.jwcarman.nessy;

/**
 * An agent's build-time configuration is incomplete — a required piece (the model; any other
 * configuration-of-the-agent knob added later) was never supplied, neither by the agent's own
 * builder nor by the harness that seeds it. The message names exactly what is missing.
 *
 * <p>Distinct from {@link IllegalArgumentException}, which still covers a hand-rolled wiring desync
 * (a grant map that disagrees with the tool registry passed alongside it) — a caller's programming
 * error at the call site, not an incomplete declaration.
 */
public final class AgentConfigurationException extends RuntimeException {

  public AgentConfigurationException(String message) {
    super(message);
  }
}
