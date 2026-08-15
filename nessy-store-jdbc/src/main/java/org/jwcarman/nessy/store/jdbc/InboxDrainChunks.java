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
package org.jwcarman.nessy.store.jdbc;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a drain's entry ids into batches no larger than {@link #BATCH_SIZE}, a pure function kept
 * separate from any connection so it can be pinned by an offline test at exact batch boundaries.
 *
 * <p>{@link JdbcStatements#inboxDrainDeleteSql(int)}'s dynamically-sized {@code IN (?, …, ?)} has
 * no cap of its own — a long-parked conversation's inbox can in principle grow past what a single
 * {@code DELETE} can safely bind. Two real ceilings motivate {@link #BATCH_SIZE}'s value: Oracle
 * rejects more than 1000 expressions in an {@code IN} list outright ({@code ORA-01795}), and SQL
 * Server caps a single statement at roughly 2100 bound parameters. 500 sits comfortably under both,
 * with headroom left for the drain delete's own trailing {@code conversation_id} parameter and for
 * whatever a future caller adds. {@code JdbcConversationStore#drainInbox} runs each batch inside
 * the same explicit transaction {@code save} already opened, so the drain's atomicity with the save
 * it accompanies is unaffected by however many batches a large drain needs.
 */
final class InboxDrainChunks {

  static final int BATCH_SIZE = 500;

  private InboxDrainChunks() {}

  /**
   * Splits {@code ids} into consecutive batches of at most {@link #BATCH_SIZE} entries each,
   * preserving order. An empty input yields an empty list of batches, never a single empty batch.
   */
  static List<List<String>> chunk(List<String> ids) {
    List<List<String>> batches = new ArrayList<>();
    for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
      batches.add(ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())));
    }
    return batches;
  }
}
