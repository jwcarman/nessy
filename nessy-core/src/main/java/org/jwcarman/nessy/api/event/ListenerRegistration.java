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

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One build-time listener registration — what {@code listen}/{@code listenAsync} on {@code
 * HarnessBuilder}/{@code AgentBuilder} each capture — frozen into a {@link ListenerRegistry} at
 * {@code build()} (design §17: "Prepare is a build-time phase").
 *
 * <p>{@link #sync} registrations propagate whatever they throw: the veto is the throw, exactly as
 * for {@link ConversationEvents#subscribe}. {@link #async} registrations never do — by the time
 * this registration's delivery call returns, the listener has already been handed to a fresh
 * virtual thread (see {@link AsyncDelivery}), so nothing it throws can reach the emitting thread or
 * stop the chain.
 */
public final class ListenerRegistration {

  private final Class<?> type;
  private final Delivery delivery;

  private ListenerRegistration(Class<?> type, Delivery delivery) {
    this.type = type;
    this.delivery = delivery;
  }

  public static <T> ListenerRegistration sync(Class<T> type, Consumer<T> listener) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    return new ListenerRegistration(type, event -> listener.accept(type.cast(event)));
  }

  public static <T> ListenerRegistration async(
      Class<T> type, Consumer<T> listener, Consumer<Throwable> onError) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(listener, "listener must not be null");
    Objects.requireNonNull(onError, "onError must not be null");
    return new ListenerRegistration(
        type, event -> AsyncDelivery.deliver(type.cast(event), listener, onError));
  }

  /** Delivers {@code event} if it matches this registration's type; a no-op otherwise. */
  void deliverIfMatches(Object event) {
    if (type.isInstance(event)) {
      delivery.run(event);
    }
  }

  @FunctionalInterface
  private interface Delivery {
    void run(Object event);
  }
}
