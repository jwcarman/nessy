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
import org.jwcarman.nessy.api.AgentEvent;

class RecordingSubscriberTest {

  private static final AgentEvent STARTED = new AgentEvent.TurnStarted("t1");
  private static final AgentEvent SAID = new AgentEvent.TextDelta("t1", "hello");
  private static final AgentEvent MORE = new AgentEvent.TextDelta("t1", " there");

  @Test
  void records_every_event_handed_to_it_in_order() {
    RecordingSubscriber subscriber = new RecordingSubscriber();

    subscriber.on(STARTED);
    subscriber.on(SAID);
    subscriber.on(MORE);

    assertThat(subscriber.all()).containsExactly(STARTED, SAID, MORE);
  }

  @Test
  void all_is_unmodifiable() {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.on(STARTED);
    List<AgentEvent> all = subscriber.all();

    assertThatThrownBy(() -> all.add(SAID)).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void all_is_a_snapshot_rather_than_a_live_view() {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.on(STARTED);
    List<AgentEvent> snapshot = subscriber.all();

    subscriber.on(SAID);

    assertThat(snapshot).containsExactly(STARTED);
  }

  @Test
  void of_type_filters_and_casts_to_the_requested_variant() {
    RecordingSubscriber subscriber = new RecordingSubscriber();

    subscriber.on(STARTED);
    subscriber.on(SAID);
    subscriber.on(MORE);

    assertThat(subscriber.ofType(AgentEvent.TextDelta.class))
        .extracting(AgentEvent.TextDelta::text)
        .containsExactly("hello", " there");
  }

  @Test
  void of_type_returns_an_empty_list_when_nothing_matches() {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.on(STARTED);

    assertThat(subscriber.all()).isNotEmpty();
    assertThat(subscriber.ofType(AgentEvent.ToolCallCompleted.class)).isEmpty();
  }
}
