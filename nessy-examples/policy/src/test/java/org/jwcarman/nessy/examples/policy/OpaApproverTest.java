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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * The gate, against the engine that actually enforces it.
 *
 * <p>Rego is not Java and cannot be reasoned about from here: a rule that reads {@code
 * input.arguments.target} either sees the field or it does not, and the only honest way to know is
 * to run OPA. So this loads the shipped policy file — not a copy — into the real binary.
 *
 * <p>Tagged {@code container} and skipped by default, because {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Testcontainers
@Tag("container")
@DisplayName("A gate written in Rego")
class OpaApproverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  static final GenericContainer<?> OPA =
      new GenericContainer<>("openpolicyagent/opa:0.68.0")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("policy/nessy.rego"), "/policy/nessy.rego")
          .withCommand("run", "--server", "--addr=0.0.0.0:8181", "/policy")
          .withExposedPorts(8181)
          .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  private static OpaApprover approver() {
    return new OpaApprover(
        "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181), "nessy/tools", MAPPER);
  }

  private static ApprovalRequest asking(String agentType, String tool, ObjectNode arguments) {
    return new ApprovalRequest(
        AgentType.of(agentType),
        AgentId.of("house-12"),
        TurnId.of("turn-1"),
        CallId.of("call-1"),
        tool,
        arguments,
        tool + " " + arguments,
        Instant.parse("2026-09-02T12:00:00Z"),
        () -> ReplyToken.of("a-capability-no-policy-should-see"),
        JsonNodeFactory.instance.objectNode());
  }

  /** The reason a denial gives, or a failure if the answer was not a denial at all. */
  private static String denialOf(Awaited<ApprovalResult> answer) {
    ApprovalResult result = ((Awaited.Ready<ApprovalResult>) answer).result();
    if (result instanceof ApprovalResult.Denied denied) {
      return denied.reason();
    }
    throw new AssertionError("expected a denial, but the policy allowed it");
  }

  private static ObjectNode target(String value) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("target", value);
    return arguments;
  }

  @Nested
  @DisplayName("what the rules decide")
  class Decisions {

    @Test
    void a_read_only_tool_needs_nobody() {
      var answer = approver().approve(asking("watchman", "disk_usage", target("staging-1")));

      assertThat(answer).isEqualTo(Awaited.ready(ApprovalResult.approved()));
    }

    @Test
    @DisplayName("the same tool is allowed for one agent type and refused for another")
    void a_destructive_tool_is_scoped_to_the_agent_that_owns_it() {
      var forWatchman = approver().approve(asking("watchman", "prune_images", target("staging-1")));
      var forChat = approver().approve(asking("chat", "prune_images", target("staging-1")));

      assertThat(forWatchman).isEqualTo(Awaited.ready(ApprovalResult.approved()));
      assertThat(denialOf(forChat)).isNotBlank();
    }

    @Test
    @DisplayName("the rule reads the arguments, so the target is what decides")
    void production_is_refused_for_the_agent_that_is_otherwise_allowed() {
      var answer = approver().approve(asking("watchman", "prune_images", target("prod-eu-1")));

      assertThat(denialOf(answer)).isEqualTo("targets production; a person has to say yes");
    }

    @Test
    void a_tool_no_rule_mentions_is_refused_rather_than_ignored() {
      var answer = approver().approve(asking("watchman", "delete_everything", target("staging-1")));

      assertThat(denialOf(answer)).isNotBlank();
    }
  }

  @Nested
  @DisplayName("what the engine is told")
  class TheInput {

    @Test
    @DisplayName("the reply token never leaves the process")
    void the_document_carries_no_capability() {
      ApprovalRequest request = asking("watchman", "prune_images", target("prod-eu-1"));

      String document = approver().asInput(request).toString();

      // The token settles the call. A policy engine logs its input and is often somebody else's
      // service, so it is the one field that must not be there.
      assertThat(document).doesNotContain("a-capability-no-policy-should-see");
      assertThat(document).contains("prune_images", "prod-eu-1", "watchman", "house-12");
    }
  }

  @Nested
  @DisplayName("when the engine cannot be reached")
  class Unreachable {

    @Test
    @DisplayName("a broken control is not permission")
    void an_unreachable_policy_engine_denies() {
      var offline = new OpaApprover("http://127.0.0.1:1", "nessy/tools", MAPPER);

      var answer = offline.approve(asking("watchman", "disk_usage", target("staging-1")));

      assertThat(denialOf(answer)).isNotBlank();
    }
  }
}
