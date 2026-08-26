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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.continuum.Continuum;
import org.jwcarman.continuum.DefaultContinuum;
import org.jwcarman.continuum.jdbc.JdbcContinuumRepository;
import org.jwcarman.continuum.memory.InMemoryContinuumRepository;
import org.jwcarman.nessy.agent.ApprovalDesk;
import org.jwcarman.nessy.agent.CompletionDesk;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.agent.host.Nessy;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Nessy harness, assembled from Spring beans (watchman spec §1).
 *
 * <p>It COMPOSES; it invents nothing. Every bean here is one the application could have written by
 * hand from the same public API, and every one steps aside for an application's own via {@link
 * ConditionalOnMissingBean}. What the starter contributes is the wiring nobody should have to write
 * twice: a {@code DataSource} becoming the durable store pair, {@code Tool} beans becoming grants,
 * Boot's {@code ObservationRegistry} reaching the harness's observability seam, {@code
 * harness.shutdown()} on context close.
 *
 * <p>What it deliberately does NOT do (spec §1.2): no web layer, no scheduling, no security, no
 * approvers. Those belong to the application — the starter wires a harness; the app decides what it
 * is for.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
    })
@EnableConfigurationProperties(NessyProperties.class)
public class NessyAutoConfiguration {

  /**
   * The two store beans' names — the only handle {@link #requireMatchedDurability} has on whether
   * the starter or the application supplied each one. Both {@code DurableStores} and {@code
   * VolatileStores} use these names; only one of the two configurations is ever active.
   */
  static final String NESSY_SUBSTRATE = "nessySubstrate";

  static final String NESSY_CONTINUUM = "nessyContinuum";

  /**
   * The gateway discovery builds, as a bean with a destroy method — because a {@link ModelProvider}
   * owns an SDK client, its connection pool and its threads, and a container that builds one and
   * never closes it leaks all three for the life of the process (ruled 2026-08-26). {@code
   * destroyMethod = "close"} is what a starter is for: {@link ModelDiscovery.Selection} is {@code
   * AutoCloseable} and delegates to the gateway, so Spring closes it on context close the same way
   * it already calls {@code harness.shutdown()}.
   *
   * <p>Conditional on a missing {@link Model} rather than a missing {@code Selection}: an
   * application declaring its own {@code Model} bean owns whatever built it, and discovery must not
   * run at all — running it would reach for credentials the application deliberately did not
   * supply.
   *
   * <p><b>Declared BEFORE {@link #nessyModel}, and that ordering is load-bearing</b> (fix round,
   * 2026-08-26 — it was the other way round for one commit and broke the default path outright).
   * {@code @ConditionalOnMissingBean} is evaluated against the bean definitions registered SO FAR,
   * in declaration order within a configuration class. With {@code nessyModel} declared first, its
   * own definition is already registered by the time this condition runs, so
   * {@code @ConditionalOnMissingBean(Model.class)} matched it, backed off, and left {@code
   * nessyModel} asking for a {@code Selection} bean that no longer existed — a context that failed
   * with "No qualifying bean of type ModelDiscovery$Selection" for every application that did NOT
   * supply its own model, which is the default.
   *
   * <p>Declaring this one first fixes it without weakening the back-off, because an application's
   * own {@code Model} bean comes from USER configuration, which Spring registers ahead of every
   * auto-configuration class: by the time this condition is evaluated, a user's model is already
   * there to be found, and discovery still stands down.
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(Model.class)
  public ModelDiscovery.Selection nessyModelSelection() {
    return ModelDiscovery.select();
  }

  /**
   * The harness's one required dependency, from the process environment (spec §1.1). {@code
   * ModelDiscovery} picks the provider from whichever credentials are present and honours {@code
   * NESSY_MODEL} for the model id and {@code NESSY_PROVIDER} for the tie-break.
   *
   * <p>There is no {@code nessy.model.id} property. Discovery reads the environment and only the
   * environment, and an application that wants the id to come from anywhere else — a property, a
   * database, a feature flag — declares its own {@link Model} bean, which wins outright. That is
   * one less way to say the same thing, not a missing feature.
   *
   * <p>Takes the {@link ModelDiscovery.Selection} declared above rather than calling discovery
   * itself, so that the gateway behind this model has an owner the container can close.
   */
  @Bean
  @ConditionalOnMissingBean
  public Model nessyModel(ModelDiscovery.Selection selection) {
    return selection.model();
  }

