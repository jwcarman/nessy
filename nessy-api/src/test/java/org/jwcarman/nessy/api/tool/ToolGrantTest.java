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

  static final class GreetTool implements Tool<GreetInput> {
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
    public Awaited<ToolResult> execute(GreetInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("hi"));
    }
  }

  private static final ActionContributor<GreetInput, String> GREETING =
      input -> "greet " + input.name();

  static final class ThrowingActionTool implements Tool<GreetInput> {
    @Override
    public String name() {
      return "throws_on_action";
    }

    @Override
    public String description() {
      return "always fails rendering its action";
    }

    @Override
    public Class<GreetInput> inputType() {
      return GreetInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(GreetInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("hi"));
    }
  }

  private static final ActionContributor<GreetInput, String> THROWING_CONTRIBUTOR =
      input -> {
        throw new IllegalArgumentException("boom");
      };

  private static ToolCall callFor(String name) {
    return new ToolCall("c1", name, JsonNodeFactory.instance.objectNode());
  }

  @Test
  void theWeldedJudgmentRunsEnrichersThenPolicyOverTheContributedAction() {
    Key<String> seen = new Key<>(String.class, "seenAction");
    Tool<GreetInput> tool = new GreetTool();
    Enricher<String> recorder = (context, action) -> context.with(seen, action);
    UsagePolicy<String> policy =
        UsagePolicy.of(
            (context, action) ->
                new PolicyDecision.Deny("saw " + context.get(seen).orElse("nothing")));
    ToolGrant grant = ToolGrant.grant(tool, GREETING, List.of(recorder), policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("saw greet Ada"));
    assertThat(judged.action()).isEqualTo("greet Ada");
    assertThat(judged.context().get(seen)).contains("greet Ada");
  }

  private static final Key<String> SAW_VIA_ACTION = new Key<>(String.class, "sawViaAction");

  @Test
  void theActionIsDepositedUnderTheActionKeyBeforeEnrichersRun() {
    Tool<GreetInput> tool = new GreetTool();
    Enricher<String> readsActionFromContext =
        (context, action) -> context.with(SAW_VIA_ACTION, (String) context.action().orElse(null));
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());
    ToolGrant grant = ToolGrant.grant(tool, GREETING, List.of(readsActionFromContext), policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.context().get(SAW_VIA_ACTION)).contains("greet Ada");
    assertThat(judged.context().action()).contains("greet Ada");
  }

  @Test
  void enrichersRunInOrderEachThreadingTheContextItWasHandedIntoTheNext() {
    Key<String> seen = new Key<>(String.class, "seenAction");
    Tool<GreetInput> tool = new GreetTool();
    Enricher<String> first = (context, action) -> context.with(seen, action);
    Enricher<String> second =
        (context, action) -> context.with(seen, context.get(seen).orElse("") + "|B");
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());
    ToolGrant grant = ToolGrant.grant(tool, GREETING, List.of(first, second), policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.context().get(seen)).contains("greet Ada|B");
  }

  @Test
  void theTypedNoEnrichersDoorWeldsTheContributorWithoutRunningAnyEnricher() {
    Tool<GreetInput> tool = new GreetTool();
    UsagePolicy<String> policy =
        UsagePolicy.of((context, action) -> new PolicyDecision.Deny("saw " + action));
    ToolGrant grant = ToolGrant.grant(tool, GREETING, policy);

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("saw greet Ada"));
    assertThat(judged.action()).isEqualTo("greet Ada");
    assertThat(grant.enrichers()).isEmpty();
  }

  @Test
  void theUntypedDoorJudgesOverTheDefaultContributorsStringValueOf() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), UsagePolicy.deny("no"));

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.decision()).isEqualTo(new PolicyDecision.Deny("no"));
    assertThat(judged.action()).isEqualTo(String.valueOf(new GreetInput("Ada")));
  }

  @Test
  void theUntypedDoorsDefaultContributorDepositsUnderTheActionKeyToo() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), UsagePolicy.requireApproval());

    ToolGrant.Judged judged =
        grant.judgment().decide(AuthzContext.of("agent", callFor("greet")), new GreetInput("Ada"));

    assertThat(judged.context().action()).contains(String.valueOf(new GreetInput("Ada")));
  }

  @Test
  void aThrowingContributorFailsClosedNamingTheActionStage() {
    ToolGrant grant =
        ToolGrant.grant(
            new ThrowingActionTool(), THROWING_CONTRIBUTOR, List.of(), UsagePolicy.allow());
    AuthzContext context = AuthzContext.of("agent", callFor("throws_on_action"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: boom")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aThrowingEnricherFailsClosedNamingTheEnricherStage() {
    Enricher<String> boom =
        (context, action) -> {
          throw new IllegalStateException("kaboom");
        };
    ToolGrant grant =
        ToolGrant.grant(new GreetTool(), GREETING, List.of(boom), UsagePolicy.allow());
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
            (context, action) -> {
              throw new IllegalStateException("kaboom");
            });
    ToolGrant grant =
        ToolGrant.grant(new GreetTool(), GREETING, List.of(boom), UsagePolicy.allow());
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("enricher stage quota-check: kaboom");
  }

  @Test
  void aThrowingPolicyFailsClosedNamingThePolicyStage() {
    UsagePolicy<String> boom =
        UsagePolicy.of(
            (context, action) -> {
              throw new IllegalStateException("nope");
            });
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, List.of(), boom);
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("policy stage: nope")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void theNoEnrichersTypedDoorRejectsANullContributor() {
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());

    assertThatThrownBy(() -> ToolGrant.grant(new GreetTool(), null, policy))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("contributor");
  }

  @Test
  void theFullyWiredTypedDoorRejectsANullContributor() {
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());

    assertThatThrownBy(() -> ToolGrant.grant(new GreetTool(), null, List.of(), policy))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("contributor");
  }

  @Test
  void aContributorReturningNullFailsClosedNamingTheActionStage() {
    ActionContributor<GreetInput, String> returnsNull = input -> null;
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());
    ToolGrant grant = ToolGrant.grant(new GreetTool(), returnsNull, List.of(), policy);
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, new GreetInput("Ada")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: ");
  }

  @Test
  void aWrongTypedInputFailsClosedNamingTheActionStage() {
    UsagePolicy<String> policy = UsagePolicy.of((context, action) -> new PolicyDecision.Allow());
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, List.of(), policy);
    AuthzContext context = AuthzContext.of("agent", callFor("greet"));

    assertThatThrownBy(() -> grant.judgment().decide(context, "not a GreetInput"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: ");
  }
}
