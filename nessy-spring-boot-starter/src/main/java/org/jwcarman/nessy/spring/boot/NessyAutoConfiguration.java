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
package org.jwcarman.nessy.spring.boot;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.ObservationRenderer;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.Replies;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires a Nessy harness from {@code application.yaml}, and steps aside for anything the application
 * declares itself.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}: the starter is a convenience over the
 * public API, never a different way of reaching it. An application that wants a different
 * substrate, actor system, or harness declares one and this backs off entirely.
 *
 * <p><b>The application supplies the {@link ModelProvider}.</b> The starter used to discover one
 * through {@code ModelDiscovery}, and that seam has no counterpart yet — so rather than guess, this
 * requires a provider bean and fails at startup if none exists, which is a better failure than a
 * mystery at the first turn. When a discovery seam returns, a {@code @ConditionalOnMissingBean}
 * provider bean goes back here and nothing else changes.
 */
// AFTER Boot's own JDBC auto-configuration. Ordering is not cosmetic here: the approvals
// projection is @ConditionalOnBean(JdbcTemplate), and a condition evaluated before Boot has
// registered that bean quietly decides there is no database — an application with Postgres right
// there then fails because its own controller cannot find the repository.
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  /**
   * A substrate over the application's {@link DataSource} when it has one, and an in-memory one
   * when it does not.
   *
   * <p>The fallback is LOUD about what it costs: an in-memory substrate loses every transcript,
   * every backlog and every parked call when the process stops. It exists so a test or a spike
   * starts without a database, never as a production default that nobody chose.
   */
  @Bean
  @ConditionalOnMissingBean
  public Substrate nessySubstrate(ObjectProvider<DataSource> dataSources, Clock clock) {
    DataSource dataSource = dataSources.getIfAvailable();
    if (dataSource != null) {
      return new JdbcSubstrate(dataSource);
    }
    org.slf4j.LoggerFactory.getLogger(NessyAutoConfiguration.class)
        .warn(
            "NESSY IS RUNNING IN MEMORY: no DataSource bean was found, so transcripts, backlogs and"
                + " parked approvals will not survive a restart. Add a DataSource, or declare a"
                + " Substrate bean, for anything that is not a test.");
    return new InMemorySubstrate(clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public Clock nessyClock() {
    return Clock.systemUTC();
  }

  /**
   * The keys reply tokens are sealed with, newest first.
   *
   * <p>An absent {@code nessy.reply-keys} means an EPHEMERAL key, and that is said out loud for the
   * same reason the in-memory substrate is: a token minted before a restart cannot be read after
   * one, so every call parked on a human silently becomes unanswerable.
   */
  @Bean
  @ConditionalOnMissingBean
  public ReplyTokens nessyReplyTokens(NessyProperties properties) {
    List<String> keys = properties.replyKeys();
    if (keys.isEmpty()) {
      org.slf4j.LoggerFactory.getLogger(NessyAutoConfiguration.class)
          .warn(
              "NESSY REPLY TOKENS ARE EPHEMERAL: no nessy.reply-keys configured, so any approval"
                  + " parked on a person becomes unanswerable after a restart. Configure a"
                  + " base64 32-byte key for anything that is not a test.");
      return ReplyTokens.ephemeral();
    }
    return ReplyTokens.withKeys(
        keys.stream().map(key -> Base64.getDecoder().decode(key)).toArray(byte[][]::new));
  }

  /**
   * One actor system for the whole application, shut down with the context.
   *
   * <p><b>It joins itself, and waits.</b> The engine always shards, and {@code ClusterSharding} on
   * a node that has not joined leaves entities unreachable — a failure that looks like messages
   * quietly going nowhere rather than like an error. So a single-node deployment forms its cluster
   * here, before any harness is built, and blocks until the node is {@code Up}.
   *
   * <p>An application that configures {@code pekko.cluster.seed-nodes} is running a real cluster
   * and joins through those instead; this steps aside rather than joining a second time.
   */
  @Bean(destroyMethod = "terminate")
  @ConditionalOnMissingBean
  public ActorSystem<Void> nessyActorSystem(ObjectProvider<Config> configs) {
    // An application contributes Pekko config as a bean — credentials for a persistence plugin,
    // say — so it can build them from the same properties Spring already read rather than
    // repeating them in a second file. What it supplies wins; reference.conf is the fallback.
    Config config =
        configs.stream()
            .reduce(Config::withFallback)
            .orElseGet(ConfigFactory::empty)
            .withFallback(ConfigFactory.load());
    ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "nessy", config);
    if (system.settings().config().getStringList("pekko.cluster.seed-nodes").isEmpty()) {
      Cluster cluster = Cluster.get(system);
      cluster.manager().tell(Join.create(cluster.selfMember().address()));
      awaitUp(cluster);
    }
    return system;
  }

  private static void awaitUp(Cluster cluster) {
    Instant deadline = Instant.now().plusSeconds(30);
    while (!cluster.selfMember().status().equals(MemberStatus.up())) {
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException(
            "this node never reached Up, so sharding would silently drop every message");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while forming the cluster", e);
      }
    }
  }

  /**
   * Where blocking tool work runs. Virtual threads, because a tool that shells out or calls a slow
   * HTTP service should not consume a platform thread while it waits.
   *
   * <p><b>Context-propagating, and that is what makes a trace a tree.</b> Every tool call and every
   * model call crosses this executor, and a thread-local scope does not follow {@code
   * executor.execute} — so without this wrapper each of them opens a span with no parent, and one
   * round arrives in Tempo as five unrelated traces instead of one. The wrapper captures whatever
   * context the submitting actor holds and restores it on the worker thread, so the spans nest.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean(name = "nessyBlockingExecutor")
  public java.util.concurrent.ExecutorService nessyBlockingExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        ContextSnapshotFactory.builder().build()::captureAll);
  }

  @Bean
  @ConditionalOnMissingBean
  public PekkoHarnessFactory nessyHarnessFactory(
      ActorSystem<Void> system,
      Substrate substrate,
      ModelProvider models,
      ObjectProvider<ObservationRegistry> registries,
      ObjectProvider<MeterRegistry> meterRegistries,
      NessyProperties properties,
      @Qualifier("nessyBlockingExecutor") Executor blocking,
      Clock clock,
      ReplyTokens tokens) {
    // Every model this factory resolves is observed, so a chat span lasts exactly as long as the
    // provider did and carries the tokens THAT call reported. An application with no registry gets
    // its own provider back untouched.
    ObservationRegistry registry = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    MeterRegistry meters = meterRegistries.getIfAvailable();
    boolean observing = !ObservationRegistry.NOOP.equals(registry) && meters != null;
    return new PekkoHarnessFactory(
        system,
        substrate,
        observing ? Observed.models(models, properties.provider(), registry, meters) : models,
        properties.maxTokens(),
        properties.capabilities(),
        blocking,
        clock,
        tokens);
  }

  /** The door an application answers a parked call through. */
  @Bean
  @ConditionalOnMissingBean
  public Replies nessyReplies(PekkoHarnessFactory factory) {
    return factory.replies();
  }

  /**
   * The harness, configured from properties and given every {@link Tool} bean the application
   * declared.
   *
   * <p>Tools arrive ungated. A tool that needs a human needs an approver, and an approver is a
   * decision about THIS application's policy — so an application that gates anything declares its
   * own harness rather than teaching this method a rule it cannot know.
   */
  @Bean
  @ConditionalOnMissingBean
  public Harness<String> nessyHarness(
      PekkoHarnessFactory factory,
      NessyProperties properties,
      ObjectProvider<Tool<?>> tools,
      ObjectProvider<ObservationRenderer<String>> renderers,
      ObjectProvider<ObservationRegistry> registries) {
    ObservationRegistry registry = registries.getIfAvailable(() -> ObservationRegistry.NOOP);
    List<Tool<?>> declared = tools.orderedStream().toList();
    String systemPrompt = properties.resolveSystemPrompt();
    ObservationRenderer<String> renderer = renderers.getIfAvailable(() -> UserMessage::of);
    return factory.createHarness(
        String.class,
        config -> {
          config
              .type(AgentType.of(properties.type()))
              .systemPrompt(systemPrompt)
              .renderer(renderer);
          config.model(ModelId.of(requireModel(properties)));
          declared.forEach(tool -> grant(config, tool, registry));
        });
  }

  /**
   * The configured model id, or a failure that names the missing property.
   *
   * <p>Required rather than defaulted: a harness must say which model it talks to, and guessing one
   * would produce an application that starts cleanly and fails at its first turn against a model
   * nobody chose.
   */
  private static String requireModel(NessyProperties properties) {
    String model = properties.model();
    if (model == null || model.isBlank()) {
      throw new IllegalStateException(
          "nessy.model must name the model these agents talk to; it is resolved against your"
              + " ModelProvider bean");
    }
    return model;
  }

  /**
   * Grants one tool.
   *
   * <p>A method of its own purely so that {@code I} names the wildcard a {@code Tool<?>} out of the
   * bean factory carries. Java's capture conversion binds it at the call, so {@code
   * HarnessConfig#tool} gets the concrete input type it needs to tie a tool to its describer — and
   * no cast is needed to get it.
   */
  private static <I> void grant(
      HarnessConfig<String> config, Tool<I> tool, ObservationRegistry registry) {
    config.tool(ObservationRegistry.NOOP.equals(registry) ? tool : Observed.tool(tool, registry));
  }

  /**
   * The approvals projection, when there is a database to keep it in.
   *
   * <p>Conditional rather than fallback-to-memory on purpose: an approval waiting on a person is
   * the single thing most likely to outlive the process that asked, so a projection that quietly
   * lost them on restart would be worse than not having one. An application with no {@code
   * JdbcTemplate} gets no repository, and can still hear approvals through its own subscriber.
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(JdbcTemplate.class)
  public PendingApprovalsRepository nessyPendingApprovals(JdbcTemplate jdbc) {
    return new PendingApprovalsRepository(jdbc);
  }
}
