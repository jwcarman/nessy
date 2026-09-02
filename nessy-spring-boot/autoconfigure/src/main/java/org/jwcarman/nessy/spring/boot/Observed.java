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

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Iterator;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

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

  /** Semconv's histogram of tokens used, split by {@code gen_ai.token.type}. */
  private static final String TOKENS = "gen_ai.client.token.usage";

  private static final String OPERATION_NAME = "gen_ai.operation.name";
  private static final String FINISH_REASONS = "gen_ai.response.finish_reasons";
  private static final String DELEGATE_NOT_NULL = "delegate must not be null";
  private static final String OBSERVATIONS_NOT_NULL = "observations must not be null";

  private Observed() {}

  /**
   * Every model this provider hands out, observed.
   *
   * @param providerName the semconv {@code gen_ai.provider.name} for this vendor — {@code
   *     "openai"}, {@code "anthropic"}, {@code "gcp.gemini"}, {@code "aws.bedrock"}. Passed in
   *     because {@link Model} no longer reports its own vendor, and the application that built the
   *     provider is the one thing that knows. Each adapter publishes the right value as its own
   *     {@code PROVIDER_NAME} constant.
   */
  public static ModelProvider models(
      ModelProvider delegate,
      String providerName,
      ObservationRegistry observations,
      MeterRegistry meters) {
    Objects.requireNonNull(delegate, DELEGATE_NOT_NULL);
    return id -> model(delegate.model(id), providerName, observations, meters);
  }

  /** One model, observed: a span per call, lasting as long as the provider actually takes. */
  public static Model model(
      Model delegate, String providerName, ObservationRegistry observations, MeterRegistry meters) {
    Objects.requireNonNull(delegate, DELEGATE_NOT_NULL);
    Objects.requireNonNull(providerName, "providerName must not be null");
    Objects.requireNonNull(observations, OBSERVATIONS_NOT_NULL);
    Objects.requireNonNull(meters, "meters must not be null");
    return new Model() {
      @Override
      public org.jwcarman.nessy.api.model.ModelId id() {
        return delegate.id();
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        String model = delegate.id().value();
        Observation observation =
            Observation.createNotStarted(DURATION, observations)
                // Semconv's span name is "{operation} {model}", which the metric name cannot also
                // be — so the contextual name carries it and the meter keeps the histogram's name.
                .contextualName("chat " + model)
                .lowCardinalityKeyValue(OPERATION_NAME, "chat")
                .lowCardinalityKeyValue("gen_ai.provider.name", providerName)
                .lowCardinalityKeyValue("gen_ai.request.model", model)
                .lowCardinalityKeyValue("gen_ai.request.stream", "true")
                // Set at START, not on outcome. Micrometer compares an observation's key set
                // against others recorded under the same name, so a chat that only sometimes
                // carried a finish reason would be a different shape from one that did.
                .lowCardinalityKeyValue(FINISH_REASONS, "none")
                .lowCardinalityKeyValue("error.type", "none")
                .start();
        try {
          return new ObservedStream(
              delegate.stream(request), observation, meters, providerName, model);
        } catch (RuntimeException e) {
          observation.lowCardinalityKeyValue("error.type", e.getClass().getSimpleName());
          observation.error(e);
          observation.stop();
          throw e;
        }
      }
    };
  }

  /**
   * A stream that ends its observation when the caller is done with it.
   *
   * <p>Timing {@code stream()} alone would report almost nothing: the provider does its work as the
   * events are consumed. So the span stays open across iteration and closes with the stream, which
   * is what makes its duration the model's latency rather than the cost of returning an iterator.
   */
  private static final class ObservedStream implements ModelStream {

    private final ModelStream delegate;
    private final Observation observation;
    private final MeterRegistry meters;
    private final String providerName;
    private final String model;
    private final long startedAt = System.nanoTime();
    private boolean firstChunkSeen;
    private boolean stopped;

    private ObservedStream(
        ModelStream delegate,
        Observation observation,
        MeterRegistry meters,
        String providerName,
        String model) {
      this.delegate = delegate;
      this.observation = observation;
      this.meters = meters;
      this.providerName = providerName;
      this.model = model;
    }

    @Override
    public Iterator<ModelEvent> iterator() {
      Iterator<ModelEvent> events = delegate.iterator();
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return events.hasNext();
        }

        @Override
        public ModelEvent next() {
          ModelEvent event = events.next();
          if (!firstChunkSeen) {
            firstChunkSeen = true;
            // Distinguishes a model that is slow to start from one that is simply long-winded.
            observation.highCardinalityKeyValue(
                "gen_ai.response.time_to_first_chunk",
                String.valueOf((System.nanoTime() - startedAt) / 1_000_000));
          }
          recordOutcome(event);
          return event;
        }
      };
    }

    /** The tokens THIS call reported, and how it ended. */
    private void recordOutcome(ModelEvent event) {
      switch (event) {
        case ModelEvent.Stopped(StopReason reason, Usage usage) -> {
          observation.lowCardinalityKeyValue(FINISH_REASONS, finishReason(reason));
          tokens(usage);
        }
        case ModelEvent.Refused(_, _, Usage usage) -> {
          observation.lowCardinalityKeyValue(FINISH_REASONS, "content_filter");
          tokens(usage);
        }
        default -> {
          // Deltas and tool calls are the body of the turn, not its outcome.
        }
      }
    }

    /**
     * Our normalized reason as semconv spells it. {@code MAX_TOKENS} is {@code "length"} rather
     * than anything about tokens, which reads oddly and is what every provider reports.
     */
    private static String finishReason(StopReason reason) {
      return switch (reason) {
        case END_TURN -> "stop";
        case TOOL_USE -> "tool_calls";
        case MAX_TOKENS -> "length";
      };
    }

    /**
     * Records what the call cost, and records NOTHING for what the provider did not report.
     *
     * <p>A reported zero is still written, for the reason it always was: a missing attribute and a
     * genuine zero look identical on a graph, so "is the cache working" cannot be answered by an
     * attribute that appears only when caching happened. An UNREPORTED count is the other case
     * entirely — there is no measurement to write, and inventing a zero would drag a cache-hit rate
     * towards zero for every provider that keeps no cache books.
     */
    private void tokens(Usage usage) {
      count("gen_ai.usage.input_tokens", "input", usage.inputTokens());
      count("gen_ai.usage.output_tokens", "output", usage.outputTokens());
      // Subsets of input_tokens, never siblings — see Usage.
      count("gen_ai.usage.cache_read.input_tokens", "cache_read", usage.cacheReadInputTokens());
      count("gen_ai.usage.cache_write.input_tokens", "cache_write", usage.cacheWriteInputTokens());
    }

    /**
     * One count, onto both the span and its own {@code gen_ai.token.type} histogram — so a cache
     * hit rate is one query rather than arithmetic across metrics.
     */
    private void count(String attribute, String type, Integer tokens) {
      if (tokens == null) {
        return;
      }
      observation.highCardinalityKeyValue(attribute, String.valueOf(tokens));
      tokenSummary(type).record(tokens);
    }

    private DistributionSummary tokenSummary(String type) {
      return DistributionSummary.builder(TOKENS)
          .baseUnit("token")
          .tag(OPERATION_NAME, "chat")
          .tag("gen_ai.provider.name", providerName)
          .tag("gen_ai.request.model", model)
          .tag("gen_ai.token.type", type)
          .register(meters);
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        if (!stopped) {
          stopped = true;
          observation.stop();
        }
      }
    }
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
      public String name() {
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
      public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
        return delegate.inputSchema();
      }

      @Override
      public Awaited<ToolResult> execute(ToolCallRequest<I> call) {
        Observation observation =
            Observation.createNotStarted(DURATION, observations)
                .contextualName("execute_tool " + delegate.name())
                .lowCardinalityKeyValue(OPERATION_NAME, "execute_tool")
                .lowCardinalityKeyValue("gen_ai.tool.name", delegate.name())
                .lowCardinalityKeyValue("gen_ai.tool.type", "function")
                .lowCardinalityKeyValue("nessy.tool.outcome", "none")
                .lowCardinalityKeyValue("nessy.tool.deferred", "none");
        return observation.observe(
            () -> {
              Awaited<ToolResult> answer = delegate.execute(call);
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
              .contextualName("approve " + request.toolName())
              .lowCardinalityKeyValue("gen_ai.agent.name", request.agentType().name())
              .lowCardinalityKeyValue("gen_ai.tool.name", request.toolName())
              .highCardinalityKeyValue("gen_ai.agent.id", request.agentId().value())
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
