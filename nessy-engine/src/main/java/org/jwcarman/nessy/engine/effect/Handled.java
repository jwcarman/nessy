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
package org.jwcarman.nessy.engine.effect;

import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.backend.effect.EffectOutcome;

/**
 * What a handler did with an effect, as the dispatcher reads it.
 *
 * <p>Engine-internal. It is public only because the direct harness, in another package, reads it
 * too; nothing in the API names it. Approvers and tools answer with {@link
 * org.jwcarman.nessy.api.Awaited}, which carries nothing when it defers, so a handler translates:
 * an answer becomes {@link Settled}, and a deferral becomes {@link Deferred} with whatever the
 * handler knows about what is now being waited for.
 */
public sealed interface Handled {

  /** The effect has an outcome now. */
  record Settled(EffectOutcome outcome) implements Handled {}

  /**
   * The work is elsewhere and an answer will arrive later.
   *
   * @param question the stored question somebody is being asked, when the handler asked one
   */
  record Deferred(Optional<PayloadRef> question) implements Handled {}

  static Handled settled(EffectOutcome outcome) {
    return new Settled(outcome);
  }

  static Handled deferred() {
    return new Deferred(Optional.empty());
  }
}
