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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * SHUTDOWN ORDERING — Ctrl-C on a box that is mid-round.
 *
 * <p>This is the integration question that actually bites, and it bites in two directions. Shut the
 * ActorSystem down too LATE (in a bean destroy method, after the DataSource has gone) and a turn
 * mid-persist fails on a closed pool. Shut it down with an unbounded wait and a single wedged actor
 * hangs the JVM forever on a box whose normal end is somebody pressing Ctrl-C. Our answer is a
 * {@link org.springframework.context.SmartLifecycle} at a phase below the web server's, with a
 * bounded {@code stop(Runnable)} — see {@link WatchmanActorSystem}.
 *
 * <p>What this test proves: a context closed while a round is genuinely in flight closes PROMPTLY,
 * and the round is not lost — it is exactly where it was, and a fresh process picks it up.
 */
@Tag("container")
@DisplayName("Shutting down mid-round")
class SpringShutdownTest {

  /** A model slow enough that the context is certainly closed while a call is in flight. */
  @Configuration
  static class SlowModel {

    @Bean
    @Primary
    WatchmanModel slowModel() {
      return new ScriptedModel(Duration.ofSeconds(120));
    }

    @Bean
    @Primary
    CommandRunner fakeRunner() {
      return new FakeRunner();
    }
  }

  private ConfigurableApplicationContext boot() {
    SpringApplication application =
        new SpringApplication(WatchmanApplication.class, SlowModel.class);
    // A REAL web server, because "actors stop after HTTP drains" is the thing under test.
    application.setWebApplicationType(WebApplicationType.SERVLET);
    return application.run(
        "--spring.datasource.url=" + WatchmanPostgres.URL,
        "--spring.datasource.username=" + WatchmanPostgres.USER,
        "--spring.datasource.password=" + WatchmanPostgres.PASSWORD,
        "--watchman.scripted=true",
        // No cron: this test drives the round itself so the timing is deterministic.
        "--watchman.cron=0 0 5 31 2 ?",
        "--spring.lifecycle.timeout-per-shutdown-phase=30s",
        "--server.port=0");
  }

  @Test
  void a_context_closed_mid_round_shuts_down_promptly_and_loses_nothing() throws Exception {
    String agent = "shutdown-" + UUID.randomUUID();

    ConfigurableApplicationContext context = boot();
    WatchmanActorSystem actors = context.getBean(WatchmanActorSystem.class);
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", java.util.Map.of()));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () ->
                assertThat(actors.inspect(agent).toCompletableFuture().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(TurnState.CallingModel.class));

    // Ctrl-C, with a 120-second model call in flight.
    long began = System.nanoTime();
    context.close();
    Duration took = Duration.ofNanos(System.nanoTime() - began);

    assertThat(context.isActive()).isFalse();
    assertThat(actors.isRunning()).isFalse();
    assertThat(actors.raw().getWhenTerminated().toCompletableFuture().isDone())
        .as("the actor system terminated, not merely stopped being referenced")
        .isTrue();
    assertThat(took)
        .as("shutdown must not wait for the in-flight model call")
        .isLessThan(Duration.ofSeconds(30));

    // Nothing was lost: the round is exactly where it was, and a fresh process resumes it.
    assertThat(new StartupSweep(WatchmanPostgres.dataSource()).unfinishedAgents()).contains(agent);

    WatchmanActorSystem next = WatchmanPostgres.start(new ScriptedModel(Duration.ofMillis(20)));
    try {
      next.tell(agent, new AgentActor.Wake());
      await()
          .atMost(Duration.ofSeconds(45))
          .untilAsserted(
              () ->
                  assertThat(next.inspect(agent).toCompletableFuture().get(10, TimeUnit.SECONDS))
                      .isInstanceOf(TurnState.WorkingTools.class));
    } finally {
      next.stop();
    }
  }

  @Test
  void the_actor_system_stops_after_the_web_server_because_its_phase_is_lower() {
    // Spring stops SmartLifecycle beans in DESCENDING phase order, so a lower phase stops later.
    // Stating it as an assertion keeps the ordering from being silently changed.
    assertThat(WatchmanActorSystem.PHASE)
        .as("actors must stop after the web server has drained")
        .isLessThan(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 1024);
  }
}
