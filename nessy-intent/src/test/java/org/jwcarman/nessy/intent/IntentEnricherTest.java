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
package org.jwcarman.nessy.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentEnricherTest {

  private static AuthzContext freshContext() {
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    return AuthzContext.of("ops", call);
  }

  private static StoredIntentStore<Intent> freshStore() {
    return new StoredIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class);
  }

  @Test
  void itIsNamedIntentForTheAuthorizationReport() {
    var enricher = new IntentEnricher(freshStore());

    assertThat(enricher.displayName()).contains("intent");
  }

  @Test
  void itDepositsTheLatestDeclarationWhenOneWasRecorded() {
    var store = freshStore();
    store.declare(new Intent("restart prod-eu to clear the stuck deploy"));
    var enricher = new IntentEnricher(store);

    AuthzContext enriched = enricher.enrich(freshContext());

    assertThat(enriched.declaredIntent())
        .contains(new Intent("restart prod-eu to clear the stuck deploy"));
  }

  @Test
  void itLeavesTheContextUntouchedWhenNoDeclarationWasEverRecorded() {
    var enricher = new IntentEnricher(freshStore());
    var context = freshContext();

    AuthzContext enriched = enricher.enrich(context);

    assertThat(enriched.declaredIntent()).isEmpty();
    assertThat(enriched).isEqualTo(context);
  }
}
