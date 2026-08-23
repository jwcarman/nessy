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
package org.jwcarman.nessy.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link SubstrateMemory}'s per-key marker (remembrance spec §3): the idempotency mechanism is a
 * small document, CAS-written in the SAME {@link org.jwcarman.nessy.spi.substrate.Substrate#batch}
 * as the journal append it guards — a {@link org.jwcarman.nessy.spi.Remembrance#key()} already
 * present here means {@code remember} is a no-op, which is how re-remembering the same key
 * converges to one fact.
 */
record RememberedKeys(List<String> keys) {

  static final RememberedKeys EMPTY = new RememberedKeys(List.of());

  RememberedKeys {
    keys = List.copyOf(keys);
  }

  boolean contains(String key) {
    return keys.contains(key);
  }

  RememberedKeys plus(String key) {
    Objects.requireNonNull(key, "key must not be null");
    List<String> updated = new ArrayList<>(keys);
    updated.add(key);
    return new RememberedKeys(updated);
  }
}
