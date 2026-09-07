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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscription;

/**
 * Who is watching, and what they get.
 *
 * <p>A subscriber that throws is the case worth pinning. Narration is best-effort, so one broken
 * watcher must not stop a turn or silence the others -- a property that used to belong to an actor
 * watching an address, and now has to be written down.
 */
@DisplayName("Node-local narration")
class NarrationTest {

  private static final AgentId AGENT = AgentId.of("house-1");
  private static final AgentId OTHER = AgentId.of("house-2");

  private Narration narration;

  @BeforeEach
  void fresh() {
    narration = new Narration();
  }

  @Test
  @DisplayName("narrating with nobody listening is silent, not an error")
  void nobody_listening_is_fine() {
    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-1"));
  }

  @Test
  @DisplayName("a subscriber gets this agent's events and not another agent's")
  void events_reach_only_their_own_agent() {
    List<AgentEvent> seen = new ArrayList<>();
    narration.subscribe(AGENT, seen::add);

    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-1"));
    narration.narratorFor(OTHER).narrate(new AgentEvent.TurnStarted("e-2"));

    assertThat(seen).hasSize(1);
    assertThat(seen).allMatch(event -> "e-1".equals(event.id()));
  }

  @Test
  @DisplayName("a closed subscription stops receiving")
  void closing_unsubscribes() {
    List<AgentEvent> seen = new ArrayList<>();
    AgentSubscription subscription = narration.subscribe(AGENT, seen::add);

    subscription.close();
    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-1"));

    assertThat(seen).isEmpty();
  }

  @Test
  @DisplayName("a subscriber that throws is ejected and the others still hear")
  void a_broken_subscriber_does_not_silence_the_rest() {
    List<AgentEvent> healthy = new ArrayList<>();
    narration.subscribe(
        AGENT,
        event -> {
          throw new IllegalStateException("boom");
        });
    narration.subscribe(AGENT, healthy::add);

    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-1"));
    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-2"));

    assertThat(healthy).hasSize(2);
  }

  @Test
  @DisplayName("forgetting an agent drops its subscribers")
  void forget_clears_the_registry() {
    List<AgentEvent> seen = new ArrayList<>();
    narration.subscribe(AGENT, seen::add);

    narration.forget(AGENT);
    narration.narratorFor(AGENT).narrate(new AgentEvent.TurnStarted("e-1"));

    assertThat(seen).isEmpty();
  }
}
