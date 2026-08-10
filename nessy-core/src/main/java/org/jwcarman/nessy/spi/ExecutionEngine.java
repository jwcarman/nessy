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

import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.session.SessionId;

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

  /**
   * Runs one turn to completion (or until it parks) and persists the result.
   *
   * <p><strong>§6 resume-refusal contract:</strong> an implementation must refuse to {@code run} on
   * a session whose status is not {@code IDLE}, {@code COMPLETE}, or {@code FAILED} — it must
   * throw, naming the offending status, rather than silently overwrite an in-flight turn. A session
   * that crashed mid-turn is completed via {@link #resume}, inspected, or abandoned deliberately;
   * it is never resumed by a second call to {@code run} landing on top of it. {@link
   * org.jwcarman.nessy.spi.InProcessEngine InProcessEngine} does not yet enforce this — it owns the
   * whole run on one thread and has no concurrent caller to guard against — but the contract lives
   * at this seam and lands with {@code DurableEngine}, where a session can be resumed from another
   * process while a stale caller still holds the old one.
   */
  RunOutcome run(SessionId id, Event input);

  RunOutcome resume(SessionId id, ParkToken token, Event resolution);
}
