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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.examples.toollab.Task.Expected;
import org.jwcarman.nessy.examples.toollab.Task.Kind;
import tools.jackson.databind.JsonNode;

/** Reads the tasks from {@code tasks.json}. */
final class Tasks {

  private static final String RESOURCE = "/tasks.json";

  private Tasks() {}

  /** Every task in the file, in file order. */
  static List<Task> load() {
    try (InputStream in = Tasks.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the class path");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + RESOURCE, e);
    }
  }

  /** The tasks in this JSON array. */
  static List<Task> parse(String json) {
    List<Task> tasks = new ArrayList<>();
    for (JsonNode node : Schemas.MAPPER.readTree(json)) {
      List<Expected> expected = new ArrayList<>();
      for (JsonNode call : node.path("expected")) {
        expected.add(new Expected(call.path("tool").asString(""), call.path("args")));
      }
      tasks.add(
          new Task(
              node.path("id").asString(""),
              Kind.parse(node.path("kind").asString("")),
              node.path("prompt").asString(""),
              expected,
              node.path("ordered").asBoolean(true)));
    }
    return List.copyOf(tasks);
  }
}
