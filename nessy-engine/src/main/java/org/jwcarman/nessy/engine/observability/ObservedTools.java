package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Tools and approvers observed the way the OpenTelemetry GenAI semantic conventions describe them,
 * applied by the harness to every tool and approver it is given, so a dashboard sees the same spans
 * however an application registered them.
 *
 * <p>A tool call is {@code execute_tool <name>} in the same {@code
 * gen_ai.client.operation.duration} histogram as a model call, told apart by {@code
 * gen_ai.operation.name}. An approval is {@code nessy.approval}, a name of Nessy's own: semconv has
 * no convention for asking a person, and putting human latency in the same histogram as model
 * latency would say something untrue. Its duration is the decision, not the wait -- an approver
 * that defers returns at once and the person takes three days, which is the projection's business,
 * not a tracer's.
 *
 * <p>Both ask the registry per call whether anyone is listening, so an application that is not
 * tracing pays nothing but the check.
 */
public final class ObservedTools {

  private static final String DURATION = "gen_ai.client.operation.duration";
  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String TOOL_NAME = "gen_ai.tool.name";

  private ObservedTools() {}

  public static <I> Tool<I> tool(Tool<I> delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return new Tool<>() {
      @Override
      public ToolName name() {
        return delegate.name();
      }

      @Override
      public String description() {
        return delegate.description();
      }

      @Override
      public Class<I> inputType() {
        return delegate.inputType();
      }

      @Override
      public InputSchema inputSchema(InputSchemaGenerator generator) {
        return delegate.inputSchema(generator);
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<I> request) {
        if (observations.isNoop()) {
          return delegate.call(request);
        }
        Observation observation =
            Observation.createNotStarted(DURATION, observations)
                .contextualName("execute_tool " + delegate.name().value())
                .lowCardinalityKeyValue(OPERATION_NAME, "execute_tool")
                .lowCardinalityKeyValue(TOOL_NAME, delegate.name().value())
                .lowCardinalityKeyValue("gen_ai.tool.type", "function")
                .lowCardinalityKeyValue("nessy.tool.outcome", "none")
                .lowCardinalityKeyValue("nessy.tool.deferred", "none")
                .highCardinalityKeyValue("gen_ai.tool.call.id", request.callId().value());
        return observation.observe(
            () -> {
              Awaited<ToolResult> answer = delegate.call(request);
              observation.lowCardinalityKeyValue("nessy.tool.outcome", outcomeOf(answer));
              observation.lowCardinalityKeyValue(
                  "nessy.tool.deferred",
                  String.valueOf(answer instanceof Awaited.Deferred<ToolResult>));
              return answer;
            });
      }
    };
  }

  public static Approver approver(Approver delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    return request -> {
      if (observations.isNoop()) {
        return delegate.approve(request);
      }
      Observation observation =
          Observation.createNotStarted("nessy.approval", observations)
              .contextualName("approve " + request.toolName().value())
              .lowCardinalityKeyValue("gen_ai.agent.name", request.agentType().value())
              .lowCardinalityKeyValue(TOOL_NAME, request.toolName().value())
              .highCardinalityKeyValue("gen_ai.agent.id", request.agentId().value().toString())
              .highCardinalityKeyValue("gen_ai.tool.call.id", request.callId().value())
              .lowCardinalityKeyValue("nessy.approval.answer", "none");
      return observation.observe(
          () -> {
            Awaited<ApprovalResult> answer = delegate.approve(request);
            observation.lowCardinalityKeyValue("nessy.approval.answer", approvalOf(answer));
            return answer;
          });
    };
  }

  private static String outcomeOf(Awaited<ToolResult> answer) {
    return switch (answer) {
      case Awaited.Deferred<ToolResult> _ -> "deferred";
      case Awaited.Ready<ToolResult>(var result) ->
          result instanceof ToolResult.Success ? "success" : "failure";
    };
  }

  private static String approvalOf(Awaited<ApprovalResult> answer) {
    return switch (answer) {
      case Awaited.Deferred<ApprovalResult> _ -> "asked-a-person";
      case Awaited.Ready<ApprovalResult>(var result) ->
          result instanceof ApprovalResult.Approved ? "approved" : "denied";
    };
  }
}
