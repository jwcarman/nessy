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
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.substrate.jdbc.JdbcSubstrate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
   * The harness's one required dependency, from the process environment (spec §1.1). {@code
   * ModelDiscovery} picks the provider from whichever credentials are present and honours {@code
   * NESSY_MODEL} for the model id and {@code NESSY_PROVIDER} for the tie-break.
   *
   * <p>There is no {@code nessy.model.id} property. Discovery reads the environment and only the
   * environment, and an application that wants the id to come from anywhere else — a property, a
   * database, a feature flag — declares its own {@link Model} bean, which wins outright. That is
   * one less way to say the same thing, not a missing feature.
   */
  @Bean
  @ConditionalOnMissingBean
  public Model nessyModel() {
    return ModelDiscovery.fromEnv();
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
      ObjectProvider<Substrate> substrates,
      ObjectProvider<Continuum> continuums,
      ObjectProvider<Tool<?>> tools,
      ObjectProvider<ToolGrant> declaredGrants,
      ObjectProvider<HarnessObserver> observers,
      ObjectProvider<TurnObserver> turnObservers,
      ObjectProvider<ObservationRegistry> observationRegistries,
      ObjectProvider<ObjectMapper> objectMappers) {
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
}
