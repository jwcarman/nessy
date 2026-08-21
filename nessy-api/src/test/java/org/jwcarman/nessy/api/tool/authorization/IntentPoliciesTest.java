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
package org.jwcarman.nessy.api.tool.authorization;

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
    void denies_when_no_declaration_is_present() {
      UsagePolicy<Object> policy = IntentPolicies.requireDeclared(Restart.class);

      PolicyDecision decision = policy.evaluate(freshContext(), CALL);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("declare-intent");
    }

    @Test
    void denies_when_the_declaration_on_the_context_is_the_wrong_type() {
      UsagePolicy<Object> policy = IntentPolicies.requireDeclared(Restart.class);
      AuthzContext context =
          freshContext().with(AuthzContext.DECLARED_INTENT_KEY, new OtherIntent("note"));

      PolicyDecision decision = policy.evaluate(context, CALL);

      assertThat(decision).isInstanceOf(PolicyDecision.Deny.class);
      String reason = ((PolicyDecision.Deny) decision).reason();
      assertThat(reason).contains("declare-intent");
    }

    @Test
    void allows_when_a_declaration_of_the_right_type_is_present() {
      UsagePolicy<Object> policy = IntentPolicies.requireDeclared(Restart.class);
      AuthzContext context =
          freshContext().with(AuthzContext.DECLARED_INTENT_KEY, new Restart("prod-eu"));

      assertThat(policy.evaluate(context, CALL)).isEqualTo(new PolicyDecision.Allow());
    }
  }

  @Nested
  class Report_rendering {

    @Test
    void names_its_own_class_for_the_authorization_report() {
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
