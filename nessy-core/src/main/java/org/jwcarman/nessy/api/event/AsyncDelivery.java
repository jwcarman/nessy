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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The virtual-thread machinery behind {@link EventHub#subscribeAsync(Class, Consumer, Consumer)}.
 *
 * <p>Package-private and stateful (a counter), so it cannot live as a field directly on the {@link
 * EventHub} interface — interface fields are implicitly {@code public static final}, which would
 * expose the counter as part of the public API for no reason.
 */
final class AsyncDelivery {

  private static final AtomicLong THREAD_COUNTER = new AtomicLong();

  private AsyncDelivery() {}

  /**
   * Starts a fresh virtual thread named {@code nessy-delivery-<n>} that delivers {@code event} to
   * {@code listener}, routing any {@link RuntimeException} it throws to {@code onError} instead of
   * letting it escape.
   */
  static <E> void deliver(E event, Consumer<E> listener, Consumer<Throwable> onError) {
    Thread.ofVirtual()
        .name("nessy-delivery-", THREAD_COUNTER.getAndIncrement())
        .start(
            () -> {
              try {
                listener.accept(event);
              } catch (RuntimeException e) {
                onError.accept(e);
              }
            });
  }
}
