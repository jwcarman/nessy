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
package org.jwcarman.nessy.engine.harness.direct;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.backend.event.AgentEvent;

/**
 * How what a model said becomes the shape the caller asked for.
 *
 * <p>The counterpart to {@link InputRenderer}, at the other end of a turn: one says how {@code <I>}
 * reaches a model, this says how {@code <O>} comes back. {@code <O>} begins here, the same way
 * {@code <I>} ends at a renderer -- everything between the two is parameterised by neither.
 *
 * <p><b>Reading rather than rendering, and the difference is the failure.</b> A renderer never
 * declines: by the time one runs the input has been chosen and a turn is opening on it. This one
 * must be able to. A model that answered around the schema it was given has produced something that
 * does not fit {@code <O>}, and the caller asked for a shape precisely so that it would not be
 * handed prose instead -- so that turn fails, as an {@link Outcome.Failed}, rather than an
 * exception out of a door that promised a value.
 *
 * <p>Takes the answer event rather than the answer text because the text lives in a payload, and
 * which store that is belongs to whoever built the harness rather than to whoever reads the answer.
 */
@FunctionalInterface
public interface OutcomeReader<O> {

  /**
   * @param agent whose answer this is, which is half of where its payload is kept
   * @param answered the event that closed the turn
   * @return the answer in the caller's shape, or why it could not be
   */
  Outcome<O> read(AgentId agent, AgentEvent.InferenceAnswered answered);
}
