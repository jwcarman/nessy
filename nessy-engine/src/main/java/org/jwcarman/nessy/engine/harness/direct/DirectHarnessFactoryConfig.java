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
package org.jwcarman.nessy.engine.harness.direct;

import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.JsonSchemaGenerator;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.engine.harness.ProviderRegistry;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything a direct factory is built from, in one place a customizer can reach.
 *
 * <p>What is settled once for every harness a factory makes: where events and content are kept, who
 * answers, what turns a Java type into a schema, and what holds one turn at a time per agent. What
 * an individual agent is -- its type, its prompt, its tools -- is the harness's own config and not
 * here.
 *
 * <p><b>The counterpart of the queued door's {@code QueuedHarnessFactoryConfig}.</b> The direct
 * factory used to take six constructor arguments, which left nothing for anything to customize and
 * meant every caller assembling a factory had to know how to assemble its stores too.
 */
public final class DirectHarnessFactoryConfig {

  private DirectBackend backend;
  private final ProviderRegistry providers = new ProviderRegistry();
  private ProviderId defaultProvider;
  private InferenceOptions defaultOptions;
  private JsonSchemaGenerator schemas = new VictoolsJsonSchemaGenerator();
  private ObjectMapper mapper = JsonMapper.builder().build();
  private Clock clock = Clock.systemUTC();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private final List<NarrationListener> listeners = new ArrayList<>();
  private final List<Customizer<HarnessConfig<?>>> features = new ArrayList<>();
  private final List<Customizer<DirectHarnessConfig<?>>> harnesses = new ArrayList<>();

  /**
   * Where events, content and the lock that holds one turn at a time per agent all come from,
   * chosen together.
   *
   * <p>Required, and deliberately so: a config that instead took a {@code Locks}, an {@code
   * AgentEvents} and a {@code Payloads} separately could combine a durable store with an in-memory
   * lock -- no exclusion between two instances of the same agent, and no transaction around the
   * direct door's steps, since {@code withLock} IS that transaction now. A caller that genuinely
   * wants everything held in this process alone says so with {@code InMemoryDirectBackend}.
   */
  public DirectHarnessFactoryConfig backend(DirectBackend backend) {
    this.backend = backend;
    return this;
  }

  /**
   * One of the providers this factory's agents may be answered by, under the name they will ask for
   * it by. Repeatable; the same id twice fails at once.
   */
  public DirectHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider) {
    providers.register(id, provider);
    return this;
  }

  /**
   * What an agent type gets when it says nothing: which provider, which model, how much answer.
   * Optional; without it every agent type names both itself.
   */
  public DirectHarnessFactoryConfig inference(ProviderId provider, InferenceOptions options) {
    this.defaultProvider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultOptions = Objects.requireNonNull(options, "options must not be null");
    return this;
  }

  /** How a Java type becomes a schema, for tools and for a constrained answer. */
  public DirectHarnessFactoryConfig schemas(JsonSchemaGenerator schemas) {
    this.schemas = schemas;
    return this;
  }

  /** The mapper that reads a tool's arguments and a constrained answer back into types. */
  public DirectHarnessFactoryConfig mapper(ObjectMapper mapper) {
    this.mapper = mapper;
    return this;
  }

  /**
   * What a deadline is measured from.
   *
   * <p>Defaults to {@link Clock#systemUTC()}, which is right for every harness this factory makes
   * except a test: the three deadlines this door now enforces -- an inference's, a tool call's, a
   * blocking approver's -- are computed from this clock rather than from {@code Instant.now()}, so
   * a test can step it instead of waiting out a real timeout.
   */
  public DirectHarnessFactoryConfig clock(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    return this;
  }

  /**
   * Where every effect this door performs reports its own work -- the same registry the queued door
   * takes. Defaults to {@link ObservationRegistry#NOOP}: nothing to report, nothing reported, and
   * every span this door produces once a real registry is given is genuinely new -- this door
   * observed nothing before.
   */
  public DirectHarnessFactoryConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * Somebody who hears every agent of every harness this factory makes.
   *
   * <p>Here rather than on each harness because that is what "every agent" means: a listener added
   * to the factory hears harnesses made before it and after it alike.
   */
  public DirectHarnessFactoryConfig listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  /**
   * Something that equips every agent this factory serves.
   *
   * <p>For a module rather than an application: it reaches {@link HarnessConfig}, so it can add
   * tools, instructions, memory, state and ambient sources, and a chapter policy or summariser, and
   * it reads the agent type to key whatever it keeps on. It cannot set the application's own system
   * prompt or its renderer, because the application already said what the agent is FOR.
   */
  public DirectHarnessFactoryConfig feature(Customizer<HarnessConfig<?>> customizer) {
    features.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    return this;
  }

  /**
   * Something applied to every harness this factory makes, with the whole configuration in hand.
   *
   * <p>For the application that owns the factory, which may reasonably say "everything I make gets
   * this listener" or "everything I make sees twenty turns". Applied AFTER features, so what an
   * application says here wins over what a jar installed.
   */
  public DirectHarnessFactoryConfig harness(Customizer<DirectHarnessConfig<?>> customizer) {
    harnesses.add(Objects.requireNonNull(customizer, "customizer must not be null"));
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  List<Customizer<HarnessConfig<?>>> features() {
    return List.copyOf(features);
  }

  List<Customizer<DirectHarnessConfig<?>>> harnesses() {
    return List.copyOf(harnesses);
  }

  DirectBackend requiredBackend() {
    return require(backend, "backend");
  }

  ProviderRegistry providers() {
    return providers;
  }

  ProviderId defaultProvider() {
    return defaultProvider;
  }

  InferenceOptions defaultOptions() {
    return defaultOptions;
  }

  JsonSchemaGenerator schemas() {
    return schemas;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  Clock clock() {
    return clock;
  }

  ObservationRegistry observations() {
    return observations;
  }

  List<NarrationListener> listeners() {
    return List.copyOf(listeners);
  }

  // A value no customizer set. The message names the customizer call that would have set it.
  private static <T> T require(T value, String what) {
    return Optional.ofNullable(value)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    what + " is required: factory(f -> f." + what.split(" ")[0] + "(...))"));
  }
}
