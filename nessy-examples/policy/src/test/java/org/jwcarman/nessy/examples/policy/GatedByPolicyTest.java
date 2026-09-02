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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
import org.jwcarman.nessy.approval.policy.opa.OpaPolicyEngine;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * The whole stack, wired the way an application would wire it.
 *
 * <p>This is what the example is FOR now. The pieces are library code — {@code OpaPolicyEngine}
 * asks OPA, {@code PolicyApprover} carries out the verdict — so what is left here is the only part
 * that is about a particular deployment: WHICH policy, and WHO the policy is allowed to name.
 *
 * <p>The example used to carry its own copy of the adapter. It does not any more, which is the
 * point: an example that reimplements the library is not showing you how to use the library.
 */
@Testcontainers
@Tag("container")
@DisplayName("A tool call gated by a policy")
class GatedByPolicyTest {

  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

  @Container
  static final GenericContainer<?> OPA =
      new GenericContainer<>("openpolicyagent/opa:0.68.0")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("policy/nessy.rego"), "/policy/nessy.rego")
          .withCommand("run", "--server", "--addr=0.0.0.0:8181", "/policy")
          .withExposedPorts(8181)
          .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  /** What reached a person, so a test can see whether anybody was troubled. */
  private final List<ApprovalRequest> desk = new ArrayList<>();

  private Approver gate() {
    var opa =
        OpaPolicyEngine.create(
            policy ->
                policy
                    .url("http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181))
                    .decisionPath("nessy/tools/decision"));
    Approver humans =
        request -> {
          desk.add(request);
          // The term came from the POLICY: "production waits three days" is a sentence in Rego,
          // not a constant here. A desk told nothing falls back to its own default.
          Duration term =
              request
                  .fact("policy.term")
                  .map(node -> Duration.parse(node.asText()))
                  .orElse(Duration.ofHours(1));
          return Awaited.deferred(NOW.plus(term));
        };
    return PolicyApprover.create(config -> config.engine(opa).delegate("humans", humans));
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

  private static ApprovalResult resultOf(Awaited<ApprovalResult> answer) {
    return ((Awaited.Ready<ApprovalResult>) answer).result();
  }

  @Test
  void a_read_only_call_runs_without_troubling_anybody() {
    assertThat(gate().approve(asking("watchman", "disk_usage", "staging-1")))
        .isEqualTo(Awaited.ready(ApprovalResult.approved()));
    assertThat(desk).as("nobody should have been asked").isEmpty();
  }

  @Test
  @DisplayName("a call the policy refuses costs nobody's attention")
  void an_unpermitted_call_is_denied_by_the_policy_alone() {
    var answer = gate().approve(asking("chat", "prune_images", "staging-1"));

    assertThat(resultOf(answer)).isEqualTo(ApprovalResult.denied("no rule allowed this"));
    assertThat(desk).isEmpty();
  }

  @Test
  @DisplayName("a production call parks for a person, for the three days the policy asked for")
  void a_production_call_reaches_the_desk() {
    var answer = gate().approve(asking("watchman", "prune_images", "prod-eu-1"));

    assertThat(answer).isEqualTo(Awaited.deferred(NOW.plus(Duration.ofDays(3))));
    assertThat(desk).hasSize(1);
    assertThat(desk.getFirst().fact("policy.reason").orElseThrow().asText())
        .isEqualTo("prune_images targets production");
  }
}
