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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Rule;

class IntentRulesTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ToolCall CALL =
      new ToolCall(
          "c1", "restart_prod", JsonNodeFactory.instance.objectNode().put("target", "prod-eu"));

  private static ApprovalRequest.Draft freshDraft() {
    return ApprovalRequest.draft("ops", "agent-a", CALL, MAPPER);
  }

  @Test
  void aDeclaredIntentPassesTheLadderOnUndecided() {
    ApprovalRequest request =
        freshDraft()
            .deposit(IntentEnricher.declared(Intent.class), new Intent("clear it"))
            .freeze();

    Rule.Verdict verdict = IntentRules.requireDeclared(Intent.class).judge(request);

    assertThat(verdict).isEqualTo(new Rule.Verdict.Undecided());
  }

  @Test
  void anUndeclaredIntentDeniesNamingTheVocabularyAndTheTool() {
    ApprovalRequest request = freshDraft().freeze();

    Rule.Verdict verdict = IntentRules.requireDeclared(Intent.class).judge(request);

    assertThat(verdict)
        .isInstanceOf(Rule.Verdict.Answered.class)
        .extracting(v -> ((Rule.Verdict.Answered) v).approval())
        .isInstanceOf(Approval.Denied.class)
        .extracting(a -> ((Approval.Denied) a).reason())
        .asString()
        .contains("no Intent declared")
        .contains("declare-intent");
  }

  @Test
  void theRuleNamesItselfForTheLadder() {
    assertThat(IntentRules.requireDeclared(Intent.class).displayName()).contains("intent declared");
  }

  @Test
  void aNullVocabularyIsRefused() {
    assertThatThrownBy(() -> IntentRules.requireDeclared(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("vocabulary");
  }
}
