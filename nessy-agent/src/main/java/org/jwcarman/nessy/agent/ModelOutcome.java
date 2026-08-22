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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

/** What a model call came back with. Success and failure are outcomes, never separate events. */
public sealed interface ModelOutcome {

  record Responded(List<ContentBlock> content, List<ToolCall> calls, ModelResponseId responseId)
      implements ModelOutcome {
    public Responded {
      Objects.requireNonNull(content, "content must not be null");
      Objects.requireNonNull(calls, "calls must not be null");
      Objects.requireNonNull(responseId, "responseId must not be null");
      content = List.copyOf(content);
      calls = List.copyOf(calls);
    }
  }

  record Failed(String reason) implements ModelOutcome {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
