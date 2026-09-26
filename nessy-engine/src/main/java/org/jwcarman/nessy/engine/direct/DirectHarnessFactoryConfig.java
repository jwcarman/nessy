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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.HarnessConfig;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.core.AgentEventStore;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.store.PayloadStore;
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

  private Locks locks;
  private AgentEventStore events;
  private PayloadStore payloads;
  private InferenceProvider provider;
  private InputSchemaGenerator schemas = new VictoolsInputSchemaGenerator();
  private ObjectMapper mapper = JsonMapper.builder().build();
  private final List<NarrationListener> listeners = new ArrayList<>();
  private final List<Customizer<HarnessConfig<?>>> features = new ArrayList<>();
  private final List<Customizer<DirectHarnessConfig<?>>> harnesses = new ArrayList<>();

  /**
   * What holds one turn at a time per agent.
   *
   * <p>Required, and deliberately so: in a process serving several conversations the wrong answer
   * here is silent, and a caller that genuinely wants locks held in this process alone says so with
   * {@link InMemoryLocks}.
   */
  public DirectHarnessFactoryConfig locks(Locks locks) {
    this.locks = locks;
    return this;
  }

  /** Where the story is kept. */
  public DirectHarnessFactoryConfig events(AgentEventStore events) {
    this.events = events;
    return this;
  }

  /** Where content is kept, which is everything the events only name. */
  public DirectHarnessFactoryConfig payloads(PayloadStore payloads) {
    this.payloads = payloads;
    return this;
  }

  /** Who answers. */
  public DirectHarnessFactoryConfig provider(InferenceProvider provider) {
    this.provider = provider;
    return this;
  }

  /** How a Java type becomes a schema, for tools and for a constrained answer. */
  public DirectHarnessFactoryConfig schemas(InputSchemaGenerator schemas) {
    this.schemas = schemas;
    return this;
  }

  /** The mapper that reads a tool's arguments and a constrained answer back into types. */
  public DirectHarnessFactoryConfig mapper(ObjectMapper mapper) {
    this.mapper = mapper;
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
   * <p>For a module rather than an application: it can add tools, ambient context and a source of
   * summaries, and reads the agent type to key whatever it keeps on. It cannot say what the agent
   * is FOR -- no prompt, no renderer -- because the application already said that.
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

  Locks requiredLocks() {
    return require(locks, "locks");
  }

  AgentEventStore requiredEvents() {
    return require(events, "events");
  }

  PayloadStore requiredPayloads() {
    return require(payloads, "payloads");
  }

  InferenceProvider requiredProvider() {
    return require(provider, "an inference provider");
  }

  InputSchemaGenerator schemas() {
    return schemas;
  }

  ObjectMapper mapper() {
    return mapper;
  }

  List<NarrationListener> listeners() {
    return List.copyOf(listeners);
  }

  private static <T> T require(T value, String what) {
    if (value == null) {
      throw new IllegalStateException(
          what + " is required: factory(f -> f." + what.split(" ")[0] + "(...))");
    }
    return value;
  }
}
