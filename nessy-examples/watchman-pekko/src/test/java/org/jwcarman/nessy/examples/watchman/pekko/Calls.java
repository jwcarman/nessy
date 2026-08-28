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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.util.Optional;

/**
 * Finds a round's calls by TOOL rather than by id.
 *
 * <p>Ids are unique per call and never reused — the property that keeps {@code Memory}'s
 * idempotence-by-key from swallowing a second use of the same id — so no test may hardcode one.
 */
final class Calls {

  private Calls() {}

  static Optional<ToolCallRecord> byTool(TurnState state, String tool) {
    if (!(state instanceof TurnState.WorkingTools working)) {
      return Optional.empty();
    }
    return working.calls().stream().filter(call -> tool.equals(call.tool())).findFirst();
  }

  /** A call still waiting on a human. */
  static Optional<String> pending(TurnState state, String tool) {
    return byTool(state, tool)
        .filter(call -> !call.decided() && !call.settled())
        .map(ToolCallRecord::id);
  }
}
