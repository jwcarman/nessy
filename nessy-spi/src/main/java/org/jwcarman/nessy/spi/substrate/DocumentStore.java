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
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A typed view over one {@link Substrate} document kind (typed-stores spec §1 ruling 1, §2): a
 * feature holds a {@code DocumentStore<T>} and writes domain logic, not codec-and-byte plumbing.
 * Minted by {@link Substrate#document(String, Class)}, never constructed directly — the kind is
 * fixed at the mint (spec ruling 2: a stable storage name, never derived from {@code T}'s class
 * name, so a rename never orphans data).
 *
 * <p>{@link #update(String, Object, UnaryOperator)} owns the read-modify-write CAS-retry loop ONCE,
 * for every caller (spec ruling 1) — the same discipline every hand-rolled loop in the codebase
 * used to repeat. {@link #writeOp(String, Object, long)} and {@link #deleteOp(String, long)} mint
 * the same {@link Substrate.Op}s a caller would otherwise build by hand, so a typed write composes
 * into a larger atomic {@link Substrate#batch(List)} alongside other stores' ops (spec ruling 4).
 *
 * @param <T> the domain shape this document kind holds
 */
public interface DocumentStore<T> {

  /** The current value and version at {@code key}, or empty if none has ever been written. */
  Optional<Versioned<T>> read(String key);

  /**
   * Whether a document currently sits at {@code key} — a presence-only check that never decodes the
   * payload, cheaper than {@code read(key).isPresent()} for a caller that only needs presence (e.g.
   * an absorption/convergence gate checked on every completion attempt, where decoding would
   * needlessly widen the window between the check and a concurrent writer's own commit).
   */
  boolean exists(String key);

  /**
   * Writes {@code value} at {@code key} under CAS, exactly as {@link Substrate#write(String,
   * String, byte[], long)}.
   *
   * @throws ConflictException if the stored version does not match {@code expectedVersion}
   */
  void write(String key, T value, long expectedVersion);

  /**
   * The read-modify-write CAS-retry loop, once: reads the current value (or {@code seed} if {@code
   * key} has never been written), applies {@code fn}, and writes the result — retrying on {@link
   * ConflictException} until the write lands. Returns the value that was written.
   */
  T update(String key, T seed, UnaryOperator<T> fn);

  /** Keys under this kind, in ascending lexicographic order, at most {@code limit} results. */
  List<String> keys(int limit);

  /**
   * Mints the {@link Substrate.Op.WriteDocument} this write would perform, unexecuted — for
   * composing into a larger {@link Substrate#batch(List)} alongside other stores' ops (spec ruling
   * 4).
   */
  Substrate.Op writeOp(String key, T value, long expectedVersion);

  /**
   * Mints the {@link Substrate.Op.DeleteDocument} a delete at {@code key} would perform, unexecuted
   * — the delete counterpart to {@link #writeOp(String, Object, long)}.
   */
  Substrate.Op deleteOp(String key, long expectedVersion);
}
