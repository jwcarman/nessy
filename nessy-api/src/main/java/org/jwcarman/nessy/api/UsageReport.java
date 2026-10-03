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

import java.util.List;
import java.util.Objects;

/**
 * One agent's usage over its whole history, by model.
 *
 * <p>Models are never added together: a token on one model is not a token on another, and pricing
 * them is the caller's business.
 *
 * @param type the agent's type
 * @param id the agent
 * @param byModel one entry for each model the agent used, in the order each was first used
 * @param unreported inferences whose provider reported no usage at all, so no model to count under
 */
public record UsageReport(AgentType type, AgentId id, List<ModelUsage> byModel, int unreported) {

  public UsageReport {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    byModel = List.copyOf(byModel);
    if (unreported < 0) {
      throw new IllegalArgumentException("unreported must not be negative");
    }
  }
}
