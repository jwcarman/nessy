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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Approvers;
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

  private static final Key<String> TRAIL = new Key<>(String.class, "trail");

  private final ObjectMapper mapper = new ObjectMapper();

  private static ToolCall callFor(String name) {
    return new ToolCall("c1", name, JsonNodeFactory.instance.objectNode());
  }

  private ApprovalRequest requestFor(ToolGrant grant, String toolName, Object input) {
    return grant.request("agent", "scope-1", callFor(toolName), input, mapper);
  }

  @Test
  void requestCarriesTheCoordinatesTheCallAndTheContributedAction() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, Approvers.allow());

    ApprovalRequest request = requestFor(grant, "greet", new GreetInput("Ada"));

    assertThat(request.agentType()).isEqualTo("agent");
    assertThat(request.agentId()).isEqualTo("scope-1");
    assertThat(request.call().name()).isEqualTo("greet");
    assertThat(request.action()).isEqualTo("greet Ada");
  }

  @Test
  void enrichersRunInOrderEachSeeingWhatTheOneBeforeItDeposited() {
    Enricher first = draft -> draft.deposit(TRAIL, "A");
    Enricher second = draft -> draft.deposit(TRAIL, "A|B");
    ToolGrant grant =
        ToolGrant.grant(new GreetTool(), GREETING, List.of(first, second), Approvers.allow());

    ApprovalRequest request = requestFor(grant, "greet", new GreetInput("Ada"));

    assertThat(request.facts().get(TRAIL)).contains("A|B");
  }

  @Test
  void theTypedNoEnrichersDoorWeldsTheContributorWithoutRunningAnyEnricher() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, Approvers.allow());

    ApprovalRequest request = requestFor(grant, "greet", new GreetInput("Ada"));

    assertThat(request.action()).isEqualTo("greet Ada");
    assertThat(grant.enrichers()).isEmpty();
    assertThat(request.facts().names()).isEmpty();
  }

  @Test
  void theUntypedDoorRendersTheDefaultContributorsStringValueOf() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), Approvers.deny("no"));

    ApprovalRequest request = requestFor(grant, "greet", new GreetInput("Ada"));

    assertThat(request.action()).isEqualTo(String.valueOf(new GreetInput("Ada")));
  }

  @Test
  void aRicherActionTypeIsRenderedAsItsOwnStringValue() {
    record GreetAction(String who) {}
    ActionContributor<GreetInput, GreetAction> richer = input -> new GreetAction(input.name());
    ToolGrant grant = ToolGrant.grant(new GreetTool(), richer, Approvers.allow());

    ApprovalRequest request = requestFor(grant, "greet", new GreetInput("Ada"));

    assertThat(request.action()).isEqualTo(String.valueOf(new GreetAction("Ada")));
  }

  @Test
  void aThrowingContributorFailsClosedNamingTheActionStage() {
    ToolGrant grant =
        ToolGrant.grant(
            new ThrowingActionTool(), THROWING_CONTRIBUTOR, List.of(), Approvers.allow());
    var input = new GreetInput("Ada");

    assertThatThrownBy(() -> requestFor(grant, "throws_on_action", input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: boom")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aThrowingEnricherFailsClosedNamingTheEnricherStage() {
    Enricher boom =
        draft -> {
          throw new IllegalStateException("kaboom");
        };
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, List.of(boom), Approvers.allow());
    var input = new GreetInput("Ada");

    assertThatThrownBy(() -> requestFor(grant, "greet", input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("enricher stage #0: kaboom")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void aThrowingEnricherNamesItsDisplayNameOverTheIndex() {
    Enricher boom =
        Enricher.named(
            "quota-check",
            draft -> {
              throw new IllegalStateException("kaboom");
            });
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, List.of(boom), Approvers.allow());
    var input = new GreetInput("Ada");

    assertThatThrownBy(() -> requestFor(grant, "greet", input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("enricher stage quota-check: kaboom");
  }

  @Test
  void theNoEnrichersTypedDoorRejectsANullContributor() {
    var tool = new GreetTool();

    assertThatThrownBy(() -> ToolGrant.grant(tool, null, Approvers.allow()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("contributor");
  }

  @Test
  void theFullyWiredTypedDoorRejectsANullContributor() {
    var tool = new GreetTool();

    assertThatThrownBy(() -> ToolGrant.grant(tool, null, List.of(), Approvers.allow()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("contributor");
  }

  @Test
  void everyDoorRejectsANullApprover() {
    var tool = new GreetTool();

    assertThatThrownBy(() -> ToolGrant.grant(tool, GREETING, List.of(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("approver");
  }

  @Test
  void aContributorReturningNullFailsClosedNamingTheActionStage() {
    ActionContributor<GreetInput, String> returnsNull = input -> null;
    ToolGrant grant = ToolGrant.grant(new GreetTool(), returnsNull, List.of(), Approvers.allow());
    var input = new GreetInput("Ada");

    assertThatThrownBy(() -> requestFor(grant, "greet", input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: ");
  }

  @Test
  void aWrongTypedInputFailsClosedNamingTheActionStage() {
    ToolGrant grant = ToolGrant.grant(new GreetTool(), GREETING, List.of(), Approvers.allow());

    assertThatThrownBy(() -> requestFor(grant, "greet", "not a GreetInput"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("action stage: ");
  }
}
