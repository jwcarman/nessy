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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.time.Instant;
import java.util.Optional;

/**
 * A SERVICE the actor depends on — a source of the next observation — not state {@link AgentState}
 * owns. {@link AgentState} holds identifiers, status and human decisions, never content, and an
 * observation is content.
 *
 * <p>Every implementation persists over {@link org.jwcarman.nessy.spi.substrate.Substrate}'s
 * document door, so {@link #ingest} is durable before it returns: an observation arriving while a
 * round is busy must survive the process dying a millisecond later, exactly like a human's approval
 * answer does.
 *
 * <p>Draining is ONE observation at a time, deliberately: whether several queued observations ever
 * become one turn is a {@link Coalescer} decision, made at {@link #ingest}, not something a drain
 * gets to override. A vocabulary whose {@link Coalescer} returns no key is saying "these must never
 * merge" — merging everything at drain time would silently break that promise.
 *
 * @param <O> the observation type this backlog queues
 */
public interface Backlogs<O> {

  /**
   * Durably queues {@code observation}, coalescing it with whatever is already waiting per this
   * backlog's {@link Coalescer}. Returns only once the write is safe.
   */
  void ingest(String agentId, O observation, Instant receivedAt);

  /** The oldest entry still waiting, if any — a peek, not a removal. See {@link #taken}. */
  Optional<Taken<O>> next(String agentId);

  /**
   * Marks one entry drained. Idempotent: taking an entry that is already gone — a re-take after a
   * crash — is free, because the caller derives its own keys from {@code entryId} rather than
   * minting new ones.
   */
  void taken(String agentId, String entryId);

  /** One entry handed to a caller that is about to drain it. */
  record Taken<O>(String entryId, O observation) {}
}
