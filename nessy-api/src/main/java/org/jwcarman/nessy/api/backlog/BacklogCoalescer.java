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
package org.jwcarman.nessy.api.backlog;

import java.util.List;

/**
 * What a waiting backlog does when something new arrives — and the ONE place an observation can be
 * refused.
 *
 * <p>Everything is expressible by what comes back: keep the arrival by returning it in the list,
 * DROP it by returning the current items unchanged, supersede older items by leaving them out,
 * merge several into one by returning the merger. An {@code ObservationRenderer} deliberately
 * cannot decline, because declining here is a normal decision and declining there would break the
 * turn lifecycle.
 *
 * <p>This is a property of the observation vocabulary itself, declared once per kind of agent
 * rather than decided per arrival.
 *
 * @param <O> the observation type
 */
@FunctionalInterface
public interface BacklogCoalescer<O> {

  /**
   * The backlog after {@code newItem} arrives.
   *
   * @param currentItems what is already waiting, oldest first; never includes an item already in
   *     flight
   * @param newItem what just arrived
   * @return the backlog to keep
   */
  List<BacklogItem<O>> coalesce(List<BacklogItem<O>> currentItems, BacklogItem<O> newItem);
}
