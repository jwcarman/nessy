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

import java.util.ArrayList;
import java.util.List;
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

    @Test
    void survives_a_throwing_subscriber() {
      List<Ping> pings = new ArrayList<>();
      hub.subscribe(
          Ping.class,
          p -> {
            throw new IllegalStateException("observer bug");
          });
      hub.subscribe(Ping.class, pings::add);

      hub.emit(new Ping("a"));

      assertThat(pings).containsExactly(new Ping("a"));
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
