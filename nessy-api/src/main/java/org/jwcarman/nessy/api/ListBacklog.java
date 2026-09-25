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

package org.jwcarman.nessy.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A backlog held in a list, for anywhere there is no database to hold one.
 *
 * <p>Tests, and the in-memory fold. What a durable backlog does with statements this does with an
 * {@link ArrayList}, so a policy behaves the same either way -- which is the point: a strategy that
 * passed in a test and behaved differently against rows would be found late and be hard to explain.
 *
 * @param <O> the application's observation type
 */
public final class ListBacklog<O> implements Backlog<O> {

  private final List<BacklogItem<O>> items;
  private boolean ended;

  public ListBacklog() {
    this(List.of());
  }

  public ListBacklog(List<BacklogItem<O>> waiting) {
    this.items = new ArrayList<>(Objects.requireNonNull(waiting, "waiting must not be null"));
  }

  /** What is waiting now, oldest first. */
  public List<BacklogItem<O>> items() {
    return List.copyOf(items);
  }

  /** End it: abandon what was waiting, and answer every read afterwards with the pill. */
  public int seal() {
    int abandoned = items.size();
    items.clear();
    ended = true;
    return abandoned;
  }

  @Override
  public Pull<O> take() {
    if (!items.isEmpty()) {
      return new Pull.Item<>(items.removeFirst());
    }
    return ended ? new Pull.Pill<>() : new Pull.Empty<>();
  }

  @Override
  public void append(BacklogItem<O> item) {
    items.add(Objects.requireNonNull(item, "item must not be null"));
  }

  @Override
  public void prepend(BacklogItem<O> item) {
    items.addFirst(Objects.requireNonNull(item, "item must not be null"));
  }

  @Override
  public void replaceAll(BacklogItem<O> item) {
    Objects.requireNonNull(item, "item must not be null");
    items.clear();
    items.add(item);
  }

  @Override
  public int size() {
    return items.size();
  }

  @Override
  public void dropOldest(int count) {
    for (int dropped = 0; dropped < count && !items.isEmpty(); dropped++) {
      items.removeFirst();
    }
  }

  @Override
  public List<BacklogItem<O>> all() {
    return List.copyOf(items);
  }

  @Override
  public void rewrite(List<BacklogItem<O>> replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    items.clear();
    items.addAll(replacement);
  }
}
