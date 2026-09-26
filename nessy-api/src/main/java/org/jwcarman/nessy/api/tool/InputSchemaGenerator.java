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
package org.jwcarman.nessy.api.tool;

/**
 * How an input type becomes the JSON Schema a model is offered.
 *
 * <p>An interface rather than a class because generating a schema from a Java type is a choice, not
 * a fact: which annotations count, which constraints survive, which draft is spoken. The
 * application makes that choice once, and every tool that does not know its own shape is measured
 * by it.
 *
 * <p>A tool reaches this through {@link Tool#inputSchema(InputSchemaGenerator)}, which is handed
 * the configured one rather than finding it -- so a tool cannot quietly generate against different
 * rules than the harness advertises. Nothing here says how the generating is done, and nothing
 * needs to.
 */
@FunctionalInterface
public interface InputSchemaGenerator {

  /**
   * @param inputType the type a tool binds its arguments to
   * @return the schema describing it
   */
  InputSchema generate(Class<?> inputType);
}
