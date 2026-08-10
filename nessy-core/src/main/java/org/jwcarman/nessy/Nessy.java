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

import org.jwcarman.nessy.spi.model.ModelProvider;

/**
 * The front door — the only one. A {@link Harness} is the infrastructure — provider, session store,
 * observations, object mapper — every agent it builds shares; {@code provider} is the harness's one
 * required thing, enforced right here by signature rather than discovered later at {@code build()}.
 *
 * <p>The razor: if a proposed harness feature could not be expressed as "pre-configuration of an
 * agent builder," it does not belong on the harness. A single agent is still just as short as ever
 * — {@code Nessy.harness(provider).agent().model(...).build()} — the harness is never optional
 * ceremony, only ever the one place infrastructure lives.
 */
public final class Nessy {

  private Nessy() {}

  public static HarnessBuilder harness(ModelProvider provider) {
    return new HarnessBuilder(provider);
  }
}
