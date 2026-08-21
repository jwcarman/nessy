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
package org.jwcarman.nessy.agent.memory;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.codec.MessageCodec;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.store.ConflictException;
import org.jwcarman.nessy.spi.store.ScopedStore;

/**
 * The {@code memory} recipe (spec §6.2): one journal per scope, keyed by {@code agentId}, one entry
 * per message. {@link #remember(Message)} appends at head + 1; a conflicting racer means someone
 * else appended first, so the head is re-read and the append retried — near-zero in practice since
 * the scope CAS already serializes turns, but correct under a genuine race. {@link #recall()} folds
 * every entry from seq 1 into a {@link Context}. The transcript is the permanent record: nothing
 * here ever rewrites an entry.
 */
public final class StoredMemory implements Memory {

  private static final String KIND = "memory";

  private final ScopedStore store;
  private final String agentId;

  public StoredMemory(ScopedStore store, String agentId) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
  }

  @Override
  public void remember(Message message) {
    Objects.requireNonNull(message, "message must not be null");
    String payload = MessageCodec.toJson(message);
    while (true) {
      long nextSeq = head() + 1;
      try {
        store.append(KIND, agentId, nextSeq, payload);
        return;
      } catch (ConflictException e) {
        // another writer took nextSeq first; re-read the head and retry
      }
    }
  }

  @Override
  public Context recall() {
    List<Message> messages =
        store.entries(KIND, agentId, 1).stream()
            .map(entry -> MessageCodec.message(entry.payload()))
            .toList();
    return Context.of(messages);
  }

  private long head() {
    List<ScopedStore.Entry> entries = store.entries(KIND, agentId, 1);
    return entries.isEmpty() ? 0L : entries.getLast().seq();
  }
}
