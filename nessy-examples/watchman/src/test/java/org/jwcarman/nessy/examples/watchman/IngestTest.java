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
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.Coalescer;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

@DisplayName("An observation that arrives while a turn is running")
class IngestTest {

  private WatchmanActorSystem actors;

  @AfterEach
  void stop() {
    if (actors != null) {
      actors.stop();
    }
  }

  private AgentState state(String agent) {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void is_kept_rather_than_refused() {
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    // Scripted slow enough that "second" is guaranteed to land while "first" is still in flight.
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofSeconds(2)),
            new FakeRunner(),
            substrate,
            Coalescer.none(),
            8000,
            MicrometerTracing.noop(),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();
    String agent = "ingest-" + UUID.randomUUID();

    actors.tell(agent, new AgentActor.Observe("first", Map.of()));

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(state(agent).phase()).isNotInstanceOf(Phase.Idle.class));

    // Arrives mid-round. The old behaviour dropped this on the floor and logged a refusal.
    actors.tell(agent, new AgentActor.Observe("second", Map.of()));

    // Assert: it is in the durable backlog, not on the floor.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(backlogs.next(agent))
                    .hasValueSatisfying(
                        taken -> assertThat(taken.observation()).isEqualTo("second")));
  }
}
