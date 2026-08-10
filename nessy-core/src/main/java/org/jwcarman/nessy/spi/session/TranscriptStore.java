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
package org.jwcarman.nessy.spi.session;

import org.jwcarman.nessy.api.session.SessionId;

/**
 * Where a session's messages go the moment they are born, in the order they were born.
 *
 * <p>A pure sink: append is the only operation. This is deliberately not a second read model —
 * {@link SessionStore} already answers "what is the session's current state", and a store here that
 * also answered "what did the session ever say" would tempt the framework into reading its own
 * audit log, which is exactly the coupling this interface exists to avoid. Nothing in this package
 * or the engine ever calls anything but {@link #append}; a reader, where one exists (see {@link
 * InMemoryTranscriptStore#entries}), belongs to the concrete implementation, not the seam.
 *
 * <p><b>Strict by design:</b> the engine calls {@link #append} with no {@code try}/{@code catch}
 * around it. An implementation that throws fails the run outright — the exception propagates out of
 * {@code InProcessEngine.run}, just as a failing model call or a failing session save would.
 * Retention is opt-in ({@link #none()} is the default), but once an application has opted in by
 * supplying a real store, a transcript write that silently fails is worse than one that never
 * happened: it would leave the durable session state and the audit trail disagreeing about what was
 * said, with nothing to say so. A store that wants best-effort delivery has to build that itself —
 * swallow its own failures, retry, queue — rather than lean on the engine to do it.
 */
public interface TranscriptStore {

  /**
   * Appends one message to {@code id}'s transcript, in birth order.
   *
   * <p>See the interface javadoc: a thrown exception here is not caught by the engine and fails the
   * run.
   */
  void append(SessionId id, TranscriptEntry entry);

  /** The default: retention is a deliberate declaration, not a silent default. */
  static TranscriptStore none() {
    return NoOpTranscriptStore.INSTANCE;
  }

  /** An in-process, in-memory transcript, with its own reader for tests to inspect. */
  static InMemoryTranscriptStore inMemory() {
    return new InMemoryTranscriptStore();
  }
}
