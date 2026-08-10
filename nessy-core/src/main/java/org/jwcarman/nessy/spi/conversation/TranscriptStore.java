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
package org.jwcarman.nessy.spi.conversation;

import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.ListenerDeclaration;
import org.jwcarman.nessy.api.event.MessageAppended;

/**
 * Where a session's messages go the moment they are born, in the order they were born.
 *
 * <p>A pure sink: append is the only operation. This is deliberately not a second read model —
 * {@link ConversationStore} already answers "what is the session's current state", and a store here
 * that also answered "what did the session ever say" would tempt the framework into reading its own
 * audit log, which is exactly the coupling this interface exists to avoid. Nothing in this package
 * or the engine ever calls anything but {@link #append}; a reader, where one exists (see {@link
 * InMemoryTranscriptStore#entries}), belongs to the concrete implementation, not the seam.
 *
 * <p><b>The journal rides the delivery spine (design §9.1, §10.8, §17).</b> The engine no longer
 * holds a {@code TranscriptStore} at all; it emits {@link MessageAppended} at its newborn choke
 * point, and a journal is simply a declared listener — {@link #declareListener()} is that
 * declaration, which the harness/agent builders' {@code .transcript(store)} sugar declares at build
 * time. Wiring it as a synchronous declaration keeps strictness: a listener that writes on the
 * emitting thread and lets a failed {@link #append} propagate stops the run outright, the
 * synchronous spine's veto-by-throw. An application that prefers best-effort journaling declares
 * its own {@code listenAsync(MessageAppended.class, ...)} instead of relying on this sugar — a
 * declared posture, chosen at declaration time, never a default. There is no {@code
 * TranscriptStore.none()} sentinel: the absence of a journal is simply the absence of a
 * declaration.
 */
public interface TranscriptStore {

  /**
   * Appends one message to {@code id}'s transcript, in birth order.
   *
   * <p>See the interface javadoc: whether a thrown exception here fails the run depends entirely on
   * how this store was declared — synchronously (the default, via {@link #declareListener()})
   * propagates and fails the run; an application's own {@code listenAsync} declaration isolates it
   * instead.
   */
  void append(ConversationId id, TranscriptEntry entry);

  /**
   * The synchronous listener declaration that turns this store into a journal: each {@link
   * MessageAppended} becomes one {@link #append} call on the emitting thread, so a failing append
   * propagates straight out of {@code emit} and fails the run that produced it. This is what the
   * harness/agent builders' {@code .transcript(store)} sugar declares at build time.
   */
  default ListenerDeclaration declareListener() {
    return ListenerDeclaration.sync(
        MessageAppended.class,
        event ->
            append(
                event.conversationId(), new TranscriptEntry(event.message(), event.turnUsage())));
  }

  /** An in-process, in-memory transcript, with its own reader for tests to inspect. */
  static InMemoryTranscriptStore inMemory() {
    return new InMemoryTranscriptStore();
  }
}
