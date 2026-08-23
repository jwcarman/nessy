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
package org.jwcarman.nessy.spi.substrate;

import java.util.List;

/**
 * A typed view over one {@link Substrate} journal kind (typed-stores spec §1 ruling 1, §2): typed
 * append (direct and op-minting) and typed read of a scope's entries, over the existing journal
 * byte contract (unchanged). Minted by {@link Substrate#journal(String, Class)}, never constructed
 * directly — the kind is fixed at the mint (spec ruling 2).
 *
 * <p>{@link #append(String, Object)} owns the re-read-the-head-and-retry CAS-retry loop every
 * hand-rolled journal appender used to repeat. {@link #appendOp(String, long, Object)} mints the
 * same {@link Substrate.Op.AppendEntry} a caller would otherwise build by hand, for composing into
 * a larger atomic {@link Substrate#batch(java.util.List)} (spec ruling 4).
 *
 * @param <T> the domain shape this journal kind holds
 */
public interface JournalStore<T> {

  /**
   * Appends {@code value} at the next sequence after this journal's current head, retrying on a
   * lost race against a concurrent appender.
   */
  void append(String key, T value);

  /**
   * Mints the {@link Substrate.Op.AppendEntry} an append at {@code expectedSeq} would perform,
   * unexecuted — for composing into a larger {@link Substrate#batch(java.util.List)} alongside
   * other stores' ops (spec ruling 4).
   */
  Substrate.Op appendOp(String key, long expectedSeq, T value);

  /** {@code key}'s entries from {@code fromSeq} (inclusive) in ascending order, decoded. */
  List<T> entries(String key, long fromSeq);
}
