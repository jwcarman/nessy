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
package org.jwcarman.nessy.spi.execute;

import java.util.Objects;

/**
 * The "registry" of effect performers — typed, one slot per effect family, completeness enforced by
 * the compiler. No map, no class keys, no runtime lookup that can miss: adding an Effect variant
 * breaks this record's construction sites, which is the point.
 */
public record EffectExecutors(ModelCallExecutor callModel, ToolCallExecutor toolCall) {

  public EffectExecutors {
    Objects.requireNonNull(callModel, "callModel must not be null");
    Objects.requireNonNull(toolCall, "toolCall must not be null");
  }
}
