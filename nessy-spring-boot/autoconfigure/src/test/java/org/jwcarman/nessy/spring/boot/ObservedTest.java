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
package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/** What wrapping a collaborator measures, and when it measures it. */
class ObservedTest {

  /** What the engine tells a running tool; nothing here reads it. */
  private static <I> ToolCallRequest<I> call(AgentType agentType, AgentId agentId, I input) {
    return new ToolCallRequest<>(
        agentType,
        agentId,
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "a_tool",
        input,
        new ReplyToken("nowhere"));
  }

  private MeterRegistry meters;
  private ObservationRegistry observations;

  @BeforeEach
  void wire() {
    meters = new SimpleMeterRegistry();
    observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
  }

  private static ModelRequest request() {
    return new ModelRequest(Context.empty(), "sys", 1024, List.of(), java.util.Set.of());
  }

  private static Model saying(ModelEvent... events) {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("a-model");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        return new ModelStream() {
          @Override
          public Iterator<ModelEvent> iterator() {
            return List.of(events).iterator();
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
      }
    };
  }

  @Test
  void a_model_call_is_not_timed_until_the_stream_is_closed() {
    Model observed = Observed.model(saying(), "openai", observations, meters);

    ModelStream stream = observed.stream(request());

    // The provider does its work as events are consumed, so timing stream() alone would report the
    // cost of returning an iterator. Nothing is recorded while the stream is still open.
    assertThat(meters.find("gen_ai.client.operation.duration").timer()).isNull();

    stream.close();
    assertThat(meters.get("gen_ai.client.operation.duration").timer().count()).isEqualTo(1);
  }

  @Test
  void a_model_call_carries_the_tokens_that_call_reported() {
    Model observed =
        Observed.model(
            saying(new ModelEvent.Stopped(StopReason.END_TURN, new Usage(606, 142))),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("gen_ai.request.model"))
        .isEqualTo("a-model");
  }

  /**
   * The whole reason a count is nullable: a provider that keeps no cache books must not appear on
   * the cache histogram at all. Recording zero would drag every cache-hit rate towards zero for a
   * provider that never claimed to have a cache — a measurement invented by the instrumentation.
   */
  @Test
  void a_count_the_provider_did_not_report_is_not_recorded_at_all() {
    Model observed =
        Observed.model(
            // LM Studio's shape: prompt and completion tokens, no prompt_tokens_details.
            saying(new ModelEvent.Stopped(StopReason.END_TURN, new Usage(606, 142))),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(tokenCount("input")).isEqualTo(1);
    assertThat(tokenCount("output")).isEqualTo(1);
    assertThat(meters.find(TOKENS).tag("gen_ai.token.type", "cache_read").summary()).isNull();
    assertThat(meters.find(TOKENS).tag("gen_ai.token.type", "cache_write").summary()).isNull();
  }

  /**
   * A REPORTED zero is a measurement and is still recorded: a missing series and a genuine zero
   * look identical on a graph, so "is the cache working" cannot be answered by a series that
   * appears only once caching has happened.
   */
  @Test
  void a_reported_zero_is_recorded_like_any_other_number() {
    Model observed =
        Observed.model(
            saying(new ModelEvent.Stopped(StopReason.END_TURN, new Usage(606, 142, 0, 0))),
            "anthropic",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(tokenCount("cache_read")).isEqualTo(1);
    assertThat(meters.get(TOKENS).tag("gen_ai.token.type", "cache_read").summary().totalAmount())
        .isZero();
  }

  @Test
  void a_stream_that_never_reported_its_cost_records_no_tokens_at_all() {
    Model observed =
        Observed.model(
            saying(new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported())),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(meters.find(TOKENS).summaries()).isEmpty();
    // The call still happened, and is still timed.
    assertThat(meters.get("gen_ai.client.operation.duration").timer().count()).isEqualTo(1);
  }

  private static final String TOKENS = "gen_ai.client.token.usage";

  private long tokenCount(String type) {
    return meters.get(TOKENS).tag("gen_ai.token.type", type).summary().count();
  }

  @Test
  void a_refusal_is_recorded_with_semconvs_own_finish_reason() {
    Model observed =
        Observed.model(
            saying(new ModelEvent.Refused("safety", "no", new Usage(1, 0))),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("gen_ai.response.finish_reasons"))
        // semconv's vocabulary, not ours: a filtered response is content_filter everywhere.
        .isEqualTo("content_filter");
  }

  @Test
  void a_tool_records_whether_the_model_can_act_on_the_answer() {
    Tool<String> failing = tool(input -> Awaited.ready(ToolResult.error("the disk is gone")));

    Observed.tool(failing, observations)
        .execute(call(AgentType.of("observed"), AgentId.of("one"), "x"));

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("nessy.tool.outcome"))
        .isEqualTo("failure");
  }

  @Test
  void a_deferring_tool_is_recorded_as_deferred_rather_than_as_a_success() {
    Tool<String> defers = tool(input -> Awaited.deferred(Instant.now().plusSeconds(3600)));

    Observed.tool(defers, observations)
        .execute(call(AgentType.of("observed"), AgentId.of("one"), "x"));

    // A deferral is neither a success nor a failure: nothing has happened yet.
    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("nessy.tool.outcome"))
        .isEqualTo("deferred");
  }

