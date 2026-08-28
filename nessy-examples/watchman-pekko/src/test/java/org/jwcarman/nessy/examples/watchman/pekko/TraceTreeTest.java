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
package org.jwcarman.nessy.examples.watchman.pekko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TRACE CONTEXT ACROSS ACTOR MESSAGES — the highest-risk part of this port, asserted rather than
 * eyeballed.
 *
 * <p>A round crosses at least four threads: the cron's, the agent's dispatcher, a model worker's,
 * and a virtual thread per tool. OpenTelemetry's context is a thread-local, so none of that
 * propagates by itself. If the manual carrying in {@link Traces} ever breaks, the symptom is not a
 * failure — it is a trace that quietly becomes a pile of unparented spans, which is exactly the
 * kind of regression nobody notices until they need the trace. Hence this test.
 */
@DisplayName("The trace a round produces")
class TraceTreeTest {

  /** The scripted model makes ids unique per round; these are round one\'s. */
  private static final String PRUNE = "call-prune-1";

  private static final String DISK = "call-disk-1";
  private static final String CONTAINERS = "call-containers-1";

  private InMemorySpanExporter spans;
  private OpenTelemetrySdk sdk;
  private WatchmanActorSystem actors;
  private String agent;

  @BeforeEach
  void start() {
    spans = InMemorySpanExporter.create();
    sdk =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans))
                    .build())
            .setPropagators(
                io.opentelemetry.context.propagation.ContextPropagators.create(
                    io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()))
            .build();
    agent = "trace-" + UUID.randomUUID();
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedModel(Duration.ofMillis(10)),
            new FakeRunner(),
            new Transcript(
                new org.jwcarman.nessy.spi.substrate.InMemorySubstrate(Clock.systemUTC())),
            new Traces(sdk),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10));
    actors.start();
  }

  @AfterEach
  void stop() {
    actors.stop();
    sdk.close();
  }

  private TurnState state() {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void every_span_a_round_produces_hangs_off_the_one_round_span() throws Exception {
    Traces traces = new Traces(sdk);

    // The cron's span, and the carrier it hands to the agent.
    Span round = sdk.getTracer("test").spanBuilder("watchman round").startSpan();
    Map<String, String> carrier;
    try (Scope ignored = round.makeCurrent()) {
      carrier = traces.capture();
    }
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", "rounds", carrier));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(state()).isInstanceOf(TurnState.WorkingTools.class);
              assertThat(((TurnState.WorkingTools) state()).call(PRUNE)).isPresent();
            });
    actors
        .answerApproval(agent, PRUNE, false, "james", "no")
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(state()).isInstanceOf(TurnState.Idle.class));
    round.end();

    List<SpanData> finished = spans.getFinishedSpanItems();
    assertThat(finished).isNotEmpty();

    String traceId = round.getSpanContext().getTraceId();
    List<String> names = finished.stream().map(SpanData::getName).toList();

    // The whole round is ONE trace: the model calls and every tool ran under the cron's trace id,
    // despite each of them happening on a different thread in a different actor.
    assertThat(finished)
        .filteredOn(span -> !span.getName().equals("watchman round"))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));

    assertThat(names).contains("model call", "tool disk_usage", "tool containers");

    // And the tools really are children of the round rather than roots of their own.
    assertThat(finished)
        .filteredOn(span -> span.getName().startsWith("tool "))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getParentSpanId()).isNotEqualTo("0000000000000000"));

    System.out.println("[watchman] trace " + traceId + " spans: " + names);
  }

  @Test
  void a_round_resumed_after_a_park_is_a_new_trace_linked_to_the_old_one() {
    Traces traces = new Traces(sdk);
    Span asked = sdk.getTracer("test").spanBuilder("watchman round").startSpan();
    Map<String, String> carrier;
    try (Scope ignored = asked.makeCurrent()) {
      carrier = traces.capture();
    }
    asked.end();

    // Days later, in another process: the answer cannot be a child of a span that has ended.
    String resumed =
        traces.inLinkedSpan(
            "approval answered", carrier, () -> Span.current().getSpanContext().getTraceId());

    assertThat(resumed).isNotEqualTo(asked.getSpanContext().getTraceId());
    assertThat(spans.getFinishedSpanItems())
        .filteredOn(span -> span.getName().equals("approval answered"))
        .singleElement()
        .satisfies(
            span ->
                assertThat(span.getLinks())
                    .as("the answer must link back to the round that asked")
                    .anySatisfy(
                        link ->
                            assertThat(link.getSpanContext().getTraceId())
                                .isEqualTo(asked.getSpanContext().getTraceId())));
  }

  @Test
  void the_actor_system_is_the_one_we_built() {
    ActorSystem<WatchmanGuardian.Command> system = actors.raw();

    assertThat(system.name()).isEqualTo("watchman");
  }
}
