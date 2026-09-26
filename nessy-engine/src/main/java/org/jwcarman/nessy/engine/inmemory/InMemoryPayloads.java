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
package org.jwcarman.nessy.engine.inmemory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.inference.block.Block;

/**
 * A claim check that is a map, for work that outlives nothing.
 *
 * <p>This is the whole of "storage" for a turn nobody will ever resume: the content lives as long
 * as the object holding it and not a moment longer. The core cannot tell the difference between
 * this and a table, which is the point -- the discipline of keeping content out of events costs the
 * cheapest door nothing.
 */
public final class InMemoryPayloads implements Payloads {

  private final Map<PayloadRef, List<Block>> content = new ConcurrentHashMap<>();

  /**
   * Addressed by content, as the durable store is.
   *
   * <p>Minting a fresh reference each time would work here and diverge there: putting the same
   * content twice would be two references in memory and one in a table, and the difference would be
   * found by a test that passes against one and not the other. Equality of the blocks stands in for
   * the hash -- there are no bytes to take one of, and nothing here outlives the process.
   */
  @Override
  public PayloadRef put(List<? extends Block> blocks) {
    List<Block> kept = List.copyOf(new ArrayList<Block>(blocks));
    PayloadRef ref = PayloadRef.of("p" + Integer.toHexString(kept.hashCode()));
    content.put(ref, kept);
    return ref;
  }

  @Override
  public Resolved get(PayloadRef ref) {
    List<Block> found = content.get(ref);
    return found == null ? new Resolved.Missing() : new Resolved.Found(found);
  }
}
