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
package org.jwcarman.nessy.api.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * One observation waiting its turn (actor-composition spec §2).
 *
 * <p><b>{@code id} is load-bearing.</b> The {@code Remembrance} key for the observation derives
 * from it, which is what makes a re-take after a crash idempotent rather than a duplicated user
 * turn. That is not hypothetical: a live soak on 2026-08-28 recorded ONE observation and then ran
 * six rounds against an unchanging context, because a coalescing key was reused as an entry id and
 * every derived key collided. Ids are minted per arrival and never reused — a merged item is a NEW
 * item.
 *
 * <p><b>{@code receivedAt} is the only clock a pure coalescer gets.</b> Staleness ("drop anything
 * older than five minutes") is a real policy, and a pure function must not read a clock.
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
