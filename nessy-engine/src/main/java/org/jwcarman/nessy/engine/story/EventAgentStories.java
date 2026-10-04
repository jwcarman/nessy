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
package org.jwcarman.nessy.engine.story;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.engine.narration.StoryEvents;

/**
 * An agent's story read from its stored events.
 *
 * <p>Each event is told by {@link StoryEvents}, the same mapping the doors use as events commit, so
 * a replayed event equals the one a listener heard.
 */
public final class EventAgentStories implements AgentStories {

  /** The most a single replay returns, whatever limit is asked for. */
  private static final int MAXIMUM_LIMIT = 1_000;

  private final AgentEvents events;

  public EventAgentStories(AgentEvents events) {
    this.events = Objects.requireNonNull(events, "events must not be null");
  }

  @Override
  public AgentStory of(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return (after, limit) -> replay(type, id, after, limit);
  }

  private List<Narrated> replay(AgentType type, AgentId id, Seq after, int limit) {
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    int capped = Math.min(limit, MAXIMUM_LIMIT);
    List<Narrated> story = new ArrayList<>();
    try (Stream<AgentEvents.Written> written = events.streamWrittenFrom(type, id, after)) {
      written
          .limit(capped)
          .map(w -> Narrated.story(type, id, StoryEvents.of(w.event()), w.event().seq(), w.at()))
          .forEach(story::add);
    }
    return List.copyOf(story);
  }
}
