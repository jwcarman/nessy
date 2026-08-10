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
package org.jwcarman.nessy.spi.context;

import java.util.List;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.Message;

/**
 * Adds messages worth composing into one conversational request, from outside the session's own
 * transcript — a long-term store, a knowledge base, whatever a caller wires up. Memory is just a
 * {@code ContextEnricher}.
 *
 * <p>Engine-performed and I/O-sanctioned: unlike {@link Projection}, which is pure and total over a
 * {@link org.jwcarman.nessy.api.message.Context} alone, {@link #enrich} is free to call out — a
 * vector search, a network fetch, whatever finding relevant material actually costs. That freedom
 * is also why each contributor is best-effort: {@link ContextPipeline} runs it under its own
 * observation and treats any {@link RuntimeException} as an enrichment failure, never a turn
 * failure. Enrichment never touches the ledger — what it returns is additional material for one
 * request, not something the reducer folds into {@link ConversationState}.
 *
 * <p>Enrichers key on {@link ConversationState}, not on the projected {@link
 * org.jwcarman.nessy.api.message.Context}: the context is the thing that will *include* the
 * enrichment, so keying on it would be circular, and projection is a wire concern — an elided tool
 * result is {@code "[elided]"} in the projected context but full text in the working set, and
 * relevance should key on the conversation's truth, not on what one call happens to send. That is
 * also why project runs before enrich: projections govern the transcript's wire form, and enriched
 * material must be outside their reach — otherwise every projection would need a "don't touch the
 * enrichments" clause. Enrichers keying on the ledger, not the projection, is also why running them
 * after projection costs enrichers nothing in ordering.
 *
 * <p>Consulted only for conversational requests. The compaction/summarization path builds its own
 * working set and is never enriched — the strategy's request is its own business.
 *
 * <p>There is no {@code ContextEnricher.none()} sentinel: {@link ContextPipeline} takes an ordered
 * list of enrichment contributors, and the empty list — no {@code enrich(...)} calls on {@link
 * ContextPipeline.Builder} — is itself "no enrichment", costing zero allocations and zero
 * observations. A single always-empty implementation would be redundant with that
 * already-degenerate case.
 */
public interface ContextEnricher {

  /** Adds whatever messages are relevant to {@code state}, oldest first. */
  List<Message> enrich(ConversationState state);
}
