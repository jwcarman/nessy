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
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.backend.backlog.Backlog;
import org.jwcarman.nessy.backend.backlog.Pull;

/**
 * A backlog held in a list, for anywhere there is no database to hold one.
 *
 * <p>Tests, and the in-memory fold. What a durable backlog does with statements this does with an
 * {@link ArrayList}, so a policy behaves the same either way -- which is the point: a strategy that
 * passed in a test and behaved differently against rows would be found late and be hard to explain.
 *
 * @param <I> the application's input type
 */
public final class InMemoryBacklog<I> implements Backlog<I> {

  private static final String ITEM_REQUIRED = "item must not be null";

  private final List<BacklogItem<I>> items;
  private final BooleanSupplier terminated;

  public InMemoryBacklog() {
    this(List.of());
  }

  public InMemoryBacklog(List<BacklogItem<I>> waiting) {
    this(waiting, () -> false);
  }

  /**
   * @param terminated whether the agent this belongs to has been terminated. Asked rather than
   *     held, because terminating an agent is {@link org.jwcarman.nessy.backend.agent.Agents}'
   *     business and a backlog that kept its own flag would be a second answer to the same
   *     question. The durable one asks the same way: its {@code take} reads the agent's row rather
   *     than its own table.
   */
  public InMemoryBacklog(List<BacklogItem<I>> waiting, BooleanSupplier terminated) {
    this.items = new ArrayList<>(Objects.requireNonNull(waiting, "waiting must not be null"));
    this.terminated = Objects.requireNonNull(terminated, "terminated must not be null");
  }

  /** Abandons what was waiting and says how much there was, for {@code Agents} when it seals. */
  int clear() {
    int abandoned = items.size();
    items.clear();
    return abandoned;
  }

  /** What is waiting now, oldest first. */
  public List<BacklogItem<I>> items() {
    return List.copyOf(items);
  }

  /**
   * Not on {@link org.jwcarman.nessy.api.Coalescing}: a coalescing policy has no business taking.
   */
  @Override
  public Pull<I> take() {
    if (!items.isEmpty()) {
      return new Pull.Item<>(items.removeFirst());
    }
    return terminated.getAsBoolean() ? new Pull.Pill<>() : new Pull.Empty<>();
  }

  @Override
  public void append(BacklogItem<I> item) {
    items.add(Objects.requireNonNull(item, ITEM_REQUIRED));
  }

  @Override
  public void prepend(BacklogItem<I> item) {
    items.addFirst(Objects.requireNonNull(item, ITEM_REQUIRED));
  }

  @Override
  public void replaceAll(BacklogItem<I> item) {
    Objects.requireNonNull(item, ITEM_REQUIRED);
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
  public List<BacklogItem<I>> all() {
    return List.copyOf(items);
  }

  @Override
  public void rewrite(List<BacklogItem<I>> replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    items.clear();
    items.addAll(replacement);
  }
}
