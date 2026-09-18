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
package org.jwcarman.nessy.api.turn;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;

/**
 * One exchange between an agent and a model, as a conversation rather than as rows.
 *
 * <p>Derived at call time by reducing the story and thrown away afterwards. Nothing here is stored:
 * packaging turns at write time would buy atomicity and charge for it in the window that matters
 * most, since an entry would not be durable until its turn ended.
 *
 * <p><b>Turn-shaped all the way down.</b> The observation and the result are their own types, not
 * the entries they were reduced from -- a provider building a request is owed a conversation, and
 * should not have to know that a refusal is stored as a row with a sequence number.
 *
 * <p><b>It expresses no opinion about what gets sent.</b> Which parts of a turn belong in a
 * request, how a turn that produced nothing is explained, and what role anything lands on are all
 * decisions only a provider's adapter can make well -- what one vendor refuses another answers, and
 * a note that is a system message on one wire has nowhere to go on another.
 *
 * @param id the seq of the observation that opened it, so a turn needs no identifier of its own
 * @param exchanges the rounds of calls it took before the model had what it needed, in order, and
 *     usually none -- most turns are a question and an answer
 * @param result how it ended, or null while it is still in flight -- and a turn in flight is the
 *     reason the model is being called at all
 */
public record Turn(
    TurnId id, Observation observation, List<Exchange> exchanges, TurnResult result, int tokens) {

  public Turn {
    Objects.requireNonNull(observation, "observation must not be null");
    Objects.requireNonNull(exchanges, "exchanges must not be null");
    exchanges = List.copyOf(exchanges);
  }

  /** Whether this turn ended. The one still open, if any, is the last. */
  public boolean complete() {
    return result != null;
  }
}
