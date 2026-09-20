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

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Objects;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;

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
