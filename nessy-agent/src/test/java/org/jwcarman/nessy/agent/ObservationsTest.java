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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ComputationCallback;
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

  /** Any deadline: these tests are about routing, not about when a wait ends. */
  /** Any callback and any term: this file is about spans, not about who gets told. */
  private static final ComputationCallback TELL = (id, deadline) -> {};

  private static final Duration TERM = Duration.ofDays(7);

  private static final Instant DEADLINE = Instant.parse("2030-01-01T00:00:00Z");

  private static final AgentType TYPE = AgentType.of("ops");
  private static final String PROVIDER = "anthropic";
  private static final String MODEL_ID = "claude-test";
  private static final AgentId SCOPE = AgentId.of("prod-eu");
  private static final ToolCall RESTART =
      new ToolCall("call-1", "restart", JsonNodeFactory.instance.objectNode());
  private static final ToolCall DRAIN =
      new ToolCall("call-2", "drain", JsonNodeFactory.instance.objectNode());

  private final TestObservationRegistry registry = TestObservationRegistry.create();
  private final Observations observations =
      new Observations(registry, TYPE, PROVIDER, MODEL_ID, new ConcurrentHashMap<>());

  /** The scope's phase, advanced by {@link #fold} exactly as a real fold site advances it. */
  private AgentPhase phase = new AgentPhase.Idle();

  /** One fold, published the way {@code DefaultAgent} and {@code DeliveryWorker} publish it. */
  private void fold(AgentEvent event) {
    AgentTransition transition = phase.handle(event);
    if (transition.isDropped()) {
      observations.ignored(SCOPE, event);
      return;
    }
    phase = transition.next();
    observations.applied(SCOPE, event, transition);
  }

  /** The two facts a deferral folds now (deferral-by-callback spec §9a): the ask, then the park. */
  private void parksApproval(ToolCall call, ComputationId id) {
    ApprovalRequest question = requestFor(call);
    fold(new AgentEvent.ApprovalDeferralRequested(call, question, TELL, TERM));
    fold(new AgentEvent.ApprovalDeferred(call, id, question, DEADLINE));
  }

  /** The tool side's {@link #parksApproval}. */
  private void parksTool(ToolCall call, ComputationId id) {
    fold(new AgentEvent.ToolCallDeferralRequested(call, TELL, TERM));
    fold(new AgentEvent.ToolCallDeferred(call, id, DEADLINE));
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
    void the_semconv_operation_names_are_the_ones_the_spec_pins() {
      assertThat(Observations.INVOKE_AGENT).isEqualTo("invoke_agent");
      assertThat(Observations.CHAT).isEqualTo("chat");
      assertThat(Observations.EXECUTE_TOOL).isEqualTo("execute_tool");
      assertThat(Observations.SEARCH_MEMORY).isEqualTo("search_memory");
      assertThat(Observations.CREATE_MEMORY).isEqualTo("create_memory");
    }

    /**
     * Semconv gives each operation boundary its OWN duration histogram, with its own attribute set
     * — it does NOT share one name across chat, invoke_agent and execute_tool (the 2026-08-26 audit
     * correcting spec §1.2's first amendment). These three are the Micrometer names; the span names
     * above ride as contextual names.
     */
    @Test
    void the_semconv_meter_names_are_one_per_operation() {
      assertThat(Observations.OPERATION_DURATION).isEqualTo("gen_ai.client.operation.duration");
      assertThat(Observations.INVOKE_AGENT_DURATION).isEqualTo("gen_ai.invoke_agent.duration");
      assertThat(Observations.EXECUTE_TOOL_DURATION).isEqualTo("gen_ai.execute_tool.duration");
      assertThat(Observations.TOKEN_USAGE).isEqualTo("gen_ai.client.token.usage");
    }

    @Test
    void the_semconv_attribute_keys_are_the_ones_the_spec_pins() {
      assertThat(Observations.GEN_AI_OPERATION_NAME).isEqualTo("gen_ai.operation.name");
      assertThat(Observations.GEN_AI_PROVIDER_NAME).isEqualTo("gen_ai.provider.name");
      assertThat(Observations.GEN_AI_REQUEST_MODEL).isEqualTo("gen_ai.request.model");
      assertThat(Observations.GEN_AI_AGENT_NAME).isEqualTo("gen_ai.agent.name");
      assertThat(Observations.GEN_AI_AGENT_ID).isEqualTo("gen_ai.agent.id");
      assertThat(Observations.GEN_AI_CONVERSATION_ID).isEqualTo("gen_ai.conversation.id");
      assertThat(Observations.GEN_AI_TOOL_NAME).isEqualTo("gen_ai.tool.name");
      assertThat(Observations.GEN_AI_TOOL_CALL_ID).isEqualTo("gen_ai.tool.call.id");
    }

    /**
     * Ours, where semconv has no word for it (spec §0) — re-confirmed by the 2026-08-26 audit
     * against {@code open-telemetry/semantic-conventions-genai}: the {@code gen_ai.operation.name}
     * enum has no human-in-the-loop pause and no deferred long-running operation.
     */
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
      assertThat(segment.getName()).isEqualTo(Observations.INVOKE_AGENT_DURATION);
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_OPERATION_NAME).getValue())
          .isEqualTo("invoke_agent");
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_AGENT_NAME).getValue())
          .isEqualTo("ops");
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_PROVIDER_NAME).getValue())
          .isEqualTo(PROVIDER);
      assertThat(segment.getLowCardinalityKeyValue(Observations.GEN_AI_REQUEST_MODEL).getValue())
          .isEqualTo(MODEL_ID);
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
      parksApproval(RESTART, ComputationId.of("approval-1"));

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
      parksApproval(RESTART, ComputationId.of("approval-1"));

      // Declared at start as the placeholder, never overwritten: nothing ended the segment.
      assertThat(
              only("invoke_agent ops")
                  .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                  .getValue())
          .isEqualTo(KeyValue.NONE_VALUE);
      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.INVOKE_AGENT_DURATION)
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
      parksApproval(RESTART, ComputationId.of("approval-1"));
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
      parksApproval(RESTART, ComputationId.of("approval-1"));

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
      parksApproval(RESTART, ComputationId.of("approval-1"));

      assertThat(only("nessy.approval.wait restart").getParentObservation()).isNotNull();
      assertThat(only("nessy.approval.wait restart").getParentObservation().getContextView())
          .isSameAs(only("invoke_agent ops"));
    }

    @Test
    void an_answer_closes_the_approval_wait_carrying_what_the_desk_said() {
      observed();
      modelAsksFor(RESTART);
      parksApproval(RESTART, ComputationId.of("approval-1"));
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
      parksTool(RESTART, ComputationId.of("tool-1"));
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

    /**
     * The stream has no cross-publish ordering guarantee per scope (spec §3), so this keyed state
     * machine must tolerate a close it has no open for: two concurrent folds on one scope can
     * arrive either way round, and an unmatched close must be a no-op rather than an NPE or a stray
     * span.
     */
    @Test
    void closing_a_wait_this_bridge_never_opened_is_a_no_op() {
      observed();
      modelAsksFor(RESTART);

      fold(new AgentEvent.ApprovalAnswered(RESTART, Optional.empty(), Approval.approved()));

      assertThat(named("nessy.approval.wait restart")).isEmpty();
    }

    /**
     * A segment reopened by the delivery that resumed a parked scope is closed again by that
     * segment's own ending, leaving nothing open: the close removes it from the shared map the
     * executors read, so a later {@code chat} finds no stale parent to hang off. Two segments, both
     * ended — a span never straddles the park between them (spec §2).
     */
    @Test
    void a_segment_reopened_by_a_delivery_leaves_nothing_open_once_it_ends() {
      observed();
      modelAsksFor(RESTART);
      parksApproval(RESTART, ComputationId.of("approval-1"));
      // Parked: the first segment closed. The delivered denial reopens one, finishes the call, and
      // sends the turn back to the model; the model's own answer is what ends this segment.
      fold(
          new AgentEvent.ApprovalAnswered(
              RESTART, Optional.of(ComputationId.of("approval-1")), Approval.denied("no")));
      fold(
          new AgentEvent.ModelFinished(
              new ModelOutcome.Responded(
                  List.of(new TextBlock("denied, then")),
                  List.of(),
                  ModelResponseId.of("response-2"))));

      assertThat(named("invoke_agent ops")).hasSize(2);
      assertThat(named("invoke_agent ops"))
          .allSatisfy(
              segment ->
                  assertThat(
                          segment
                              .getLowCardinalityKeyValue(Observations.NESSY_TURN_OUTCOME)
                              .getValue())
                      .isNotEqualTo(KeyValue.NONE_VALUE));
      assertThat(observations.openSegment(SCOPE)).isSameAs(Observation.NOOP);
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

  /**
   * The soak's finding F2 (2026-08-26): a counter recorded as its own zero-duration observation
   * renders in Tempo as a standalone ROOT SPAN, and one healthy round produced five of them against
   * a single real round trace. A counter is a thing that happened DURING a round, so it is now an
   * EVENT on the round's own segment span — and only a scope with no segment open falls back to the
   * old shape, because that is the one case where there is no round to hang it on and dropping it
   * would lose engine-health data outright.
   */
  private record CapturedEvent(String observation, String event) {}

  private final List<CapturedEvent> events = new ArrayList<>();

  /** Captures {@code onEvent}, which neither the context nor the TCK's assertions expose. */
  private ObservationHandler<Observation.Context> eventCaptor() {
    return new ObservationHandler<>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return true;
      }

      @Override
      public void onEvent(Observation.Event event, Observation.Context context) {
        events.add(new CapturedEvent(context.getName(), event.getName()));
      }
    };
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
      observations.staleRetry(SCOPE, TYPE);
      observations.staleRetry(SCOPE, TYPE);

      assertThat(countOf(Observations.STALE_RETRIES)).isEqualTo(2);
    }

    @Test
    void every_re_fired_effect_is_counted() {
      observations.reFired(SCOPE, List.of(new Effect.CallModel(), new Effect.RunTool(RESTART)));

      assertThat(countOf(Observations.EFFECTS_REFIRED)).isEqualTo(2);
    }

    @Test
    void a_counter_with_no_round_open_carries_the_agent_type() {
      observations.staleRetry(SCOPE, TYPE);

      assertThat(registry)
          .hasObservationWithNameEqualTo(Observations.STALE_RETRIES)
          .that()
          .hasLowCardinalityKeyValue(Observations.GEN_AI_AGENT_NAME, "ops")
          .hasBeenStopped();
    }
  }

  /**
   * F2 proper: while a round is running, none of the three counters may mint an observation of its
   * own — the trace list is a list of ROUNDS, and five root spans per round buries the one trace a
   * reader came for. The agent-type dimension is not lost by the move: the segment the event lands
   * on already carries {@code gen_ai.agent.name}.
   */
  @Nested
  class TheCountersDuringAnOpenRound {

    @Test
    void a_stale_retry_is_an_event_on_the_round_and_not_an_observation_of_its_own() {
      registry.observationConfig().observationHandler(eventCaptor());
      observed();

      observations.staleRetry(SCOPE, TYPE);

      assertThat(countOf(Observations.STALE_RETRIES)).isZero();
      assertThat(events)
          .containsExactly(
              new CapturedEvent(Observations.INVOKE_AGENT_DURATION, Observations.STALE_RETRIES));
    }

    @Test
    void a_dropped_delivery_is_an_event_on_the_round_and_not_an_observation_of_its_own() {
      registry.observationConfig().observationHandler(eventCaptor());
      observed();
      modelAsksFor(RESTART);

      // A second delivery for a call the phase no longer has running: ignored, and a real drop.
      fold(
          new AgentEvent.ToolFinished(
              DRAIN,
              Optional.of(ComputationId.of("tool-9")),
              new ToolOutcome.Returned(ToolResult.ok("late"))));

      assertThat(countOf(Observations.DELIVERY_DROPPED)).isZero();
      assertThat(events)
          .containsExactly(
              new CapturedEvent(Observations.INVOKE_AGENT_DURATION, Observations.DELIVERY_DROPPED));
    }

    @Test
    void every_re_fired_effect_is_an_event_on_the_round() {
      registry.observationConfig().observationHandler(eventCaptor());
      observed();

      observations.reFired(SCOPE, List.of(new Effect.CallModel(), new Effect.RunTool(RESTART)));

      assertThat(countOf(Observations.EFFECTS_REFIRED)).isZero();
      assertThat(events)
          .containsExactly(
              new CapturedEvent(Observations.INVOKE_AGENT_DURATION, Observations.EFFECTS_REFIRED),
              new CapturedEvent(Observations.INVOKE_AGENT_DURATION, Observations.EFFECTS_REFIRED));
    }

    /**
     * Containment, unchanged by the move (spec §3.1): a handler that throws on the event is logged
     * and dropped, never propagated into the fold that recorded it.
     */
    @Test
    void a_handler_that_throws_on_the_event_never_reaches_the_caller() {
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onEvent(Observation.Event event, Observation.Context context) {
                  throw new IllegalStateException("handler is broken");
                }
              });
      observed();

      assertThatCode(() -> observations.staleRetry(SCOPE, TYPE)).doesNotThrowAnyException();
    }
  }

  /**
   * The pump spans (spec: the 2026-08-26 soak, task-pump-spans): a `nessy.pump` observation, open
   * for the whole body of every pump pass, so instrumentation a user enables — a wrapped {@code
   * DataSource}, say — has something to nest under instead of starting its own root trace. Test
   * {@link #a_span_opened_inside_a_pump_pass_descends_from_the_pumps_own_observation()} is the one
   * that actually distinguishes a correct implementation (scope opened) from the exact prior bug
   * (span merely started and stopped around the work, scope never opened): with the scope never
   * opened, {@code registry.getCurrentObservation()} stays {@code null} while the probe below is
   * created, so the probe's parent would be {@code null} and this test would fail. Every other test
   * in this class stays green either way, which is why this one is written first.
   */
  @Nested
  class ThePumpSpan {

    private Observation.Context probeContext() {
      return only("probe");
    }

    @Test
    void a_span_opened_inside_a_pump_pass_descends_from_the_pumps_own_observation() {
      observations.pump(
          Observations.PUMP_DRAIN,
          Observations.PUMP_APPROVALS,
          () -> {
            Observation probe = Observation.createNotStarted("probe.span", registry);
            probe.contextualName("probe");
            probe.start().stop();
            return 0;
          });

      Observation.Context probe = probeContext();
      assertThat(probe.getParentObservation()).isNotNull();
      assertThat(probe.getParentObservation().getContextView().getName())
          .isEqualTo(Observations.PUMP);
    }

    @Test
    void the_count_attribute_reports_what_the_pass_returned() {
      observations.pump(Observations.PUMP_DRAIN, Observations.PUMP_APPROVALS, () -> 7);

      assertThat(
              only("drain approvals").getLowCardinalityKeyValue(Observations.PUMP_COUNT).getValue())
          .isEqualTo("7");
    }

    @Test
    void an_empty_poll_reports_a_zero_count() {
      observations.pump(Observations.PUMP_EXPIRE, Observations.PUMP_TOOLS, () -> 0);

      assertThat(only("expire tools").getLowCardinalityKeyValue(Observations.PUMP_COUNT).getValue())
          .isEqualTo("0");
    }

    @Test
    void both_attributes_are_set_for_each_of_the_three_verbs() {
      observations.pump(Observations.PUMP_DRAIN, Observations.PUMP_APPROVALS, () -> 1);
      observations.pump(Observations.PUMP_EXPIRE, Observations.PUMP_TOOLS, () -> 2);
      observations.pump(Observations.PUMP_PURGE, Observations.PUMP_APPROVALS, () -> 3);

      assertThat(
              only("drain approvals").getLowCardinalityKeyValue(Observations.PUMP_PASS).getValue())
          .isEqualTo(Observations.PUMP_DRAIN);
      assertThat(
              only("drain approvals").getLowCardinalityKeyValue(Observations.PUMP_KIND).getValue())
          .isEqualTo(Observations.PUMP_APPROVALS);
      assertThat(only("expire tools").getLowCardinalityKeyValue(Observations.PUMP_PASS).getValue())
          .isEqualTo(Observations.PUMP_EXPIRE);
      assertThat(only("expire tools").getLowCardinalityKeyValue(Observations.PUMP_KIND).getValue())
          .isEqualTo(Observations.PUMP_TOOLS);
      assertThat(
              only("purge approvals").getLowCardinalityKeyValue(Observations.PUMP_PASS).getValue())
          .isEqualTo(Observations.PUMP_PURGE);
      assertThat(
              only("purge approvals").getLowCardinalityKeyValue(Observations.PUMP_KIND).getValue())
          .isEqualTo(Observations.PUMP_APPROVALS);
    }

    /**
     * The leak this test is named for: a scope started but never opened would still get stopped
     * (the {@code finally} block below reaches {@code span::stop} regardless), so {@code
     * hasBeenStopped()} alone cannot tell a closed scope from a leaked one. {@code
     * registry.getCurrentObservation()} is the discriminator — a scope that was opened but never
     * closed leaves {@code nessy.pump} current forever, including after this method returns.
     */
    @Test
    void a_pass_whose_body_throws_still_propagates_with_the_observation_closed() {
      assertThatThrownBy(
              () ->
                  observations.pump(
                      Observations.PUMP_PURGE,
                      Observations.PUMP_TOOLS,
                      () -> {
                        throw new IllegalStateException("boom");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("boom");

      assertThat(registry).hasObservationWithNameEqualTo(Observations.PUMP).that().hasBeenStopped();
      assertThat(registry.getCurrentObservation()).isNull();
    }
  }
}
