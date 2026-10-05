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
import java.util.stream.Stream;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallResult;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.api.TurnContent;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.story.EventAgentStories;
import tools.jackson.databind.JsonNode;

/**
 * Stories over several event stores, each agent read from the one store that holds it.
 *
 * <p>The store is the first that holds any event for the agent, as {@code EventUsageReports}
 * chooses, and it answers every replay for that agent, so an empty page past the end of a story
 * never falls through to another store. An agent no store holds has an empty story.
 *
 * <p>The store is looked up on each call, not chosen once: one small read per store, so an agent
 * whose first event is written later is found by the next call.
 */
final class FirstStoreHoldingStories implements AgentStories {

  /** One door's store: its events, and the payloads those events refer to. */
  record Store(AgentEvents events, Payloads payloads) {}

  private final List<Store> stores;

  FirstStoreHoldingStories(List<Store> stores) {
    this.stores = List.copyOf(stores);
  }

  @Override
  public AgentStory of(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return new AgentStory() {
      @Override
      public List<Narrated> replay(Seq after, int limit) {
        return holding(type, id)
            .map(story -> story.replay(after, limit))
            .orElseGet(
                () -> {
                  requireReadable(after, limit);
                  return List.of();
                });
      }

      @Override
      public <T> T project(StoryProjection<T> projection) {
        Objects.requireNonNull(projection, "projection must not be null");
        Optional<AgentStory> story = holding(type, id);
        if (story.isEmpty()) {
          return projection.initial();
        }
        return story.get().project(projection);
      }

      @Override
      public StoryContent content() {
        return holding(type, id).map(AgentStory::content).orElseGet(EmptyContent::new);
      }
    };
  }

  private Optional<AgentStory> holding(AgentType type, AgentId id) {
    return stores.stream()
        .filter(store -> !store.events().readWrittenFrom(type, id, Seq.NONE, 1).isEmpty())
        .findFirst()
        .map(store -> new EventAgentStories(store.events(), store.payloads()).of(type, id));
  }

  /** An empty story is read under the same rules as any other. */
  private static void requireReadable(Seq after, int limit) {
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
  }

  /** The content of an agent no store holds. */
  private static final class EmptyContent implements StoryContent {

    @Override
    public TurnContent turn(TurnId turn) {
      Objects.requireNonNull(turn, "turn must not be null");
      throw new IllegalArgumentException("no turn " + turn.value() + " in this agent's story");
    }

    @Override
    public Optional<List<Block.ToolResultContent>> result(IdempotencyKey key) {
      Objects.requireNonNull(key, "key must not be null");
      return Optional.empty();
    }

    @Override
    public Optional<JsonNode> approvalFacts(IdempotencyKey key) {
      Objects.requireNonNull(key, "key must not be null");
      return Optional.empty();
    }

    @Override
    public List<CallResult> results(Seq after, int limit) {
      requireReadable(after, limit);
      return List.of();
    }

    @Override
    public Stream<CallResult> allResults(Seq after) {
      Objects.requireNonNull(after, "after must not be null");
      return Stream.empty();
    }
  }
}