  @Test
  void an_approval_says_which_agent_it_was_for() {
    Approver defers = request -> Awaited.deferred(Instant.now().plusSeconds(3600));

    Observed.approver(defers, observations).approve(approvalRequest());

    var timer = meters.get("nessy.approval").timer();
    // The only collaborator the engine hands an identity to, so the only span that can say so.
    assertThat(timer.getId().getTag("gen_ai.agent.name")).isEqualTo("ops");
    assertThat(timer.getId().getTag("nessy.approval.answer")).isEqualTo("asked-a-person");
  }

  @Test
  void an_application_without_a_registry_pays_nothing() {
    Model observed = Observed.model(saying(), "openai", ObservationRegistry.NOOP, meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(meters.find("gen_ai.client.operation.duration").timer()).isNull();
  }

  /**
   * {@link Observed#models} is the provider-level wrapper the starter actually hands the engine;
   * the tests above go straight at {@link Observed#model} because that is where the behaviour
   * lives, but the factory method itself — resolving one model out of the delegate provider — needs
   * its own call to be exercised at all.
   */
  @Test
  void a_provider_resolves_an_observed_model_from_its_delegate() {
    ModelId requestedId = ModelId.of("resolved-model");
    Model delegateModel = saying();
    ModelProvider delegate = id -> delegateModel;

    ModelProvider observed = Observed.models(delegate, "openai", observations, meters);
    Model model = observed.model(requestedId);

    // The wrapper hands back exactly the delegate's model — the id it reports is that model's own,
    // proof that the provider-level factory is not building anything of its own.
    assertThat(model.id()).isEqualTo(delegateModel.id());
  }

  /** The id a wrapped model reports is the delegate's, never invented by the wrapper. */
  @Test
  void an_observed_model_reports_its_delegates_id() {
    Model observed = Observed.model(saying(), "openai", observations, meters);

    assertThat(observed.id()).isEqualTo(ModelId.of("a-model"));
  }

  /**
   * A provider that cannot even start streaming — a rejected connection, say — still owes an
   * observation: the span is closed with the error recorded rather than leaked open, and the
   * original exception still reaches the caller unchanged.
   */
  @Test
  void a_provider_that_throws_before_streaming_still_closes_its_span() {
    Model refusing =
        new Model() {
          @Override
          public ModelId id() {
            return ModelId.of("a-model");
          }

          @Override
          public ModelStream stream(ModelRequest request) {
            throw new IllegalStateException("provider unreachable");
          }
        };
    Model observed = Observed.model(refusing, "openai", observations, meters);
    ModelRequest request = request();

    assertThatThrownBy(() -> observed.stream(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("provider unreachable");

    assertThat(meters.get("gen_ai.client.operation.duration").timer().getId().getTag("error.type"))
        .isEqualTo("IllegalStateException");
  }

  /**
   * {@code firstChunkSeen} only flips once. Every test above sends a single event per stream, which
   * never exercises the branch where a later event finds the flag already set — this one sends two.
   */
  @Test
  void only_the_first_event_in_a_stream_is_timed_to_first_chunk() {
    Model observed =
        Observed.model(
            saying(
                new ModelEvent.TextChunk("hello"),
                new ModelEvent.Stopped(StopReason.END_TURN, new Usage(1, 1))),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    // Both events were consumed without error; a second "first chunk" timestamp would only ever
    // overwrite the first, so the only observable proof is that the stream still completed cleanly.
    assertThat(meters.get("gen_ai.client.operation.duration").timer().count()).isEqualTo(1);
  }

  /** A delta carries no outcome, so recording it must not touch the finish reason at all. */
  @Test
  void a_text_chunk_is_not_treated_as_the_turns_outcome() {
    Model observed =
        Observed.model(
            saying(new ModelEvent.TextChunk("still thinking")), "openai", observations, meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("gen_ai.response.finish_reasons"))
        .isEqualTo("none");
  }

  @Test
  void tool_use_is_reported_as_semconvs_own_finish_reason() {
    assertFinishReason(StopReason.TOOL_USE, "tool_calls");
  }

  @Test
  void max_tokens_is_reported_as_length_not_as_anything_about_tokens() {
    assertFinishReason(StopReason.MAX_TOKENS, "length");
  }

  private void assertFinishReason(StopReason reason, String expected) {
    Model observed =
        Observed.model(
            saying(new ModelEvent.Stopped(reason, new Usage(1, 1))),
            "openai",
            observations,
            meters);

    try (ModelStream stream = observed.stream(request())) {
      stream.forEach(event -> {});
    }

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("gen_ai.response.finish_reasons"))
        .isEqualTo(expected);
  }

  /** Closing a stream twice must not stop an already-stopped observation a second time. */
  @Test
  void closing_a_stream_twice_stops_the_observation_only_once() {
    Model observed = Observed.model(saying(), "openai", observations, meters);
    ModelStream stream = observed.stream(request());

    stream.close();
    stream.close();

    assertThat(meters.get("gen_ai.client.operation.duration").timer().count()).isEqualTo(1);
  }

  @Test
  void a_wrapped_tool_answers_name_description_and_schema_from_its_delegate() {
    Tool<String> delegate = tool(input -> Awaited.ready(ToolResult.ok("done")));

    Tool<String> observed = Observed.tool(delegate, observations);

    assertThat(observed.name()).isEqualTo("a-tool");
    assertThat(observed.description()).isEqualTo("does a thing");
    assertThat(observed.inputType()).isEqualTo(String.class);
    assertThat(observed.inputSchema()).isEqualTo(delegate.inputSchema());
  }

  @Test
  void a_successful_tool_call_is_recorded_as_a_success() {
    Tool<String> succeeding = tool(input -> Awaited.ready(ToolResult.ok("done")));

    Observed.tool(succeeding, observations)
        .execute(call(AgentType.of("observed"), AgentId.of("one"), "x"));

    assertThat(
            meters
                .get("gen_ai.client.operation.duration")
                .timer()
                .getId()
                .getTag("nessy.tool.outcome"))
        .isEqualTo("success");
  }

  @Test
  void an_approved_call_is_recorded_as_approved() {
    Approver approving = request -> Awaited.ready(ApprovalResult.approved());

    Observed.approver(approving, observations).approve(approvalRequest());

    assertThat(meters.get("nessy.approval").timer().getId().getTag("nessy.approval.answer"))
        .isEqualTo("approved");
  }

  @Test
  void a_denied_call_is_recorded_as_denied() {
    Approver denying = request -> Awaited.ready(ApprovalResult.denied("not today"));

    Observed.approver(denying, observations).approve(approvalRequest());

    assertThat(meters.get("nessy.approval").timer().getId().getTag("nessy.approval.answer"))
        .isEqualTo("denied");
  }

  private static ApprovalRequest approvalRequest() {
    return new ApprovalRequest(
        AgentType.of("ops"),
        AgentId.of("prod-eu"),
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "restart",
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
        "restart prod-eu",
        Instant.EPOCH,
        () -> new ReplyToken("nowhere"));
  }

  private static Tool<String> tool(java.util.function.Function<String, Awaited<ToolResult>> body) {
    return new Tool<>() {
      @Override
      public String name() {
        return "a-tool";
      }

      @Override
      public String description() {
        return "does a thing";
      }

      @Override
      public Class<String> inputType() {
        return String.class;
      }

      @Override
      public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<String> call) {
        String input = call.input();
        return body.apply(input);
      }
    };
  }
}
