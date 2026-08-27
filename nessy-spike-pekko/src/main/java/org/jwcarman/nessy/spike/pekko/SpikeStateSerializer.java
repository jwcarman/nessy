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
package org.jwcarman.nessy.spike.pekko;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.pekko.serialization.SerializerWithStringManifest;

/**
 * THROWAWAY SPIKE. Our codec, plugged into Pekko — and the end of the Jackson thread.
 *
 * <p>Rounds 1 and 2 spent their biggest finding on {@code pekko-serialization-jackson}: it always
 * registers {@code DefaultScalaModule}, and {@code jackson-module-scala} enforces a hard {@code
 * jackson-databind} version range at registration time, so with the reactor's pinned 2.22.0 the
 * ActorSystem would not boot at all. Round 2 escaped by moving to Pekko 2.0's {@code
 * pekko-serialization-jackson3}, which put Pekko on the {@code tools.jackson} line instead.
 *
 * <p><b>Neither is necessary.</b> Pekko's serialization is an SPI, and this class is the whole of
 * it: four methods. Pekko never sees Jackson, never registers a module against it, and imposes no
 * version range on anything. The mapper below is ours, at whatever version we choose, configured
 * however we like — in a real integration it would simply be Nessy's own pinned {@code
 * ObjectMapper} or a {@code org.jwcarman.codec.Codec}. The whole dependency (and with it the
 * Scala-module constraint, the CBOR dataformat, the jdk8/jsr310/parameter-names modules, lz4, and
 * the un-Java-shaped {@code JacksonObjectMapperFactory}) is gone.
 *
 * <p>A second, smaller win: {@link #manifest} is a string WE choose. Round 1 found Pekko storing
 * the fully-qualified Java class name in {@code state_serial_manifest}, making a class rename a
 * silent read failure. Here the compatibility surface is one explicit, versioned token.
 */
public final class SpikeStateSerializer extends SerializerWithStringManifest {

  /** Our stable wire name, versioned deliberately. Never a Java class name. */
  public static final String TURN_STATE_V1 = "spike-turn-state-v1";

  /** Any value above 40 that no other serializer on this classpath claims. */
  private static final int IDENTIFIER = 918_273;

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public int identifier() {
    return IDENTIFIER;
  }

  @Override
  public String manifest(Object o) {
    if (o instanceof SpikeTurnState) {
      return TURN_STATE_V1;
    }
    throw new IllegalArgumentException("not a spike state: " + o.getClass());
  }

  @Override
  public byte[] toBinary(Object o) {
    try {
      return mapper.writeValueAsBytes(o);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + o.getClass(), e);
    }
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    if (!TURN_STATE_V1.equals(manifest)) {
      // Loud, not silent: an unknown manifest is the one failure mode a codec must never guess at.
      throw new IllegalArgumentException("unknown manifest: " + manifest);
    }
    try {
      return mapper.readValue(bytes, SpikeTurnState.class);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + manifest, e);
    }
  }
}
