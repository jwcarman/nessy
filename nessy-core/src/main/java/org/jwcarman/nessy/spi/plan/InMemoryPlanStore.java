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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The in-process {@link PlanStore}: one {@link Plan} per conversation, kept in a map for the life
 * of the process, last write wins.
 */
final class InMemoryPlanStore implements PlanStore {

  private final Map<ConversationId, Plan> plans = new ConcurrentHashMap<>();

  @Override
  public Optional<Plan> find(ConversationId id) {
    return Optional.ofNullable(plans.get(id));
  }

  @Override
  public void save(ConversationId id, Plan plan) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    plans.put(id, plan);
  }
}
