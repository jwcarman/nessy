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

import java.util.Optional;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * One conversation's current plan. Last-write-wins, no fencing, same justification as {@link
 * org.jwcarman.nessy.spi.memory.SummaryStore}: the writer is the update_plan tool, executing inside
 * the loop, which runs one turn at a time per conversation — and an at-least-once replay rewrites
 * the identical plan, so a clobbered write is re-done work, never a lost word.
 */
public interface PlanStore {

  /**
   * The current plan for {@code id}, or empty if the model has never written one — or has cleared
   * it (see {@link #save}).
   */
  Optional<Plan> find(ConversationId id);

  /**
   * Replaces whatever plan {@code id} had, wholesale. Saving {@link Plan#empty()} clears: a
   * subsequent {@link #find} returns {@link Optional#empty()} — "no plan" and "empty plan" are one
   * state, for every backend, because nothing downstream distinguishes them (the transformer
   * injects nothing either way) and one-row-per-task storage could not tell them apart without a
   * marker it has no other use for.
   */
  void save(ConversationId id, Plan plan);

  /** The zero-configuration default: plans live in this JVM and die with it. */
  static PlanStore inMemory() {
    return new InMemoryPlanStore();
  }
}
