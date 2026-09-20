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
package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.embedding.EmbedderFactory;
import org.jwcarman.nessy.api.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.trace.TraceCarrier;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * How a {@link org.jwcarman.nessy.api.Nessy} is wired.
 *
 * <p>Wiring rather than vocabulary: a database, a provider, a way of turning a type into a schema.
 * An application says this once -- in Spring, its starter says it -- and then works in the terms
 * the API offers.
 *
 * <p>The model is set here rather than per door, which is the point of the door existing. An
 * agent's inference and a quarantined read are the same connection to the same vendor; saying so
 * twice is how they drift.
 */
public final class NessyConfig {

  private final EngineConfig engine = new EngineConfig();
  private InferenceProvider provider;
  private InferenceOptions options;
  private EmbedderFactory embedders;
  private InputSchemaGenerator schemas;
  private ObjectMapper mapper;

  NessyConfig() {}

  /** Where agents live. Required: a harness is durable or it is not a harness. */
  public NessyConfig dataSource(DataSource dataSource) {
    engine.dataSource(dataSource);
    return this;
  }

  /**
   * The model, for everything that calls one.
   *
   * <p>Given to agents and to extractors alike. An extractor may name a different model when it is
   * built -- a cheap one reads invoice numbers -- but it talks through this provider.
   */
  public NessyConfig inference(InferenceProvider provider, InferenceOptions options) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    engine.inference(provider, options);
    return this;
  }

  /**
   * Where embedders come from. Left unset, memory ranks by recency rather than relevance.
   *
   * <p>A factory rather than an embedder, because a store is keyed on the vectors of one model and
   * two stores need not agree. In Spring this is the bean an embedding auto-configuration makes.
   */
  public NessyConfig embedders(EmbedderFactory embedders) {
    this.embedders = Objects.requireNonNull(embedders, "embedders must not be null");
    return this;
  }

  /**
   * How a Java type becomes the schema a model is shown.
   *
   * <p>Defaulted, because there is a right answer and it is the one the engine already uses for
   * tools. Set it to register modules of your own -- jakarta validation constraints reaching the
   * schema is the usual reason.
   */
  public NessyConfig schemas(InputSchemaGenerator schemas) {
    this.schemas = Objects.requireNonNull(schemas, "schemas must not be null");
    return this;
  }

  /** How recorded fields become the type they were asked for. Defaulted. */
  public NessyConfig mapper(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    return this;
  }

  /** Something told about every agent's events, as they happen. */
  public NessyConfig listener(AgentEventListener listener) {
    engine.listener(listener);
    return this;
  }

  /** Where calls are reported. Left unset, nothing is reported and nothing costs anything. */
  public NessyConfig observations(ObservationRegistry observations) {
    engine.observations(observations);
    return this;
  }

  /** How the trace in force is written down beside an effect, for the turn to come back as one. */
  public NessyConfig traceCarrier(TraceCarrier traceCarrier) {
    engine.traceCarrier(traceCarrier);
    return this;
  }

  /** The keys a deferred answer's address is minted and read with. */
  public NessyConfig replyTokens(ReplyTokens replyTokens) {
    engine.replyTokens(replyTokens);
    return this;
  }

  /** What happens to a payload on its way to the database, and back. */
  public NessyConfig storage(Codec<byte[]> transform) {
    engine.storage(transform);
    return this;
  }

  /** Whether what a model was shown is kept. On by default. */
  public NessyConfig recordInferenceContexts(boolean recorded) {
    engine.recordInferenceContexts(recorded);
    return this;
  }

  EngineConfig engine() {
    return engine;
  }

  InferenceProvider requiredProvider() {
    return Objects.requireNonNull(provider, "a model is required: inference(provider, options)");
  }

  InferenceOptions requiredOptions() {
    return Objects.requireNonNull(options, "a model is required: inference(provider, options)");
  }

  EmbedderFactory embedders() {
    return embedders;
  }

  InputSchemaGenerator schemas() {
    return schemas == null ? new VictoolsInputSchemaGenerator() : schemas;
  }

  ObjectMapper mapper() {
    return mapper == null ? JsonMapper.builder().build() : mapper;
  }
}
