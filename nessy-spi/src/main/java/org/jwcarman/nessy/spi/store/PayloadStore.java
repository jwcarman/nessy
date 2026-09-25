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
package org.jwcarman.nessy.spi.store;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.inference.block.Block;

/**
 * Where content lives, for the content the core never sees.
 *
 * <p>Everything an event would otherwise carry -- an observation, a model's answer, a tool's result
 * -- is put here on the way in and fetched back on the way out. Events hold identifiers, status,
 * human decisions, counts and a {@link PayloadRef}; this holds the rest.
 *
 * <p>That split is what keeps a stream small enough to replay on every command, keeps every type in
 * it one of Nessy's own, and lets content expire on a different schedule from the record of what
 * happened.
 *
 * <p><b>Nothing here is governed.</b> A resolve finds the content or something is broken -- there
 * is no third answer. Where a value must not reach a model, it is a surrogate inside the content,
 * and the reveal happens in the tool that holds the charter, not here.
 */
public interface PayloadStore {

  /** Puts content away and returns the reference that will fetch it. */
  /**
   * This store, for one agent's content.
   *
   * <p>Everything an agent ever said is scoped to it, so forgetting an agent is one statement over
   * one table rather than a traversal of what it might share with others. A store that keeps
   * nothing beyond the process has nothing to scope and answers with itself.
   */
  default PayloadStore forAgent(AgentId agent) {
    return this;
  }

  /**
   * Keeps content, and says where it went.
   *
   * <p><b>Idempotent.</b> Putting the same content twice is the same reference and one copy, so an
   * effect retried after a failure cannot leave a second one behind. That is what makes the
   * reference worth deriving from the content rather than minting.
   */
  PayloadRef put(List<? extends Block> content);

  /** What is behind a reference. */
  Resolved get(PayloadRef ref);

  /**
   * Everything behind these references, in one go.
   *
   * <p>Building a window of turns needs every payload in it, and asking one at a time is a round
   * trip per block -- free in memory, and the difference between one query and fifty over a
   * database. A store that can fetch a set should; the default asks one at a time, so an
   * implementation that has nothing better is still correct.
   *
   * @return what was found, keyed by reference. A reference with nothing behind it maps to {@link
   *     Resolved.Missing}, so the result always has an entry for every reference asked about.
   */
  default Map<PayloadRef, Resolved> get(Collection<PayloadRef> refs) {
    Map<PayloadRef, Resolved> found = new LinkedHashMap<>();
    for (PayloadRef ref : refs) {
      found.computeIfAbsent(ref, this::get);
    }
    return found;
  }

  /** What came of asking. */
  sealed interface Resolved {

    record Found(List<Block> content) implements Resolved {}

    /** Nothing is there: a dangling reference, which is always a fault. */
    record Missing() implements Resolved {}
  }
}
