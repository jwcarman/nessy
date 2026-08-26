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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentEnricherTest {

  /** A plainly-pinned mapper — tolerant reads, same as the substrate's format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static ApprovalRequest.Draft freshDraft() {
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    return ApprovalRequest.draft("ops", "agent-a", call, Map.of(), MAPPER);
  }

  private static SubstrateIntentStore<Intent> freshStore() {
    return new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER);
  }

  @Test
  void itIsNamedIntentForTheAuthorizationReport() {
    var enricher = new IntentEnricher<>(freshStore(), Intent.class);

    assertThat(enricher.displayName()).contains("intent");
  }

  @Test
  void itDepositsTheLatestDeclarationWhenOneWasRecorded() {
    var store = freshStore();
    store.declare(new Intent("restart prod-eu to clear the stuck deploy"));
    var enricher = new IntentEnricher<>(store, Intent.class);
    var draft = freshDraft();

    enricher.enrich(draft);

    assertThat(draft.freeze().facts().get(IntentEnricher.declared(Intent.class)))
        .contains(new Intent("restart prod-eu to clear the stuck deploy"));
  }

  @Test
  void itLeavesTheDraftUntouchedWhenNoDeclarationWasEverRecorded() {
    var enricher = new IntentEnricher<>(freshStore(), Intent.class);
    var draft = freshDraft();

    enricher.enrich(draft);

    ApprovalRequest request = draft.freeze();
    assertThat(request.facts().names()).isEmpty();
    assertThat(request.facts().get(IntentEnricher.declared(Intent.class))).isEmpty();
  }
}
