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

/**
 * How the context for a call is built: how much of the tail is sent whole, and what background
 * rides along.
 *
 * <p>The story an agent is sent is built in one fixed order, and these are its parts:
 *
 * <pre>
 *   summaries   the engine's own: one per closed chapter, oldest first
 *   tail        the last {@link #maxTail} turns of the story
 *   ambient     whatever every {@link AmbientSource} has to say right now
 * </pre>
 *
 * <p>Nothing here reads history except the tail.
 */
public interface ContextConfig {

  /**
   * At most this many turns of the tail, counting back from the newest, whole.
   *
   * <p>Turns rather than tokens: an estimate written when an entry is stored and the provider's
   * tokenizer never agree, so a token budget is a number that cannot be checked against the one
   * that decides whether a request is accepted. Defaults to 40.
   */
  ContextConfig maxTail(int turns);

  /**
   * Offers background the model should have in mind, asked afresh on every call.
   *
   * <p>The other half of a tool. A notebook the agent writes to is a tool and one of these; so is a
   * plan it keeps, or a view of a system it is operating. Tool in, background out.
   *
   * <p>Two sources may not offer the same {@link Ambient#kind()} -- refused here rather than at
   * render time, because an adapter would write two sections under one label and the model would
   * see a contradiction with no way to tell which is current.
   */
  ContextConfig ambient(AmbientSource source);

  /** Background that is the same for every agent and every turn. */
  default ContextConfig ambient(Ambient ambient) {
    return ambient(AmbientSource.constant(ambient));
  }
}
