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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.ScriptedModel;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalOutcome;
import org.jwcarman.nessy.api.tool.approval.Approver;
import org.jwcarman.nessy.api.tool.approval.Approvers;
import org.jwcarman.nessy.spi.model.ModelEvent;

/**
 * {@code nessy.approval.seek} (in-the-loop amendment §2): the ask, measured.
 *
 * <p>The whole premise of the approval design is that an approver may do arbitrary work — call
 * Slack, ask a policy service, walk a rules ladder — and until this span existed all of that was a
 * gap inside {@code invoke_agent} with nothing in it. The span wraps the request construction, the
 * action contributor, every enricher and the approver call, holds a scope so anything the approver
 * touches nests inside it, and says what was DECIDED rather than only how long it took.
 *
 * <p>The outcome is mapped from the sealed {@code ApprovalOutcome}/{@code Approval} grammar with no
 * default arm, so a new variant fails this build rather than silently reading as something else.
 */
class ApprovalSeekSpanTest {

  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final String SEEK = "nessy.approval.seek restart";
  private static final String OUTCOME = "nessy.approval.outcome";

  private final TestObservationRegistry registry = TestObservationRegistry.create();

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  private List<Observation.Context> contexts() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    return captured;
  }

  private Observation.Context only(String contextualName) {
    List<Observation.Context> found =
        contexts().stream()
            .filter(context -> contextualName.equals(context.getContextualName()))
            .toList();
    assertThat(found).as("observations contextually named '%s'", contextualName).hasSize(1);
    return found.getFirst();
  }

  /** One turn whose single tool call is gated by {@code approver}, driven to quiescence. */
  private void turnGatedBy(Approver approver) {
    var pump = new PumpedExecutor();
    var restart = new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode());
    var model =
        new ScriptedModel(
            List.of(
                List.of(
                    new ModelEvent.ToolUseEmitted(restart, null),
                    new ModelEvent.TurnEnded(StopReason.TOOL_USE, new Usage(10, 2, 0, 0))),
                List.of(
                    new ModelEvent.TextChunk("done"),
                    new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(20, 3, 0, 0)))));

    Harness<String> harness =
        Nessy.harness(
            h ->
                h.model(model)
                    .systemPrompt(TestSettings.SYSTEM_PROMPT)
                    .settings(TestSettings.settings())
                    .executor(pump)
                    .observationRegistry(registry)
                    .grants(ToolGrant.grant(new EchoTool(), approver)));
    HarnessTeardown.track(harness);

    harness.bind(SCOPE).tell("restart prod-eu");
    pump.pumpUntilQuiet();
  }

  @Nested
  class TheOutcomeItRecords {

    @Test
    void an_allowing_approver_yields_approved() {
      turnGatedBy(context -> new ApprovalOutcome.Answered(Approval.approved()));

      Observation.Context seek = only(SEEK);
      assertThat(seek.getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("approved");
      assertThat(seek.getLowCardinalityKeyValue("error.type").getValue())
          .isEqualTo(KeyValue.NONE_VALUE);
      assertThat(seek.getLowCardinalityKeyValue("gen_ai.tool.name").getValue())
          .isEqualTo("restart");
      assertThat(seek.getHighCardinalityKeyValue("gen_ai.tool.call.id").getValue()).isEqualTo("c1");
    }

    @Test
    void a_denying_approver_yields_denied() {
      turnGatedBy(context -> new ApprovalOutcome.Answered(Approval.denied("not on a Friday")));

      assertThat(only(SEEK).getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("denied");
    }

    @Test
    void an_approver_that_defers_yields_deferred() {
      turnGatedBy(Approvers.defer());

      assertThat(only(SEEK).getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("deferred");
    }

    /**
     * The executor already turns a throwing approver into a fail-closed denial. The span has to say
     * so — a bare success on a span whose approver exploded is the kind of lie that makes a trace
     * worse than no trace.
     */
    @Test
    void a_throwing_approver_yields_denied_with_its_error_type() {
      turnGatedBy(
          context -> {
            throw new IllegalStateException("the policy service is down");
          });

      Observation.Context seek = only(SEEK);
      assertThat(seek.getLowCardinalityKeyValue(OUTCOME).getValue()).isEqualTo("denied");
      assertThat(seek.getLowCardinalityKeyValue("error.type").getValue())
          .isEqualTo("IllegalStateException");
    }
  }

  @Nested
  class TheScopeItHolds {

    /** An approver's own work — a Slack call, a policy lookup — belongs under the ask. */
    @Test
    void an_observation_the_approver_records_nests_inside_the_seek_span() {
      turnGatedBy(
          context -> {
            Observation.createNotStarted("probe", registry).contextualName("policy").start().stop();
            return new ApprovalOutcome.Answered(Approval.approved());
          });

      Observation.Context probe = only("policy");
      assertThat(probe.getParentObservation()).isNotNull();
      assertThat(probe.getParentObservation().getContextView().getContextualName()).isEqualTo(SEEK);
    }

    /** And the ask itself is never a root: it belongs to the round that asked. */
    @Test
    void the_seek_span_is_not_a_root() {
      turnGatedBy(context -> new ApprovalOutcome.Answered(Approval.approved()));

      assertThat(only(SEEK).getParentObservation()).isNotNull();
    }
  }

  record NoInput() {}

  static final class EchoTool implements Tool<NoInput> {

    @Override
    public String name() {
      return "restart";
    }

    @Override
    public String description() {
      return "echoes, for the approval-seek tests";
    }

    @Override
    public Class<NoInput> inputType() {
      return NoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(NoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("restarted"));
    }
  }
}
