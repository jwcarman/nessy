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

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.core.AgentEventStore;
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
 * <p>Nothing here is closeable, which is the difference worth noticing. The queued factory owns the
 * engine's timer and every harness it made, because work outlives the call that submitted it. Here
 * the turn ends when {@code ask} returns and there is nothing left running to shut down.
 */
public final class DirectHarnessFactory {

  private final Locks locks;
  private final AgentEventStore events;
  private final PayloadStore payloads;
  private final InferenceProvider provider;
  private final InputSchemaGenerator schemas;
  private final ObjectMapper mapper;

  public DirectHarnessFactory(
      Locks locks,
      AgentEventStore events,
      PayloadStore payloads,
      InferenceProvider provider,
      InputSchemaGenerator schemas,
      ObjectMapper mapper) {
    this.locks = Objects.requireNonNull(locks, "locks must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.schemas = Objects.requireNonNull(schemas, "schemas must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /** Everything in one process and nothing written down: a CLI, a test, a one-shot. */
  public static DirectHarnessFactory inMemory(
      InferenceProvider provider, InputSchemaGenerator schemas, ObjectMapper mapper) {
    return new DirectHarnessFactory(
        new InMemoryLocks(),
        new InMemoryAgentEventStore(),
        new InMemoryPayloads(),
        provider,
        schemas,
        mapper);
  }

  /**
   * The vendor behind this, as the OpenTelemetry GenAI conventions name it.
   *
   * <p>Worth being able to ask. Which provider answers is decided by which key happens to be set,
   * and a model name that belongs to a different vendor fails as a 404 from one nobody meant to
   * call.
   */
  public String providerName() {
    return provider.providerName();
  }

  public <I> DirectHarness<I> create(Consumer<DirectHarnessConfig<I>> customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    DefaultDirectHarnessConfig<I> config = new DefaultDirectHarnessConfig<>();
    customizer.accept(config);
    List<ToolBinding<?>> bindings = config.bindings(schemas, mapper);
    DefaultDirectHarnessConfig.Inference inference = config.inference();
    if (inference.modelName() == null) {
      throw new IllegalStateException("a model is required: inference(in -> in.model(...))");
    }
    return new DefaultDirectHarness<>(
        locks,
        config.agentType(),
        events,
        payloads,
        provider,
        config.systemPromptSource(),
        new InferenceOptions(inference.modelName(), inference.maxTokens()),
        config.renderer()::render,
        new Tools(bindings),
        schemas,
        mapper,
        inference.summaries(),
        inference.maxTail(),
        inference.ambient(),
        config.listeners());
  }
}
