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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;

/**
 * A typed slot in an {@link AuthzContext}: a class token plus a name.
 *
 * <p>Nessy ships a few well-known keys ({@link AuthzContext#PRINCIPAL_KEY}, {@link
 * AuthzContext#DECLARED_INTENT_KEY}) as its own opinions; everything else is an application's or an
 * enricher library's own escape hatch — declare a {@code static final Key<Foo> FOO = new
 * Key<>(Foo.class, "foo")} and deposit into it with {@link AuthzContext#with}.
 *
 * <p>Equality is identity, deliberately: two {@code Key} instances never collide just because they
 * share a type and a name. A key is meant to be referenced as the one static constant both the
 * depositing enricher and the reading policy import, not reconstructed ad hoc — value equality
 * would only invite accidental collisions between unrelated modules that happened to pick the same
 * name. This is a {@code record} for its free constructor/accessors only; {@code equals}/{@code
 * hashCode} are overridden back to identity to preserve that guarantee.
 *
 * @param type the class token values deposited under this key are checked-cast against on the way
 *     out
 * @param name a human-readable label — for diagnostics only; identity, not this, drives equality
 * @param <T> the type of value this key looks up
 */
public record Key<T>(Class<T> type, String name) {

  public Key {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  @Override
  public boolean equals(Object other) {
    return this == other;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "Key[" + name + ": " + type.getSimpleName() + "]";
  }
}
