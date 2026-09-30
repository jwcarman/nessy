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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * How a Java type becomes the JSON Schema describing it.
 *
 * <p>An interface rather than a class because generating a schema from a Java type is a choice, not
 * a fact: which annotations count, which constraints survive, which draft is spoken. The
 * application makes that choice once, and every type that does not describe itself is measured by
 * it.
 *
 * <p>A tool reaches this through {@link Tool#inputSchema(JsonSchemaGenerator)}, which is handed the
 * configured one rather than finding it -- so a tool cannot quietly generate against different
 * rules than the harness advertises. Nothing here says how the generating is done, and nothing
 * needs to.
 */
@FunctionalInterface
public interface JsonSchemaGenerator {

  /**
   * @param type the type to describe
   * @return the schema describing it
   */
  JsonSchema generate(Class<?> type);

  /**
   * The same, for a type a {@code Class} cannot carry: {@code List<Item>} is described with typed
   * items, where its class alone would describe a list of anything.
   *
   * <p>The default erases to the raw class and delegates, which is all a generator that only knows
   * classes can honestly do; a generator that understands type arguments overrides it.
   *
   * @param type the type to describe, type arguments included
   * @return the schema describing it
   */
  default JsonSchema generate(Type type) {
    if (type instanceof Class<?> raw) {
      return generate(raw);
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw) {
      return generate(raw);
    }
    return generate(Object.class);
  }
}
