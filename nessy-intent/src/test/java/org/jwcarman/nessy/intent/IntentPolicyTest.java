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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalContext;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentPolicyTest {

  /** Nothing in these tests answers a deferred question, so the address is never read. */
  private static final ApprovalContext NOWHERE = () -> new ReplyToken("nowhere");

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
  void an_undeclared_call_is_denied_and_told_how_to_proceed() {
    var policy =
        IntentPolicy.requireDeclared(new IntentEnricher<>(freshStore(), MAPPER), Approver.always());

    Awaited<ApprovalResult> result = policy.approve(freshRequest(), NOWHERE);

    assertThat(result).isInstanceOf(Awaited.Ready.class);
    var answer = ((Awaited.Ready<ApprovalResult>) result).result();
    assertThat(answer).isInstanceOf(ApprovalResult.Denied.class);
    assertThat(((ApprovalResult.Denied) answer).reason()).contains("declare-intent");
  }

  @Test
  void a_declared_call_passes_to_the_approver_it_guards() {
    var store = freshStore();
    store.declare(new Intent("restart prod-eu to clear the stuck deploy"));
    var policy =
        IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), Approver.always());

    Awaited<ApprovalResult> result = policy.approve(freshRequest(), NOWHERE);

    assertThat(result).isEqualTo(Awaited.ready(ApprovalResult.approved()));
  }

  @Test
  void it_never_approves_on_its_own_only_defers_to_what_it_guards() {
    var store = freshStore();
    store.declare(new Intent("declared, but still not allowed"));
    Approver alwaysDenies =
        (request, context) -> Awaited.ready(ApprovalResult.denied("policy says no"));
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), alwaysDenies);

    Awaited<ApprovalResult> result = policy.approve(freshRequest(), NOWHERE);

    // A declaration is a precondition, never a reason to allow: the guarded approver still rules.
    assertThat(result).isEqualTo(Awaited.ready(ApprovalResult.denied("policy says no")));
  }

  @Test
  void the_guarded_approver_never_runs_when_nothing_was_declared() {
    var calls = new AtomicInteger();
    Approver counting =
        (request, context) -> {
          calls.incrementAndGet();
          return Awaited.ready(ApprovalResult.approved());
        };
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(freshStore(), MAPPER), counting);

    policy.approve(freshRequest(), NOWHERE);

    assertThat(calls).hasValue(0);
  }

  @Test
  void the_guarded_approver_sees_the_declaration_this_policy_recorded() {
    var store = freshStore();
    store.declare(new Intent("restart prod-eu"));
    Approver reader =
        (request, context) ->
            Awaited.ready(
                request.fact(IntentEnricher.DECLARED).isPresent()
                    ? ApprovalResult.approved()
                    : ApprovalResult.denied("the fact did not reach me"));
    var policy = IntentPolicy.requireDeclared(new IntentEnricher<>(store, MAPPER), reader);

    Awaited<ApprovalResult> result = policy.approve(freshRequest(), NOWHERE);

    assertThat(result).isEqualTo(Awaited.ready(ApprovalResult.approved()));
  }
}
