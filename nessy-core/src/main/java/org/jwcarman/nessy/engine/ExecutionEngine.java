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
package org.jwcarman.nessy.engine;

import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.ParkToken;
import org.jwcarman.nessy.core.SessionId;

/**
 * Drives the reducer and performs its effects.
 *
 * <p>The line this interface draws is the sharpest one in Nessy: the reducer is the
 * <em>semantics</em>, an engine is the <em>execution strategy</em>. Swapping engines changes
 * durability, retry, and concurrency. It never changes what the agent does.
 *
 * <p>Two methods on purpose. {@code cancel}, {@code status}, and {@code list} all feel obvious to
 * add and are all guesses until a front-end needs them.
 */
public interface ExecutionEngine {

  RunOutcome run(SessionId id, Event input);

  RunOutcome resume(SessionId id, ParkToken token, Event resolution);
}
