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

import java.time.Instant;
import java.util.Objects;

/**
 * One observation waiting to become a turn.
 *
 * <p>{@code id} exists so a {@link BacklogCoalescer} can talk about items — supersede this one,
 * keep that one — without comparing observations for equality, which is the application's
 * vocabulary and may not have equality worth relying on.
 *
 * @param <O> the observation type
 */
public record BacklogItem<O>(String id, O observation, Instant receivedAt) {

  public BacklogItem {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(observation, "observation must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");
  }
}
