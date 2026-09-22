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
package org.jwcarman.nessy.engine.backlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.BacklogItem;
import tools.jackson.databind.json.JsonMapper;

/**
 * A backlog is stored as one blob beside the agent's state, so it has to survive a round trip whole
 * -- including which arm of the state machine it was in, and the application's own observation type
 * inside it.
 */
class BacklogCodecTest {

  private record UserSaid(String text) {}

  private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

  private final Codec<Backlog<UserSaid>> codec =
      new JacksonCodecFactory(JsonMapper.builder().build())
          .create(new TypeRef<Backlog<UserSaid>>() {});

  private static BacklogItem<UserSaid> item(String text) {
    return new BacklogItem<>(new UserSaid(text), T0);
  }

  private Backlog<UserSaid> roundTrip(Backlog<UserSaid> backlog) {
    return codec.decode(codec.encode(backlog));
  }

  private String json(Backlog<UserSaid> backlog) {
    return new String(codec.encode(backlog), StandardCharsets.UTF_8);
  }

  @Test
  void anOpenBacklogSurvivesWithItsObservationsAndTheirArrivalTimes() {
    Backlog<UserSaid> backlog = new Backlog.Open<>(List.of(item("first"), item("second")));

    assertThat(roundTrip(backlog)).isEqualTo(backlog);
  }

  @Test
  void anEmptyOpenBacklogSurvivesAsOpenRatherThanSealed() {
    assertThat(roundTrip(Backlog.empty()))
        .as("losing the arm would silently resurrect a terminated agent")
        .isInstanceOf(Backlog.Open.class);
  }

  @Test
  void aSealedBacklogSurvivesAsSealed() {
    Backlog<UserSaid> sealed = Backlog.<UserSaid>empty().seal();

    assertThat(roundTrip(sealed)).isInstanceOf(Backlog.Sealed.class);
    assertThat(roundTrip(sealed).next()).isInstanceOf(Pull.Pill.class);
  }

  /**
   * These strings are in every stored backlog. Pinning them means renaming a record cannot quietly
   * orphan agents that had work queued.
   */
  @Test
  void theStoredDiscriminatorsAreTheShortNames() {
    assertThat(json(Backlog.empty())).contains("\"type\":\"open\"");
    assertThat(json(Backlog.<UserSaid>empty().seal())).contains("\"type\":\"sealed\"");
  }
}
