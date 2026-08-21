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
package org.jwcarman.nessy.agent.intent;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.spi.intent.IntentStore;

/**
 * Reads the {@link IntentStore} this enricher was built over and deposits its latest declaration
 * under {@link AuthzContext#DECLARED_INTENT_KEY} — the claim a policy may read back through {@link
 * AuthzContext#declaredIntent()}. Absent a declaration, the context passes through untouched: a
 * missing claim is not this enricher's failure to report, only a policy's own choice to weigh.
 *
 * <p>Carries no type parameter of its own (vocabulary amendment §3): it deposits whatever {@link
 * IntentStore#latest()} yields, untyped, since {@link AuthzContext#DECLARED_INTENT_KEY} holds any
 * declaration by {@link Object}. Typed recovery is a policy's own concern, through {@link
 * AuthzContext#declaredIntent(Class)}.
 */
public final class IntentEnricher implements Enricher {

  private final IntentStore<?> store;

  public IntentEnricher(IntentStore<?> store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  @Override
  public AuthzContext enrich(AuthzContext context) {
    Optional<?> declared = store.latest();
    return declared
        .map(intent -> context.with(AuthzContext.DECLARED_INTENT_KEY, intent))
        .orElse(context);
  }

  @Override
  public Optional<String> displayName() {
    return Optional.of("intent");
  }
}
