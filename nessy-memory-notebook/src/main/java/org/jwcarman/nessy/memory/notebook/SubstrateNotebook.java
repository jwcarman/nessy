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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A notebook kept in the substrate: one document per agent, holding all of its notes.
 *
 * <p><b>One document rather than one per note.</b> The index is the read that happens on every
 * single turn, and as one document it is one read. Per-note rows would make the common path a scan
 * and the rare path — reading one body — the cheap one, which is backwards. The cost is that a note
 * body rides along with the index; a notebook is a handful of short notes, and if that ever stops
 * being true the shape here can change without the interface moving.
 *
 * <p><b>Kinded by agent type, keyed by agent</b>, the same rule the transcript follows: an agent id
 * is only unique within its type, so two agents of different kinds sharing an id string must not
 * share a notebook.
 */
public final class SubstrateNotebook implements Notebook {

  /**
   * The notes, in the order they were written.
   *
   * <p>A LIST, not a map. A map was the obvious shape and the wrong one: {@code Map.copyOf} makes
   * an unordered copy, so the index the model sees would shuffle between turns even when nothing
   * changed — invalidating a cached prefix for no reason and making the same notebook read
   * differently twice. Order is part of what this stores, so it is stored in something ordered.
   */
  record Notes(List<Entry> entries) {

    Notes {
      entries = List.copyOf(entries);
    }

    static Notes empty() {
      return new Notes(List.of());
    }

    Optional<Entry> byId(String id) {
      return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** Adds a note, or replaces one of the same id in place — keeping where it sat. */
    Notes with(Entry entry) {
      if (byId(entry.id()).isEmpty()) {
        List<Entry> appended = new ArrayList<>(entries);
        appended.add(entry);
        return new Notes(appended);
      }
      return new Notes(
          entries.stream()
              .map(existing -> existing.id().equals(entry.id()) ? entry : existing)
              .toList());
    }

    Notes without(String id) {
      return new Notes(entries.stream().filter(entry -> !entry.id().equals(id)).toList());
    }

    List<Heading> headings() {
      return entries.stream().map(entry -> new Heading(entry.id(), entry.hook())).toList();
    }
  }

  /** No vowels, so an id cannot spell anything; no look-alikes, so a model cannot mistype one. */
  private static final String ALPHABET = "bcdfghjkmnpqrstvwxz23456789";

  private static final int ID_LENGTH = 10;

  private static final int MINT_ATTEMPTS = 100;

  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  private final DocumentStore<Notes> notes;

  public SubstrateNotebook(Substrate substrate, AgentType agentType) {
    Objects.requireNonNull(substrate, "substrate must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    this.notes = substrate.document("notebook/" + agentType.name(), Notes.class);
  }

  @Override
  public List<Heading> headings(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    return read(agentId).headings();
  }

  @Override
  public Optional<Entry> find(AgentId agentId, String id) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return read(agentId).byId(id);
  }

  @Override
  public Entry write(AgentId agentId, String hook, String body) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Entry entry = new Entry(mintUnusedIn(agentId), hook, body);
    // update() reads, applies, and writes at the version it read — so two tools writing at once
    // settle one after the other rather than one silently losing.
    notes.update(agentId.value(), Notes.empty(), current -> current.with(entry));
    return entry;
  }

  @Override
  public Optional<Entry> revise(AgentId agentId, String id, String hook, String body) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    if (read(agentId).byId(id).isEmpty()) {
      return Optional.empty();
    }
    Entry revised = new Entry(id, hook, body);
    notes.update(agentId.value(), Notes.empty(), current -> current.with(revised));
    return Optional.of(revised);
  }

  @Override
  public void forget(AgentId agentId, String id) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    notes.update(agentId.value(), Notes.empty(), current -> current.without(id));
  }

  /**
   * An id no note in this notebook is using.
   *
   * <p>Ten characters from an unambiguous alphabet: short enough that a line of index costs almost
   * nothing and a model can copy one back without slipping, and random enough that two notes filed
   * in the same breath do not collide. Uniqueness is checked rather than assumed, because "unique
   * within one notebook" is a promise this class makes and a probability is not a promise.
   */
  private String mintUnusedIn(AgentId agentId) {
    Notes existing = read(agentId);
    for (int attempt = 0; attempt < MINT_ATTEMPTS; attempt++) {
      String id = mint();
      if (existing.byId(id).isEmpty()) {
        return id;
      }
    }
    throw new IllegalStateException(
        "could not mint an unused notebook id in " + MINT_ATTEMPTS + " attempts");
  }

  private static String mint() {
    StringBuilder id = new StringBuilder(ID_LENGTH);
    for (int i = 0; i < ID_LENGTH; i++) {
      id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return id.toString();
  }

  private Notes read(AgentId agentId) {
    return notes.read(agentId.value()).map(versioned -> versioned.value()).orElseGet(Notes::empty);
  }
}
