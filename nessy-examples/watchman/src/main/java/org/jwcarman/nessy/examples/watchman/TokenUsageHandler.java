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

  /**
   * The observation's Micrometer NAME — semconv's duration histogram for a provider-facing client
   * call, not the {@code chat {model}} SPAN name, which rides as the contextual name. A handler
   * matching on the span name here would match nothing (2026-08-26 semconv audit).
   */
  private static final String CHAT = "gen_ai.client.operation.duration";

  private static final String ERROR_TYPE = "error.type";
  private static final String NONE = "none";

  /**
   * Custom {@code gen_ai.token.type} values, permitted by the registry's own "otherwise, a custom
   * value MAY be used" — see {@link #recordIfPresent} for why neither {@code input} nor {@code
   * output} applies and why reusing {@code input} would have been the wrong answer.
   */
  private static final String CACHE_READ = "cache_read";

  private static final String CACHE_WRITE = "cache_write";

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
    recordIfPresent(context, provider, model, CACHE_READ, "gen_ai.usage.cache_read.input_tokens");
    recordIfPresent(context, provider, model, CACHE_WRITE, "gen_ai.usage.cache_write.input_tokens");
  }

  /**
   * The cache halves of the input count, when the provider reported them (final review, finding
   * #6).
   *
   * <p>{@code application.yml} asks for {@code prompt-caching} on purpose: a round resends the same
   * system prompt and the same tool schemas every thirty minutes, forever, which is the
   * prompt-cache case exactly. Without this, "did the cache actually work?" was answerable only by
   * opening individual chat spans in Tempo — never on a dashboard, never as a trend, and never over
   * the weeks the soak runs for.
   *
   * <p><b>These two series are a SUBSET of {@code input}, not additions to it.</b> The GenAI
   * conventions are explicit — for both {@code gen_ai.usage.cache_read.input_tokens} and {@code
   * gen_ai.usage.cache_write.input_tokens}, "the value SHOULD be included in {@code
   * gen_ai.usage.input_tokens}". So a panel that sums {@code gen_ai.client.token.usage} across all
   * values of {@code gen_ai.token.type} DOUBLE-COUNTS the cached tokens. Sum {@code input} and
   * {@code output} for spend; read {@code cache_read} against {@code input} for the hit rate.
   *
   * <p><b>{@code cache_read} and {@code cache_write} are custom values for {@code
   * gen_ai.token.type}</b>, and deliberately so. The registry's well-known list is exactly {@code
   * input} and {@code output} — checked against {@code gen-ai-metrics.md} at {@code main} rather
   * than assumed — and it says plainly: "If one of them applies, then the respective value MUST be
   * used; otherwise, a custom value MAY be used." Neither applies to a cache count, so these are
   * the permitted custom case rather than an invention working around the spec. Recording them as
   * extra {@code input} series instead would have been the actually non-conformant choice, because
   * it would double the input number.
   */
  private void recordIfPresent(
      Observation.Context context, String provider, String model, String tokenType, String key) {
    KeyValue tokens = context.getHighCardinalityKeyValue(key);
    if (tokens != null) {
      record(provider, model, tokenType, tokens.getValue());
    }
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
