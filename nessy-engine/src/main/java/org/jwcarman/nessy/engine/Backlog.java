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
package org.jwcarman.nessy.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Observations accepted but not yet turned into a turn.
 *
 * <p>An observation arriving while a turn is in flight used to be DESTROYED — "it is not queued and
 * it is not coming back". Measured live on a one-minute cadence while parked on a single approval:
 * 26 of 31 rounds refused. That is not an edge case, it is the steady state of any agent that both
 * runs continuously and asks a human anything.
 *
 * <p><b>This holds the observation itself, not its rendered blocks.</b> Rendering happens at the
 * drain. Once an observation is rendered the domain object is gone, and with it any way for a user
 * to say what should happen when two of them meet — coalescing would collapse to matching key
 * strings. Keeping {@code O} is what makes {@link Coalescer} able to express "keep the higher
 * price" or "fold five errors into a count", and it means a renderer fix reaches observations that
 * are already queued.
 *
 * <p><b>Immutable, and ordered by first arrival.</b> An entry replaced by a newer observation keeps
 * its ORIGINAL position (see {@link #supersede}), so the merged user message reads in the order
 * topics first came up, which is what a reader expects of a conversation.
 *
 * @param entries in arrival order, oldest first
 */
public record Backlog<O>(List<Entry<O>> entries) {

  /**
   * One accepted observation.
   *
   * <p><b>{@code id} is the entry's identity, minted fresh on every arrival — never the coalescing
   * key.</b> {@link org.jwcarman.nessy.spi.Remembrance} keys derive from it, so it must be unique
   * per arrival: reusing a coalescing key as the id (as an earlier version of this class did) makes
   * every coalesced arrival share one {@code Remembrance} key, and idempotence-by-key then silently
   * swallows every arrival after the first. See {@link #supersede}.
   *
   * @param receivedAt when it first arrived — kept because staleness ("drop quotes older than five
   *     minutes") is a real policy, and a coalescer is a pure function that must not read a clock.
   *     A superseding observation does NOT refresh this: the entry's position and its age both
   *     describe when the topic first appeared.
   * @param coalesceKey what {@link Coalescer#byKey} matches on to find the entry an arrival
   *     supersedes. {@code null} for an entry that never coalesces (an unkeyed observation, or one
   *     queued under {@link Coalescer#none()}) — plain {@code null} rather than {@code Optional}
   *     because this record round-trips through Jackson, which does not handle {@code Optional}
   *     fields without an extra module this port has no other reason to pull in.
   */
  public record Entry<O>(String id, O observation, Instant receivedAt, String coalesceKey) {

    /** An entry with no coalescing key — the shape of anything that only ever accumulates. */
    public Entry(String id, O observation, Instant receivedAt) {
      this(id, observation, receivedAt, null);
    }
  }

  public Backlog {
    entries = List.copyOf(entries);
  }

  public static <O> Backlog<O> empty() {
    return new Backlog<>(List.of());
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  public int size() {
    return entries.size();
  }

  public List<O> observations() {
    return entries.stream().map(Entry::observation).toList();
  }

  /** Append to the end with no coalescing key. The path for anything that does not coalesce. */
  public Backlog<O> append(String id, O observation, Instant receivedAt) {
    return append(id, observation, receivedAt, null);
  }

  /** Append to the end, remembering the key a later arrival can supersede it by. */
  public Backlog<O> append(String id, O observation, Instant receivedAt, String coalesceKey) {
    List<Entry<O>> next = new ArrayList<>(entries);
    next.add(new Entry<>(id, observation, receivedAt, coalesceKey));
    return new Backlog<>(next);
  }

  /**
   * Supersede the entry matching {@code coalesceKey}: the arrival's id and observation take its
   * place, IN PLACE.
   *
   * <p>Position and {@code receivedAt} both survive — moving a superseded entry to the end would
   * make a chatty topic outrank an older one purely by being noisy, and refreshing {@code
   * receivedAt} would make a continuously-updated topic look eternally fresh to a staleness policy.
   *
   * <p><b>The id does NOT survive — that is the whole point.</b> It becomes {@code newId}, so the
   * next {@code Remembrance} key derived from it differs from the last one and is not swallowed by
   * idempotence-by-key. See the {@link Entry} javadoc.
   */
  public Backlog<O> supersede(String coalesceKey, String newId, O observation) {
    List<Entry<O>> next = new ArrayList<>(entries.size());
    for (Entry<O> entry : entries) {
      next.add(
          coalesceKey.equals(entry.coalesceKey())
              ? new Entry<>(newId, observation, entry.receivedAt(), coalesceKey)
              : entry);
    }
    return new Backlog<>(next);
  }

  public Backlog<O> remove(String id) {
    return new Backlog<>(entries.stream().filter(entry -> !entry.id().equals(id)).toList());
  }

  public Optional<Entry<O>> findByKey(String coalesceKey) {
    return entries.stream().filter(entry -> coalesceKey.equals(entry.coalesceKey())).findFirst();
  }
}
