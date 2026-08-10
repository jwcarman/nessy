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
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * Recalls messages worth composing into one conversational request, from outside the session's own
 * transcript — a long-term store, a knowledge base, whatever a caller wires up.
 *
 * <p>Engine-performed and I/O-sanctioned: unlike {@link org.jwcarman.nessy.spi.context.Shape},
 * which is pure and total over a {@link org.jwcarman.nessy.api.message.Context} alone, {@link
 * #recall} is free to call out — a vector search, a network fetch, whatever finding relevant
 * memories actually costs. That freedom is also why each contributor is best-effort: {@link
 * org.jwcarman.nessy.spi.context.ContextPipeline} runs it under its own observation and treats any
 * {@link RuntimeException} as a recall failure, never a turn failure. Recall never touches the
 * ledger — what it returns is enrichment for one request, not something the reducer folds into
 * {@link SessionState}.
 *
 * <p>Recall cues on {@link SessionState}, not on the shaped {@link
 * org.jwcarman.nessy.api.message.Context}: the context is the thing that will *include* the
 * memories, so keying recall on it would be circular, and shaping is a wire concern — an elided
 * tool result is {@code "[elided]"} in the shaped context but full text in the working set, and
 * relevance should key on the conversation's truth, not on what one call happens to send.
 *
 * <p>Consulted only for conversational requests. The compaction/summarization path builds its own
 * working set and is never memory-enriched — the strategy's request is its own business.
 *
 * <p>There is no {@code Memory.none()} sentinel: {@link
 * org.jwcarman.nessy.spi.context.ContextPipeline} takes an ordered list of recall contributors, and
 * the empty list — no {@code recall(...)} calls on {@link
 * org.jwcarman.nessy.spi.context.ContextPipeline.Builder} — is itself "no recall", costing zero
 * allocations and zero observations. A single always-empty implementation would be redundant with
 * that already-degenerate case.
 */
public interface Memory {

  /** Recalls whatever messages are relevant to {@code state}, oldest first. */
  List<Message> recall(SessionState state);
}
