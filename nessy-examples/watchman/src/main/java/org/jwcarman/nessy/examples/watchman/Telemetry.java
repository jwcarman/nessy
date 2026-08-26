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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one thing the application must contribute to its own observability (spec §2.3).
 *
 * <p>Everything else is Boot's: the actuator owns the {@code ObservationRegistry}, {@code
 * micrometer-tracing-bridge-otel} turns observations into OpenTelemetry spans, and the OTLP
 * registries ship both to the LGTM box, all steered from {@code management.otlp.*} and {@code
 * management.tracing.*}. The starter hands Boot's registry to the harness's seam, so every {@code
 * invoke_agent}, {@code chat} and tool span is already there.
 *
 * <p>What is NOT there is {@code gen_ai.client.token.usage}: an {@code ObservationRegistry} times
 * observations and cannot record a value histogram, so the vendor's token counts ride the {@code
 * chat} observation as key-values and something has to turn them into a metric. Per the o11y spec's
 * ruling that is an application's job, not {@code nessy-agent}'s — hence {@link TokenUsageHandler},
 * registered here on Boot's own registry rather than on one built by hand.
 */
@Configuration(proxyBeanMethods = false)
public class Telemetry {

  /** Adds the token-usage handler to whichever {@code ObservationRegistry} Boot built. */
  @Bean
  public ObservationRegistryCustomizer<ObservationRegistry> tokenUsage(MeterRegistry meters) {
    return registry ->
        registry.observationConfig().observationHandler(new TokenUsageHandler(meters));
  }
}
