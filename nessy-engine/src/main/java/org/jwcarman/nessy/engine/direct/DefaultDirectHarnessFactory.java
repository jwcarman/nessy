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

package org.jwcarman.nessy.engine.direct;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.effect.EffectTermsSource;
import org.jwcarman.nessy.engine.narration.Listeners;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.store.PayloadStore;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes direct harnesses that share what should be shared.
 *
 * <p>What sits here rather than on a harness is what an application owns once: where events are
 * written, where payloads are kept, who provides inference, and what keeps two callers off one
 * agent. A harness adds what is its own -- a type, a prompt, its tools, what it is shown.
 *
 * <p><b>Closeable, which the door's interface used to say nothing here was.</b> Enforcing the three
 * deadlines this door now enforces (design record {@code 2026-09-25-locks-as-plumbing}, {@code 4c})
 * means every effect runs on a virtual thread rather than the caller's, so there is one executor --
 * this one, shared by every harness this factory makes. A turn still ends when {@code ask} returns;
 * what outlives the call is an abandoned effect whose caller has already been told it failed.
 *
 * <p>One executor here rather than one per harness, because a thread-per-task executor over virtual
 * threads holds nothing while idle: a harness has nothing to own and nothing to release. The
 * alternative was a harness that owned one, which would have to be closeable, which would mean this
 * factory keeping a list of every harness it ever made in order to close them -- an unbounded
 * registry to release resources that are not held.
 *
 * <p>Closing is optional: a caller that never does loses nothing, since virtual threads that finish
 * need no shutdown. A container managing this factory's lifecycle should let it.
 */
public final class DefaultDirectHarnessFactory implements DirectHarnessFactory, AutoCloseable {

  /**
   * Everyone who hears every agent of every harness this factory makes.
   *
   * <p>Filled after the factory exists, because a container finds its listeners once everything
   * else is built. Without this a listener an application declares can reach the queued door and
   * not this one, which is a difference nobody would predict from the outside.
   */
  private final List<NarrationListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * Adds somebody who hears every agent this factory serves. Harnesses already made hear it too.
   */
  public void listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
  }

  private final Locks locks;
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final InferenceProvider provider;
  private final InputSchemaGenerator schemas;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final List<Customizer<HarnessConfig<?>>> features;
  private final List<Customizer<DirectHarnessConfig<?>>> harnesses;

  /**
   * One virtual thread per effect, for every harness this factory makes -- see the class javadoc
   * for why it is not one each.
   */
  private final ExecutorService effects =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("nessy-direct-effect-", 0).factory());

  /**
   * Reads the config rather than holding it, so a caller that keeps a reference and changes it
   * afterwards does not change a factory that already exists.
   */
  private DefaultDirectHarnessFactory(DirectHarnessFactoryConfig config) {
    this.locks = config.requiredLocks();
    this.events = config.requiredEvents();
    this.payloads = config.requiredPayloads();
    this.provider = config.requiredProvider();
    this.schemas = config.schemas();
    this.mapper = config.mapper();
    this.clock = config.clock();
    this.listeners.addAll(config.listeners());
    this.features = config.features();
    this.harnesses = config.harnesses();
  }

  /**
   * One factory, from every customizer that has something to say about it.
   *
   * <p>A list rather than one, because this is what a container hands over: every {@code
   * Customizer<DirectHarnessFactoryConfig>} bean an application declared, in order, each adding to
   * the same config before anything is built from it.
   */
  public static DefaultDirectHarnessFactory of(
      List<Customizer<DirectHarnessFactoryConfig>> customizers) {
    Objects.requireNonNull(customizers, "customizers must not be null");
    DirectHarnessFactoryConfig config = new DirectHarnessFactoryConfig();
    customizers.forEach(customizer -> customizer.customize(config));
    return new DefaultDirectHarnessFactory(config);
  }

  /** One customizer, for a caller that is not a container. */
  public static DefaultDirectHarnessFactory of(Customizer<DirectHarnessFactoryConfig> customizer) {
    return of(List.of(Objects.requireNonNull(customizer, "customizer must not be null")));
  }

  /** Everything in one process and nothing written down: a CLI, a test, a one-shot. */
  public static DefaultDirectHarnessFactory inMemory(
      InferenceProvider provider, InputSchemaGenerator schemas, ObjectMapper mapper) {
    return of(
        config ->
            config
                .locks(new InMemoryLocks())
                .events(new InMemoryAgentEventStore())
                .payloads(new InMemoryPayloads())
                .provider(provider)
                .schemas(schemas)
                .mapper(mapper));
  }

  /**
   * The vendor behind this, as the OpenTelemetry GenAI conventions name it.
   *
   * <p>Worth being able to ask. Which provider answers is decided by which key happens to be set,
   * and a model name that belongs to a different vendor fails as a 404 from one nobody meant to
   * call.
   */
  @Override
  public String providerName() {
    return provider.providerName();
  }

  @Override
  public <I> DirectHarness<I> create(
      AgentType agentType, Customizer<DirectHarnessConfig<I>> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    DefaultDirectHarnessConfig<I> config = new DefaultDirectHarnessConfig<>(agentType);
    // What jars installed, then what this application says about every harness, then what this
    // caller asked for -- each able to override the one before it.
    features.forEach(feature -> feature.customize(config));
    harnesses.forEach(blanket -> blanket.customize(config));
    customizer.customize(config);
    List<ToolBinding<?>> bindings = config.bindings(schemas, mapper);
    DefaultDirectHarnessConfig.Inference inference = config.inference();
    if (inference.modelName() == null) {
      throw new IllegalStateException("a model is required: inference(in -> in.model(...))");
    }
    Tools tools = new Tools(bindings);
    // What each kind of effect is worth, from the tools this harness bound and the harness-wide
    // defaults alone -- exactly what the queued factory builds, so the phase-to-timeout mapping
    // lives in one place and neither door repeats it.
    EffectTermsSource terms =
        new EffectTermsSource(
            tools,
            DefaultDirectHarnessConfig.DEFAULT_TOOL_TIMEOUT,
            DefaultDirectHarnessConfig.DEFAULT_RETRY_POLICY,
            DefaultDirectHarnessConfig.DEFAULT_APPROVAL_TIMEOUT,
            DefaultDirectHarnessConfig.DEFAULT_RETRY_POLICY,
            inference.timeout(),
            inference.retryPolicy());
    DefaultDirectHarness<I> harness =
        new DefaultDirectHarness<>(
            locks,
            config.agentType(),
            events,
            payloads,
            provider,
            config.systemPromptSource(),
            new InferenceOptions(inference.modelName(), inference.maxTokens()),
            config.renderer()::render,
            tools,
            schemas,
            mapper,
            inference.summaries(),
            inference.maxTail(),
            inference.ambient(),
            new Listeners(listeners, config.listeners()),
            clock,
            terms,
            effects);
    return harness;
  }

  /**
   * Shuts down the executor every harness's deadline enforcement runs on.
   *
   * <p>Nothing already running is interrupted -- only a deadline of its own does that -- so a
   * factory closed mid-turn lets each turn's own timeout be what ends it. Inferred by Spring as the
   * bean's destroy method, and there for anyone who built a factory without Spring.
   */
  @Override
  public void close() {
    effects.close();
  }
}
