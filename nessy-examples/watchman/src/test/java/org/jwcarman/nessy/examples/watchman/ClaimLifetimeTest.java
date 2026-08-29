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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.Coalescer;
import org.jwcarman.nessy.engine.AgentActor;
import org.jwcarman.nessy.engine.AgentState;
import org.jwcarman.nessy.engine.Backlogs;
import org.jwcarman.nessy.engine.BlockingWork;
import org.jwcarman.nessy.engine.Calls;
import org.jwcarman.nessy.engine.Claims;
import org.jwcarman.nessy.engine.Memories;
import org.jwcarman.nessy.engine.MicrometerTracing;
import org.jwcarman.nessy.engine.Phase;
import org.jwcarman.nessy.engine.SubstrateBacklogs;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * What a finished turn leaves behind: nothing in {@link Claims}, not even an orphan. See {@link
 * AgentActor#startTurnIfWork} and {@link Claims#deleteTurn}.
 */
@DisplayName("What a finished turn leaves behind")
class ClaimLifetimeTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  private WatchmanActorSystem actors;
  private Claims claims;
  private String agent;

  @BeforeEach
  void start() {
    agent = "watchman-" + UUID.randomUUID();
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    Memories memories = new Memories(substrate, 8000);
    Backlogs<String> backlogs = new SubstrateBacklogs<>(substrate, Coalescer.none(), String.class);
    claims = new Claims(substrate);
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(20)),
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
  }

  @AfterEach
  void stop() {
    actors.stop();
  }

  private AgentState state() {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void a_completed_turn_leaves_no_claims() throws Exception {
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", Map.of()));

    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(state().phase()).isInstanceOf(Phase.WorkingTools.class));
    String turnId = state().turnId();
    assertThat(claims.keysOf(agent, turnId)).isNotEmpty();

    String prune = Calls.byTool(state(), "prune_images").orElseThrow().id();
    actors
        .answerApproval(agent, prune, true, "james", "go on then")
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);

    await()
        .atMost(PATIENCE)
        .untilAsserted(() -> assertThat(state().phase()).isInstanceOf(Phase.Idle.class));

    assertThat(claims.keysOf(agent, turnId)).isEmpty();
  }
}
