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
package org.jwcarman.nessy.spi.intent;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The in-process {@link IntentStore}: one {@link IntentStore.StoredIntent} per conversation, kept
 * in a map for the life of the process, last write wins. {@link ConcurrentHashMap} makes concurrent
 * {@link #put} calls on the same conversation safe without any upsert dance of its own — unlike a
 * durable implementation, there is no separate read-then-write race to recover from here.
 */
final class InMemoryIntentStore implements IntentStore {

  private final Map<ConversationId, StoredIntent> intents = new ConcurrentHashMap<>();

  @Override
  public Optional<StoredIntent> get(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    return Optional.ofNullable(intents.get(id));
  }

  @Override
  public void put(ConversationId id, String type, String json) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(json, "json must not be null");
    intents.put(id, new StoredIntent(type, json));
  }

  @Override
  public void clear(ConversationId id) {
    Objects.requireNonNull(id, "id must not be null");
    intents.remove(id);
  }
}
