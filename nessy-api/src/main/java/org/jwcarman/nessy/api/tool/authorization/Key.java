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
 * A typed slot in a fact bag: a class token plus a name. Value equality, deliberately: facts are
 * stored by name in a JSON document (approval-lifecycle spec §1.2), so two keys with the same name
 * address the same fact wherever they were constructed — an enricher in one module and a rule in
 * another agree on {@code new Key<>(Intent.class, "intent.declared")} by construction. Namespace
 * names with a dotted prefix; a bare name is the framework's own.
 *
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
  public String toString() {
    return "Key[" + name + ": " + type.getSimpleName() + "]";
  }
}
