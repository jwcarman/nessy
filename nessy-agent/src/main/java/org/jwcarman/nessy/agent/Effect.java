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
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Commands, imperative. Three variants (approval-lifecycle spec §3): {@code CallModel} is a bare
 * marker — the executor asks Memory for context itself, and a fat effect could not be re-derived by
 * recovery (spec §6.1). {@code SeekApproval} asks and never runs; {@code RunTool} runs and never
 * asks — the answer between them is always a folded fact.
 */
public sealed interface Effect {

  record CallModel() implements Effect {}

  /** Ask: yields {@code ApprovalAnswered} or {@code ApprovalDeferred}. */
  record SeekApproval(ToolCall call) implements Effect {
    public SeekApproval {
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /** Run: yields {@code ToolFinished} or {@code ToolDeferred}. */
  record RunTool(ToolCall call) implements Effect {
    public RunTool {
      Objects.requireNonNull(call, "call must not be null");
    }
  }
}
