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

import java.util.List;
import org.jwcarman.nessy.api.Context;
import org.jwcarman.nessy.api.Message;

/**
 * Recalls messages worth prepending to one conversational request, from outside the session's own
 * transcript — a long-term store, a knowledge base, whatever a caller wires up.
 *
 * <p>Engine-performed and I/O-sanctioned: unlike {@link
 * org.jwcarman.nessy.spi.context.ContextBuilder}, which is pure and total over {@link
 * org.jwcarman.nessy.api.SessionState} alone, {@link #recall} is free to call out — a vector
 * search, a network fetch, whatever finding relevant memories actually costs. That freedom is also
 * why it is best-effort: the engine runs it under its own observation and treats any {@link
 * RuntimeException} as a recall failure, never a turn failure. Recall never touches the ledger —
 * what it returns is enrichment for one request, not something the reducer folds into {@link
 * org.jwcarman.nessy.api.SessionState}.
 *
 * <p>Consulted only for conversational requests. The compaction/summarization path builds its own
 * working set and is never memory-enriched — the strategy's request is its own business.
 */
public interface Memory {

  /**
   * The singleton {@link #none()} instance. A dedicated constant (rather than a fresh lambda
   * returned on every call) gives {@link #none()} a stable identity the engine depends on being
   * able to recognize — {@code memory == Memory.NONE} — so the default path costs zero allocations
   * and zero observations, the same load-bearing trick {@link
   * org.jwcarman.nessy.api.CompactionTrigger#NEVER} uses.
   */
  Memory NONE = context -> List.of();

  /** Recalls whatever messages are relevant to {@code context}, oldest first. */
  List<Message> recall(Context context);

  /** The default: recalls nothing. */
  static Memory none() {
    return NONE;
  }
}
