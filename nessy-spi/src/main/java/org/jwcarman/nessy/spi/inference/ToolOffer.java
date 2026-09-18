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
package org.jwcarman.nessy.spi.inference;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * One tool as a provider is told about it.
 *
 * <p>The narrow view: a name, a sentence, and a schema. What a tool actually is -- a Java type, a
 * codec, a timeout, an approver -- is the engine's business and none of a provider's, so none of it
 * is reachable from here. An adapter given the bound tool could call it, which is exactly the thing
 * the effect table exists to prevent.
 *
 * <p>Built once when the harness is created, not per inference: a tool's shape cannot change
 * between calls.
 */
public record ToolOffer(ToolName name, String description, InputSchema schema) {

  public ToolOffer {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(description, "description must not be null");
    Objects.requireNonNull(schema, "schema must not be null");
    if (description.isBlank()) {
      // The description is the whole of what tells a model when to reach for this rather
      // than something else. A nameless choice between two tools is made at random.
      throw new IllegalArgumentException("description must not be blank");
    }
  }
}
