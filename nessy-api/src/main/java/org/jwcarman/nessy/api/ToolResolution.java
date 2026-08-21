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

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * What a parked tool call was waiting for, now arrived.
 *
 * <p>The grammar is two variants because the scope is (design 2026-08-11, ruling 3): a parked call
 * awaits either the gate's verdict — {@link Decided}, the HITL approval case — or its slow
 * completion — {@link Completed}, the sub-agent / long-running-process case. The parked executor
 * receives its resolution and finishes its yield; the fold never learns time passed.
 *
 * <p>Sealed-grammar etiquette: core switches are exhaustive with no {@code default} arm.
 */
public sealed interface ToolResolution {

  /** The gate's verdict arrived. */
  record Decided(Decision decision) implements ToolResolution {
    public Decided {
      Objects.requireNonNull(decision, "decision must not be null");
    }
  }

  /** The slow completion arrived. */
  record Completed(ToolResult result) implements ToolResolution {
    public Completed {
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
