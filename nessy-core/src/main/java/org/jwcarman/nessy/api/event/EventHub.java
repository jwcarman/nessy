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
package org.jwcarman.nessy.api.event;

import java.util.function.Consumer;

/**
 * Where runtime narrative flows.
 *
 * <p>Three commitments, all load-bearing. Delivery is synchronous, in subscription order, on the
 * emitting thread — live streaming and deterministic tests depend on it; asynchronous delivery is a
 * decorator's job. The hub is exhaust, never intake: no return values, no vetoes, and input reaches
 * the reducer only through the engine. A subscriber's {@link RuntimeException} is contained here —
 * logged nowhere, swallowed, and never allowed to alter or abort execution — but an {@link Error}
 * is not: it propagates, because a broken subscriber is a bug to route around, while a JVM-level
 * error (an {@code OutOfMemoryError}, say) is not something the hub can safely pretend didn't
 * happen.
 *
 * <p>The vocabulary is open on purpose: any module may publish its own event records, and
 * subscribers select by type. The reducer's sealed {@code Event} grammar stays closed; the hub
 * re-publishes loop activity wrapped in {@link SessionEvent} and never feeds the loop.
 */
public interface EventHub extends EventEmitter {

  <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber);

  /** The default: dispatches on the emitting thread, in subscription order. */
  static EventHub synchronous() {
    return new SynchronousEventHub();
  }
}
