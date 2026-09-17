package org.jwcarman.nessy.spring.boot;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Objects;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.jwcarman.nessy.spi.inference.Usage;

/**
 * Records semconv's {@code gen_ai.client.token.usage} histogram from the {@link Usage} the engine
 * puts in a model call's observation context: one sample for tokens in and one for tokens out, told
 * apart by {@code gen_ai.token.type}, tagged with the same operation, provider and model as the
 * duration. A count is a sample, never a tag.
 */
public final class TokenUsageHandler implements ObservationHandler<Observation.Context> {

  public static final String TOKEN_USAGE = "gen_ai.client.token.usage";

  private final MeterRegistry meters;

  public TokenUsageHandler(MeterRegistry meters) {
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return ObservedInferenceProvider.DURATION.equals(context.getName());
  }

  @Override
  public void onStop(Observation.Context context) {
    Usage usage = context.get(Usage.class);
    if (usage == null || !usage.known()) {
      return;
    }
    sample(context, "input", usage.inputTokens());
    sample(context, "output", usage.outputTokens());
  }

  private void sample(Observation.Context context, String type, long tokens) {
    DistributionSummary.Builder summary =
        DistributionSummary.builder(TOKEN_USAGE).baseUnit("token").tag("gen_ai.token.type", type);
    for (KeyValue tag : context.getLowCardinalityKeyValues()) {
      if (tag.getKey().startsWith("gen_ai.") && !tag.getKey().startsWith("gen_ai.response")) {
        summary.tag(tag.getKey(), tag.getValue());
      }
    }
    summary.register(meters).record(tokens);
  }
}
