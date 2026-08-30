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

import java.time.Instant;
import java.util.Objects;

/**
 * The outcome of something that might have to wait.
 *
 * <p>Two arms, no third: {@link Ready} is the answer in hand; {@link Deferred} says the answer
 * arrives later, through whatever the deferring party already told the world about.
 *
 * <p>Deferring carries no callback. The return address exists BEFORE the deferring party runs — it
 * reads the handle from its context, tells the vendor, and returns — so there is nothing to run
 * afterwards and no id that does not exist yet.
 *
 * @param <T> what the wait produces
 */
public sealed interface Awaited<T> permits Awaited.Ready, Awaited.Deferred {

  /** The wait finished in-process: {@code result} is the answer, in hand right now. */
  record Ready<T>(T result) implements Awaited<T> {
    public Ready {
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  /**
   * The wait outlives this call.
   *
   * <p><b>A lease, not a promise.</b> {@code expiresAt} does not commit the deferring party to
   * answering by then; it tells the engine when to stop waiting and release what it is holding for
   * this call — the parked state, and the claim on the arguments. An answer arriving after it finds
   * the call already settled and is rejected harmlessly.
   *
   * <p><b>Absolute, not a duration.</b> Three things follow. The engine stores the instant rather
   * than a start time plus a term, so a restart recomputes nothing. "Relative to which moment" —
   * the return, or the commit — stops being a question. And a time beyond the engine's ceiling is
   * an impossible value that is REFUSED rather than silently shortened, so the deferring party
   * always knows exactly what it was granted and can never promise a human something false.
   */
  record Deferred<T>(Instant expiresAt) implements Awaited<T> {
    public Deferred {
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
  }

  static <T> Awaited<T> ready(T result) {
    return new Ready<>(result);
  }

  static <T> Awaited<T> deferred(Instant expiresAt) {
    return new Deferred<>(expiresAt);
  }
}
