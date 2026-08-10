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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EventHubTest {

  record Ping(String name) {}

  record Pong(String name) {}

  private final EventHub hub = EventHub.synchronous();

  @Nested
  class Delivery {

    @Test
    void reaches_only_matching_types() {
      List<Ping> pings = new ArrayList<>();
      hub.subscribe(Ping.class, pings::add);

      hub.emit(new Ping("a"));
      hub.emit(new Pong("ignored"));

      assertThat(pings).containsExactly(new Ping("a"));
    }

    @Test
    void reaches_supertype_subscribers_for_every_subtype() {
      List<Object> everything = new ArrayList<>();
      hub.subscribe(Object.class, everything::add);

      hub.emit(new Ping("a"));
      hub.emit(new Pong("b"));

      assertThat(everything).containsExactly(new Ping("a"), new Pong("b"));
    }

    @Test
    void is_synchronous_and_in_subscription_order() {
      List<String> order = new ArrayList<>();
      hub.subscribe(Ping.class, p -> order.add("first"));
      hub.subscribe(Ping.class, p -> order.add("second"));

      hub.emit(new Ping("a"));

      assertThat(order).containsExactly("first", "second");
    }

    /**
     * The synchronous spine's veto-by-throw (design §9.1): a throwing subscriber is not contained
     * by the hub. Its exception propagates straight out of {@code emit}, and every registration
     * after it in subscription order never runs — the throw stops the operation that emitted, it
     * does not merely skip the one broken subscriber.
     */
    @Test
    void a_throwing_subscriber_propagates_and_stops_delivery_to_the_rest() {
      List<Ping> pings = new ArrayList<>();
      hub.subscribe(
          Ping.class,
          p -> {
            throw new IllegalStateException("observer bug");
          });
      hub.subscribe(Ping.class, pings::add);

      assertThatThrownBy(() -> hub.emit(new Ping("a")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("observer bug");

      assertThat(pings).isEmpty();
    }
  }

  @Nested
  class Async {

    @Test
    void a_wrapped_listener_runs_off_the_emitting_thread_and_does_not_fail_the_run()
        throws InterruptedException {
      CountDownLatch handled = new CountDownLatch(1);
      List<Ping> pings = new CopyOnWriteArrayList<>();
      hub.subscribeAsync(
          Ping.class,
          p -> {
            pings.add(p);
            handled.countDown();
          },
          t -> {});

      hub.emit(new Ping("a"));

      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pings).containsExactly(new Ping("a"));
    }

    @Test
    void a_wrapped_listeners_exception_reaches_onError_and_never_the_emitting_thread()
        throws InterruptedException {
      CountDownLatch errored = new CountDownLatch(1);
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      hub.subscribeAsync(
          Ping.class,
          p -> {
            throw new IllegalStateException("async observer bug");
          },
          t -> {
            errors.add(t);
            errored.countDown();
          });

      hub.emit(new Ping("a"));

      assertThat(errored.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(errors).hasSize(1);
      assertThat(errors.getFirst())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("async observer bug");
    }

    /**
     * Subscription-time choice: an async subscriber that throws never gets the power a sync
     * subscriber has to stop the emitting operation. {@code emit} returns normally; the exception
     * reaches only the error handler, observed here via a latch since it lands on a virtual thread.
     */
    @Test
    void an_async_subscriber_never_vetoes() throws InterruptedException {
      CountDownLatch errored = new CountDownLatch(1);
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      hub.subscribeAsync(
          Ping.class,
          p -> {
            throw new IllegalStateException("async observer bug");
          },
          t -> {
            errors.add(t);
            errored.countDown();
          });

      assertThatCode(() -> hub.emit(new Ping("a"))).doesNotThrowAnyException();

      assertThat(errored.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(errors).hasSize(1);
      assertThat(errors.getFirst())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("async observer bug");
    }

    @Test
    void async_delivery_leaves_the_emitting_thread() throws InterruptedException {
      CountDownLatch delivered = new CountDownLatch(1);
      Thread[] deliveryThread = new Thread[1];
      hub.subscribeAsync(
          Ping.class,
          p -> {
            deliveryThread[0] = Thread.currentThread();
            delivered.countDown();
          },
          t -> {});
      Thread emittingThread = Thread.currentThread();

      hub.emit(new Ping("a"));

      assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(deliveryThread[0]).isNotSameAs(emittingThread);
      assertThat(deliveryThread[0].isVirtual()).isTrue();
    }

    /**
     * The {@code System.Logger} convenience overload: a throwing listener is logged, not
     * propagated. We can only assert non-propagation here — capturing {@code System.Logger} output
     * is out of scope.
     */
    @Test
    void the_convenience_overload_logs_instead_of_killing() throws InterruptedException {
      CountDownLatch handled = new CountDownLatch(1);
      hub.subscribeAsync(
          Ping.class,
          p -> {
            handled.countDown();
            throw new IllegalStateException("async observer bug");
          });

      assertThatCode(() -> hub.emit(new Ping("a"))).doesNotThrowAnyException();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Nested
  class Subscriptions {

    @Test
    void closing_stops_delivery_and_is_idempotent() {
      List<Ping> pings = new ArrayList<>();
      Subscription subscription = hub.subscribe(Ping.class, pings::add);

      subscription.close();
      subscription.close();
      hub.emit(new Ping("a"));

      assertThat(pings).isEmpty();
    }

    @Test
    void
        closing_one_of_two_subscriptions_for_the_same_consumer_twice_still_delivers_via_the_other() {
      List<Ping> pings = new ArrayList<>();
      Subscription first = hub.subscribe(Ping.class, pings::add);
      Subscription second = hub.subscribe(Ping.class, pings::add);

      first.close();
      first.close();
      hub.emit(new Ping("a"));

      assertThat(pings).containsExactly(new Ping("a"));

      second.close();
      hub.emit(new Ping("b"));

      assertThat(pings).containsExactly(new Ping("a"));
    }

    @Test
    void a_subscriber_added_during_an_emit_does_not_receive_the_in_flight_event() {
      List<Ping> lateArrivals = new ArrayList<>();
      hub.subscribe(
          Ping.class,
          p -> {
            if (lateArrivals.isEmpty()) {
              hub.subscribe(Ping.class, lateArrivals::add);
            }
          });

      hub.emit(new Ping("a"));

      assertThat(lateArrivals).isEmpty();

      hub.emit(new Ping("b"));

      assertThat(lateArrivals).containsExactly(new Ping("b"));
    }

    @Test
    void a_subscription_closed_during_an_emit_still_receives_the_in_flight_event() {
      List<Ping> pings = new ArrayList<>();
      Subscription[] holder = new Subscription[1];
      holder[0] =
          hub.subscribe(
              Ping.class,
              p -> {
                pings.add(p);
                holder[0].close();
              });

      hub.emit(new Ping("a"));

      assertThat(pings).containsExactly(new Ping("a"));

      hub.emit(new Ping("b"));

      assertThat(pings).containsExactly(new Ping("a"));
    }
  }
}
