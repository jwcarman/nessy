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
package org.jwcarman.nessy.spi.substrate;

import java.util.Objects;

/**
 * A decoded {@link DocumentStore} value paired with the document version it was read at (typed-
 * stores spec §2) — a mechanical carrier, disclosed: {@link DocumentStore#read(String)} returns
 * this so a caller has both the value and the CAS token it must present to {@link
 * DocumentStore#write(String, Object, long)} in the same breath {@link
 * Substrate.Document#version()} always has.
 */
public record Versioned<T>(T value, long version) {

  public Versioned {
    Objects.requireNonNull(value, "value must not be null");
  }
}
