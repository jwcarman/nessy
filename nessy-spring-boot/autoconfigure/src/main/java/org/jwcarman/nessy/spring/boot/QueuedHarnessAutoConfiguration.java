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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.QueuedHarnessFactoryConfig;
import org.jwcarman.nessy.engine.jdbc.JdbcQueuedBackend;
import org.jwcarman.nessy.engine.store.StorageCodec;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The queued door, for work nobody is waiting on.
 *
 * <p>Its own auto-configuration rather than part of the base, because it demands what the base does
 * not: a database to keep a backlog and an outbox in, and somebody to answer. Before this was split
 * out, an application wanting only the direct door had to EXCLUDE the whole of Nessy to avoid being
 * asked for them -- which two of this project's own examples did.
 *
 * <p>The engine reads every {@code Customizer<QueuedHarnessFactoryConfig>} bean after the starter
 * has said what it knows, so an application changes how the engine is put together -- its storage
 * codec, its dispatcher, its trace carrier -- without declaring the factory itself.
 */
@AutoConfiguration(after = NessyAutoConfiguration.class)
@ConditionalOnProperty(name = "nessy.enabled", havingValue = "true", matchIfMissing = true)
// No @ConditionalOnClass here, deliberately. Both doors' factories live in nessy-engine, which
// this module depends on outright, so a condition naming either class can never be false -- and a
// reader who found one would reasonably conclude the classpath tells the two doors apart. It does
// not. An application that wants one door excludes the other by name.
public class QueuedHarnessAutoConfiguration {

  @Bean
  // Against the INTERFACE, as the direct door's is: an application declaring its own factory
  // declares the interface, and a condition naming the concrete class never sees it.
  @ConditionalOnMissingBean(QueuedHarnessFactory.class)
  public DefaultQueuedHarnessFactory nessyHarnessFactory(
      DataSource dataSource,
      ReplyTokens replyTokens,
      InferenceProvider models,
      NessyProperties properties,
      NessyAutoConfiguration.NessySchema schema,
      ObservationRegistry observations,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<QueuedBackend> backends,
      ObjectProvider<PlatformTransactionManager> transactions,
      ObjectProvider<StorageCodec> storage,
      ObjectProvider<Tracer> tracers,
      ObjectProvider<Propagator> propagators,
      ObjectProvider<Customizer<QueuedHarnessFactoryConfig>> customizers) {

    // Token counts become semconv's histogram when there is a meter registry to hold it.
    meters.ifAvailable(
        registry ->
            observations.observationConfig().observationHandler(new TokenUsageHandler(registry)));
    // A backend chooses its own codec together with its own stores, applying the application's
    // storage transform to every one of them -- including a backlog's, the one table the schema
    // flags as holding raw user text. An application says which backend it wants as a whole, or
    // gets the JDBC one built from the DataSource, whatever PlatformTransactionManager it has, and
    // whatever storage transform it declared.
    QueuedBackend backend =
        backends.getIfAvailable(
            () ->
                new JdbcQueuedBackend(
                    dataSource,
                    transactions.getIfAvailable(() -> new JdbcTransactionManager(dataSource)),
                    storage.getIfAvailable()));
    List<Customizer<QueuedHarnessFactoryConfig>> all = new ArrayList<>();
    all.add(
        engine -> {
          engine
              .backend(backend)
              .inference(
                  models, new InferenceOptions(requireModel(properties), properties.maxTokens()))
              .observations(observations)
              .replyTokens(replyTokens);
          // With a tracer and its propagator the context is written straight into the effect
          // row; without them the engine opens a momentary span to have it written.
          Tracer tracer = tracers.getIfAvailable();
          Propagator propagator = propagators.getIfAvailable();
          if (tracer != null && propagator != null) {
            engine.traceCarrier(new PropagatingTraceCarrier(tracer, propagator));
          }
        });
    // Then whatever the application has to say about the engine itself.
    customizers.orderedStream().forEach(all::add);
    return DefaultQueuedHarnessFactory.of(all);
  }

  /**
   * Every {@link NarrationListener} bean, attached engine-wide once every bean exists -- after
   * rather than at the factory's making, so a listener that reads the story (through the factory)
   * is not a circle.
   */
  @Bean
  public SmartInitializingSingleton nessyListeners(
      DefaultQueuedHarnessFactory factory, ObjectProvider<NarrationListener> listeners) {
    return () -> listeners.orderedStream().forEach(factory::listener);
  }

  /** The story, for an application that shows what its agents said. */
  @Bean
  @ConditionalOnMissingBean
  public TurnHistories nessyHistories(DefaultQueuedHarnessFactory factory) {
    return factory.histories();
  }

  @Bean
  @ConditionalOnMissingBean
  public Replies nessyReplies(DefaultQueuedHarnessFactory factory) {
    return factory.replies();
  }

  private static String requireModel(NessyProperties properties) {
    String model = properties.model();
    if (model == null || model.isBlank()) {
      throw new IllegalStateException(
          "nessy.model must name the model these agents talk to; it is sent to your"
              + " InferenceProvider bean with every call");
    }
    return model;
  }
}
