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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;

class InMemoryPlanStoreTest {

  private final PlanStore store = PlanStore.inMemory();

  @Nested
  class Finding {

    @Test
    void a_conversation_never_saved_is_not_found() {
      assertThat(store.find(ConversationId.generate())).isEmpty();
    }
  }

  @Nested
  class Saving {

    @Test
    void a_saved_plan_is_found_by_the_same_id() {
      ConversationId id = ConversationId.generate();
      Plan plan = new Plan(List.of(new Plan.Task("write the plan", Plan.Status.PENDING)));

      store.save(id, plan);

      assertThat(store.find(id)).contains(plan);
    }

    @Test
    void a_second_save_replaces_the_plan_wholesale() {
      ConversationId id = ConversationId.generate();
      Plan first =
          new Plan(
              List.of(
                  new Plan.Task("write the plan", Plan.Status.DONE),
                  new Plan.Task("review the plan", Plan.Status.IN_PROGRESS)));
      Plan second = new Plan(List.of(new Plan.Task("write the plan", Plan.Status.DONE)));
      store.save(id, first);

      store.save(id, second);

      Optional<Plan> found = store.find(id);
      assertThat(found).contains(second);
      assertThat(found.orElseThrow().tasks()).hasSize(1);
    }

    @Test
    void saving_an_empty_plan_is_found_as_empty() {
      ConversationId id = ConversationId.generate();

      store.save(id, Plan.empty());

      Optional<Plan> found = store.find(id);
      assertThat(found).isPresent();
      assertThat(found.orElseThrow().isEmpty()).isTrue();
    }

    @Test
    void a_null_id_is_rejected() {
      Plan plan = Plan.empty();

      assertThatThrownBy(() -> store.save(null, plan))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("id must not be null");
    }

    @Test
    void a_null_plan_is_rejected() {
      ConversationId id = ConversationId.generate();

      assertThatThrownBy(() -> store.save(id, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("plan must not be null");
    }
  }
}
