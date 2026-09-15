package org.jwcarman.nessy.approval.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.approval.intent.Fixtures.AGENT;
import static org.jwcarman.nessy.approval.intent.Fixtures.MAPPER;
import static org.jwcarman.nessy.approval.intent.Fixtures.freshStore;
import static org.jwcarman.nessy.approval.intent.Fixtures.request;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

class IntentEnricherTest {

  @Test
  void it_records_the_latest_declaration_when_one_was_made() {
    var store = freshStore();
    store.declare(AGENT, new Intent("restart prod-eu to clear the stuck deploy"));
    var request = request();

    new IntentEnricher<>(store, MAPPER).enrich(request);

    // Read back through the same mapper that wrote it. Facts are JSON on the way through, so the
    // decode is the reader's job rather than a typed key's.
    assertThat(request.fact(IntentEnricher.DECLARED))
        .hasValueSatisfying(
            fact ->
                assertThat(MAPPER.convertValue(fact, Intent.class))
                    .isEqualTo(new Intent("restart prod-eu to clear the stuck deploy")));
  }

  @Test
  void it_records_only_the_latest_declaration() {
    var store = freshStore();
    store.declare(AGENT, new Intent("first"));
    store.declare(AGENT, new Intent("second"));
    var request = request();

    new IntentEnricher<>(store, MAPPER).enrich(request);

    assertThat(request.fact(IntentEnricher.DECLARED))
        .hasValueSatisfying(
            fact ->
                assertThat(MAPPER.convertValue(fact, Intent.class).declaration())
                    .isEqualTo("second"));
  }

  @Test
  void it_leaves_the_request_untouched_when_no_declaration_was_ever_made() {
    var request = request();

    new IntentEnricher<>(freshStore(), MAPPER).enrich(request);

    assertThat(request.facts().isEmpty()).isTrue();
    assertThat(request.fact(IntentEnricher.DECLARED)).isEmpty();
  }

  /**
   * The agent on the request is whose declaration is read, which is the whole point of per-call.
   */
  @Test
  void it_reads_the_declaration_of_the_agent_the_request_names_and_no_other() {
    var store = freshStore();
    store.declare(new AgentId(UUID.randomUUID()), new Intent("somebody else's plan"));
    ApprovalRequest request = request();

    new IntentEnricher<>(store, MAPPER).enrich(request);

    assertThat(request.fact(IntentEnricher.DECLARED)).isEmpty();
  }

  @Test
  void it_namespaces_its_fact_so_two_modules_cannot_collide() {
    assertThat(IntentEnricher.DECLARED).isEqualTo("intent.declared");
  }
}
