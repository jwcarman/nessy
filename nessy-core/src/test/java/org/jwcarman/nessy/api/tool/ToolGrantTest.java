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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;
import org.jwcarman.nessy.api.tool.authorization.Enricher;
import org.jwcarman.nessy.api.tool.authorization.Key;

class ToolGrantTest {

  record GreetInput(String name) {}

  static final class GreetTool implements EffectfulTool<GreetInput, String> {
    @Override
    public String name() {
      return "greet";
    }

    @Override
    public String description() {
      return "Greets somebody by name";
    }

    @Override
    public Class<GreetInput> inputType() {
      return GreetInput.class;
    }

    @Override
    public String effect(GreetInput input) {
      return "greet " + input.name();
    }

    @Override
    public Awaited<ToolResult> execute(GreetInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("hi"));
    }
  }

  private static ToolCall callFor(String name) {
    return new ToolCall("c1", name, JsonNodeFactory.instance.objectNode());
  }

  @Test
  void theWeldedJudgmentRunsEnrichersThenPolicyOverTheTypedEffect() {
    Key<String> seen = new Key<>(String.class, "seenEffect");
    EffectfulTool<GreetInput, String> tool = new GreetTool();
    Enricher<String> recorder = (context, effect) -> context.with(seen, effect);
    UsagePolicy<String> policy =
        UsagePolicy.of(
            (context, effect) ->
                new PolicyDecision.Deny("saw " + context.get(seen).orElse("nothing")));
    ToolGrant grant = ToolGrant.grant(tool, List.of(recorder), policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("saw greet Ada"));
    assertThat(judged.effect()).isEqualTo("greet Ada");
  }

  @Test
  void theUntypedDoorJudgesOverTheToolsOwnEffect() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), UsagePolicy.deny("no"));

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("no"));
  }
}
