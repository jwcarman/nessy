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
package org.jwcarman.nessy.api.backlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.TurnId;

/**
 * {@code BacklogItem} exists so a {@link BacklogCoalescer} can talk about one waiting observation
 * without relying on the observation's own equality — every field it carries is therefore load-
 * bearing enough to refuse a null.
 */
@DisplayName("One observation waiting to become a turn")
class BacklogItemTest {

  @Test
  @DisplayName("carries its id, observation, and arrival time")
  void carries_its_fields() {
    TurnId id = TurnId.of("turn-1");
    Instant receivedAt = Instant.parse("2026-08-29T00:00:00Z");

    BacklogItem<String> item = new BacklogItem<>(id, "hello", receivedAt);

    assertThat(item.id()).isEqualTo(id);
    assertThat(item.observation()).isEqualTo("hello");
    assertThat(item.receivedAt()).isEqualTo(receivedAt);
  }

  @Test
  @DisplayName("refuses a null id")
  void refuses_a_null_id() {
    Instant receivedAt = Instant.now();

    assertThatThrownBy(() -> new BacklogItem<>(null, "hello", receivedAt))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id must not be null");
  }

  @Test
  @DisplayName("refuses a null observation")
  void refuses_a_null_observation() {
    TurnId id = TurnId.of("turn-1");
    Instant receivedAt = Instant.now();

    assertThatThrownBy(() -> new BacklogItem<>(id, null, receivedAt))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("observation must not be null");
  }

  @Test
  @DisplayName("refuses a null arrival time")
  void refuses_a_null_received_at() {
    TurnId id = TurnId.of("turn-1");

    assertThatThrownBy(() -> new BacklogItem<>(id, "hello", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("receivedAt must not be null");
  }
}
