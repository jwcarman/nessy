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

import java.util.Objects;

/**
 * {@link SubstrateMemory}'s per-remembrance idempotency marker (remembrance spec §3, fix round 1
 * Q5): one document per remembered key, at {@code kind=memory-keys}, keyed by {@code agentId + "/"
 * + remembrance.key()} — never a per-scope list that grows unbounded. The marker document is
 * create-only ({@code expectedVersion=0}): the create succeeding IS "not yet remembered"; a {@link
 * org.jwcarman.nessy.spi.substrate.ConflictException} on that exact create IS "already remembered"
 * — O(1) per {@code remember} call, no read-before-write, no list to decode or grow.
 */
public record RememberedMarker(String key) {

  public RememberedMarker {
    Objects.requireNonNull(key, "key must not be null");
  }
}
