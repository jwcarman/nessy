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
import org.jwcarman.nessy.api.computation.Outcome;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The one mapping between a durable outcome and the tool grammar — checked casts, in-band failures,
 * both directions.
 */
public final class DurableOutcomes {

  private DurableOutcomes() {}

  public static ToolOutcome toToolOutcome(Outcome outcome) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    return switch (outcome) {
      case Outcome.Success(Object value) when value instanceof ToolResult result ->
          new ToolOutcome.Returned(result);
      case Outcome.Success(Object value) ->
          new ToolOutcome.Failed(
              new ToolError("unexpected durable payload: " + value.getClass().getName()));
      case Outcome.Failure(String message) -> new ToolOutcome.Failed(new ToolError(message));
      case Outcome.Cancelled(String reason) ->
          new ToolOutcome.Failed(new ToolError("cancelled: " + reason));
    };
  }

  /**
   * The reverse mapping: a reaper redispatch that answers immediately (spec §6, F2) rides this into
   * {@code complete(id, outcome)} so its computation is consumed by the normal pipeline rather than
   * orphaned.
   */
  public static Outcome toOutcome(ToolOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    return switch (outcome) {
      case ToolOutcome.Returned(ToolResult result) -> new Outcome.Success(result);
      case ToolOutcome.Failed(ToolError error) -> new Outcome.Failure(error.message());
    };
  }
}
