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
package org.jwcarman.nessy.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.Plan.Status;
import org.jwcarman.nessy.spi.plan.Plan.Task;
import org.jwcarman.nessy.spi.plan.PlanStore;

/**
 * The technology-compatibility kit every {@link PlanStore} implementation must pass:
 * save-then-find, wholesale replacement, ordering, the empty-save-clears rule, absence, and
 * last-write-wins — pinned as law rather than left to each implementation's own judgment.
 */
public abstract class PlanStoreContract {

  /** The store under test — fresh and empty for each test. */
  protected abstract PlanStore plans();

  @Test
  public void a_saved_plan_is_found_by_its_conversation_id() {
    ConversationId id = ConversationId.generate();
    Plan plan =
        new Plan(
            List.of(
                new Task("fetch the order history", Status.DONE),
                new Task("summarize the disputes", Status.IN_PROGRESS)));

    plans().save(id, plan);

    assertThat(plans().find(id)).contains(plan);
  }

  @Test
  public void a_wholesale_replacement_removes_departed_tasks() {
    ConversationId id = ConversationId.generate();
    plans()
        .save(
            id,
            new Plan(
                List.of(
                    new Task("fetch the order history", Status.DONE),
                    new Task("summarize the disputes", Status.IN_PROGRESS))));

    Plan replacement = new Plan(List.of(new Task("draft the refund email", Status.PENDING)));
    plans().save(id, replacement);

    assertThat(plans().find(id)).contains(replacement);
  }

  @Test
  public void ordering_is_preserved_across_save_and_find() {
    ConversationId id = ConversationId.generate();
    Plan plan =
        new Plan(
            List.of(
                new Task("first", Status.DONE),
                new Task("second", Status.IN_PROGRESS),
                new Task("third", Status.PENDING),
                new Task("fourth", Status.PENDING),
                new Task("fifth", Status.PENDING)));

    plans().save(id, plan);

    assertThat(plans().find(id)).isPresent().get().extracting(Plan::tasks).isEqualTo(plan.tasks());
  }

  @Test
  public void saving_the_empty_plan_clears_it() {
    ConversationId id = ConversationId.generate();
    plans().save(id, new Plan(List.of(new Task("fetch the order history", Status.DONE))));

    plans().save(id, Plan.empty());

    assertThat(plans().find(id)).isEmpty();
  }

  @Test
  public void a_conversation_that_never_saved_a_plan_finds_nothing() {
    assertThat(plans().find(ConversationId.generate())).isEmpty();
  }

  @Test
  public void saving_again_replaces_the_prior_plan_last_write_wins() {
    ConversationId id = ConversationId.generate();
    plans().save(id, new Plan(List.of(new Task("fetch the order history", Status.DONE))));

    Plan replacement = new Plan(List.of(new Task("summarize the disputes", Status.IN_PROGRESS)));
    plans().save(id, replacement);

    assertThat(plans().find(id)).contains(replacement);
  }
}
