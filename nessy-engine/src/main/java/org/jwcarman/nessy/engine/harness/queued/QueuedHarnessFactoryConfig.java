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
package org.jwcarman.nessy.engine.harness.queued;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.engine.harness.ProviderRegistry;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.trace.TraceCarrier;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * What an engine needs from the application, and what it will assume if not told.
 *
 * <p><b>Two required things: somewhere to keep agents and something to ask.</b> The rest are
 * application facts with defaults: who hears what agents do, where spans go, which keys seal a
 * reply token. Anything an agent type might tune -- timeouts, retries, the context it is shown --
 * has its default in the engine and is overridden on the harness that wants otherwise.
 *
 * <p><b>Nothing here is the engine's own plumbing.</b> How rows are encoded, how tool arguments are
 * described to a model, how tokens are estimated and which thread looks for due work are all
 * decided by the engine from the backend -- they used to be knobs, and every caller set them to the
 * same thing, which is what a knob that should not exist looks like.
 *
 * <p>Customizer-shaped, like {@link org.jwcarman.nessy.api.QueuedHarnessConfig} and the configs
 * beneath it: an application says what it wants and stays silent about the rest.
 */
public final class QueuedHarnessFactoryConfig {

  private QueuedBackend backend;
  private final ProviderRegistry providers = new ProviderRegistry();
  private ProviderId defaultProvider;
  private InferenceOptions defaultOptions;
  private final List<NarrationListener> listeners = new ArrayList<>();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private TraceCarrier traceCarrier;
  private ReplyTokens replyTokens;

  QueuedHarnessFactoryConfig() {}

  /**
   * Where agents, their stories and their outstanding work are kept, chosen together. Required.
   *
   * <p>A backend rather than a {@code DataSource} plus a storage transform: only the backend can
   * build the codec that applies the application's storage transform to every store it makes,
   * including a backlog's -- the one table the schema flags as holding raw user text. A caller
   * supplying stores piecemeal could bypass that transform for exactly that table.
   */
  public QueuedHarnessFactoryConfig backend(QueuedBackend backend) {
    this.backend = backend;
    return this;
  }

  /**
   * One of the providers this factory's agents may be answered by, under the name they will ask for
   * it by. Repeatable; the same id twice fails at once.
   */
  public QueuedHarnessFactoryConfig provider(ProviderId id, InferenceProvider provider) {
    providers.register(id, provider);
    return this;
  }

  /**
   * What an agent type gets when it says nothing: which provider, which model, how much answer.
   * Optional; without it every agent type names both itself.
   */
  public QueuedHarnessFactoryConfig inference(ProviderId provider, InferenceOptions options) {
    this.defaultProvider = Objects.requireNonNull(provider, "provider must not be null");
    this.defaultOptions = Objects.requireNonNull(options, "options must not be null");
    return this;
  }

  /**
   * Somebody who hears what every agent of every harness does. Repeatable; defaults to nobody,
   * because narration costs a line per event and nobody asked. A harness adds its own with {@code
   * QueuedHarnessConfig.listener(...)}.
   */
  public QueuedHarnessFactoryConfig listener(NarrationListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  /**
   * Where spans go. Defaults to {@link ObservationRegistry#NOOP}, which is the whole of switching
   * tracing off: no carrier is captured, no column is written, no span is opened.
   */
  public QueuedHarnessFactoryConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * How the trace in force is written beside an effect without a span of its own. Defaults to
   * opening a momentary {@code nessy.effect.emit} span, which is the only way an input can have
   * headers written for it; a tracing library can do it directly, and the Boot starter hands one
   * in.
   */
  public QueuedHarnessFactoryConfig traceCarrier(TraceCarrier traceCarrier) {
    this.traceCarrier = Objects.requireNonNull(traceCarrier, "traceCarrier must not be null");
    return this;
  }

  /**
   * How a deferred answer finds its way back. Defaults to a key that dies with this process, so an
   * approval parked on a person becomes unanswerable after a restart -- fine for a test, and the
   * reason an application configures one.
   */
  public QueuedHarnessFactoryConfig replyTokens(ReplyTokens replyTokens) {
    this.replyTokens = replyTokens;
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  QueuedBackend requiredBackend() {
    return Objects.requireNonNull(
        backend,
        "an engine needs a backend: agents, their stories and their outstanding work are all"
            + " rows, and there is nowhere to keep them (engine(e -> e.backend(...)))");
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

  List<NarrationListener> listeners() {
    return List.copyOf(listeners);
  }

  ObservationRegistry observations() {
    return observations;
  }

  Optional<TraceCarrier> traceCarrier() {
    return Optional.ofNullable(traceCarrier);
  }

  ReplyTokens replyTokens() {
    return replyTokens != null ? replyTokens : ReplyTokens.ephemeral();
  }
}
