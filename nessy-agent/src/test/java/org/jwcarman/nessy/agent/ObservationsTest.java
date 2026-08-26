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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * The roster (agentic-o11y spec §1, §6), unit-tested against hand-folded events. Every fact here
 * goes through the REAL reducer — {@code phase.handle(event)} — so a transition this test feeds
 * {@link Observations} is one the engine could actually produce, and a phase-grammar change that
 * would break the segment rules breaks this file too.
 */
class ObservationsTest {

  private static final AgentType TYPE = AgentType.of("ops");
  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final ToolCall RESTART =
      new ToolCall("call-1", "restart", JsonNodeFactory.instance.objectNode());
  private static final ToolCall DRAIN =
      new ToolCall("call-2", "drain", JsonNodeFactory.instance.objectNode());

  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final Observations observations =
      new Observations(registry, TYPE, new ConcurrentHashMap<>());

  /** The scope's phase, advanced by {@link #fold} exactly as a real fold site advances it. */
  private Phase phase = new Phase.Idle();

  /** One fold, published the way {@code DefaultAgent} and {@code DeliveryWorker} publish it. */
  private void fold(AgentEvent event) {
    Transition transition = phase.handle(event);
    if (transition.isIgnored()) {
      observations.ignored(SCOPE, event);
      return;
    }
    phase = transition.next();
    observations.applied(SCOPE, event, transition);
  }

  private void observed() {
    fold(new AgentEvent.Observed(List.of(new TextBlock("restart prod-eu"))));
  }

  private void modelAsksFor(ToolCall... calls) {
    fold(
        new AgentEvent.ModelFinished(
            new ModelOutcome.Responded(
                Arrays.stream(calls)
                    .map(call -> (ContentBlock) new ToolUseBlock(call, null))
                    .toList(),
                List.of(calls),
                ModelResponseId.of("response-1"))));
  }

  private static ApprovalRequest requestFor(ToolCall call) {
    return ApprovalRequest.draft(TYPE.name(), SCOPE.value(), call, Map.of(), new ObjectMapper())
        .freeze();
  }

  /** Every context the registry has seen, whether or not it has been stopped. */
  private List<Observation.Context> contexts() {
    List<Observation.Context> captured = new ArrayList<>();
    assertThat(registry).hasHandledContextsThatSatisfy(captured::addAll);
    return captured;
  }

  private List<Observation.Context> named(String contextualName) {
    return contexts().stream()
        .filter(context -> contextualName.equals(context.getContextualName()))
        .toList();
  }

  private Observation.Context only(String contextualName) {
    List<Observation.Context> found = named(contextualName);
    assertThat(found).as("observations contextually named '%s'", contextualName).hasSize(1);
    return found.getFirst();
  }

  private long countOf(String name) {
    return contexts().stream().filter(context -> name.equals(context.getName())).count();
  }

  @Nested
  class TheRoster {

    /**
     * The names are a compatibility surface with the OpenTelemetry GenAI semantic conventions and
     * with whatever dashboard reads them: pinned here so a rename is a deliberate, visible act
     * rather than a silent break of every query someone wrote.
     */
    @Test
    void the_semconv_names_are_the_ones_the_spec_pins() {
      assertThat(Observations.INVOKE_AGENT).isEqualTo("invoke_agent");
      assertThat(Observations.CHAT).isEqualTo("chat");
      assertThat(Observations.EXECUTE_TOOL).isEqualTo("execute_tool");
      assertThat(Observations.GEN_AI_OPERATION_NAME).isEqualTo("gen_ai.operation.name");
      assertThat(Observations.GEN_AI_AGENT_NAME).isEqualTo("gen_ai.agent.name");
      assertThat(Observations.GEN_AI_AGENT_ID).isEqualTo("gen_ai.agent.id");
      assertThat(Observations.GEN_AI_CONVERSATION_ID).isEqualTo("gen_ai.conversation.id");
      assertThat(Observations.GEN_AI_TOOL_NAME).isEqualTo("gen_ai.tool.name");
      assertThat(Observations.GEN_AI_TOOL_CALL_ID).isEqualTo("gen_ai.tool.call.id");
    }

