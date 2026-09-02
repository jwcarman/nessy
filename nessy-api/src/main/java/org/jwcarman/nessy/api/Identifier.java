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

/**
 * The one rule every identifier in Nessy obeys, in one place.
 *
 * <p>Package-private on purpose: this is not a type anyone names. It is the shared guard behind
 * {@link AgentType}, {@link AgentId}, {@code TurnId} and {@code CallId}, so the four cannot drift
 * apart into four slightly different notions of what a legal id is.
 *
 * <p><b>Why there is a rule at all.</b> These values are primary-key columns. An identifier that is
 * too long, or that carries a character something downstream treats as structure, does not fail
 * where it was written — it fails much later, in an INSERT or an actor, in a way that reads as a
 * bug in the code that was merely carrying it.
 */
final class Identifier {

  /**
   * Comfortably under what any index will take.
   *
   * <p>Measured, not guessed: a PostgreSQL btree rejects an index row over 2704 bytes, and an
   * identity key is three of these columns together. Worse, PostgreSQL COMPRESSES index entries, so
   * the true ceiling moves with the data — 3000 repeated characters are accepted where 3000 random
   * ones are not. A fixed cap well below the limit turns an intermittent production failure into a
   * deterministic one at the call site.
   */
  static final int MAX_LENGTH = 256;

  /**
   * ASCII letters and digits, plus the punctuation real identifiers actually use.
   *
   * <p>Covers a UUID, {@code house-12}, {@code PROJ-123}, {@code acme:user-7}, and an email-shaped
   * id. Three deliberate exclusions:
   *
   * <ul>
   *   <li>{@code |} — Pekko reserves it as the separator inside a persistence id, and rejects an
   *       entity id containing it.
   *   <li>{@code /} and whitespace — not a technical requirement (Pekko sharding URL-encodes an
   *       entity id, so these route perfectly well) but a legibility one: an id is read by people
   *       in logs, URLs and approval pages.
   *   <li>everything non-ASCII — two different Unicode normalizations can look identical and key
   *       differently, which is the last property an identity should have.
   * </ul>
   */
  private static final String PUNCTUATION = "-_.:@+=";

  private Identifier() {}

  /** Returns {@code value}, or throws naming {@code label} and what was wrong with it. */
  static String checked(String label, String value) {
    Objects.requireNonNull(value, label + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          label + " must be at most " + MAX_LENGTH + " characters, but was " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!legal(c)) {
        throw new IllegalArgumentException(
            "%s must contain only letters, digits or %s, but had [%c] at position %d: %s"
                .formatted(label, PUNCTUATION, c, i, value));
      }
    }
    return value;
  }

  private static boolean legal(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || PUNCTUATION.indexOf(c) >= 0;
  }
}
