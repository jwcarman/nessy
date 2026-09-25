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
package org.jwcarman.nessy.spi.narration;

import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.inference.InferenceNarrator;

/**
 * A {@link Narrator} that already knows whose story it is telling.
 *
 * <p>Handed to anything that says what happened without being allowed to know who it happened to. A
 * provider is the case that matters: {@link org.jwcarman.nessy.inference.InferenceRequest} carries
 * no agent identity on purpose -- "a provider that could see it could read something it has no
 * business reading" -- and a narrator taking an agent argument would hand that straight back.
 *
 * <p>Same promises as the narrator behind it: best-effort, never durable, and safe to call from
 * whatever thread is doing the work.
 */
@FunctionalInterface
public interface AgentNarrator {

  void narrate(AgentEvent event);

  /** Nobody is listening. */
  static AgentNarrator silent() {
    return _ -> {};
  }

  /**
   * This narrator, as the thing a provider is handed.
   *
   * <p>The one place a fragment off a wire becomes something an agent said. A provider knows it
   * read text; only this side knows that the text is an agent's answer, and that somebody is
   * watching for it.
   */
  default InferenceNarrator forInference() {
    return new InferenceNarrator() {
      @Override
      public void text(String delta) {
        narrate(new AgentEvent.ContentDelta(delta));
      }

      @Override
      public void thinking(String delta) {
        narrate(new AgentEvent.ThinkingDelta(delta));
      }
    };
  }
}