    /** Ours, where semconv has no word for it (spec §0). */
    @Test
    void the_nessy_names_are_the_ones_the_spec_pins() {
      assertThat(Observations.APPROVAL_WAIT).isEqualTo("nessy.approval.wait");
      assertThat(Observations.TOOL_WAIT).isEqualTo("nessy.tool.wait");
      assertThat(Observations.DELIVERY_DROPPED).isEqualTo("nessy.delivery.dropped");
      assertThat(Observations.STALE_RETRIES).isEqualTo("nessy.state.stale_retries");
      assertThat(Observations.EFFECTS_REFIRED).isEqualTo("nessy.effects.refired");
      assertThat(Observations.NESSY_TURN_OUTCOME).isEqualTo("nessy.turn.outcome");
      assertThat(Observations.NESSY_APPROVAL_ANSWER).isEqualTo("nessy.approval.answer");
      assertThat(Observations.NESSY_TOOL_OUTCOME).isEqualTo("nessy.tool.outcome");
    }
  }

  @Nested
  class ASegment {

    @Test
    void an_observation_opens_one_invoke_agent_span_naming_the_scope() {
      observed();

      Observation.Context segment = only("invoke_agent ops");
      assertThat(segment.getName()).isEqualTo(Observations.INVOKE_AGENT);
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_OPERATION_NAME).getValue())
          .isEqualTo("invoke_agent");
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_AGENT_NAME).getValue())
          .isEqualTo("ops");
      assertThat(segment.getHighCardinalityKeyValue(Observations.GEN_AI_AGENT_ID).getValue())
          .isEqualTo("prod-eu");
      assertThat(segment.getHighCardinalityKeyValue(Observations.GEN_AI_CONVERSATION_ID).getValue())
          .isEqualTo("prod-eu");
    }

    @Test
    void reaching_idle_closes_the_segment_as_complete() {
      observed();
      fold(
          new AgentEvent.ModelFinished(
              new ModelOutcome.Responded(
                  List.of(new TextBlock("done")), List.of(), ModelResponseId.of("response-1"))));

      assertThat(
              only("invoke_agent ops")
                  .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                  .getValue())
          .isEqualTo("complete");
    }

    @Test
    void a_model_failure_closes_the_segment_as_failed() {
      observed();
      fold(new AgentEvent.ModelFinished(new ModelOutcome.Failed("overloaded")));

      assertThat(
              only("invoke_agent ops")
                  .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                  .getValue())
          .isEqualTo("failed");
    }

    @Test
    void a_park_closes_the_segment_rather_than_straddling_it() {
      observed();
      modelAsksFor(RESTART);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));

      assertThat(
              only("invoke_agent ops")
                  .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                  .getValue())
          .isEqualTo("parked");
    }

    /**
     * §2's "with no other call running": a call parking while a sibling still runs has not ended
     * the segment — there is work in flight, and the span must cover it.
     */
    @Test
    void a_park_with_a_sibling_still_pending_keeps_the_segment_open() {
      observed();
      modelAsksFor(RESTART, DRAIN);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));

      // Declared at start as the placeholder, never overwritten: nothing ended the segment.
      assertThat(
              only("invoke_agent ops")
                  .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                  .getValue())
          .isEqualTo(KeyValue.NONE_VALUE);
      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.INVOKE_AGENT)
          .that()
          .isNotStopped();
    }

    /**
     * A delivery arriving hours after the park resumes the scope, and that is the start of a NEW
     * segment: the old one died at the park (and, in production, possibly with the process).
     */
    @Test
    void a_delivery_resuming_a_parked_scope_opens_a_second_segment() {
      observed();
      modelAsksFor(RESTART);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));
      fold(
          new AgentEvent.ApprovalAnswered(
              RESTART, Optional.of(ComputationId.of("approval-1")), Approval.approved()));

      assertThat(named("invoke_agent ops")).hasSize(2);
    }
  }

  @Nested
  class TheWaits {

    @Test
    void an_approval_wait_opens_at_the_park_and_stays_open_across_it() {
      observed();
      modelAsksFor(RESTART);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));

      Observation.Context wait = only("nessy.approval.wait restart");
      assertThat(wait.getName()).isEqualTo(Observations.APPROVAL_WAIT);
      assertThat(wait.getLowCardinalityKeyValue(Observations.GEN_AI_TOOL_NAME).getValue())
          .isEqualTo("restart");
      assertThat(wait.getHighCardinalityKeyValue(Observations.GEN_AI_TOOL_CALL_ID).getValue())
          .isEqualTo("call-1");
      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.APPROVAL_WAIT)
          .that()
          .isNotStopped();
    }

    @Test
    void the_approval_wait_is_a_child_of_the_segment_that_parked_it() {
      observed();
      modelAsksFor(RESTART);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));

      assertThat(only("nessy.approval.wait restart").getParentObservation()).isNotNull();
      assertThat(only("nessy.approval.wait restart").getParentObservation().getContextView())
          .isSameAs(only("invoke_agent ops"));
    }

    @Test
    void an_answer_closes_the_approval_wait_carrying_what_the_desk_said() {
      observed();
      modelAsksFor(RESTART);
      fold(
          new AgentEvent.ApprovalDeferred(
              RESTART, ComputationId.of("approval-1"), requestFor(RESTART)));
      fold(
          new AgentEvent.ApprovalAnswered(
              RESTART,
              Optional.of(ComputationId.of("approval-1")),
              Approval.denied("not during the freeze")));

      assertThat(
              only("nessy.approval.wait restart")
                  .getLowCardinalityKeyValue(Observations.NESSY_APPROVAL_ANSWER)
                  .getValue())
          .isEqualTo("denied");
      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.APPROVAL_WAIT)
          .that()
          .hasBeenStopped();
    }

    @Test
    void a_deferred_tool_opens_a_wait_that_its_delivered_result_closes() {
      observed();
      modelAsksFor(RESTART);
      fold(new AgentEvent.ApprovalAnswered(RESTART, Optional.empty(), Approval.approved()));
      fold(new AgentEvent.ToolDeferred(RESTART, ComputationId.of("tool-1")));
      fold(
          new AgentEvent.ToolFinished(
              RESTART,
              Optional.of(ComputationId.of("tool-1")),
              new ToolOutcome.Returned(ToolResult.ok("restarted"))));

      assertThat(
              only("nessy.tool.wait restart")
                  .getLowCardinalityKeyValue(Observations.NESSY_TOOL_OUTCOME)
                  .getValue())
          .isEqualTo("returned");
      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.TOOL_WAIT)
          .that()
          .hasBeenStopped();
    }

    /** A call that ran in-band never parked, so there is no dwell to record. */
    @Test
    void an_in_band_result_opens_no_wait_at_all() {
      observed();
      modelAsksFor(RESTART);
      fold(new AgentEvent.ApprovalAnswered(RESTART, Optional.empty(), Approval.approved()));
      fold(
          new AgentEvent.ToolFinished(
              RESTART, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("restarted"))));

      assertThat(named("nessy.tool.wait restart")).isEmpty();
    }
  }

  @Nested
  class TheCounters {

    @Test
    void a_dropped_delivery_is_counted() {
      // Idle ignores a tool result, and this one arrived as a delivery: an orphan or a duplicate.
      fold(
          new AgentEvent.ToolFinished(
              RESTART,
              Optional.of(ComputationId.of("tool-1")),
              new ToolOutcome.Returned(ToolResult.ok("late"))));

      assertThat(countOf(Observations.DELIVERY_DROPPED)).isEqualTo(1);
    }

    /**
     * An in-band stale event is an ordinary race the shell absorbs by design, not the operational
     * event this counter exists to surface — counting it would drown the real drops.
     */
    @Test
    void an_ignored_in_band_event_is_not_counted_as_a_dropped_delivery() {
      fold(
          new AgentEvent.ToolFinished(
              RESTART, Optional.empty(), new ToolOutcome.Returned(ToolResult.ok("late"))));

      assertThat(countOf(Observations.DELIVERY_DROPPED)).isZero();
    }

    @Test
    void a_stale_retry_is_counted() {
      observations.staleRetry(TYPE);
      observations.staleRetry(TYPE);

      assertThat(countOf(Observations.STALE_RETRIES)).isEqualTo(2);
    }

    @Test
    void every_re_fired_effect_is_counted() {
      observations.reFired(SCOPE, List.of(new Effect.CallModel(), new Effect.RunTool(RESTART)));

      assertThat(countOf(Observations.EFFECTS_REFIRED)).isEqualTo(2);
    }

    @Test
    void a_counter_carries_the_agent_type() {
      observations.staleRetry(TYPE);

      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.STALE_RETRIES)
          .that()
          .hasLowCardinalityKeyValue(Observations.GEN_AI_AGENT_NAME, "ops")
          .hasBeenStopped();
    }
  }
}
