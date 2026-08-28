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

import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where Spring meets Pekko. Note how little there is: the ActorSystem is one bean, and the actors'
 * dependencies are ordinary beans passed into a {@link org.apache.pekko.actor.typed.Behavior}
 * factory.
 *
 * <p><b>Dependency injection into actors was a non-problem</b>, and it is worth saying why rather
 * than leaving it looking easy by luck. In Pekko Classic an actor was instantiated reflectively
 * from a {@code Props} class, so Spring could not inject into it and the whole {@code
 * SpringExtension} / {@code IndirectActorProducer} genre existed to work around that. In Typed the
 * Behavior factory IS the injection point: Spring builds {@link CommandRunner}, {@link
 * WatchmanModel} and the rest, and {@link WatchmanGuardian#create} takes them as parameters. No
 * extension, no producer, no ApplicationContext lookup, no bean-name strings.
 */
@Configuration
@EnableConfigurationProperties(WatchmanProperties.class)
public class WatchmanConfiguration {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public CommandRunner commandRunner() {
    return new ProcessRunner();
  }

  @Bean
  public BlockingWork blockingWork() {
    return new BlockingWork();
  }

  /**
   * Falls back to a no-op when nothing has configured OpenTelemetry, so the tests do not need a
   * collector and the application still starts on a box with no observability stack.
   */
  @Bean
  public Traces traces(ObjectProvider<OpenTelemetry> openTelemetry) {
    return new Traces(openTelemetry.getIfAvailable(OpenTelemetry::noop));
  }

  @Bean
  public WatchmanModel watchmanModel(WatchmanProperties properties) {
    return properties.isScripted()
        ? new ScriptedModel(java.time.Duration.ofMillis(50))
        : new LmStudioModel(
            properties.getModelUrl(), properties.getModelId(), properties.getModelApiKey());
  }

  /**
   * Nessy's substrate, on the DataSource Spring already owns — one pool, not a second one. This is
   * the piece the port reuses rather than reinvents: {@code JournalStore} gives append-at-expected-
   * seq and resumable reads, which is exactly a transcript.
   */
  @Bean
  public org.jwcarman.nessy.spi.substrate.Substrate substrate(DataSource dataSource, Clock clock) {
    return new org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate(dataSource, clock);
  }

  @Bean
  public Transcript transcript(org.jwcarman.nessy.spi.substrate.Substrate substrate) {
    return new Transcript(substrate);
  }

  @Bean
  public StartupSweep startupSweep(DataSource dataSource) {
    return new StartupSweep(dataSource);
  }

  @Bean
  public PendingApprovals pendingApprovals(DataSource dataSource, Clock clock) {
    return new PendingApprovals(dataSource, clock);
  }

  /**
   * The ActorSystem. A {@link org.springframework.context.SmartLifecycle}, not a bean with a
   * destroy method — see {@link WatchmanActorSystem} for the shutdown-ordering argument, which is
   * the part of this integration that actually bites.
   */
  @Bean
  public WatchmanActorSystem watchmanActorSystem(
      WatchmanModel model,
      CommandRunner runner,
      Transcript transcript,
      Traces traces,
      Clock clock,
      BlockingWork blocking,
      WatchmanProperties properties,
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    return new WatchmanActorSystem(
        PekkoConfigBridge.build("watchman-pekko", url, user, password),
        model,
        runner,
        transcript,
        traces,
        clock,
        blocking,
        properties.getApprovalTerm(),
        properties.getAskTimeout());
  }
}
