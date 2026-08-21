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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  static final class ThrowingEffectTool implements EffectfulTool<GreetInput, String> {
    @Override
    public String name() {
      return "throws_on_effect";
    }

    @Override
    public String description() {
      return "always fails rendering its effect";
    }

    @Override
    public Class<GreetInput> inputType() {
      return GreetInput.class;
    }

    @Override
    public String effect(GreetInput input) {
      throw new IllegalArgumentException("boom");
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
    assertThat(judged.context().get(seen)).contains("greet Ada");
  }

  @Test
  void enrichersRunInOrderEachThreadingTheContextItWasHandedIntoTheNext() {
    Key<String> seen = new Key<>(String.class, "seenEffect");
    EffectfulTool<GreetInput, String> tool = new GreetTool();
    Enricher<String> first = (context, effect) -> context.with(seen, effect);
    Enricher<String> second =
        (context, effect) -> context.with(seen, context.get(seen).orElse("") + "|B");
    UsagePolicy<String> policy = UsagePolicy.of((context, effect) -> new PolicyDecision.Allow());
    ToolGrant grant = ToolGrant.grant(tool, List.of(first, second), policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.context().get(seen)).contains("greet Ada|B");
  }

  @Test
  void theUntypedDoorJudgesOverTheToolsOwnEffect() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), UsagePolicy.deny("no"));

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("no"));
  }

  @Test
  void aThrowingEffectFailsClosedNamingTheEffectStage() {
    ToolGrant grant = ToolGrant.grant(new ThrowingEffectTool(), List.of(), UsagePolicy.allow());
    AuthzContext context = AuthzContext.of("agent", callFor("throws_on_effect"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("effect stage: boom")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aThrowingEnricherFailsClosedNamingTheEnricherStage() {
    Enricher<String> boom =
        (context, effect) -> {
          throw new IllegalStateException("kaboom");
        };
    ToolGrant grant = ToolGrant.grant(new GreetTool(), List.of(boom), UsagePolicy.allow());
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("enricher stage #0: kaboom")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void aThrowingEnricherNamesItsDisplayNameOverTheIndex() {
    Enricher<String> boom =
        Enricher.named(
            "quota-check",
            (context, effect) -> {
              throw new IllegalStateException("kaboom");
            });
    ToolGrant grant = ToolGrant.grant(new GreetTool(), List.of(boom), UsagePolicy.allow());
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("enricher stage quota-check: kaboom");
  }

  @Test
  void aThrowingPolicyFailsClosedNamingThePolicyStage() {
    UsagePolicy<String> boom =
        UsagePolicy.of(
            (context, effect) -> {
              throw new IllegalStateException("nope");
            });
    ToolGrant grant = ToolGrant.grant(new GreetTool(), List.of(), boom);
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("policy stage: nope")
        .hasCauseInstanceOf(IllegalStateException.class);
  }
}
