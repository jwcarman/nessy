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
package org.jwcarman.nessy.memory.plan;

import java.util.List;
import java.util.Objects;

/**
 * The model's own task list, in the model's own order.
 *
 * <p>Deliberately minimal: a title and a status, nothing else. No ids — wholesale replacement makes
 * them unnecessary — no notes, no nesting, no timestamps. Every one of those is easy to add when
 * something asks for it and impossible to remove once written down.
 *
 * <p><b>No plan and an empty plan are one state.</b> Saving an empty plan clears it, and nothing
 * downstream tells the two apart: a context gets no background either way.
 */
public record Plan(List<Task> tasks) {

  public Plan {
    Objects.requireNonNull(tasks, "tasks must not be null");
    tasks = List.copyOf(tasks);
    if (tasks.stream().anyMatch(task -> task.title().isBlank())) {
      throw new IllegalArgumentException("task titles must not be blank");
    }
  }

  /** Where a task stands. Three states, because a fourth would need a reason to exist. */
  public enum Status {
    PENDING,
    IN_PROGRESS,
    DONE
  }

  public record Task(String title, Status status) {

    public Task {
      Objects.requireNonNull(title, "title must not be null");
      Objects.requireNonNull(status, "status must not be null");
    }
  }

  public static Plan empty() {
    return new Plan(List.of());
  }

  public boolean isEmpty() {
    return tasks.isEmpty();
  }
}
