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
package org.jwcarman.nessy.agent.intent;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.spi.intent.IntentStore;

/**
 * The in-process {@link IntentStore}: one declaration held for the life of the process, last write
 * wins. {@code synchronized} because a completion may declare from an executor thread while a
 * concurrent enricher reads — the same concurrency posture as {@link
 * org.jwcarman.nessy.agent.memory.VerbatimMemory}.
 *
 * @param <T> the declared-intent vocabulary this store holds
 */
public final class InMemoryIntentStore<T> implements IntentStore<T> {

  private T latest;

  @Override
  public synchronized void declare(T declaration) {
    this.latest = Objects.requireNonNull(declaration, "declaration must not be null");
  }

  @Override
  public synchronized Optional<T> latest() {
    return Optional.ofNullable(latest);
  }
}
