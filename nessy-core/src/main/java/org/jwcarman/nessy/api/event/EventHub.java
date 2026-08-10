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

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Where runtime narrative flows.
 *
 * <p>The synchronous spine (design §9.1): delivery is synchronous, in subscription order, on the
 * emitting thread — and <b>a throwing subscriber stops the operation that emitted</b>. That is not
 * an accident to guard against; it is the point. A subscriber that has to stand in the way of
 * something (an audit write that must not be lost, an invariant check) writes inline and lets its
 * exception propagate out through {@code emit} and whatever called it — the veto is the throw. A
 * subscriber with no business stopping anything wraps itself with {@link #async(Consumer,
 * Consumer)} and goes about its business on a fresh virtual thread instead, where nothing it throws
 * can ever reach the emitting thread.
 *
 * <p>The hub is otherwise exhaust, never intake: no return values, no vetoes-by-value. Input
 * reaches the reducer only through the engine; a subscriber's only lever over the run it is
 * watching is the exception it throws.
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

  /**
   * Wraps {@code listener} so each event it receives is handled on a fresh virtual thread instead
   * of the emitting thread — the per-subscriber opt-out of the synchronous spine's veto-by-throw.
   * An exception the wrapped listener throws is caught on that virtual thread and handed to {@code
   * onError}; it never reaches the thread that called {@code emit}, and it never stops the emitting
   * operation or any other subscriber.
   *
   * <p>One virtual thread per event, so ordering across events for this one subscriber is not
   * guaranteed the way the synchronous default's is — a slow or reordered virtual thread scheduling
   * is explicitly this wrapper's business, not the hub's.
   *
   * @param listener the subscriber logic to run off the emitting thread
   * @param onError where a thrown exception goes instead of the emitting thread
   */
  static <E> Consumer<E> async(Consumer<E> listener, Consumer<Throwable> onError) {
    Objects.requireNonNull(listener, "listener must not be null");
    Objects.requireNonNull(onError, "onError must not be null");
    return event ->
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    listener.accept(event);
                  } catch (RuntimeException e) {
                    onError.accept(e);
                  }
                });
  }

  /**
   * {@link #async(Consumer, Consumer)}, reporting a failed listener to a JDK {@link
   * java.lang.System.Logger} rather than requiring every caller to supply its own handler — no new
   * dependency, since {@code nessy-core} stays slf4j-free.
   *
   * @param listener the subscriber logic to run off the emitting thread
   */
  static <E> Consumer<E> async(Consumer<E> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    System.Logger logger = System.getLogger(EventHub.class.getName());
    return async(listener, e -> logger.log(Level.ERROR, "async event subscriber failed", e));
  }
}
