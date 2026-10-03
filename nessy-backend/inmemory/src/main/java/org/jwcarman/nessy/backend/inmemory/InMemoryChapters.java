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
package org.jwcarman.nessy.backend.inmemory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.chapter.Chapters;

/**
 * Closed chapters held in this process and nowhere else.
 *
 * <p>For a CLI, a test, or anything else with one JVM and no database. Every call takes one lock,
 * which is what makes {@link #append} atomic and conditional: reading where the closed chapters end
 * and writing the new ones happen as one step, so two callers appending after the same point cannot
 * both be told they stored.
 */
public final class InMemoryChapters implements Chapters {

  private record Key(AgentType type, AgentId agent) {}

  /** A closed chapter and its encoded text, which is null until it has been written. */
  private record Entry(Chapter chapter, byte[] text) {

    @Override
    public boolean equals(Object other) {
      return other instanceof Entry that
          && chapter.equals(that.chapter)
          && Arrays.equals(text, that.text);
    }

    @Override
    public int hashCode() {
      return 31 * chapter.hashCode() + Arrays.hashCode(text);
    }

    @Override
    public String toString() {
      return "Entry[chapter=%s, text=%s]"
          .formatted(chapter, text == null ? "unwritten" : text.length + " bytes");
    }
  }

  private final Map<Key, List<Entry>> closed = new HashMap<>();
  private final Codec<String> codec;

  /**
   * The same factory every other store is handed. A summary is kept as the bytes the codec made of
   * it, exactly as {@link InMemoryPayloads} keeps content, so this stands in for the durable store
   * without standing in for a kinder one.
   */
  public InMemoryChapters(CodecFactory codecs) {
    this.codec = Objects.requireNonNull(codecs, "codecs must not be null").create(String.class);
  }

  @Override
  public synchronized boolean append(
      AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    Objects.requireNonNull(after, "after must not be null");
    Objects.requireNonNull(chapters, "chapters must not be null");
    validate(type, agent, after, chapters);
    if (chapters.isEmpty()) {
      return true;
    }
    List<Entry> entries = closed.computeIfAbsent(new Key(type, agent), _ -> new ArrayList<>());
    if (!endOf(entries).equals(after)) {
      return false;
    }
    chapters.forEach(chapter -> entries.add(new Entry(chapter, null)));
    return true;
  }

  private static void validate(
      AgentType type, AgentId agent, Optional<TurnId> after, List<Chapter> chapters) {
    TurnId previous = after.orElse(null);
    for (Chapter chapter : chapters) {
      if (!chapter.agentType().equals(type) || !chapter.agentId().equals(agent)) {
        throw new IllegalArgumentException(
            "chapter %s through %s is not for agent %s/%s"
                .formatted(chapter.from(), chapter.through(), type, agent));
      }
      if (previous != null && chapter.from().value() <= previous.value()) {
        throw new IllegalArgumentException(
            "chapter from %s does not come after turn %s".formatted(chapter.from(), previous));
      }
      previous = chapter.through();
    }
  }

  private static Optional<TurnId> endOf(List<Entry> entries) {
    return entries.isEmpty()
        ? Optional.empty()
        : Optional.of(entries.getLast().chapter().through());
  }

  @Override
  public synchronized boolean summarize(Summary summary) {
    Objects.requireNonNull(summary, "summary must not be null");
    Chapter chapter = summary.chapter();
    List<Entry> entries = closed.get(new Key(chapter.agentType(), chapter.agentId()));
    if (entries == null) {
      return false;
    }
    for (int i = 0; i < entries.size(); i++) {
      Entry entry = entries.get(i);
      if (entry.chapter().equals(chapter)) {
        if (entry.text() != null) {
          return false;
        }
        entries.set(i, new Entry(chapter, codec.encode(summary.text())));
        return true;
      }
    }
    return false;
  }

  @Override
  public synchronized Optional<TurnId> closedThrough(AgentType type, AgentId agent) {
    return endOf(entries(type, agent));
  }

  @Override
  public synchronized List<Chapter> unsummarized(AgentType type, AgentId agent) {
    return entries(type, agent).stream()
        .filter(entry -> entry.text() == null)
        .map(Entry::chapter)
        .toList();
  }

  @Override
  public synchronized List<Summary> summaries(AgentType type, AgentId agent) {
    return entries(type, agent).stream()
        .takeWhile(entry -> entry.text() != null)
        .map(entry -> new Summary(entry.chapter(), codec.decode(entry.text())))
        .toList();
  }

  private List<Entry> entries(AgentType type, AgentId agent) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return closed.getOrDefault(new Key(type, agent), List.of());
  }
}
