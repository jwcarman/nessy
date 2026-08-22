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
package org.jwcarman.nessy.agent.support;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import org.jwcarman.nessy.spi.substrate.Codec;

/**
 * A trivial byte-transform {@link Codec}: prepends a fixed marker to every encoded payload and
 * strips it back off on decode. Chained onto a {@code Codec<T>} via {@link Codec#then(Codec)}, it
 * proves a caller-supplied codec is actually honored by a recipe — the substrate's raw stored bytes
 * carry the marker, and a read still round-trips through it.
 */
public final class MarkerBytesCodec implements Codec<byte[]> {

  private static final byte[] MARKER = "MARKER:".getBytes(StandardCharsets.UTF_8);

  @Override
  public byte[] encode(byte[] value) {
    Objects.requireNonNull(value, "value must not be null");
    byte[] marked = new byte[MARKER.length + value.length];
    System.arraycopy(MARKER, 0, marked, 0, MARKER.length);
    System.arraycopy(value, 0, marked, MARKER.length, value.length);
    return marked;
  }

  @Override
  public byte[] decode(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes must not be null");
    return Arrays.copyOfRange(bytes, MARKER.length, bytes.length);
  }

  /** Whether {@code payload} begins with this codec's marker — the substrate-visibility check. */
  public static boolean isMarked(byte[] payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    if (payload.length < MARKER.length) {
      return false;
    }
    return Arrays.equals(payload, 0, MARKER.length, MARKER, 0, MARKER.length);
  }
}
