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
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;

/**
 * Test-only codec source: the trivial UTF-8 {@code Codec<String>} tests use wherever a {@link
 * org.jwcarman.nessy.agent.backlog.SubstrateBacklog} is built directly (bypassing {@code Nessy}'s
 * builders, which pin their own equivalent for the {@code String} door).
 */
public final class TestCodecs {

  private TestCodecs() {}

  public static Codec<String> utf8String() {
    return new Codec<>() {
      @Override
      public byte[] encode(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return value.getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public String decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        return new String(bytes, StandardCharsets.UTF_8);
      }
    };
  }

  /**
   * A UTF-8 {@code Codec<String>} whose {@code decode} throws a {@link RuntimeException} carrying
   * {@code message} for exactly the element whose plain-text value is {@code poisonValue}; every
   * other element decodes normally. Backs the poison-decode contract test: {@code
   * SubstrateBacklog#poll()} must remove the poison element from the queue before decoding it, so
   * the exception propagates without starving the elements behind it.
   */
  public static Codec<String> poisonOnDecode(String message, String poisonValue) {
    Codec<String> plain = utf8String();
    return new Codec<>() {
      @Override
      public byte[] encode(String value) {
        return plain.encode(value);
      }

      @Override
      public String decode(byte[] bytes) {
        String decoded = plain.decode(bytes);
        if (decoded.equals(poisonValue)) {
          throw new RuntimeException(message);
        }
        return decoded;
      }
    };
  }
}
