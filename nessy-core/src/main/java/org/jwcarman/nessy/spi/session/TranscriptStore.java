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

import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.MessageAppended;
import org.jwcarman.nessy.api.event.Subscription;
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
 * <p><b>The journal rides the hub (design §9.1, §10.8).</b> The engine no longer holds a {@code
 * TranscriptStore} at all; it emits {@link MessageAppended} at its newborn choke point, and a
 * journal is simply a subscriber — {@link #feedFrom(EventHub)} is that subscription. Wiring it
 * inline (the default {@code .transcript(store)} sugar on the harness/agent builders) keeps
 * strictness: a subscriber that writes on the emitting thread and lets a failed {@link #append}
 * propagate stops the run outright, the synchronous spine's veto-by-throw, exactly as a direct
 * engine dependency once did. An application that prefers best-effort journaling subscribes with
 * {@link EventHub#subscribeAsync(Class, java.util.function.Consumer, java.util.function.Consumer)}
 * instead — a declared posture, chosen at subscription time, never a default. There is no {@code
 * TranscriptStore.none()} sentinel any more: the absence of a journal is simply the absence of a
 * subscriber.
 */
public interface TranscriptStore {

  /**
   * Appends one message to {@code id}'s transcript, in birth order.
   *
   * <p>See the interface javadoc: whether a thrown exception here fails the run depends entirely on
   * how this store was subscribed — inline (the default) propagates and fails the run; {@link
   * EventHub#subscribeAsync(Class, java.util.function.Consumer, java.util.function.Consumer)}
   * isolates it instead.
   */
  void append(SessionId id, TranscriptEntry entry);

  /**
   * Subscribes this store to {@code hub}'s {@link MessageAppended} stream, inline: each event is
   * turned into one {@link #append} call on the emitting thread, so a failing append propagates
   * straight out of {@code emit} and fails the run that produced it. This is the one obvious way to
   * wire a store to the hub — the harness/agent builders' {@code .transcript(store)} sugar calls
   * exactly this at build time, once per hub.
   *
   * @return the subscription, closable like any other
   */
  default Subscription feedFrom(EventHub hub) {
    return hub.subscribe(
        MessageAppended.class,
        event ->
            append(event.sessionId(), new TranscriptEntry(event.message(), event.turnUsage())));
  }

  /** An in-process, in-memory transcript, with its own reader for tests to inspect. */
  static InMemoryTranscriptStore inMemory() {
    return new InMemoryTranscriptStore();
  }
}