  /**
   * The harness itself, and the only bean here that is more than a one-liner.
   *
   * <p>{@code destroyMethod = "shutdown"} is the spec's shutdown row: Spring calls it when the
   * context closes, which is the container-destroy callback {@code Harness#shutdown()} says it
   * exists for. The harness is deliberately not {@code AutoCloseable}, so this is named explicitly
   * rather than inferred.
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean
  public Harness<String> nessyHarness(
      Model model,
      NessyProperties properties,
      ConfigurableListableBeanFactory beanFactory,
      ObjectProvider<DataSource> dataSources,
      ObjectProvider<Substrate> substrates,
      ObjectProvider<Continuum> continuums,
      ObjectProvider<Tool<?>> tools,
      ObjectProvider<ToolGrant> declaredGrants,
      ObjectProvider<HarnessObserver> observers,
      ObjectProvider<TurnObserver> turnObservers,
      ObjectProvider<ObservationRegistry> observationRegistries,
      ObjectProvider<ObjectMapper> objectMappers) {
    requireMatchedDurability(
        dataSources.getIfAvailable() != null,
        beanFactory.containsBeanDefinition(NESSY_SUBSTRATE),
        beanFactory.containsBeanDefinition(NESSY_CONTINUUM));
    String systemPrompt = properties.resolveSystemPrompt();
    List<ToolGrant> grants =
        grants(tools.orderedStream().toList(), declaredGrants.orderedStream().toList());
    TurnObserver turnObserver = turnObservers.getIfAvailable(TurnObserver::noop);
    ObjectMapper mapper = objectMappers.getIfAvailable(ObjectMapper::new);
    List<HarnessObserver> factObservers = observers.orderedStream().toList();
    return Nessy.harness(
        config -> {
          config
              .type(properties.type())
              .model(model)
              .systemPrompt(systemPrompt)
              .grants(grants.toArray(ToolGrant[]::new))
              .staleness(StalenessPolicy.after(properties.staleness()))
              .backlogCapacity(properties.backlogCapacity())
              .objectMapper(mapper)
              .turnObserver(turnObserver)
              .settings(settings(properties))
              .observationRegistry(
                  observationRegistries.getIfAvailable(() -> ObservationRegistry.NOOP));
          // One call per listener, because harnessObserver(...) is additive: every HarnessObserver
          // bean the application declared — and the pending-approvals projection, which is one of
          // them — is subscribed to the fact stream alongside the harness's own narrator, which
          // nothing here replaces.
          factObservers.forEach(config::harnessObserver);
          substrates.ifAvailable(config::substrate);
          continuums.ifAvailable(config::continuum);
        });
  }

  /**
   * {@code ModelSettings} from the properties: {@link ModelSettings#defaults()}'s token budget with
   * whatever {@code nessy.capabilities} asked for laid over it. Called unconditionally rather than
   * only when the set is non-empty, so there is one path to read rather than two — an empty set is
   * what {@code defaults()} carries anyway.
   */
  static ModelSettings settings(NessyProperties properties) {
    ModelSettings defaults = ModelSettings.defaults();
    return new ModelSettings(
        defaults.maxTokens(), properties.capabilities(), defaults.contextWindow());
  }

  /**
   * The both-or-neither rule, enforced (Task 1 review, finding #4). {@code HarnessConfig#type}
   * spells out that a durable substrate over a volatile computation store silently drops every
   * delivery, and the reverse hangs every parked call. The starter wires the durable pair from one
   * {@code DataSource} precisely so that cannot happen by accident — but an application supplying
   * ONE of the two beans itself reopens the hole: its {@link Substrate} suppresses ours while our
   * JDBC {@link Continuum} is still built, and nobody says a word.
   *
   * <p>So it is said here, at startup, rather than discovered as a lost approval weeks later. The
   * two starter beans are named, and a bean DEFINITION missing under one of those names means
   * {@code @ConditionalOnMissingBean} stepped aside for the application's own. With a {@code
   * DataSource} present, exactly one of them missing is the mixed pair; both missing is the
   * application wiring both, which is its own business.
   *
   * <p>Without a {@code DataSource} both starter beans are the in-memory pair, so a single
   * user-supplied bean pairs a volatile store with a volatile one — no mismatch, nothing to say.
   *
   * @throws IllegalStateException when a {@code DataSource} is present and exactly one of the two
   *     stores was supplied by the application
   */
  static void requireMatchedDurability(
      boolean dataSourcePresent, boolean substrateIsOurs, boolean continuumIsOurs) {
    if (dataSourcePresent && substrateIsOurs != continuumIsOurs) {
      String supplied = substrateIsOurs ? "Continuum" : "Substrate";
      String ours = substrateIsOurs ? "Substrate" : "Continuum";
      throw new IllegalStateException(
          "Mixed durability: this application declares its own "
              + supplied
              + " bean while a DataSource is present, so the starter still builds a JDBC-backed "
              + ours
              + ". The two stores must be BOTH durable or BOTH volatile — a durable substrate over"
              + " a volatile computation store silently drops every delivery, and the reverse hangs"
              + " every parked call. Declare both beans, or neither and let the DataSource wire the"
              + " durable pair.");
    }
  }

  /**
   * Every {@code ToolGrant} bean exactly as the application declared it, plus every bare {@code
   * Tool} bean granted {@link Approvers#allow()} (spec §1.1). The two are alternatives, not layers:
   * a tool whose authority matters is declared as a grant; a tool that simply reads something is
   * declared as a tool. Declaring the same tool both ways would register it twice, and {@code
   * ToolRegistry} rejects a duplicate name — loudly, at startup, which is the right time.
   */
  static List<ToolGrant> grants(List<? extends Tool<?>> tools, List<ToolGrant> declared) {
    List<ToolGrant> grants = new ArrayList<>(declared);
    for (Tool<?> tool : tools) {
      grants.add(ToolGrant.grant(tool, Approvers.allow()));
    }
    return List.copyOf(grants);
  }

