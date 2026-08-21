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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

class IntentEnricherTest {

  private static AuthzContext freshContext() {
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    return AuthzContext.of("ops", call);
  }

  @Test
  void it_is_named_intent_for_the_authorization_report() {
    var enricher = new IntentEnricher(new InMemoryIntentStore());

    assertThat(enricher.displayName()).contains("intent");
  }

  @Test
  void it_deposits_the_latest_declaration_when_one_was_recorded() {
    var store = new InMemoryIntentStore();
    store.record(new Intent("restart prod-eu to clear the stuck deploy"));
    var enricher = new IntentEnricher(store);

    AuthzContext enriched = enricher.enrich(freshContext(), "restart prod-eu");

    assertThat(enriched.declaredIntent())
        .contains(new Intent("restart prod-eu to clear the stuck deploy"));
  }

  @Test
  void it_leaves_the_context_untouched_when_no_declaration_was_ever_recorded() {
    var enricher = new IntentEnricher(new InMemoryIntentStore());
    var context = freshContext();

    AuthzContext enriched = enricher.enrich(context, "restart prod-eu");

    assertThat(enriched.declaredIntent()).isEmpty();
    assertThat(enriched).isEqualTo(context);
  }
}
