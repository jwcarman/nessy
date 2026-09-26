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
package org.jwcarman.nessy.backend.inmemory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * A claim check that is a map, for work that outlives nothing.
 *
 * <p>This is the whole of "storage" for a turn nobody will ever resume: the content lives as long
 * as the object holding it and not a moment longer. The core cannot tell the difference between
 * this and a table, which is the point -- the discipline of keeping content out of events costs the
 * cheapest door nothing.
 *
 * <p><b>It encodes, and that is deliberate work a map does not need.</b> Holding the caller's own
 * list would make this store behave differently from a durable one in two ways that matter. What
 * comes back would be the very object handed in, so a caller mutating its copy would change what is
 * "stored" -- while a table always answers with a fresh decode. And a type that cannot round-trip,
 * a record missing its {@code @JsonCreator}, would pass here and fail only against a database. That
 * is the same trap as testing on a different engine than the one that runs in production, one layer
 * up: the fast test would pass against exactly the bug worth catching. Encoding costs a little and
 * buys a store that means what the durable one means, which is what makes a shared suite of tests
 * worth writing.
 */
public final class InMemoryPayloads implements Payloads {

  private final Codec<Payloads.Content> codec;
  private final Map<PayloadRef, byte[]> content = new ConcurrentHashMap<>();

  public InMemoryPayloads(CodecFactory codecs) {
    this.codec =
        Objects.requireNonNull(codecs, "codecs must not be null").create(Payloads.Content.class);
  }

  /**
   * Addressed by content, exactly as the durable store is: the same blocks encode to the same bytes
   * and hash to the same reference, so the two stores agree on what a reference IS rather than each
   * deriving one its own way.
   */
  @Override
  public PayloadRef put(List<? extends Block> blocks) {
    byte[] encoded = codec.encode(new Payloads.Content(List.copyOf(blocks)));
    PayloadRef ref = Payloads.reference(encoded);
    content.put(ref, encoded);
    return ref;
  }

  @Override
  public Resolved get(PayloadRef ref) {
    byte[] found = content.get(ref);
    return found == null
        ? new Resolved.Missing()
        : new Resolved.Found(codec.decode(found).blocks());
  }
}
