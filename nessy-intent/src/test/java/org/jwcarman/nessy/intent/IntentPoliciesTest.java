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
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationReport;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.GrantStory;

class IntentPoliciesTest {

  private record Restart(String target) {}

  private record OtherIntent(String note) {}

  private static final ToolCall CALL =
      new ToolCall("c1", "restart_prod", JsonNodeFactory.instance.objectNode());

  private static AuthzContext freshContext() {
    return AuthzContext.of("ops", CALL);
  }

  @Nested
  class Require_declared {

    @Test
    void deniesWhenNoDeclarationIsPresent() {
      UsagePolicy policy = IntentPolicies.requireDeclared(Restart.class);

      PolicyDecision decision = policy.evaluate(freshContext());

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("declare-intent");
    }

    @Test
    void deniesWhenTheDeclarationOnTheContextIsTheWrongType() {
      UsagePolicy policy = IntentPolicies.requireDeclared(Restart.class);
      AuthzContext context =
          freshContext().with(AuthzContext.DECLARED_INTENT_KEY, new OtherIntent("note"));

      PolicyDecision decision = policy.evaluate(context);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("declare-intent");
    }

    @Test
    void allowsWhenADeclarationOfTheRightTypeIsPresent() {
      UsagePolicy policy = IntentPolicies.requireDeclared(Restart.class);
      AuthzContext context =
          freshContext().with(AuthzContext.DECLARED_INTENT_KEY, new Restart("prod-eu"));

      assertThat(policy.evaluate(context)).isEqualTo(new PolicyDecision.Allow());
    }

    @Test
    void aRequireDeclaredPolicyIsNeverStatic() {
      UsagePolicy policy = IntentPolicies.requireDeclared(Restart.class);

      assertThat(policy).isNotInstanceOf(UsagePolicy.Static.class);
    }
  }

  @Nested
  class Report_rendering {

    @Test
    void namesItsOwnClassForTheAuthorizationReport() {
      ToolGrant grant =
          ToolGrant.grant(new NoOpTool(), IntentPolicies.requireDeclared(Restart.class));

      GrantStory story = AuthorizationReport.of(List.of(grant)).grants().getFirst();

      assertThat(story.policy()).isEqualTo("RequireDeclaredPolicy");
    }
  }

  record NoOpInput() {}

  static final class NoOpTool implements Tool<NoOpInput> {

    @Override
    public String name() {
      return "no-op";
    }

    @Override
    public String description() {
      return "Does nothing";
    }

    @Override
    public Class<NoOpInput> inputType() {
      return NoOpInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoOpInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("done"));
    }
  }
}
