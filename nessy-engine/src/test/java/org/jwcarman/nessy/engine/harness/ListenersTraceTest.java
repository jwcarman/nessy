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
package org.jwcarman.nessy.engine.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

/** The observation current when an event is narrated is current where the listener runs. */
@DisplayName("Listeners and the trace")
class ListenersTraceTest {

  private static final AgentType TYPE = new AgentType("chat");

  /** A registry with a handler, so it is not no-op and scopes are real. */
  private static ObservationRegistry registry() {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(context -> true);
    return registry;
  }

  @Test
  void a_sync_and_an_async_listener_both_see_the_narrating_observation() {
    ObservationRegistry registry = registry();
    List<String> seen = new CopyOnWriteArrayList<>();
    AgentEventListener sync =
        (_, _, _) -> seen.add("sync:" + name(registry.getCurrentObservation()));
    AgentEventListener async =
        ((AgentEventListener)
                (_, _, _) ->
                    seen.add(
                        "async:"
                            + name(registry.getCurrentObservation())
                            + ":"
                            + Thread.currentThread().getName()))
            .async();
    assertThat(async).isInstanceOf(AgentEventListener.Async.class);

    try (Listeners listeners = new Listeners(List.of(sync), List.of(async))) {
      Observation.createNotStarted("nessy.turn", registry)
          .observe(
              () ->
                  listeners.narrate(
                      TYPE,
                      new AgentId(UUID.randomUUID()),
                      new AgentEvent.TurnEnded(new TurnId(1))));
      await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 2);
    }

    assertThat(seen).contains("sync:nessy.turn", "async:nessy.turn:nessy-listener");
  }

  @Test
  void told_with_no_engine_an_async_listener_still_runs_on_its_own_thread() {
    List<String> seen = new CopyOnWriteArrayList<>();
    AgentEventListener async =
        ((AgentEventListener) (_, _, _) -> seen.add(Thread.currentThread().getName())).async();

    async.on(TYPE, new AgentId(UUID.randomUUID()), new AgentEvent.TurnEnded(new TurnId(1)));

    await().atMost(Duration.ofSeconds(5)).until(() -> !seen.isEmpty());
    assertThat(seen).containsExactly("nessy-listener");
    assertThat(async.async()).isSameAs(async);
  }

  private static String name(Observation current) {
    return current == null ? "none" : current.getContext().getName();
  }
}
