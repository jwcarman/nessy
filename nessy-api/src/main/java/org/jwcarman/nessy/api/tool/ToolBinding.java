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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;

/**
 * One tool, as a particular agent may use it: what it is, who may say no, and how to explain a call
 * of it to a person.
 *
 * <p>The two things beyond the tool itself are the application's statements ABOUT the tool, not the
 * tool's statements about itself — which is the whole point of binding rather than configuring. A
 * third-party tool becomes governable without wrapping it in a class.
 *
 * <p><b>Every binding has an approver</b>, including tools nobody gates: {@link Approver#always()}
 * is the one that just says yes. That keeps the engine on a single path — ask, then run — instead
 * of branching on whether a gate exists.
 *
 * <p>Built by {@link ToolBindingConfig}, not assembled by hand: applications call {@code
 * HarnessConfig.tool(...)} and never name this type.
 *
 * @param <I> the tool's bound input
 */
public record ToolBinding<I>(Tool<I> tool, Approver approver, ToolDescriber<I> describer) {

  public ToolBinding {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(approver, "approver must not be null");
    Objects.requireNonNull(describer, "describer must not be null");
  }
}
