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
package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The behavioural identity of a completed turn: a versioned digest of the rounds of tool calls it
 * made, what each came to, and how the turn ended, with every execution-specific value left out.
 *
 * <p>Two turns with the same trajectory followed the same abstract path: the same tools in the same
 * rounds with the same outcomes, ending the same way. Which customer was looked up, what the tools
 * returned, how long it took and what it cost are not part of it.
 *
 * <p>The digest is meaningful only under its version: a later version may count or encode
 * differently, and a comparison across versions says nothing.
 *
 * @param version the canonicalisation version the hash was computed under
 * @param hash the digest as 64 lowercase hexadecimal characters, the same string the row stores and
 *     the span carries
 */
public record Trajectory(short version, String hash) {

  private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

  public Trajectory {
    Objects.requireNonNull(hash, "hash must not be null");
    if (!HEX_64.matcher(hash).matches()) {
      throw new IllegalArgumentException(
          "a trajectory hash is 64 lowercase hex characters, not '" + hash + "'");
    }
  }
}
