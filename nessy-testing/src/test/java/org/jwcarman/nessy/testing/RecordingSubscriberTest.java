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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingSubscriberTest {

  @Test
  void records_every_event_handed_to_it_in_order() {
    RecordingSubscriber subscriber = new RecordingSubscriber();

    subscriber.accept("first");
    subscriber.accept(42);
    subscriber.accept("second");

    assertThat(subscriber.all()).containsExactly("first", 42, "second");
  }

  @Test
  void all_is_unmodifiable() {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.accept("event");

    List<Object> all = subscriber.all();

    assertThatThrownBy(() -> all.add("intruder")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void of_type_filters_and_casts_to_the_requested_type() {
    RecordingSubscriber subscriber = new RecordingSubscriber();

    subscriber.accept("a string event");
    subscriber.accept(7);
    subscriber.accept("another string event");
    subscriber.accept(3.14);

    assertThat(subscriber.ofType(String.class))
        .containsExactly("a string event", "another string event");
    assertThat(subscriber.ofType(Integer.class)).containsExactly(7);
  }

  @Test
  void of_type_returns_empty_list_when_nothing_matches() {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.accept("only a string");

    assertThat(subscriber.ofType(Integer.class)).isEmpty();
  }
}
