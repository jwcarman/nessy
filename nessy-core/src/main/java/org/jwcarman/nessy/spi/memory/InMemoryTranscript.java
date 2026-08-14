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
package org.jwcarman.nessy.spi.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Message;

/**
 * The in-process transcript: every entry ever appended, kept in a map for the life of the process.
 *
 * <p>{@link #append} runs the whole read-decide-write step inside one {@link Map#compute}, so two
 * concurrent appenders for the same conversation never race to assign the same version or step on
 * each other's no-stutter check. Every value ever stored under a key is an immutable snapshot: a
 * fresh {@link List#copyOf} is built and stored, never a list already published to the map mutated
 * in place. The three read methods' unsynchronized {@link Map#get} are therefore safe by
 * construction — {@link ConcurrentHashMap}'s per-key happens-before on the reference swap is all
 * the safety a read of an immutable value ever needs.
 *
 * <p>Every conversation it has ever been told about grows without eviction for the life of the
 * process — there is no forgetting, no cap, no compaction. That suits a process that owns its
 * sessions, not a long-lived multi-tenant server.
 */
final class InMemoryTranscript implements Transcript {

  private final Map<ConversationId, List<Entry>> conversations = new ConcurrentHashMap<>();

  @Override
  public Entry append(ConversationId id, Message message) {
    List<Entry> updated =
        conversations.compute(
            id,
            (key, existing) -> {
              if (existing != null
                  && !existing.isEmpty()
                  && existing.getLast().message().equals(message)) {
                return existing;
              }
              long nextVersion = existing == null ? 0L : existing.size();
              List<Entry> appended = new ArrayList<>(existing == null ? List.of() : existing);
              appended.add(new Entry(nextVersion, message));
              return List.copyOf(appended);
            });
    return updated.getLast();
  }

  @Override
  public List<Entry> all(ConversationId id) {
    return conversations.getOrDefault(id, List.of());
  }

  @Override
  public List<Entry> tail(ConversationId id, long afterVersion) {
    return all(id).stream().filter(entry -> entry.version() > afterVersion).toList();
  }

  @Override
  public List<Entry> page(ConversationId id, long beforeVersion, int limit) {
    List<Entry> below = all(id).stream().filter(entry -> entry.version() < beforeVersion).toList();
    int fromIndex = Math.max(0, below.size() - limit);
    return below.subList(fromIndex, below.size());
  }
}
