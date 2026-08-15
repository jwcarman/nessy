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
package org.jwcarman.nessy.spi.plan;

import java.util.List;
import java.util.Objects;

/**
 * A conversation's current plan: the model's own task list, in the model's own order.
 *
 * <p>Deliberately minimal: title and status, nothing else. No ids (wholesale replacement makes them
 * unnecessary), no notes, no nesting, no timestamps — YAGNI until a consumer demands otherwise.
 */
public record Plan(List<Task> tasks) {

  public Plan {
    tasks = List.copyOf(tasks);
    if (tasks.stream().anyMatch(task -> task.title().isBlank())) {
      throw new IllegalArgumentException("task titles must not be blank");
    }
  }

  /** A plan with no tasks — the state of a conversation the model has never planned for. */
  public static Plan empty() {
    return new Plan(List.of());
  }

  /** {@code true} iff this plan has no tasks. */
  public boolean isEmpty() {
    return tasks.isEmpty();
  }

  /** How far a single task on the plan has gotten. */
  public enum Status {
    /** Not yet started. */
    PENDING,
    /** Underway. */
    IN_PROGRESS,
    /** Finished. */
    DONE
  }

  /**
   * One line of the plan.
   *
   * @param title what the task is, in the model's own words; never blank once the task is part of a
   *     {@link Plan} (the enclosing record enforces it)
   * @param status how far along it is
   */
  public record Task(String title, Status status) {

    public Task {
      Objects.requireNonNull(title, "title must not be null");
      Objects.requireNonNull(status, "status must not be null");
    }
  }
}
