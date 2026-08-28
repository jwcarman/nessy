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
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
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
    org.jwcarman.nessy.spi.substrate.Substrate substrate =
        new org.jwcarman.nessy.spi.substrate.InMemorySubstrate(Clock.systemUTC());
    actors =
        new WatchmanActorSystem(
            ConfigFactory.load("watchman-inmemory").resolve(),
            new ScriptedWatchmanModel(Duration.ofMillis(10)),
            new FakeRunner(),
            new Memories(substrate, 8000),
            new SubstrateBacklogs<>(substrate, WatchmanObservations.COALESCER, String.class),
            WatchmanObservations.RENDERER,
            MicrometerTracing.over(sdk),
            Clock.systemUTC(),
            new BlockingWork(),
            Duration.ofMinutes(10),
            Duration.ofSeconds(10),
            new Claims(substrate));
    actors.start();
  }

  @AfterEach
  void stop() {
    actors.stop();
    sdk.close();
  }

  private AgentState state() {
    try {
      return actors.inspect(agent).toCompletableFuture().get(15, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The pending prune call's id, which is unique per call and never hardcoded. */
  private static String prune(WatchmanActorSystem actors, String agent) {
    try {
      return Calls.pending(
              actors.inspect(agent).toCompletableFuture().get(20, TimeUnit.SECONDS), "prune_images")
          .orElseThrow();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void every_span_a_round_produces_hangs_off_the_one_round_span() throws Exception {
    Traces traces = MicrometerTracing.over(sdk);

    // The cron's span, and the carrier it hands to the agent.
    Span round = sdk.getTracer("test").spanBuilder("watchman round").startSpan();
    Map<String, String> carrier;
    try (Scope ignored = round.makeCurrent()) {
      carrier = traces.capture();
    }
    actors.tell(agent, new AgentActor.Observe("It is noon. Do your rounds.", carrier));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(state().phase()).isInstanceOf(Phase.WorkingTools.class);
              assertThat(Calls.pending(state(), "prune_images")).isPresent();
            });
    actors
        .answerApproval(agent, prune(actors, agent), false, "james", "no")
        .toCompletableFuture()
        .get(15, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(state().phase()).isInstanceOf(Phase.Idle.class));
    round.end();

    List<SpanData> finished = spans.getFinishedSpanItems();
    assertThat(finished).isNotEmpty();

    String traceId = round.getSpanContext().getTraceId();
    List<String> names = finished.stream().map(SpanData::getName).toList();

    // The whole ROUND is one trace: the model calls and every tool ran under the cron's trace id,
    // despite each of them happening on a different thread in a different actor.
    //
    // "The round" is the operative word. Every receive now opens a span, including messages that
    // did NOT come from the round -- Inspect from the transcript page, AnswerApproval from the
    // approvals page. Those are correctly roots of their OWN traces, because that is what they
    // are: a human opening a page is not part of the cron tick that happened an hour ago. An
    // earlier version of this assertion demanded that every span share the round's trace, which
    // was only true back when the actor spanned nothing it had not been asked to do.
    List<String> externallyCaused =
        List.of("agent receive Inspect", "agent receive AnswerApproval");
    assertThat(finished)
        .filteredOn(span -> !span.getName().equals("watchman round"))
        .filteredOn(span -> !externallyCaused.contains(span.getName()))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));

    // And the externally-caused ones really are roots, not orphans pointing at a dead parent.
    assertThat(finished)
        .filteredOn(span -> externallyCaused.contains(span.getName()))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo("0000000000000000"));

    // The actor topology, readable straight off the trace: this is the argument for spanning every
    // receive rather than only the work. The names say which actor handled which message.
    assertThat(names)
        .contains(
            "agent receive Observe", "agent receive ModelReplied", "agent receive ToolCallSettled");

    // ...and the attributes are what make that topology answer operational questions rather than
    // merely showing that something happened. Asserted because they are easy to drop by accident
    // and nothing else would notice.
    assertThat(finished)
        .filteredOn(span -> span.getName().equals("agent receive Observe"))
        .singleElement()
        .satisfies(
            span -> {
              var attributes = span.getAttributes();
              assertThat(attributes.get(AttributeKey.stringKey("nessy.agent.id"))).isEqualTo(agent);
              assertThat(attributes.get(AttributeKey.stringKey("nessy.actor.path")))
                  .contains("agent-");
              assertThat(attributes.get(AttributeKey.stringKey("nessy.node.address")))
                  .startsWith("pekko://watchman");
              assertThat(attributes.get(AttributeKey.stringKey("nessy.turn.phase")))
                  .isEqualTo("Idle");
              assertThat(attributes.get(AttributeKey.stringKey("messaging.system")))
                  .isEqualTo("pekko");
            });

    // GenAI semantic conventions, asserted by name. These are DECLARED at their call sites rather
    // than derived from a message class, and this assertion is what keeps that honest: a missed
    // declaration produces an orphan span, never a compile error or an exception.
    assertThat(names).contains("search_memory", "chat", "create_memory", "execute_tool");

    // Three spans where there used to be one: recall, the model call, and the remembrance are
    // three different kinds of work with three different costs, and folding them into one leaf
    // span is exactly the regression this task fixes. All three must hang off the SAME parent --
    // whatever `chat` was already parented to -- so the trace still nests under the agent's
    // receive span rather than the three scattering under each other.
    List<String> chatParents =
        finished.stream()
            .filter(span -> span.getName().equals("chat"))
            .map(SpanData::getParentSpanId)
            .sorted()
            .toList();
    List<String> searchMemoryParents =
        finished.stream()
            .filter(span -> span.getName().equals("search_memory"))
            .map(SpanData::getParentSpanId)
            .sorted()
            .toList();
    List<String> createMemoryParents =
        finished.stream()
            .filter(span -> span.getName().equals("create_memory"))
            .map(SpanData::getParentSpanId)
            .sorted()
            .toList();
    assertThat(chatParents).isNotEmpty();
    assertThat(searchMemoryParents).isEqualTo(chatParents);
    assertThat(createMemoryParents).isEqualTo(chatParents);

    // search_memory and create_memory are internal work, not a remote call -- no Span.Kind, which
    // OpenTelemetry reports as INTERNAL.
    assertThat(finished)
        .filteredOn(
            span ->
                span.getName().equals("search_memory") || span.getName().equals("create_memory"))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL));

    // search_memory reports the size of what recall returned -- the number nobody could see
    // before, and the one a token-budget bug would move.
    assertThat(finished)
        .filteredOn(span -> span.getName().equals("search_memory"))
        .isNotEmpty()
        .allSatisfy(
            span -> {
              var attributes = span.getAttributes();
              assertThat(attributes.get(AttributeKey.stringKey("gen_ai.operation.name")))
                  .isEqualTo("search_memory");
              assertThat(attributes.get(AttributeKey.longKey("nessy.memory.messages"))).isNotNull();
              assertThat(attributes.get(AttributeKey.longKey("nessy.memory.tokens"))).isNotNull();
            });

    // The tool's identity is a TAG, not part of the span name -- a span name per tool would blow
    // up cardinality in every backend that indexes on it.
    assertThat(finished)
        .filteredOn(span -> span.getName().equals("execute_tool"))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getParentSpanId()).isNotEqualTo("0000000000000000"))
        .extracting(span -> span.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
        .contains("disk_usage", "containers");

    // The model call is CLIENT: the model is a remote service, not internal work.
    assertThat(finished)
        .filteredOn(span -> span.getName().equals("chat"))
        .isNotEmpty()
        .allSatisfy(span -> assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT));

    // Every chat span carries what it cost -- the whole point of this task. The scripted model
    // reports plausible, non-zero usage precisely so this cannot pass against an empty attribute.
    assertThat(finished)
        .filteredOn(span -> span.getName().equals("chat"))
        .isNotEmpty()
        .allSatisfy(
            span -> {
              var attributes = span.getAttributes();
              assertThat(attributes.get(AttributeKey.stringKey("gen_ai.operation.name")))
                  .isEqualTo("chat");
              assertThat(attributes.get(AttributeKey.longKey("gen_ai.usage.input_tokens")))
                  .isEqualTo(606L);
              assertThat(attributes.get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                  .isEqualTo(142L);
              assertThat(
                      attributes.get(AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")))
                  .isEqualTo(0L);
              assertThat(
                      attributes.get(AttributeKey.longKey("gen_ai.usage.cache_write.input_tokens")))
                  .isEqualTo(0L);
            });

    System.out.println("[watchman] trace " + traceId + " spans: " + names);
  }

  @Test
  void a_round_resumed_after_a_park_is_a_new_trace_linked_to_the_old_one() {
    Traces traces = MicrometerTracing.over(sdk);
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
