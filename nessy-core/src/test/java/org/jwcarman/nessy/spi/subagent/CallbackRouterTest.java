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
package org.jwcarman.nessy.spi.subagent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

class CallbackRouterTest {

  /**
   * A model that replays one scripted text turn per call — enough to build a real {@link Agent}.
   */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk(replies.removeFirst()),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  private Agent<String> agentNamed(String name) {
    return Nessy.harness(new FakeProvider("hi")).build().agent().name(name).model("m").build();
  }

  private final CallbackRouter router = new CallbackRouter();

  @Nested
  class Registering {

    @Test
    void registering_a_second_agent_under_a_name_already_taken_throws() {
      Agent<String> first = agentNamed("keeper");
      Agent<String> second = agentNamed("keeper");
      router.register(first);

      assertThatThrownBy(() -> router.register(second))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keeper");
    }
  }

  @Nested
  class Resuming {

    @Test
    void resuming_a_registered_agent_delegates_to_its_own_resume_door() {
      Agent<String> agent = agentNamed("keeper");
      router.register(agent);
      ParkToken unknownToken = ParkToken.generate();

      assertThatThrownBy(
              () ->
                  router.resume(
                      "keeper", unknownToken, new ToolResolution.Decided(Decision.allow())))
          .isInstanceOf(UnknownParkTokenException.class);
    }

    @Test
    void resuming_a_name_no_agent_was_ever_registered_under_throws_naming_it() {
      ParkToken token = ParkToken.generate();

      assertThatThrownBy(
              () -> router.resume("nobody", token, new ToolResolution.Decided(Decision.allow())))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("nobody");
    }
  }
}
