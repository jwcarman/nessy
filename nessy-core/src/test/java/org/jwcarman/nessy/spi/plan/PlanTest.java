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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PlanTest {

  @Nested
  class Construction {

    @Test
    void a_blank_title_is_rejected() {
      List<Plan.Task> tasks = List.of(new Plan.Task("  ", Plan.Status.PENDING));

      assertThatThrownBy(() -> new Plan(tasks))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("task titles must not be blank");
    }

    @Test
    void a_null_title_is_rejected() {
      assertThatThrownBy(() -> new Plan.Task(null, Plan.Status.PENDING))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("title must not be null");
    }

    @Test
    void a_null_status_is_rejected() {
      assertThatThrownBy(() -> new Plan.Task("write the plan", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("status must not be null");
    }

    @Test
    void the_task_list_is_defensively_copied() {
      List<Plan.Task> mutable = new ArrayList<>();
      mutable.add(new Plan.Task("write the plan", Plan.Status.PENDING));

      Plan plan = new Plan(mutable);
      mutable.add(new Plan.Task("a task the plan never saw", Plan.Status.PENDING));

      assertThat(plan.tasks()).hasSize(1);
    }
  }

  @Nested
  class Emptiness {

    @Test
    void empty_produces_a_plan_with_no_tasks() {
      assertThat(Plan.empty().tasks()).isEmpty();
    }

    @Test
    void an_empty_plan_reports_itself_empty() {
      assertThat(Plan.empty().isEmpty()).isTrue();
    }

    @Test
    void a_plan_with_a_task_reports_itself_not_empty() {
      Plan plan = new Plan(List.of(new Plan.Task("write the plan", Plan.Status.PENDING)));

      assertThat(plan.isEmpty()).isFalse();
    }
  }
}
