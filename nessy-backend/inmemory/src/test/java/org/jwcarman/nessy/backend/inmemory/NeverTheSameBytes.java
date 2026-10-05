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

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.jwcarman.codec.Codec;

/**
 * A storage transform that never writes the same bytes twice, as encryption with a fresh nonce
 * does: a counter byte, from 1 to 100, in front of what it is given.
 */
final class NeverTheSameBytes implements Codec<byte[]> {

  private final AtomicInteger counter = new AtomicInteger();

  @Override
  public byte[] encode(byte[] bytes) {
    byte[] out = new byte[bytes.length + 1];
    out[0] = (byte) (counter.incrementAndGet() % 100 + 1);
    System.arraycopy(bytes, 0, out, 1, bytes.length);
    return out;
  }

  @Override
  public byte[] decode(byte[] bytes) {
    return Arrays.copyOfRange(bytes, 1, bytes.length);
  }
}
