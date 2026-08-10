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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The default {@link EventHub}: synchronous, in subscription order, on the emitting thread — and a
 * throwing subscriber propagates straight out of {@link #emit}, stopping delivery to every
 * registration after it. See the {@link EventHub} javadoc for why that is the contract rather than
 * a bug: the veto is the throw.
 */
final class SynchronousEventHub implements EventHub {

  private final List<Registration<?>> registrations = new CopyOnWriteArrayList<>();

  @Override
  public void emit(Object event) {
    for (Registration<?> registration : registrations) {
      registration.deliver(event);
    }
  }

  @Override
  public <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber) {
    Registration<E> registration = new Registration<>(type, subscriber);
    registrations.add(registration);
    return () -> registrations.remove(registration);
  }

  /**
   * A private, identity-equality class rather than a record.
   *
   * <p>Two subscriptions of the same consumer to the same type would be {@code equals} as a record,
   * so closing one would remove whichever registration the list happened to hold — possibly the
   * other one. Identity equality (the default for a plain class) makes every subscription its own
   * registration, closable independently of any other subscription that looks just like it.
   */
  private static final class Registration<E> {

    private final Class<E> type;
    private final Consumer<E> subscriber;

    Registration(Class<E> type, Consumer<E> subscriber) {
      this.type = type;
      this.subscriber = subscriber;
    }

    /**
     * Delivers {@code event} to this registration, uncaught: a throwing subscriber propagates
     * straight out of here, through {@link #emit}'s loop, and into whatever called {@code emit} —
     * the synchronous spine's veto-by-throw (design §9.1). A subscriber that must not be allowed to
     * stop the run wraps itself with {@link EventHub#async(Consumer, Consumer)} instead of relying
     * on this class to protect it.
     */
    void deliver(Object event) {
      if (!type.isInstance(event)) {
        return;
      }
      subscriber.accept(type.cast(event));
    }
  }
}
