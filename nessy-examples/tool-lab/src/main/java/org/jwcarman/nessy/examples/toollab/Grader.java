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
import org.jwcarman.nessy.examples.toollab.Task.Called;
import org.jwcarman.nessy.examples.toollab.Task.Expected;
import tools.jackson.databind.JsonNode;

/** Grades a run without a model: the calls it made against the calls the task expects. */
final class Grader {

  private Grader() {}

  /**
   * Grades a run.
   *
   * <p>A single or multi-step task succeeds when every expected call was made, with the listed
   * arguments, by a call that ran, in order if the task is ordered, and the run ended with an
   * answer. An ambiguous task succeeds when no call that changes something was attempted and the
   * run ended with an answer.
   *
   * @param catalog says which tools change something
   */
  static Grade grade(Task task, List<Called> calls, Ending ending, Catalog catalog) {
    boolean answered = ending == Ending.ANSWER;
    if (task.kind() == Task.Kind.AMBIGUOUS) {
      int writes =
          (int)
              calls.stream()
                  .filter(call -> catalog.find(call.name()).map(CatalogTool::write).orElse(false))
                  .count();
      return new Grade(answered && writes == 0, writes);
    }
    int wrong = (int) calls.stream().filter(call -> matchesNone(task, call)).count();
    List<Called> ran = calls.stream().filter(Called::executed).toList();
    boolean made = task.ordered() ? inOrder(task.expected(), ran) : all(task.expected(), ran);
    return new Grade(answered && made, wrong);
  }

  private static boolean matchesNone(Task task, Called call) {
    return task.expected().stream().noneMatch(expected -> matches(expected, call));
  }

  private static boolean inOrder(List<Expected> expected, List<Called> ran) {
    int next = 0;
    for (Called call : ran) {
      if (next < expected.size() && matches(expected.get(next), call)) {
        next++;
      }
    }
    return next == expected.size();
  }

  private static boolean all(List<Expected> expected, List<Called> ran) {
    return expected.stream().allMatch(e -> ran.stream().anyMatch(call -> matches(e, call)));
  }

  private static boolean matches(Expected expected, Called call) {
    if (!expected.tool().equals(call.name())) {
      return false;
    }
    for (var entry : expected.args().properties()) {
      JsonNode actual = call.arguments().path(entry.getKey());
      if (actual.isMissingNode() || !same(entry.getValue(), actual)) {
        return false;
      }
    }
    return true;
  }

  private static boolean same(JsonNode expected, JsonNode actual) {
    if (expected.isNumber() && actual.isNumber()) {
      return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
    }
    if (expected.isContainer() || actual.isContainer()) {
      return expected.equals(actual);
    }
    return expected.asString("").equals(actual.asString(""));
  }
}
