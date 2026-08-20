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

/**
 * Facts, past tense. Three variants is the designed ceiling: every effect has exactly one
 * completion event, and {@code Observed} is the sole inbound fact (spec §2.1).
 */
public sealed interface AgentEvent {

  record Observed(List<ContentBlock> content) implements AgentEvent {
    public Observed {
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  record ModelFinished(ModelOutcome outcome) implements AgentEvent {
    public ModelFinished {
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  record ToolFinished(ToolCall call, ToolOutcome outcome) implements AgentEvent {
    public ToolFinished {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }
}
