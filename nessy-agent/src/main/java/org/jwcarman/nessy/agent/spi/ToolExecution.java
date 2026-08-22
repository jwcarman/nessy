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
package org.jwcarman.nessy.agent.spi;

import java.util.Objects;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.durable.ComputationId;

/**
 * What executing a tool came to: an outcome in hand, or a reference to the durable computation that
 * will carry the answer (durable spec, two-armed ruling — there is no third lifetime). A deferred
 * execution delivers nothing now; its completion re-enters through the computation's registered
 * continuation. Core's {@code Awaited} migrates onto this shape at distillation.
 */
public sealed interface ToolExecution {

  record Immediate(ToolOutcome outcome) implements ToolExecution {
    public Immediate {
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  record Deferred(ComputationId computation) implements ToolExecution {
    public Deferred {
      Objects.requireNonNull(computation, "computation must not be null");
    }
  }
}
