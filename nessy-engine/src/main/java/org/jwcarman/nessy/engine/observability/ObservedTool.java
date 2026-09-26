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
package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * A tool whose every call is semconv's {@code execute_tool} span: the tool's name, the call it
 * answers, whose agent asked, and how it came out.
 *
 * <p>Wrapped where a tool is bound, so a tool registered by hand and one bound by the Boot starter
 * make the same span, and nothing a tool itself writes has to mention observability.
 */
public final class ObservedTool {

  private static final String DURATION = "gen_ai.client.operation.duration";
  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String TOOL_NAME = "gen_ai.tool.name";

  private ObservedTool() {}

  public static <I> Tool<I> wrap(Tool<I> delegate, ObservationRegistry observations) {
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
        new Identity(request.agentType(), request.agentId()).on(observation, request.turn());
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

  private static String outcomeOf(Awaited<ToolResult> answer) {
    return switch (answer) {
      case Awaited.Deferred<ToolResult> _ -> "deferred";
      case Awaited.Ready<ToolResult>(var result) ->
          result instanceof ToolResult.Success ? "success" : "failure";
    };
  }
}
