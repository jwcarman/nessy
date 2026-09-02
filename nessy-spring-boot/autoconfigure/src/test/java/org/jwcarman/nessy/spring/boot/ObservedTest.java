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
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
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
