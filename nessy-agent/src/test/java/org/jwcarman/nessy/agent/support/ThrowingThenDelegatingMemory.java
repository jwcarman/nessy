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
package org.jwcarman.nessy.agent.support;

import java.util.Objects;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;

/**
 * A hand-rolled {@link Memory} that throws on its first {@code throwCount} {@link #remember} calls,
 * then delegates every call after that — no mocking library, per house convention. Models a foreign
 * store that is down and then recovers: the caller's own retry/redrive machinery is what this class
 * exists to exercise (remembrance spec §1 law 1).
 */
public final class ThrowingThenDelegatingMemory implements Memory {

  private final Memory delegate;
  private int throwsRemaining;

  public ThrowingThenDelegatingMemory(Memory delegate, int throwCount) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    if (throwCount < 0) {
      throw new IllegalArgumentException("throwCount must not be negative");
    }
    this.throwsRemaining = throwCount;
  }

  @Override
  public void remember(Remembrance remembrance) {
    if (throwsRemaining > 0) {
      throwsRemaining--;
      throw new IllegalStateException("memory unavailable (test double, healing after this call)");
    }
    delegate.remember(remembrance);
  }

  @Override
  public Context recall() {
    return delegate.recall();
  }
}
