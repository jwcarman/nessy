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
package org.jwcarman.nessy.spi;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.session.SessionState;

/** What one turn of the reducer produced: the next state, and what to do about it. */
public record Step(SessionState state, List<Effect> effects) {

  public Step {
    Objects.requireNonNull(state, "state must not be null");
    effects = List.copyOf(effects);
  }

  public static Step of(SessionState state, Effect... effects) {
    return new Step(state, List.of(effects));
  }
}
