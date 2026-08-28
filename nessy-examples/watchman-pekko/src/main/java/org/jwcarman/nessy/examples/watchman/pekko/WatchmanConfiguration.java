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

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
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
   * Falls back to no-ops when nothing has configured tracing, so the tests do not need a collector
   * and the application still starts on a box with no observability stack.
   *
   * <p>Both beans come from Boot's tracing autoconfiguration when {@code micrometer-tracing} and a
   * bridge are present. Depending on {@link Tracer} and {@link Propagator} rather than on {@code
   * OpenTelemetry} directly is the point: OTel is the implementation underneath Micrometer, not the
   * API this port codes against.
   */
  @Bean
  public Traces traces(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    Tracer resolved = tracer.getIfAvailable(() -> Tracer.NOOP);
    Propagator resolvedPropagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    // SAY SO. A silent fall back to NOOP is how tracing died once already: opentelemetry-api left
    // the runtime classpath, Boot's @ConditionalOnClass tracing autoconfiguration never ran, no
    // Tracer bean existed, and every span in the application became a no-op while the app kept
    // running and the tests kept passing. A fallback that hides a misconfiguration is worse than
    // no fallback; this one still starts the app on a box with no observability stack, but it is
    // no longer quiet about what that costs.
    if (resolved == Tracer.NOOP || resolvedPropagator == Propagator.NOOP) {
      LoggerFactory.getLogger(WatchmanConfiguration.class)
          .warn(
              "TRACING IS DISABLED: tracer={}, propagator={}. No spans will be recorded. This is"
                  + " expected only when running without an observability stack; if you expected"
                  + " traces, check that opentelemetry-api is on the RUNTIME classpath.",
              resolved == Tracer.NOOP ? "NOOP" : resolved.getClass().getName(),
              resolvedPropagator == Propagator.NOOP
                  ? "NOOP"
                  : resolvedPropagator.getClass().getName());
    }
    return new Traces(resolved, resolvedPropagator);
  }

  @Bean
  public WatchmanModel watchmanModel(
      WatchmanProperties properties,
      org.jwcarman.nessy.spi.model.ModelProvider provider,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return properties.isScripted()
        ? new ScriptedWatchmanModel(java.time.Duration.ofMillis(50))
        : new ProviderWatchmanModel(
            provider.model(properties.getModelId()), meters, properties.getMaxTokens());
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

  /**
   * Memory, per agent, with the token budget applied on the way out. See {@link Memories} for why
   * the budget is a decorator here rather than something Nessy applies for us.
   */
  @Bean
  public Memories memories(
      org.jwcarman.nessy.spi.substrate.Substrate substrate, WatchmanProperties properties) {
    return new Memories(substrate, properties.getContextBudgetTokens());
  }

  /**
   * The durable backlog, on the same substrate as everything else, coalescing per {@link
   * WatchmanObservations#COALESCER}: twenty queued cron ticks become one.
   */
  @Bean
  public Backlogs<String> backlogs(org.jwcarman.nessy.spi.substrate.Substrate substrate) {
    return new SubstrateBacklogs<>(substrate, WatchmanObservations.COALESCER, String.class);
  }

  /** How a drained observation becomes what the model reads. See {@link ObservationRenderer}. */
  @Bean
  public ObservationRenderer<String> observationRenderer() {
    return WatchmanObservations.RENDERER;
  }

  /**
   * Tool arguments, on the same substrate as everything else — see {@link Claims} for why they live
   * here rather than in the agent's own persisted document.
   */
  @Bean
  public Claims claims(org.jwcarman.nessy.spi.substrate.Substrate substrate) {
    return new Claims(substrate);
  }

  /**
   * The model provider, pointed at LM Studio. Base URL and key are the entire difference between
   * talking to OpenAI and talking to a local endpoint.
   *
   * <p><b>One thing an application outside the module cannot do</b>, and it is worth reporting
   * rather than working around silently: {@code OpenAiProviderConfig.provider(String)} is
   * package-private. {@code XaiModelProviderBootstrap} can label its traffic {@code x_ai} only
   * because it lives inside {@code org.jwcarman.nessy.model.openai}. From out here the provider
   * name is stuck at {@code openai}, so every {@code gen_ai.provider.name} on this soak says
   * "openai" when it means LM Studio. {@code apiKey} and {@code baseUrl} are public; this one is
   * not, and there is no reason for the asymmetry.
   */
  @Bean(destroyMethod = "close")
  public org.jwcarman.nessy.spi.model.ModelProvider modelProvider(WatchmanProperties properties) {
    return org.jwcarman.nessy.model.openai.OpenAiModelProvider.create(
        c -> c.apiKey(properties.getModelApiKey()).baseUrl(properties.getModelUrl()));
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
      Memories memories,
      Backlogs<String> backlogs,
      ObservationRenderer<String> renderer,
      Traces traces,
      Clock clock,
      BlockingWork blocking,
      WatchmanProperties properties,
      Claims claims,
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password}") String password) {
    return new WatchmanActorSystem(
        PekkoConfigBridge.build("watchman-pekko", url, user, password),
        model,
        runner,
        memories,
        backlogs,
        renderer,
        traces,
        clock,
        blocking,
        properties.getApprovalTerm(),
        properties.getAskTimeout(),
        claims);
  }
}
