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
import java.util.function.Function;

/** The one implementation behind {@link VendorProperty}'s factories: a name, a reader, a writer. */
record TypedVendorProperty<T>(
    String name, Function<String, T> reader, Function<T, String> writer, String accepted)
    implements VendorProperty<T> {

  @Override
  public T parse(String text) {
    try {
      return reader.apply(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("must be " + accepted + ", was '" + text + "'", e);
    }
  }

  @Override
  public String format(T value) {
    Objects.requireNonNull(value, "value must not be null");
    return writer.apply(value);
  }
}
