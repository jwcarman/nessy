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
package org.jwcarman.nessy;

import java.util.Objects;

/**
 * The front door — the only one. A {@link Harness} is the infrastructure — provider, session store,
 * observations, object mapper — every agent it builds shares; {@code provider} is the harness's one
 * required thing, set inside the customizer and validated the instant it returns (design of record
 * 2026-08-16 §1) rather than enforced by this method's own signature.
 *
 * <p>The razor: if a proposed harness feature could not be expressed as "pre-configuration of an
 * agent config," it does not belong on the harness. A single agent is still just as short as ever —
 * {@code Nessy.harness(h -> h.provider(provider)).agent(a -> a.name(...).model(...))} — the harness
 * is never optional ceremony, only ever the one place infrastructure lives.
 */
public final class Nessy {

  private Nessy() {}

  /**
   * Builds a {@link Harness} from a live {@link HarnessConfig}: {@code customizer} fills it in,
   * then this factory validates {@link
   * HarnessConfig#provider(org.jwcarman.nessy.spi.model.ModelProvider)} was called — the harness's
   * one required field — and constructs the finished {@link Harness}. No public {@code build()}
   * survives here; the factory is the only place a {@link HarnessConfig} ever turns into a {@link
   * Harness}.
   */
  public static Harness harness(HarnessCustomizer customizer) {
    Objects.requireNonNull(customizer, "customizer must not be null");
    HarnessConfig config = new HarnessConfig();
    customizer.customize(config);
    return config.build();
  }
}
