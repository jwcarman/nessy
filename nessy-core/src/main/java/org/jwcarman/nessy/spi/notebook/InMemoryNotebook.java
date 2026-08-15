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
package org.jwcarman.nessy.spi.notebook;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.SubjectId;

/**
 * The in-process {@link Notebook}: one map of entries per subject, kept for the life of the
 * process. Concurrent writers to the same subject are safe — {@link ConcurrentHashMap} at both
 * levels — last write wins at entry granularity.
 */
final class InMemoryNotebook implements Notebook {

  private final Map<SubjectId, Map<String, Entry>> notes = new ConcurrentHashMap<>();

  @Override
  public List<Heading> headings(SubjectId subject) {
    Objects.requireNonNull(subject, "subject must not be null");
    Map<String, Entry> entries = notes.get(subject);
    if (entries == null) {
      return List.of();
    }
    return entries.values().stream()
        .map(entry -> new Heading(entry.name(), entry.hook()))
        .sorted(Comparator.comparing(Heading::name))
        .toList();
  }

  @Override
  public Optional<Entry> find(SubjectId subject, String name) {
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Map<String, Entry> entries = notes.get(subject);
    if (entries == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(entries.get(name));
  }

  @Override
  public void save(SubjectId subject, Entry entry) {
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    notes.computeIfAbsent(subject, ignored -> new ConcurrentHashMap<>()).put(entry.name(), entry);
  }

  @Override
  public void forget(SubjectId subject, String name) {
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Map<String, Entry> entries = notes.get(subject);
    if (entries != null) {
      entries.remove(name);
    }
  }
}
