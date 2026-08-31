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
package org.jwcarman.nessy.memory.notebook;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;

/**
 * Notes an agent keeps for itself.
 *
 * <p>"Remember this for me." The model writes named notes through tools, and every context carries
 * an INDEX of what it has written — one line each — so it knows what it knows. It reads a note's
 * full body only when it judges it relevant, by calling for it. Relevance lives in the model rather
 * than in an embedding: no vector store, no new dependency, and nothing here to tune.
 *
 * <p><b>Why an index rather than the notes themselves.</b> Notes accumulate; contexts do not have
 * room. A hook per note costs a line and lets the model choose, which is the same trade a person
 * makes with a table of contents.
 *
 * <p><b>Keyed by agent.</b> Agents are durable and sharded, so an agent already IS the identity a
 * note belongs to. An application that wants notes shared across agents can put a resolver in front
 * of this without any of the tools changing.
 */
public interface Notebook {

  /**
   * A note: the id it is filed under, the one-line hook the index shows, and the body.
   *
   * <p><b>The id is minted, not chosen.</b> A model-chosen name and a hook are two descriptions of
   * one note, and the second is better at it — so the name stops being a description and becomes an
   * identifier. It also removes a quiet failure: a model reusing a name for an unrelated note used
   * to overwrite the first one, and an id it did not choose cannot collide with a meaning it did
   * not intend.
   *
   * @param id short, opaque, and unique within one agent's notebook
   * @param hook one line saying what this note is about, which is what the index carries
   * @param body the note itself, returned only when asked for
   */
  record Entry(String id, String hook, String body) {

    public Entry {
      requireText(id, "id");
      requireText(hook, "hook");
      requireText(body, "body");
    }

    private static void requireText(String value, String what) {
      Objects.requireNonNull(value, what + " must not be null");
      if (value.isBlank()) {
        throw new IllegalArgumentException(what + " must not be blank");
      }
    }
  }

  /** The index view: id and hook, bodies deliberately absent — that absence is the point. */
  record Heading(String id, String hook) {}

  /** Every heading for {@code agentId}, in a stable order so a context does not churn. */
  List<Heading> headings(AgentId agentId);

  /** The whole note, or empty if there is none with that id. */
  Optional<Entry> find(AgentId agentId, String id);

  /**
   * Files a new note under an id this notebook mints, and returns it.
   *
   * <p>Always a new note. Replacing one is {@link #revise}, which has to be told which — the
   * difference between adding and overwriting is now something the caller states rather than
   * something that happens because two names matched.
   */
  Entry write(AgentId agentId, String hook, String body);

  /**
   * Replaces the note with this id, or does nothing and returns empty if there is no such note.
   *
   * <p>Empty rather than a throw: a model working from a stale index should be told its note is
   * gone, not have its turn fail.
   */
  Optional<Entry> revise(AgentId agentId, String id, String hook, String body);

  /** Removes a note. Absent is not an error: forgetting twice is forgetting. */
  void forget(AgentId agentId, String id);
}
