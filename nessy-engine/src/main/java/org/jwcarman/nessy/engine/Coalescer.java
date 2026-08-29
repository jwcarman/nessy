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

import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * What happens when an observation meets a backlog that is already holding some.
 *
 * <p>This is a REDUCTION, deliberately the same shape as the phase reducer: a pure function from
 * (state, input) to state, testable with no actor anywhere near it. One method expresses everything
 * we could foresee — keep-latest, folding, veto, cross-key supersede, ordering, and a cap if one is
 * ever wanted:
 *
 * <pre>
 *   keep the latest quote per symbol   Coalescer.byKey(q -&gt; Optional.of(q.symbol()))
 *   fold five errors into a count      Coalescer.byKey(Error::kind, Error::plus)
 *   a Cancel clears the queue          implement ingest directly
 *   ignore a heartbeat entirely        return current, unchanged
 * </pre>
 *
 * <p><b>Declared by the vocabulary, not passed per call.</b> The policy is a property of the
 * observation TYPE — a quote always keeps-latest-per-symbol, a person's message never merges — so
 * one caller passing an inconsistent key cannot silently break coalescing for everyone else. The
 * original reasoning survives ("only the sender knows whether its message replaces or
 * accumulates"); the decision just moves to the one place the sender defines their world.
 *
 * <p><b>Purity is the contract.</b> No clock and no I/O: the arriving entry carries its own {@code
 * receivedAt}, so staleness ("drop anything older than five minutes") is expressible without
 * reading a clock, and the function stays reproducible. Debounce ("coalesce anything within 500ms")
 * is deliberately NOT expressible here — it requires waiting, which is a timer, which belongs to an
 * actor.
 */
@FunctionalInterface
public interface Coalescer<O> {

  /**
   * @param incoming already carries its id and {@code receivedAt}; the caller mints those, because
   *     a pure function cannot invent a unique id or read a clock
   */
  Backlog<O> ingest(Backlog<O> current, Backlog.Entry<O> incoming);

  /** Everything accumulates. The default, and correct for anything a person typed. */
  static <O> Coalescer<O> none() {
    return (current, incoming) ->
        current.append(incoming.id(), incoming.observation(), incoming.receivedAt());
  }

  /** Group by key, keep the latest. Twenty cron ticks become one tick. */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key) {
    return byKey(key, (existing, incoming) -> incoming);
  }

  /**
   * Group by key, folding within the group.
   *
   * <p>An absent key means "never coalesce": that observation accumulates like any unkeyed one. A
   * superseded entry keeps its position and its original {@code receivedAt} — a chatty topic must
   * not outrank an older one merely by being noisy.
   */
  static <O> Coalescer<O> byKey(Function<O, Optional<String>> key, BinaryOperator<O> merge) {
    return (current, incoming) ->
        key.apply(incoming.observation())
            .map(
                k ->
                    current
                        .findByKey(k)
                        .map(
                            existing ->
                                current.supersede(
                                    k,
                                    incoming.id(),
                                    merge.apply(existing.observation(), incoming.observation())))
                        .orElseGet(
                            () ->
                                current.append(
                                    incoming.id(),
                                    incoming.observation(),
                                    incoming.receivedAt(),
                                    k)))
            .orElseGet(
                () -> current.append(incoming.id(), incoming.observation(), incoming.receivedAt()));
  }
}
