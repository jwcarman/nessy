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
package org.jwcarman.nessy.agent;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolResult;

/** What a tool call came back with. A failed tool is in-band: the model reads it and reacts. */
public sealed interface ToolOutcome {

  record Returned(ToolResult result) implements ToolOutcome {
    public Returned {
      Objects.requireNonNull(result, "result must not be null");
    }
  }

  record Failed(ToolError error) implements ToolOutcome {
    public Failed {
      Objects.requireNonNull(error, "error must not be null");
    }
  }
}
