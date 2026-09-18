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
package org.jwcarman.nessy.prompt;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;

/**
 * Where a prompt's variables come from, for a given agent.
 *
 * <p>A prompt is rendered per agent, and most of what fills it is per agent too: who this agent
 * serves, what it is called, what it was told at the start. Some is not -- the date, a setting --
 * and those are the static forms below. Sources compose: {@link #firstOf} asks each in turn and
 * takes the first answer.
 */
@FunctionalInterface
public interface PromptVariableSource {

  Optional<String> variable(AgentId agentId, String name);

  /** The same values for every agent. */
  static PromptVariableSource of(Map<String, String> values) {
    PromptVariables fixed = PromptVariables.of(values);
    return (_, name) -> fixed.variable(name);
  }

  /** One name, answered afresh each time: a date, a counter, a setting read late. */
  static PromptVariableSource supplied(String name, Supplier<String> value) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(value, "value must not be null");
    return (_, asked) -> asked.equals(name) ? Optional.ofNullable(value.get()) : Optional.empty();
  }

  /** The first source with an answer wins, in the order given. */
  static PromptVariableSource firstOf(List<PromptVariableSource> sources) {
    List<PromptVariableSource> ordered = List.copyOf(sources);
    return (agentId, name) ->
        ordered.stream()
            .map(source -> source.variable(agentId, name))
            .flatMap(Optional::stream)
            .findFirst();
  }

  /** This source's answers for one agent, as what a template is rendered with. */
  default PromptVariables forAgent(AgentId agentId) {
    return name -> variable(agentId, name);
  }
}
