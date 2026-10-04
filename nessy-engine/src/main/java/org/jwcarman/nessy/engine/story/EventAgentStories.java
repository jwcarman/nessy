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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.payload.Payloads;
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
  private final Payloads payloads;

  public EventAgentStories(AgentEvents events, Payloads payloads) {
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
  }

  /**
   * Folds one agent's whole story, a page at a time, without needing the content behind it.
   *
   * <p>For a reader that holds only the event store, such as usage reports.
   */
  public static <T> T project(
      AgentEvents events, AgentType type, AgentId id, StoryProjection<T> projection) {
    Objects.requireNonNull(projection, "projection must not be null");
    T value = projection.initial();
    Seq after = Seq.NONE;
    List<Narrated> page;
    do {
      page = replay(events, type, id, after, MAXIMUM_LIMIT);
      for (Narrated story : page) {
        value = projection.apply(value, story);
      }
      if (!page.isEmpty()) {
        after = page.getLast().position().orElseThrow().seq();
      }
    } while (page.size() == MAXIMUM_LIMIT);
    return value;
  }

  @Override
  public AgentStory of(AgentType type, AgentId id) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(id, "id must not be null");
    return new StoredStory(type, id);
  }

  private final class StoredStory implements AgentStory {

    private final AgentType type;
    private final AgentId id;

    private StoredStory(AgentType type, AgentId id) {
      this.type = type;
      this.id = id;
    }

    @Override
    public List<Narrated> replay(Seq after, int limit) {
      return EventAgentStories.replay(events, type, id, after, limit);
    }

    @Override
    public <T> T project(StoryProjection<T> projection) {
      return EventAgentStories.project(events, type, id, projection);
    }

    @Override
    public StoryContent content() {
      return new StoredContent(events, payloads, type, id);
    }
  }

  private static List<Narrated> replay(
      AgentEvents events, AgentType type, AgentId id, Seq after, int limit) {
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    int capped = Math.min(limit, MAXIMUM_LIMIT);
    return events.readWrittenFrom(type, id, after, capped).stream()
        .map(w -> Narrated.story(type, id, StoryEvents.of(w.event()), w.event().seq(), w.at()))
        .toList();
  }
}
