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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentEnricherTest {

  /** A plainly-pinned mapper — tolerant reads, same as the substrate's format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static ApprovalRequest freshRequest() {
    var call =
        new ToolCall(
            "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));
    return new ApprovalRequest(
        AgentType.of("ops"), AgentId.of("agent-a"), call, "restart prod-eu", Instant.EPOCH);
  }

  private static SubstrateIntentStore<Intent> freshStore() {
    return new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER);
  }

  @Test
  void it_records_the_latest_declaration_when_one_was_made() {
    var store = freshStore();
    store.declare(new Intent("restart prod-eu to clear the stuck deploy"));
    var enricher = new IntentEnricher<>(store, MAPPER);
    var request = freshRequest();

    enricher.enrich(request);

    // Read back through the same mapper that wrote it. Facts are JSON on the way through, so the
    // decode is the reader's job now rather than a typed key's.
    assertThat(request.fact(IntentEnricher.DECLARED))
        .hasValueSatisfying(
            fact ->
                assertThat(MAPPER.convertValue(fact, Intent.class))
                    .isEqualTo(new Intent("restart prod-eu to clear the stuck deploy")));
  }

  @Test
  void it_records_only_the_latest_declaration() {
    var store = freshStore();
    store.declare(new Intent("first"));
    store.declare(new Intent("second"));
    var request = freshRequest();

    new IntentEnricher<>(store, MAPPER).enrich(request);

    assertThat(request.fact(IntentEnricher.DECLARED))
        .hasValueSatisfying(
            fact ->
                assertThat(MAPPER.convertValue(fact, Intent.class).declaration())
                    .isEqualTo("second"));
  }

  @Test
  void it_leaves_the_request_untouched_when_no_declaration_was_ever_made() {
    var enricher = new IntentEnricher<>(freshStore(), MAPPER);
    var request = freshRequest();

    enricher.enrich(request);

    assertThat(request.facts().isEmpty()).isTrue();
    assertThat(request.fact(IntentEnricher.DECLARED)).isEmpty();
  }

  @Test
  void it_namespaces_its_fact_so_two_modules_cannot_collide() {
    assertThat(IntentEnricher.DECLARED).isEqualTo("intent.declared");
  }
}
