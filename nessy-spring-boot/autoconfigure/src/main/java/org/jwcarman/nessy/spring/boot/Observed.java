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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * Observability by WRAPPING the collaborators the engine calls, rather than by listening to what it
 * narrates.
 *
 * <p><b>Why wrapping and not listening.</b> A subscriber hears that a turn started and that it
 * ended, and can time the gap — but a turn that calls tools makes several model calls inside that
 * gap, and narration draws no boundary around any of them. Measured against a real provider: one
 * round was two calls of 5.5s and 6.7s, which a subscriber could only have reported as twelve
 * seconds of something.
 *
 * <p><b>The names are the OpenTelemetry GenAI semantic conventions', not ours.</b> That is the
 * whole point of emitting them: a dashboard that already groups by {@code gen_ai.provider.name}, or
 * an alert that already watches {@code gen_ai.client.operation.duration}, works on a Nessy
 * application without being taught anything. Inventing {@code nessy.model.call} would have made
 * every one of them useless here.
 *
 * <p>Token counts are a HISTOGRAM, never tags. A tag whose value is 606 makes a new time series per
 * distinct token count, which is how a metrics bill becomes a story.
 *
 * <p><b>What this cannot do, and why.</b> Nothing here can say which agent or turn a model call
 * belongs to: {@link ModelRequest} carries a context, a prompt, tools and capabilities, and no
 * identity; {@link ToolCallRequest} carries a reply address and nothing else. So model and tool
 * spans are correctly timed and correctly attributed, and they are ROOTS — they do not nest under a
 * turn, because there is nothing to nest them under. Approvals are the exception: {@link
 * ApprovalRequest} knows its agent and its call.
 */
public final class Observed {

  /** Semconv's histogram of how long a GenAI operation took. */
  private static final String DURATION = "gen_ai.client.operation.duration";

  // Semconv's gen_ai.client.token.usage histogram is NOT recorded, because InferenceResult does
  // not carry usage. The old streaming SPI reported it per event; the new one returns one finished
  // result and says nothing about what it cost. Adding it back is a change to InferenceResult, and
  // is worth making when somebody actually wants to bill or budget on it.

  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String ERROR_TYPE = "error.type";
  private static final String DELEGATE_NOT_NULL = "delegate must not be null";
  private static final String OBSERVATIONS_NOT_NULL = "observations must not be null";

  private Observed() {}

  /**
   * One provider, observed: a span per inference, lasting as long as the provider actually takes.
   *
   * <p><b>Simpler than it was, because the SPI is.</b> Timing used to have to stay open across
   * iteration of a stream -- the provider did its work as events were consumed, so timing the call
   * that returned the iterator measured almost nothing. An inference is one call that returns when
   * it is done, so the span is just the call.
   *
   * @param providerName the semconv {@code gen_ai.provider.name} for this vendor -- {@code
   *     "openai"}, {@code "anthropic"}, {@code "x_ai"}. Passed in because the application that
   *     built the provider is the one thing that knows; each adapter publishes the right value as
   *     its own {@code PROVIDER_NAME}.
   */
  public static InferenceProvider inference(
      InferenceProvider delegate, String providerName, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, DELEGATE_NOT_NULL);
    Objects.requireNonNull(providerName, "providerName must not be null");
    Objects.requireNonNull(observations, OBSERVATIONS_NOT_NULL);

    return (request, narrator) -> {
      String model = request.options().modelName();
      Observation observation =
          Observation.createNotStarted(DURATION, observations)
              // Semconv's span name is "{operation} {model}", which the metric name cannot also
              // be -- so the contextual name carries it and the meter keeps the histogram's name.
              .contextualName("chat " + model)
              .lowCardinalityKeyValue(OPERATION_NAME, "chat")
              .lowCardinalityKeyValue("gen_ai.provider.name", providerName)
              .lowCardinalityKeyValue("gen_ai.request.model", model)
              // Set at START, not on outcome. Micrometer compares an observation's key set
              // against others recorded under the same name, so a chat that only sometimes
              // carried a finish reason would be a different shape from one that did.
              .lowCardinalityKeyValue(FINISH_REASONS, "none")
              .lowCardinalityKeyValue(ERROR_TYPE, "none")
              .start();
      try {
        InferenceResult result = delegate.infer(request, narrator);
        observation.lowCardinalityKeyValue(FINISH_REASONS, finishReasonOf(result));
        // A provider that answers with a Fault did not throw, and the span must still say so --
        // this is the whole point of a total SPI: the failure is a value, and a value that
        // nothing recorded would be a call that looks successful in every dashboard.
        if (result instanceof InferenceResult.Fault(Failure failure)) {
          observation.lowCardinalityKeyValue(ERROR_TYPE, failure.getClass().getSimpleName());
          // The adapter's own account of what went wrong -- a provider's stop reason, an HTTP
          // status -- which is the one thing worth reading on the span. High cardinality, so it
          // reaches the trace and stays out of the metric.
          observation.highCardinalityKeyValue("error.message", failure.reason());
        }
        return result;
      } catch (RuntimeException e) {
        observation.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
        observation.error(e);
        throw e;
      } finally {
        observation.stop();
      }
    };
  }

  /**
   * What the model did, in semconv's vocabulary.
   *
   * <p>Exhaustive, so a new kind of result has to be given a name here rather than silently
   * reported as whatever the last arm happened to be.
   */
  private static String finishReasonOf(InferenceResult result) {
    return switch (result) {
      case InferenceResult.Answer _ -> "stop";
      case InferenceResult.Actions _ -> "tool_calls";
      case InferenceResult.Refusal _ -> "content_filter";
      case InferenceResult.Fault _ -> "error";
    };
  }

  /**
   * One tool, observed.
   *
   * <p>Semconv gives tool execution its own operation name and its own attribute, so this is not a
   * Nessy-shaped metric either: it lands in the same {@code gen_ai.client.operation.duration}
   * histogram as a chat call, distinguished by {@code gen_ai.operation.name}.
   */
  public static <I> Tool<I> tool(Tool<I> delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, DELEGATE_NOT_NULL);
    Objects.requireNonNull(observations, OBSERVATIONS_NOT_NULL);
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
        Observation observation =
            Observation.createNotStarted(DURATION, observations)
                .contextualName("execute_tool " + delegate.name().value())
                .lowCardinalityKeyValue(OPERATION_NAME, "execute_tool")
                .lowCardinalityKeyValue("gen_ai.tool.name", delegate.name().value())
                .lowCardinalityKeyValue("gen_ai.tool.type", "function")
                .lowCardinalityKeyValue("nessy.tool.outcome", "none")
                .lowCardinalityKeyValue("nessy.tool.deferred", "none");
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

  /**
   * One approver, observed.
   *
   * <p>Nessy's own name, deliberately: semconv has no convention for asking a person, and
   * pretending an approval is a GenAI operation would put human latency in the same histogram as
   * model latency.
   *
   * <p>Its DURATION is the decision, not the wait. An approver that defers returns immediately and
   * the person takes three days; timing the human would mean holding a span open across a restart,
   * which is a job for the projection's asked_at, not for a tracer.
   */
  public static Approver approver(Approver delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, DELEGATE_NOT_NULL);
    Objects.requireNonNull(observations, OBSERVATIONS_NOT_NULL);
    return request -> {
      Observation observation =
          Observation.createNotStarted("nessy.approval", observations)
              .contextualName("approve " + request.toolName().value())
              .lowCardinalityKeyValue("gen_ai.agent.name", request.agentType().value())
              .lowCardinalityKeyValue("gen_ai.tool.name", request.toolName().value())
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
