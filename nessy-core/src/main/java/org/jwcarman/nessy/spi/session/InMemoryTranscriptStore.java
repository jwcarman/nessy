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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.SessionId;

/**
 * The default {@link TranscriptStore#inMemory()} implementation: every session's transcript lives
 * in this JVM and dies with it.
 *
 * <p>Public, unlike {@code InMemorySessionStore}: {@link #entries} is how tests (and any other
 * caller that constructed this type directly) read back what was journaled. It is deliberately not
 * part of the {@link TranscriptStore} seam — the framework itself never reads a transcript, only
 * writes to one.
 */
public final class InMemoryTranscriptStore implements TranscriptStore {

  private final Map<SessionId, List<TranscriptEntry>> entries = new ConcurrentHashMap<>();

  @Override
  public void append(SessionId id, TranscriptEntry entry) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    synchronized (entries) {
      entries.computeIfAbsent(id, unused -> new ArrayList<>()).add(entry);
    }
  }

  /** This session's entries, in append order. Empty for an unknown session. A defensive copy. */
  public List<TranscriptEntry> entries(SessionId id) {
    Objects.requireNonNull(id, "id must not be null");
    synchronized (entries) {
      List<TranscriptEntry> existing = entries.get(id);
      return existing == null ? List.of() : List.copyOf(existing);
    }
  }
}
