package org.jwcarman.nessy.engine.harness;

import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import javax.sql.DataSource;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.narration.Narrator;

/**
 * What an engine needs from the application, and what it will assume if not told.
 *
 * <p><b>Two required things: somewhere to keep agents and something to ask.</b> The rest are
 * application facts with defaults: where narration goes, where spans go, which keys seal a reply
 * token. Anything an agent type might tune -- timeouts, retries, the context it is shown -- has its
 * default in the engine and is overridden on the harness that wants otherwise.
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
  private Narrator narrator = Narrator.silent();
  private ObservationRegistry observations = ObservationRegistry.NOOP;
  private ReplyTokens replyTokens;

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

  /** Where an agent says what it is doing. Defaults to saying nothing. */
  public EngineConfig narrator(Narrator narrator) {
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
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

  // ---- what the factory reads ------------------------------------------------------------

  DataSource requiredDataSource() {
    if (dataSource == null) {
      throw new IllegalStateException(
          "an engine needs a DataSource: agents, their stories and their outstanding work are all"
              + " rows, and there is nowhere to keep them");
    }
    return dataSource;
  }

  InferenceProvider requiredProvider() {
    if (provider == null) {
      throw new IllegalStateException(
          "an engine needs an InferenceProvider: it is the thing an agent asks, and there is"
              + " nothing to ask without one");
    }
    return provider;
  }

  InferenceOptions requiredOptions() {
    if (options == null) {
      throw new IllegalStateException("inference(provider, options) needs both");
    }
    return options;
  }

  Narrator narrator() {
    return narrator;
  }

  ObservationRegistry observations() {
    return observations;
  }

  ReplyTokens replyTokens() {
    return replyTokens != null ? replyTokens : ReplyTokens.ephemeral();
  }
}
