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
package org.jwcarman.nessy.examples.toollab;

import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * One thing a user asks, and what a correct run does about it.
 *
 * @param id the task's id, like {@code t01}
 * @param kind how many calls a correct run makes, or whether it should make none
 * @param prompt the user's message
 * @param expected the calls a correct run makes; empty for an ambiguous task
 * @param ordered whether the expected calls must come in the order listed
 */
record Task(String id, Kind kind, String prompt, List<Expected> expected, boolean ordered) {

  Task {
    expected = List.copyOf(expected);
  }

  /** What sort of task it is. */
  enum Kind {
    /** One catalog call answers it. */
    SINGLE,
    /** Two or three chained calls, each later one needing a value from an earlier result. */
    MULTI,
    /** It fits two systems equally; the right behaviour is to ask which, and change nothing. */
    AMBIGUOUS;

    static Kind parse(String name) {
      for (Kind kind : values()) {
        if (kind.name().toLowerCase(Locale.ROOT).equals(name)) {
          return kind;
        }
      }
      throw new IllegalArgumentException(
          "unknown task kind '%s'; choose single, multi or ambiguous".formatted(name));
    }

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A call a correct run makes.
   *
   * @param tool the catalog tool
   * @param args only the arguments that must match; a run may pass more
   */
  record Expected(String tool, JsonNode args) {}

  /**
   * A call to a catalog tool that a run made.
   *
   * @param name the tool called
   * @param arguments the arguments, parsed; empty if they were not valid JSON
   * @param executed whether the call ran and returned a result, rather than failing
   */
  record Called(String name, JsonNode arguments, boolean executed) {}
}
