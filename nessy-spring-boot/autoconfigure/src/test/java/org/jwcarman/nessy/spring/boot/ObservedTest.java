package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * What an application sees in its tracing and its meters.
 *
 * <p><b>Much smaller than it was, because the SPI is.</b> Half of this used to be about when a
 * stream's span opened and closed, whether a chunk counted as the turn's outcome, and what happened
 * if a stream was closed twice. An inference is one call that returns when it is done, so all of
 * that is gone along with the machinery it tested.
 */
class ObservedTest {

  private static final String DURATION = "gen_ai.client.operation.duration";

  private MeterRegistry meters;
  private ObservationRegistry observations;

  @BeforeEach
  void wire() {
    meters = new SimpleMeterRegistry();
    observations = ObservationRegistry.create();
    observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
  }

  private static InferenceRequest request() {
    return new InferenceRequest(
        new SystemPrompt("you are a test assistant"),
        InferenceContext.of(
            List.of(
                new Turn(
                    new TurnId(1),
                    new Observation(new Seq(1), List.of(new Block.Text("hello"))),
                    List.of(),
                    null,
                    0))),
        List.of(),
        new InferenceOptions("a-model", 1024, Set.of()));
  }

  private String tagOf(String meter, String tag) {
    return meters.get(meter).timer().getId().getTag(tag);
  }

  private void observe(InferenceProvider provider) {
    Observed.inference(provider, "openai", observations).infer(request());
  }

  // ---- inference -------------------------------------------------------------------------

  @Test
  void a_call_is_timed_and_says_which_vendor_and_model_it_went_to() {
    observe((_, _) -> new InferenceResult.Answer(List.of(new Block.Text("done"))));

    assertThat(meters.get(DURATION).timer().count()).isEqualTo(1);
    assertThat(tagOf(DURATION, "gen_ai.provider.name")).isEqualTo("openai");
    assertThat(tagOf(DURATION, "gen_ai.request.model")).isEqualTo("a-model");
    assertThat(tagOf(DURATION, "gen_ai.operation.name")).isEqualTo("chat");
  }

  @Test
  void an_answer_is_recorded_with_semconvs_own_finish_reason() {
    observe((_, _) -> new InferenceResult.Answer(List.of(new Block.Text("done"))));

    assertThat(tagOf(DURATION, "gen_ai.response.finish_reasons")).isEqualTo("stop");
    assertThat(tagOf(DURATION, "error.type")).isEqualTo("none");
  }

  @Test
  void a_request_for_actions_is_recorded_as_tool_calls() {
    observe(
        (_, _) ->
            new InferenceResult.Actions(
                List.of(new Block.ToolCall(new CallId("c1"), new ToolName("lookup"), "{}"))));

    assertThat(tagOf(DURATION, "gen_ai.response.finish_reasons")).isEqualTo("tool_calls");
  }

  @Test
  void a_refusal_is_recorded_with_semconvs_own_finish_reason() {
    observe((_, _) -> new InferenceResult.Refusal("not that"));

    // semconv's vocabulary, not ours: a filtered response is content_filter everywhere.
    assertThat(tagOf(DURATION, "gen_ai.response.finish_reasons")).isEqualTo("content_filter");
  }

  /**
   * <b>The assertion the new SPI most needs.</b> A provider that cannot answer returns a {@code
   * Fault} rather than throwing, which is what makes the seam total -- and a failure that is a
   * value is a failure nothing records unless somebody looks for it. Without this, every failed
   * call would show up in a dashboard as a successful one.
   */
  @Test
  void a_fault_is_an_error_even_though_nothing_was_thrown() {
    observe((_, _) -> new InferenceResult.Fault(new Failure.Transient("the model is busy")));

    assertThat(tagOf(DURATION, "error.type")).isEqualTo("Transient");
    assertThat(tagOf(DURATION, "gen_ai.response.finish_reasons")).isEqualTo("error");
  }

  /** A bug in an adapter still throws, and the span has to close rather than leak. */
  @Test
  void a_provider_that_throws_still_closes_its_span_and_says_what_broke() {
    try {
      observe(
          (_, _) -> {
            throw new IllegalStateException("a bug in the adapter");
          });
    } catch (IllegalStateException expected) {
      // The point is that it propagates: only the adapter's own bugs reach here.
    }

    assertThat(meters.get(DURATION).timer().count()).isEqualTo(1);
    assertThat(tagOf(DURATION, "error.type")).isEqualTo("IllegalStateException");
  }

  @Test
  void an_application_without_a_registry_pays_nothing() {
    Observed.inference(
            (_, _) -> new InferenceResult.Answer(List.of(new Block.Text("done"))),
            "openai",
            ObservationRegistry.NOOP)
        .infer(request());

    assertThat(meters.find(DURATION).timer()).isNull();
  }

