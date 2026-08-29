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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * Remembers everything, in order — the cli() default (§7.1). Idempotent by key, exactly as the SPI
 * requires (remembrance spec §1 law 2): a {@link LinkedHashMap} keyed on {@link Remembrance#key()}
 * both dedups a repeated key and preserves first-remembered order. Thread-safe because completions
 * arrive on executor threads while the shell commits on others; a synchronized map is entirely
 * adequate at conversation cadence. {@link #recall()} reassembles through {@link RemembranceFold},
 * the same pairing logic {@link SubstrateMemory} shares.
 */
public final class VerbatimMemory implements Memory {

  private final Map<String, Remembrance> remembered = new LinkedHashMap<>();

  @Override
  public synchronized void remember(Remembrance remembrance) {
    Objects.requireNonNull(remembrance, "remembrance must not be null");
    remembered.putIfAbsent(remembrance.key(), remembrance);
  }

  @Override
  public synchronized Context recall() {
    RemembranceFold fold = new RemembranceFold();
    remembered.values().forEach(fold::add);
    return fold.toContext();
  }
}
