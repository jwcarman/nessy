package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;

/**
 * What an engine needs from the application, and what it will assume if not told.
 *
 * <p><b>Two required things: somewhere to keep agents and something to ask.</b> The rest are
 * application facts with defaults: who hears what agents do, where spans go, which keys seal a
 * reply token, what is done to the bytes it stores. Anything an agent type might tune -- timeouts,
 * retries, the context it is shown -- has its default in the engine and is overridden on the
 * harness that wants otherwise.
 *
 * <p><b>Nothing here is the engine's own plumbing.</b> How rows are encoded, how tool arguments are
 * described to a model, how tokens are estimated, which transaction manager wraps a fold and which
 * thread looks for due work are all decided by the engine from the {@code DataSource} -- they used
 * to be knobs, and every caller set them to the same thing, which is what a knob that should not
 * exist looks like.
 *
 * <p>Customizer-shaped, like {@link org.jwcarman.nessy.api.HarnessConfig} and the configs beneath
 * it: an application says what it wants and stays silent about the rest.
 */
public final class EngineConfig {

  private DataSource dataSource;
  private InferenceProvider provider;
  private InferenceOptions options;
  private final List<AgentEventListener> listeners = new ArrayList<>();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private ReplyTokens replyTokens;
  private Codec<byte[]> storage;

  EngineConfig() {}

  /** Where agents, their stories and their outstanding work are kept. Required. */
  public EngineConfig dataSource(DataSource dataSource) {
    this.dataSource = dataSource;
    return this;
  }

  /**
   * The model every agent type talks to, and the terms it is asked on.
   *
   * <p>One provider for the engine because a provider is a connection; which model to call travels
   * per request, so an agent type wanting a different one overrides it on its harness.
   */
  public EngineConfig inference(InferenceProvider provider, InferenceOptions options) {
    this.provider = provider;
    this.options = options;
    return this;
  }

  /**
   * Somebody who hears what every agent of every harness does. Repeatable; defaults to nobody,
   * because narration costs a line per event and nobody asked. A harness adds its own with {@code
   * HarnessConfig.listener(...)}.
   */
  public EngineConfig listener(AgentEventListener listener) {
    listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    return this;
  }

  /**
   * Where spans go. Defaults to {@link ObservationRegistry#NOOP}, which is the whole of switching
   * tracing off: no carrier is captured, no column is written, no span is opened.
   */
  public EngineConfig observations(ObservationRegistry observations) {
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    return this;
  }

  /**
   * How a deferred answer finds its way back. Defaults to a key that dies with this process, so an
   * approval parked on a person becomes unanswerable after a restart -- fine for a test, and the
   * reason an application configures one.
   */
  public EngineConfig replyTokens(ReplyTokens replyTokens) {
    this.replyTokens = replyTokens;
    return this;
  }

  /**
   * What happens to every byte the engine stores, after Jackson has written it and before Jackson
   * reads it back: compression, encryption, both, composed with {@link Codec#andThen}. Defaults to
   * nothing. Fixed for the life of the data: rows written under one transform are unreadable under
   * another, which is the same fact as an encryption key.
   */
  public EngineConfig storage(Codec<byte[]> transform) {
    this.storage = Objects.requireNonNull(transform, "transform must not be null");
    return this;
  }

  // ---- what the factory reads ------------------------------------------------------------

  DataSource requiredDataSource() {
    return Objects.requireNonNull(
        dataSource,
        "an engine needs a DataSource: agents, their stories and their outstanding work are all"
            + " rows, and there is nowhere to keep them");
  }

  InferenceProvider requiredProvider() {
    return Objects.requireNonNull(
        provider,
        "an engine needs an InferenceProvider: it is the thing an agent asks, and there is"
            + " nothing to ask without one");
  }

  InferenceOptions requiredOptions() {
    return Objects.requireNonNull(options, "inference(provider, options) needs both");
  }

  List<AgentEventListener> listeners() {
    return List.copyOf(listeners);
  }

  ObservationRegistry observations() {
    return observations;
  }

  Optional<Codec<byte[]>> storage() {
    return Optional.ofNullable(storage);
  }

  ReplyTokens replyTokens() {
    return replyTokens != null ? replyTokens : ReplyTokens.ephemeral();
  }
}