  // ---- tools -----------------------------------------------------------------------------

  @Test
  void a_wrapped_tool_answers_for_its_delegate() {
    Tool<String> delegate = tool(_ -> Awaited.ready(ToolResult.ok(new Block.Text("done"))));

    Tool<String> observed = Observed.tool(delegate, observations);

    assertThat(observed.name()).isEqualTo(new ToolName("a_tool"));
    assertThat(observed.description()).isEqualTo("does a thing");
    assertThat(observed.inputType()).isEqualTo(String.class);
  }

  @Test
  void a_successful_tool_call_is_recorded_as_a_success() {
    Observed.tool(tool(_ -> Awaited.ready(ToolResult.ok(new Block.Text("done")))), observations)
        .call(call("x"));

    assertThat(tagOf(DURATION, "nessy.tool.outcome")).isEqualTo("success");
    assertThat(tagOf(DURATION, "gen_ai.tool.name")).isEqualTo("a_tool");
  }

  @Test
  void a_tool_records_whether_the_model_can_act_on_the_answer() {
    Observed.tool(
            tool(_ -> Awaited.ready(new ToolResult.Failure("the disk is gone"))), observations)
        .call(call("x"));

    assertThat(tagOf(DURATION, "nessy.tool.outcome")).isEqualTo("failure");
  }

  /** A deferral is neither a success nor a failure: nothing has happened yet. */
  @Test
  void a_deferring_tool_is_recorded_as_deferred_rather_than_as_a_success() {
    Observed.tool(tool(_ -> Awaited.deferred()), observations).call(call("x"));

    assertThat(tagOf(DURATION, "nessy.tool.outcome")).isEqualTo("deferred");
    assertThat(tagOf(DURATION, "nessy.tool.deferred")).isEqualTo("true");
  }

  // ---- approvals -------------------------------------------------------------------------

  @Test
  void an_approval_says_which_agent_it_was_for() {
    Observed.approver(_ -> Awaited.deferred(), observations).approve(approvalRequest());

    // The only collaborator the engine hands an identity to, so the only span that can say so.
    assertThat(tagOf("nessy.approval", "gen_ai.agent.name")).isEqualTo("ops");
    assertThat(tagOf("nessy.approval", "nessy.approval.answer")).isEqualTo("asked-a-person");
  }

  @Test
  void an_approved_call_is_recorded_as_approved() {
    Observed.approver(_ -> Awaited.ready(ApprovalResult.approved()), observations)
        .approve(approvalRequest());

    assertThat(tagOf("nessy.approval", "nessy.approval.answer")).isEqualTo("approved");
  }

  @Test
  void a_denied_call_is_recorded_as_denied() {
    Observed.approver(_ -> Awaited.ready(ApprovalResult.denied("not today")), observations)
        .approve(approvalRequest());

    assertThat(tagOf("nessy.approval", "nessy.approval.answer")).isEqualTo("denied");
  }

  // ---- fixtures --------------------------------------------------------------------------

  private static ApprovalRequest approvalRequest() {
    return new ApprovalRequest(
        new AgentType("ops"),
        new AgentId(java.util.UUID.randomUUID()),
        new TurnId(1),
        new CallId("c1"),
        new ToolName("restart"),
        "{}",
        "restart prod-eu",
        Instant.EPOCH,
        Instant.EPOCH.plusSeconds(3600),
        new ReplyToken("nowhere"));
  }

  private static ToolCallRequest<String> call(String input) {
    return new ToolCallRequest<>() {
      @Override
      public AgentType agentType() {
        return new AgentType("observed");
      }

      @Override
      public AgentId agentId() {
        return new AgentId(java.util.UUID.randomUUID());
      }

      @Override
      public TurnId turn() {
        return new TurnId(1);
      }

      @Override
      public CallId callId() {
        return new CallId("c1");
      }

      @Override
      public ToolName toolName() {
        return new ToolName("a_tool");
      }

      @Override
      public String input() {
        return input;
      }

      @Override
      public Instant deadline() {
        return Instant.EPOCH.plusSeconds(3600);
      }

      @Override
      public ReplyToken replyToken() {
        return new ReplyToken("nowhere");
      }
    };
  }

  private static Tool<String> tool(java.util.function.Function<String, Awaited<ToolResult>> body) {
    return new Tool<>() {
      @Override
      public Class<String> inputType() {
        return String.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("a_tool");
      }

      @Override
      public String description() {
        return "does a thing";
      }

      @Override
      public InputSchema inputSchema(InputSchemaGenerator generator) {
        return new InputSchema("{\"type\":\"object\",\"properties\":{}}");
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<String> request) {
        return body.apply(request.input());
      }
    };
  }
}
