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

import java.util.Objects;

/**
 * The JSON Schema describing a tool's arguments, as text.
 *
 * <p><b>Text rather than a parsed tree, on purpose.</b> A tree would have to be some library's
 * tree, and that library would become every adapter's problem: this engine speaks Jackson 3, the
 * Anthropic SDK's {@code JsonValue.fromJsonNode} takes Jackson 2, AWS wants its own {@code
 * Document}, and somebody will eventually arrive with Gson. Handing out a tree makes one of those
 * free and taxes the rest with a conversion they did not choose. JSON text is the neutral form all
 * of them already read.
 *
 * <p>Being immutable is the second half of that. One schema is generated per tool and held for the
 * life of the harness, and adapters hand it onward -- Gemini passes it straight into its SDK. A
 * mutable node in that position is shared state that any adapter could corrupt for every other; a
 * string cannot be. An adapter is free to parse once into whatever its own SDK speaks and cache
 * that, knowing the source can never change underneath it.
 *
 * @param json the schema document; well-formedness is the producer's responsibility, checked where
 *     a tool is bound rather than here
 */
public record InputSchema(String json) {

  public InputSchema {
    Objects.requireNonNull(json, "json must not be null");
    if (json.isBlank()) {
      throw new IllegalArgumentException("json must not be blank");
    }
  }
}
