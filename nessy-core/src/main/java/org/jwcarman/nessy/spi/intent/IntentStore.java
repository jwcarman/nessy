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

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * One conversation's declared intent — an app-defined object the model announces (design §7's
 * {@code declare_intent} tool, Task 3b) so an authorization policy can read it back with one keyed
 * fetch, never a transcript scan. This store is dumb CRUD, last write wins: it does not resolve,
 * validate, or link {@link StoredIntent#type()} to any class, and it never inspects {@link
 * StoredIntent#json()}'s content — reconstituting an Object from that JSON, and deciding an
 * unresolvable or foreign type reads as absent rather than throwing, is entirely the reader
 * enricher's job (Task 3b), never this store's (the grant principle).
 *
 * <p>{@code type} rides alongside {@code json} in the same row because reconstituting an arbitrary
 * Object from JSON requires knowing what it was — an {@code ObjectMapper} needs a {@code Class<?>}
 * to deserialize into, and this store has no opinion on how that class gets resolved from the name
 * it stores faithfully.
 *
 * <p><b>Concurrency note (mirrors {@link org.jwcarman.nessy.spi.notebook.Notebook}, not {@link
 * org.jwcarman.nessy.spi.plan.PlanStore}):</b> {@code IntentStore} makes no single-writer
 * assumption of its own — a durable implementation must make its upsert race-safe rather than lean
 * on one tool being the row's only writer.
 */
public interface IntentStore {

  /**
   * The stored row: {@code type} names the class the model's intent was declared against, {@code
   * json} is that object serialized. Both are opaque strings to this store — neither is parsed,
   * resolved, or validated here.
   *
   * @param type the intent's declared type name, never blank
   * @param json the intent serialized, never blank
   */
  record StoredIntent(String type, String json) {

    public StoredIntent {
      Objects.requireNonNull(type, "type must not be null");
      Objects.requireNonNull(json, "json must not be null");
      if (type.isBlank()) {
        throw new IllegalArgumentException("type must not be blank");
      }
      if (json.isBlank()) {
        throw new IllegalArgumentException("json must not be blank");
      }
    }
  }

  /**
   * The current declared intent for {@code id}, or empty if none was ever declared, or it was
   * cleared.
   */
  Optional<StoredIntent> get(ConversationId id);

  /**
   * Upserts {@code id}'s intent to {@code (type, json)}, last write wins — a redeclare replaces
   * whatever was there, a replayed declare rewrites the identical row.
   */
  void put(ConversationId id, String type, String json);

  /** Removes {@code id}'s declared intent; absent is a no-op (idempotent). */
  void clear(ConversationId id);

  /** The zero-configuration default: declared intent lives in this JVM and dies with it. */
  static IntentStore inMemory() {
    return new InMemoryIntentStore();
  }
}
