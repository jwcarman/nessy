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
package org.jwcarman.nessy.spring.boot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.engine.story.EventAgentStories;

/**
 * Stories over several event stores, each agent read from the one store that holds it.
 *
 * <p>The store is the first that holds any event for the agent, as {@code EventUsageReports}
 * chooses, and it answers every replay for that agent, so an empty page past the end of a story
 * never falls through to another store. An agent no store holds has an empty story.
 */
final class FirstStoreHoldingStories implements AgentStories {

  private final List<AgentEvents> stores;

  FirstStoreHoldingStories(List<AgentEvents> stores) {
    this.stores = List.copyOf(stores);
  }

  @Override
  public AgentStory of(AgentType type, AgentId id) {
    return new AgentStory() {
      @Override
      public List<Narrated> replay(Seq after, int limit) {
        return holding(type, id)
            .map(store -> new EventAgentStories(store).of(type, id).replay(after, limit))
            .orElseGet(List::of);
      }

      @Override
      public <T> T project(StoryProjection<T> projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        Optional<AgentEvents> store = holding(type, id);
        if (store.isEmpty()) {
          return projection.initial();
        }
        return new EventAgentStories(store.get()).of(type, id).project(projection);
      }
    };
  }

  private Optional<AgentEvents> holding(AgentType type, AgentId id) {
    return stores.stream()
        .filter(store -> !store.readWrittenFrom(type, id, Seq.NONE, 1).isEmpty())
        .findFirst();
  }
}
