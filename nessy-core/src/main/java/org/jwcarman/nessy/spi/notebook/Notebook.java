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
package org.jwcarman.nessy.spi.notebook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.conversation.SubjectId;

/**
 * Durable, named notes about a {@link SubjectId} — who or what the notes concern, not any one
 * conversation. The model writes entries through {@link NotebookTools#remember}; every recall
 * injects a compact index ({@link #headings}) so the model can read a note's full body, via {@link
 * NotebookTools#recall}, only when it judges the note relevant.
 *
 * <p><b>Concurrency note (differs from {@link org.jwcarman.nessy.spi.plan.PlanStore}):</b> a {@code
 * PlanStore} has exactly one writer per conversation, so last-write-wins needs no more care than a
 * plain map write. A {@code Notebook} has no such guarantee — multiple conversations can share a
 * subject and write concurrently — so last-write-wins here means the entry granularity stays atomic
 * under concurrent {@link #save} calls, not that races cannot happen; a durable implementation must
 * make its upsert race-safe.
 */
public interface Notebook {

  /**
   * A note: the name the model files it under, the one-line hook the index shows, the body {@link
   * #find} returns, and the {@code source} that wrote it. {@code name}, {@code hook}, and {@code
   * body} are non-blank; {@code name} is the upsert key within a subject. {@code source} is only
   * required non-null — it is supplied by trusted wiring (an agent name, or {@code "reflection"}
   * for the critic), never by the model, so it carries no blank check of its own. The store itself
   * treats {@code source} as an opaque field to persist and return faithfully (the grant
   * principle); only {@link NotebookTools} reads it to gate mutation.
   *
   * @param name the note's key within its subject, never blank
   * @param hook the one line the index shows, never blank
   * @param body the full content {@link NotebookTools#recall} returns, never blank
   * @param source the author's identity — an agent name, or {@code "reflection"} for the critic
   */
  record Entry(String name, String hook, String body, String source) {

    public Entry {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(hook, "hook must not be null");
      Objects.requireNonNull(body, "body must not be null");
      Objects.requireNonNull(source, "source must not be null");
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (hook.isBlank()) {
        throw new IllegalArgumentException("hook must not be blank");
      }
      if (body.isBlank()) {
        throw new IllegalArgumentException("body must not be blank");
      }
    }
  }

  /**
   * The index view of an {@link Entry}: name, hook, and source — the body deliberately absent — the
   * whole point of gating recall behind a separate tool call. {@code source} rides along so {@link
   * NotebookTools}'s rendered index can annotate entries a different author wrote.
   *
   * @param name the note's key, matching some {@link Entry#name()}
   * @param hook the one line the index shows
   * @param source the author's identity, matching some {@link Entry#source()}
   */
  record Heading(String name, String hook, String source) {}

  /**
   * Every heading for {@code subject}, in a stable order — alphabetical by name — so a rendered
   * index never reorders itself between calls. "Alphabetical" is each implementation's own
   * collation — an in-memory notebook orders by {@link String} code point, a JDBC one by its
   * database's collation — which only agrees across implementations because {@link
   * NotebookTools#remember}'s kebab-case, lowercase names order identically under either rule.
   */
  List<Heading> headings(SubjectId subject);

  /** The full entry named {@code name} under {@code subject}, or empty if there is none. */
  Optional<Entry> find(SubjectId subject, String name);

  /**
   * Upserts {@code entry} by {@code (subject, entry.name())}, last write wins — a replayed {@code
   * remember} rewrites the identical entry.
   */
  void save(SubjectId subject, Entry entry);

  /** Removes the note named {@code name} under {@code subject}; absent is a no-op (idempotent). */
  void forget(SubjectId subject, String name);

  /** The zero-configuration default: notes live in this JVM and die with it. */
  static Notebook inMemory() {
    return new InMemoryNotebook();
  }
}
