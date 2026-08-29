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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.BacklogItem;
import org.jwcarman.nessy.api.agent.ObservationRenderer;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.engine.AgentActor;

@DisplayName("The watchman's own observation vocabulary")
class WatchmanObservationsTest {

  @Nested
  @DisplayName("How the watchman's own observations pile up")
  class Coalescing {

    @Test
    void twenty_cron_ticks_waiting_behind_a_turn_become_one() {
      List<BacklogItem<String>> backlog = List.of();
      for (int i = 0; i < 20; i++) {
        backlog =
            WatchmanObservations.COALESCER.ingest(
                backlog,
                new BacklogItem<>("t" + i, "It is 12:0" + i + ". Do your rounds.", Instant.EPOCH));
      }

      assertThat(backlog.size()).isEqualTo(1);
      assertThat(backlog.stream().map(BacklogItem::observation))
          .containsExactly("It is 12:019. Do your rounds.");
    }
  }

  @Nested
  @DisplayName("How a drained observation becomes what the model reads")
  class Rendering {

    @Test
    void a_renderer_change_reaches_an_observation_already_sitting_in_the_backlog() {
      // Waiting under WatchmanObservations.RENDERER -- the renderer that will "change" below did
      // not exist yet when this observation arrived. The item lives in the agent's state, so this
      // is the same value the actor would hand its renderer.
      BacklogItem<String> queued = new BacklogItem<>("e1", "disk at 91%", Instant.EPOCH);

      ObservationRenderer<String> shouting =
          observation -> List.of(new TextBlock(observation.toUpperCase(java.util.Locale.ROOT)));

      // The SAME queued entry, drained through AgentActor.userMessage -- the exact call
      // AgentActor#startTurnIfWork makes -- with two different renderers. The output tracks
      // whichever renderer is supplied at drain, not anything captured at ingest.
      assertThat(AgentActor.userMessage(WatchmanObservations.RENDERER, queued).message().content())
          .containsExactly(new TextBlock("disk at 91%"));
      assertThat(AgentActor.userMessage(shouting, queued).message().content())
          .containsExactly(new TextBlock("DISK AT 91%"));
    }
  }
}
