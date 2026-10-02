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

/**
 * What a template is rendered with: a name, answered or not. Asked by name rather than handed a
 * map, because that is what filling a hole is, and because the answer may cost something -- a
 * lookup, a clock -- that should only be paid for the names a template actually has.
 */
@FunctionalInterface
public interface PromptVariables {

  Optional<String> variable(String name);

  static PromptVariables of(Map<String, String> values) {
    Map<String, String> copy =
        Map.copyOf(Objects.requireNonNull(values, "values must not be null"));
    return name -> Optional.ofNullable(copy.get(name));
  }

  static PromptVariables none() {
    return _ -> Optional.empty();
  }

  /**
   * One name, answered by asking {@code value}: a date, a counter, a setting read late. A template
   * is rendered once, when a harness is built, so it is asked once for each hole that names it.
   */
  static PromptVariables supplied(String name, Supplier<String> value) {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(value, "value must not be null");
    return asked -> asked.equals(name) ? Optional.ofNullable(value.get()) : Optional.empty();
  }

  /** The first with an answer wins, in the order given. */
  static PromptVariables firstOf(List<PromptVariables> variables) {
    List<PromptVariables> ordered = List.copyOf(variables);
    return name ->
        ordered.stream().map(each -> each.variable(name)).flatMap(Optional::stream).findFirst();
  }
}
