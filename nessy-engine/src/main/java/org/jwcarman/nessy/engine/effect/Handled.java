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

import org.jwcarman.nessy.backend.effect.EffectOutcome;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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
   * @param facts the facts the approver was shown, as they stood when it put the call aside; an
   *     empty object for a tool that defers. The record holds its own copy.
   */
  record Deferred(ObjectNode facts) implements Handled {
    public Deferred {
      facts = facts == null ? JsonNodeFactory.instance.objectNode() : facts.deepCopy();
    }
  }

  static Handled settled(EffectOutcome outcome) {
    return new Settled(outcome);
  }

  static Handled deferred() {
    return new Deferred(JsonNodeFactory.instance.objectNode());
  }
}
