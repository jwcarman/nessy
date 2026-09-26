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
package org.jwcarman.nessy.engine.inference;

import org.jwcarman.nessy.api.AgentNarrator;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.inference.InferenceNarrator;

/**
 * An {@link AgentNarrator}, as the thing a provider is handed.
 *
 * <p>The one place a fragment off a wire becomes something an agent said. A provider knows it read
 * text; only this side knows that the text is an agent's answer, and that somebody is watching for
 * it. Lives here rather than beside {@link AgentNarrator} because it is the bridge between the
 * vocabulary a user speaks and the contract a provider speaks, and this module is the only one that
 * depends on both.
 */
public final class InferenceNarrators {

  private InferenceNarrators() {}

  public static InferenceNarrator of(AgentNarrator narrator) {
    return new InferenceNarrator() {
      @Override
      public void text(String delta) {
        narrator.narrate(new Narration.ContentDelta(delta));
      }

      @Override
      public void thinking(String delta) {
        narrator.narrate(new Narration.ThinkingDelta(delta));
      }
    };
  }
}
