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
package org.jwcarman.nessy.approval.policy.opa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.approval.policy.PolicyEngine;
import org.jwcarman.nessy.approval.policy.Verdict;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * The engine, against the binary that actually enforces the policy.
 *
 * <p>Rego cannot be reasoned about from Java: a rule either sees a field or it does not, and OPA's
 * habit of answering 200 to nearly everything means the interesting cases all look alike from a
 * distance. So this loads the shipped {@code nessy.rego} — not a copy — into the real binary.
 *
 * <p>Tagged {@code container} and skipped by default, because {@code clean verify} must pass with
 * no Docker. Run it with {@code ./mvnw test -Dnessy.excludedGroups=}.
 */
@Testcontainers
@Tag("container")
@DisplayName("A policy engine backed by OPA")
class OpaPolicyEngineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Container
  static final GenericContainer<?> OPA =
      new GenericContainer<>("openpolicyagent/opa:0.68.0")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("policy/nessy.rego"), "/policy/nessy.rego")
          .withCommand("run", "--server", "--addr=0.0.0.0:8181", "/policy")
          .withExposedPorts(8181)
          .waitingFor(Wait.forHttp("/health").forStatusCode(200));

  private static String baseUrl() {
    return "http://" + OPA.getHost() + ":" + OPA.getMappedPort(8181);
  }

  private static PolicyEngine engine() {
    return engine("nessy/tools/decision");
  }

  private static PolicyEngine engine(String path) {
    return OpaPolicyEngine.create(
        opa -> opa.url(baseUrl()).decisionPath(path).objectMapper(MAPPER));
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
        Instant.parse("2026-09-02T12:00:00Z"),
        () -> ReplyToken.of("a-capability-no-policy-should-see"),
        JsonNodeFactory.instance.objectNode());
  }

  @Nested
  @DisplayName("what the rules decide")
  class Decisions {

    @Test
    void a_read_only_tool_is_allowed() {
      assertThat(engine().decide(asking("watchman", "disk_usage", "staging-1")))
          .isEqualTo(Verdict.approve());
    }

    @Test
    @DisplayName("the same tool is allowed for one agent type and refused for another")
    void a_destructive_tool_is_scoped_to_the_agent_that_owns_it() {
      assertThat(engine().decide(asking("watchman", "prune_images", "staging-1")))
          .isEqualTo(Verdict.approve());
      assertThat(engine().decide(asking("chat", "prune_images", "staging-1")))
          .isEqualTo(Verdict.deny("no rule allowed this"));
    }

    @Test
    void an_explicit_denial_carries_the_policys_own_words() {
      assertThat(engine().decide(asking("watchman", "rm_rf", "staging-1")))
          .isEqualTo(Verdict.deny("never in this tenant"));
    }

    @Test
    @DisplayName("production routes to a person, for the term the policy named")
    void production_delegates_rather_than_deciding() {
      // The answer a boolean could not give. Both the destination and the term come from Rego.
      Verdict verdict = engine().decide(asking("watchman", "prune_images", "prod-eu-1"));

      assertThat(verdict).isInstanceOf(Verdict.Delegate.class);
      Verdict.Delegate delegate = (Verdict.Delegate) verdict;
      assertThat(delegate.to()).isEqualTo("humans");
      assertThat(delegate.facts().path("policy.term").asText()).isEqualTo("PT72H");
      assertThat(delegate.facts().path("policy.reason").asText())
          .isEqualTo("prune_images targets production");
    }

    @Test
    void a_tool_no_rule_mentions_is_refused_rather_than_ignored() {
      assertThat(engine().decide(asking("watchman", "delete_everything", "staging-1")))
          .isEqualTo(Verdict.deny("no rule allowed this"));
    }
  }

  @Nested
  @DisplayName("when the policy is not answering")
  class NotAnswering {

    @Test
    @DisplayName("a mistyped decision path throws rather than reading as a denial")
    void a_path_that_names_no_rule_is_a_broken_gate() {
      // Measured: OPA answers 200 with {} for a typo, for an undefined rule, and for a policy that
      // never loaded. Reading that as "no" denies everything, forever, in silence.
      PolicyEngine typo = engine("nessy/tools/decisionn");
      ApprovalRequest asking = asking("watchman", "disk_usage", "staging-1");

      assertThatThrownBy(() -> typo.decide(asking))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no decision at")
          .hasMessageContaining("the path is wrong");
    }

    @Test
    void an_unreachable_engine_throws() {
      PolicyEngine offline =
          OpaPolicyEngine.create(
              opa -> opa.url("http://127.0.0.1:1").decisionPath("nessy/tools/decision"));
      ApprovalRequest asking = asking("watchman", "disk_usage", "s");

      assertThatThrownBy(() -> offline.decide(asking))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("could not reach");
    }
  }

  @Nested
  @DisplayName("what the engine is told")
  class TheInput {

    @Test
    @DisplayName("the reply token never leaves the process")
    void the_document_carries_no_capability() {
      String document =
          InputRenderer.standard(MAPPER)
              .render(asking("watchman", "prune_images", "prod-eu-1"))
              .toString();

      // The token settles the call. A policy engine logs its input and is often somebody else's
      // service, so it is the one field that must not be there.
      assertThat(document)
          .doesNotContain("a-capability-no-policy-should-see")
          .contains("prune_images", "prod-eu-1", "watchman", "house-12");
    }

    @Test
    @DisplayName("the AuthZEN document keeps the token out too")
    void the_authzen_document_carries_no_capability() {
      String document =
          InputRenderer.authzen(MAPPER)
              .render(asking("watchman", "prune_images", "prod-eu-1"))
              .toString();

      assertThat(document)
          .doesNotContain("a-capability-no-policy-should-see")
          .contains("\"subject\"", "\"resource\"", "\"action\"", "house-12");
    }

    @Test
    @DisplayName("a policy can be written against a document of your own shape")
    void a_custom_renderer_is_what_the_policy_sees() {
      // This document has no toolName at all, so no rule can fire and the default answers.
      PolicyEngine mine =
          OpaPolicyEngine.create(
              opa ->
                  opa.url(baseUrl())
                      .decisionPath("nessy/tools/decision")
                      .renderer(request -> MAPPER.createObjectNode().put("whatever", "you like")));

      assertThat(mine.decide(asking("watchman", "disk_usage", "staging-1")))
          .isEqualTo(Verdict.deny("no rule allowed this"));
    }
  }
}
