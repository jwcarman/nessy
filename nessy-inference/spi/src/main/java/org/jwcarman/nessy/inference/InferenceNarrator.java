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
package org.jwcarman.nessy.inference;

/**
 * Where a provider says what is arriving while it is still arriving.
 *
 * <p>Two things come off a wire in pieces, and they are the only two: the answer, and the thinking
 * that preceded it. Every vendor that streams streams those, under different names -- a chunk
 * delta, a content block delta, a part with a thought flag -- and an adapter's whole job here is to
 * decide which of the two it just read.
 *
 * <p><b>Why this is not an agent event.</b> No agent exists at this level. An adapter that minted
 * {@code AgentEvent.ContentDelta} would be naming a thing it cannot see: it does not know whose
 * turn this is, whether anything is watching, or that an agent is involved at all -- {@link
 * InferenceRequest} carries no identity on purpose. The engine knows all three, so the engine is
 * where a fragment becomes an event. What crosses this seam is text.
 *
 * <p>Same promises as whatever is behind it: best-effort, never durable, and safe to call from
 * whichever thread the read happened on. A watcher that misses every fragment still sees the answer
 * land, because the result is returned whole regardless.
 */
public interface InferenceNarrator {

  /** A piece of the answer. */
  void text(String delta);

  /** A piece of the reasoning, for a model that shows it. */
  void thinking(String delta);

  /** Nobody is listening, and a provider should not have to ask. */
  static InferenceNarrator silent() {
    return new InferenceNarrator() {
      @Override
      public void text(String delta) {
        // Nothing is listening.
      }

      @Override
      public void thinking(String delta) {
        // Nothing is listening.
      }
    };
  }
}
