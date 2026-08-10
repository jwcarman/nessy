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
 * <p>The hub itself is always a synchronous spine (design §9.1): every subscriber is delivered to
 * synchronously, in subscription order, on the calling thread — that is how a sync subscriber gets
 * veto power. A subscriber that has to stand in the way of something (an audit write that must not
 * be lost, an invariant check) subscribes with {@link #subscribe(Class, Consumer)} and lets its
 * exception propagate out through {@code emit} and whatever called it: <b>the veto is the
 * throw</b>.
 *
 * <p>Sync or async is chosen once, at subscription time, never by the hub. A subscriber with no
 * business standing in the way of anything calls {@link #subscribeAsync(Class, Consumer, Consumer)}
 * (or its {@link System.Logger}-backed convenience, {@link #subscribeAsync(Class, Consumer)})
 * instead of {@link #subscribe(Class, Consumer)}; the hub wraps that listener (and its error
 * handler) so each event it receives runs on a fresh virtual thread, and nothing it throws can ever
 * reach the emitting thread. The returned {@link Subscription} is the same type either way —
 * closing it stops delivery regardless of how the subscriber chose to receive it.
 *
 * <p>The hub is otherwise exhaust, never intake: no return values, no vetoes-by-value. Input
 * reaches the reducer only through the engine; a subscriber's only lever over the run it is
 * watching is the exception it throws — and only a sync subscriber's throw reaches anything.
 *
 * <p>The vocabulary is open on purpose: any module may publish its own event records, and
 * subscribers select by type. The reducer's sealed {@code Event} grammar stays closed; the hub
 * re-publishes loop activity wrapped in {@link SessionEvent} and never feeds the loop.
 */
public interface EventHub extends EventEmitter {

  /**
   * Subscribes {@code subscriber} synchronously: delivery happens in subscription order, on the
   * thread that calls {@code emit}, and an exception {@code subscriber} throws propagates straight
   * out of {@code emit} — the veto is the throw.
   */
  <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber);

  /**
   * Subscribes {@code listener} asynchronously: the hub wraps it so each matching event is handled
   * on a fresh virtual thread instead of the emitting thread, the per-subscriber opt-out of the
   * synchronous spine's veto-by-throw. An exception {@code listener} throws is caught on that
   * virtual thread and handed to {@code onError}; it never reaches the thread that called {@code
   * emit}, and it never stops the emitting operation or any other subscriber.
   *
   * <p><b>Ordering caveat:</b> one virtual thread is started per event, so an async subscriber may
   * observe its events out of order under load — a slow or rescheduled virtual thread is explicitly
   * this wrapper's business, not the hub's. Order-sensitive subscribers stay sync.
   *
   * @param listener the subscriber logic to run off the emitting thread
   * @param onError where a thrown exception goes instead of the emitting thread
   * @return the subscription, closable like any other
   */
  default <E> Subscription subscribeAsync(
      Class<E> type, Consumer<E> listener, Consumer<Throwable> onError) {
    Objects.requireNonNull(listener, "listener must not be null");
    Objects.requireNonNull(onError, "onError must not be null");
    return subscribe(type, event -> AsyncDelivery.deliver(event, listener, onError));
  }

  /**
   * {@link #subscribeAsync(Class, Consumer, Consumer)}, reporting a failed listener to a JDK {@link
   * System.Logger} rather than requiring every caller to supply its own handler — no new
   * dependency, since {@code nessy-core} stays slf4j-free.
   *
   * <p>Carries the same ordering caveat as {@link #subscribeAsync(Class, Consumer, Consumer)}: a
   * per-event virtual thread means this subscriber may observe events out of order under load.
   *
   * @param listener the subscriber logic to run off the emitting thread
   * @return the subscription, closable like any other
   */
  default <E> Subscription subscribeAsync(Class<E> type, Consumer<E> listener) {
    Objects.requireNonNull(listener, "listener must not be null");
    System.Logger logger = System.getLogger(EventHub.class.getName());
    return subscribeAsync(
        type, listener, e -> logger.log(Level.ERROR, "async event subscriber failed", e));
  }

  /** The default: dispatches on the emitting thread, in subscription order. */
  static EventHub synchronous() {
    return new SynchronousEventHub();
  }
}