  /** The approve/deny door, straight off the harness (spec §1.1). */
  @Bean
  @ConditionalOnMissingBean
  public ApprovalDesk nessyApprovalDesk(Harness<String> harness) {
    return harness.approvals();
  }

  /** The deferred-tool completion door, straight off the harness (spec §1.1). */
  @Bean
  @ConditionalOnMissingBean
  public CompletionDesk nessyCompletionDesk(Harness<String> harness) {
    return harness.completions();
  }

  /** The pinned mapper every JSON recipe in a harness binds through. */
  static ObjectMapper pinned(ObjectProvider<ObjectMapper> objectMappers) {
    return Codecs.copyAndPin(objectMappers.getIfAvailable(ObjectMapper::new));
  }

  /**
   * The durable pair, when {@code nessy-substrate-jdbc} and {@code continuum-jdbc} are both on the
   * classpath (spec §1.1). Both or neither, in one place on purpose: {@code HarnessConfig#type}
   * spells out that two harnesses sharing a type must share both stores or neither, and the way to
   * make that mistake impossible from a starter is to never wire one without the other.
   *
   * <p>The {@code DataSource} is read through an {@link ObjectProvider} rather than gated with
   * {@code @ConditionalOnBean}: a provider is resolved when the bean is built, not when conditions
   * are evaluated, so this cannot depend on auto-configuration ordering. Without one, the same
   * in-memory pair the classpath-free case gets.
   *
   * <p>What this arrangement makes impossible is the starter wiring one store durable and the other
   * volatile. What it cannot prevent — because {@code @ConditionalOnMissingBean} steps aside for
   * the application — is an application declaring ONE of the two itself and leaving the starter to
   * build the other from the {@code DataSource}. That mixed pair is caught at startup instead, by
   * {@link #requireMatchedDurability}, which is the honest guard the "impossible from a starter"
   * line here used to overstate.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({JdbcSubstrate.class, JdbcContinuumRepository.class})
  static class DurableStores {

    @Bean
    @ConditionalOnMissingBean
    Substrate nessySubstrate(
        ObjectProvider<DataSource> dataSources, ObjectProvider<ObjectMapper> objectMappers) {
      DataSource dataSource = dataSources.getIfAvailable();
      return dataSource == null
          ? new InMemorySubstrate(pinned(objectMappers))
          : new JdbcSubstrate(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    Continuum nessyContinuum(ObjectProvider<DataSource> dataSources) {
      DataSource dataSource = dataSources.getIfAvailable();
      return new DefaultContinuum(
          dataSource == null
              ? new InMemoryContinuumRepository()
              : new JdbcContinuumRepository(dataSource),
          InstantSource.system());
    }
  }

  /**
   * The volatile pair, when the JDBC adapters are absent from the classpath entirely — computations
   * and scopes that live exactly as long as the process, which is the right default for a test, a
   * demo, or an agent with nothing worth remembering.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnMissingClass({
    "org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate",
    "org.jwcarman.continuum.jdbc.JdbcContinuumRepository"
  })
  static class VolatileStores {

    @Bean
    @ConditionalOnMissingBean
    Substrate nessySubstrate(ObjectProvider<ObjectMapper> objectMappers) {
      return new InMemorySubstrate(pinned(objectMappers));
    }

    @Bean
    @ConditionalOnMissingBean
    Continuum nessyContinuum() {
      return new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system());
    }
  }

  /**
   * The pending-approvals projection (spec §1.3), wired only when there is a database to project
   * into. {@code @ConditionalOnBean(DataSource.class)} is right here where an {@link
   * ObjectProvider} is right for the stores: a table has no in-memory fallback, so the beans must
   * be absent, not degraded — and the condition is safe to evaluate because this auto-configuration
   * declares itself after Boot's own {@code DataSourceAutoConfiguration}.
   *
   * <p>{@link PendingApprovals} is a {@code HarnessObserver} bean like any other, so the harness
   * bean method picks it up and subscribes it with the rest. It is not special-cased anywhere.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(JdbcTemplate.class)
  @ConditionalOnBean(DataSource.class)
  static class Projection {

    @Bean
    @ConditionalOnMissingBean
    PendingApprovals pendingApprovals(
        ObjectProvider<JdbcTemplate> jdbcTemplates,
        DataSource dataSource,
        ObjectProvider<ObjectMapper> objectMappers) {
      return new PendingApprovals(
          jdbcTemplates.getIfAvailable(() -> new JdbcTemplate(dataSource)), pinned(objectMappers));
    }

    @Bean
    @ConditionalOnMissingBean
    PendingApprovalsRepository pendingApprovalsRepository(
        ObjectProvider<JdbcTemplate> jdbcTemplates, DataSource dataSource) {
      return new PendingApprovalsRepository(
          jdbcTemplates.getIfAvailable(() -> new JdbcTemplate(dataSource)));
    }
  }
}
