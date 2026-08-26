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
package org.jwcarman.nessy.examples.watchman;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

/**
 * The semconv {@code gen_ai.client.token.usage} metric (agentic-o11y spec §1.2): an {@code
 * ObservationRegistry} times observations, it cannot record a value histogram, so the {@code chat}
 * observation carries the vendor's own token counts as key-values instead and this ten-line handler
 * is what turns them into the metric on {@code onStop}. A {@code chat} that failed before the model
 * reported any usage carries no such key-values — {@link #onStop} tolerates that silently rather
 * than throwing (agentic-o11y fix round 1: an {@code ObservationHandler} must never be the reason a
 * turn fails).
 *
 * <p>Copied from {@code nessy-examples/observed} rather than shared: the o11y spec's ruling is that
 * this handler belongs to applications, not to {@code nessy-agent}, and ten lines duplicated across
 * two examples is the honest cost of that. What differs here is only where it is registered — Boot
 * owns the {@code ObservationRegistry}, so {@link Telemetry} adds it through an {@code
 * ObservationRegistryCustomizer} instead of building a registry by hand.
 */
public final class TokenUsageHandler implements ObservationHandler<Observation.Context> {

  private static final String CHAT = "chat";
  private static final String ERROR_TYPE = "error.type";
  private static final String NONE = "none";

  private final MeterRegistry meterRegistry;

  public TokenUsageHandler(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return CHAT.equals(context.getName());
  }

  @Override
  public void onStop(Observation.Context context) {
    KeyValue errorType = context.getLowCardinalityKeyValue(ERROR_TYPE);
    KeyValue input = context.getHighCardinalityKeyValue("gen_ai.usage.input_tokens");
    KeyValue output = context.getHighCardinalityKeyValue("gen_ai.usage.output_tokens");
    if (errorType == null
        || !NONE.equals(errorType.getValue())
        || input == null
        || output == null) {
      return; // a failed chat reports no usage; nothing to record
    }
    String provider = valueOf(context.getLowCardinalityKeyValue("gen_ai.provider.name"));
    String model = valueOf(context.getLowCardinalityKeyValue("gen_ai.request.model"));
    record(provider, model, "input", input.getValue());
    record(provider, model, "output", output.getValue());
  }

  private void record(String provider, String model, String tokenType, String value) {
    DistributionSummary.builder("gen_ai.client.token.usage")
        .tag("gen_ai.token.type", tokenType)
        .tag("gen_ai.provider.name", provider)
        .tag("gen_ai.request.model", model)
        .register(meterRegistry)
        .record(Long.parseLong(value));
  }

  private static String valueOf(KeyValue keyValue) {
    return keyValue == null ? "unknown" : keyValue.getValue();
  }
}
