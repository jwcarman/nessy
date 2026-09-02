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
package org.jwcarman.nessy.examples.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.approval.policy.PolicyApprover;
import org.jwcarman.nessy.approval.policy.PolicyEngine;
import org.jwcarman.nessy.approval.policy.Verdict;

/**
 * The same gate, with the rules in Java instead of Rego.
 *
 * <p><b>There is no module for this and there does not need to be.</b> {@code PolicyEngine} is one
 * method returning a {@code Verdict}, so an in-process policy is a lambda — no engine to deploy, no
 * Docker, no network, and this test needs none of them.
 *
 * <p>What it gives up is the whole point of externalizing: these rules ship when the application
 * ships, and nobody who owns the risk can read them without reading Java. What it KEEPS is
 * everything else — the three verdicts, delegation to a named approver, and the fail-closed
 * discipline.
 *
 * <p><b>And it is the same seam</b>, which is the real argument for starting here. Moving to OPA
 * later swaps the engine and changes nothing else: the approver, the allowlist and every delegate
 * stay exactly as they are.
 */
@DisplayName("A gate whose rules are Java")
class RulesInJavaTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
  private static final Set<String> READ_ONLY = Set.of("disk_usage", "containers", "days_until");

  /** The whole policy. Compare the Rego in nessy.rego — the same three decisions. */
  private static final PolicyEngine RULES =
      request -> {
        String target = request.arguments().path("target").asText("");
        if (target.startsWith("prod-")) {
          return new Verdict.Delegate(
              "humans",
              JsonNodeFactory.instance
                  .objectNode()
                  .put("policy.term", "PT72H")
                  .put("policy.reason", request.toolName() + " targets production"));
        }
        if (READ_ONLY.contains(request.toolName())) {
          return Verdict.approve();
        }
        if (request.toolName().equals("prune_images")
            && request.agentType().name().equals("watchman")) {
          return Verdict.approve();
        }
        return Verdict.deny("no rule allowed this");
      };

  private final List<ApprovalRequest> desk = new CopyOnWriteArrayList<>();

  private Approver gate() {
    Approver humans =
        request -> {
          desk.add(request);
          Duration term =
              request
                  .fact("policy.term")
                  .map(node -> Duration.parse(node.asText()))
                  .orElse(Duration.ofHours(1));
          return Awaited.deferred(NOW.plus(term));
        };
    return PolicyApprover.create(config -> config.engine(RULES).delegate("humans", humans));
  }

  private static ApprovalRequest asking(String agentType, String tool, String target) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("target", target);
    return new ApprovalRequest(
        AgentType.of(agentType),
        AgentId.of("house-12"),
        TurnId.of("turn-1"),
        CallId.of("call-1"),
        tool,
        arguments,
        tool + " on " + target,
        NOW,
        () -> ReplyToken.of("a-capability"),
        JsonNodeFactory.instance.objectNode());
  }

  @Test
  void a_read_only_call_runs() {
    assertThat(gate().approve(asking("watchman", "disk_usage", "staging-1")))
        .isEqualTo(Awaited.ready(ApprovalResult.approved()));
    assertThat(desk).isEmpty();
  }

  @Test
  void an_unpermitted_call_is_denied_without_troubling_anybody() {
    var answer = gate().approve(asking("chat", "prune_images", "staging-1"));

    assertThat(((Awaited.Ready<ApprovalResult>) answer).result())
        .isEqualTo(ApprovalResult.denied("no rule allowed this"));
    assertThat(desk).isEmpty();
  }

  @Test
  @DisplayName("production still routes to a person, for the term the rules named")
  void a_production_call_still_delegates() {
    var answer = gate().approve(asking("watchman", "prune_images", "prod-eu-1"));

    assertThat(answer).isEqualTo(Awaited.deferred(NOW.plus(Duration.ofDays(3))));
    assertThat(desk.getFirst().fact("policy.reason").orElseThrow().asText())
        .isEqualTo("prune_images targets production");
  }

  @Test
  @DisplayName("a rule that throws is a broken gate, not an open one")
  void a_policy_that_blows_up_still_denies() {
    PolicyEngine broken =
        request -> {
          throw new IllegalStateException("somebody dereferenced a null");
        };
    var gate = PolicyApprover.create(config -> config.engine(broken));

    var answer = gate.approve(asking("watchman", "disk_usage", "staging-1"));

    assertThat(((Awaited.Ready<ApprovalResult>) answer).result())
        .isInstanceOf(ApprovalResult.Denied.class);
  }
}
