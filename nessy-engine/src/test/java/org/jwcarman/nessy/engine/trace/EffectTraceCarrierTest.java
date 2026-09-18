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
package org.jwcarman.nessy.engine.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.InputSchema;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * The carrier actually riding an effect out to the database and back.
 *
 * <p><b>Why this exists when {@code TracesTest} already passes.</b> That one drives {@link Traces}
 * directly, and an engine that never called it would satisfy every assertion in it -- and every
 * other test in this module. Delete the {@code traces.capture()} from {@code EffectStore.insert},
 * or the {@code traces.restore(...)} from {@code EffectDispatcher.perform}, and nothing anywhere
 * else goes red. A turn would simply stop coming back as one trace, silently, which is the whole
 * failure mode tracing has.
 *
 * <p>So this drives a real engine against a real database and asserts on two things that cannot
 * both be true of plumbing which is not connected: what the column holds while the work is in
 * flight, and what reaches the far side when it is performed.
 */
class EffectTraceCarrierTest {

  private static final String HEADER = "traceparent";
  private static final String VALUE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /** Stands in for a tracing bridge: writes an identity on the way out, reads one on the way in. */
  private static final class Propagation implements ObservationHandler<Observation.Context> {

    private final ConcurrentLinkedQueue<String> restored = new ConcurrentLinkedQueue<>();

    @Override
    public void onStart(Observation.Context context) {
      if (context instanceof SenderContext<?> sending) {
        inject(sending);
      }
      if (context instanceof ReceiverContext<?> receiving) {
        String parent = parentOf(receiving);
        if (parent != null) {
          restored.add(parent);
        }
      }
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    /** Generic so the wildcard is captured rather than cast away. */
    private static <C> void inject(SenderContext<C> sending) {
      sending.getSetter().set(sending.getCarrier(), HEADER, VALUE);
    }

    static <C> String parentOf(ReceiverContext<C> receiving) {
      return receiving.getGetter().get(receiving.getCarrier(), HEADER);
    }
  }

  @Test
  void an_effect_carries_its_trace_to_the_row_and_back_out_again() throws InterruptedException {
    Propagation propagation = new Propagation();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(propagation);

    AgentType type = new AgentType("traced");
    AgentId agentId = new AgentId(UUID.randomUUID());

    // Holds the effect in flight so its row can be read while it still exists. A completed turn
    // leaves no row behind, and a column nobody can see afterwards still has to have been right.
    CountDownLatch performing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (EngineFixture engine =
        new EngineFixture(
            (_, _) -> {
              performing.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"));
            },
            AgentEventListener.none(),
            registry)) {

      Harness<String> harness =
          engine
              .harnesses()
              .create(
                  String.class,
                  config ->
                      config
                          .agentType(type)
                          .systemPrompt("You are a test assistant.")
                          .inference(in -> in.model("a-model"))
                          .effects(e -> e.pollInterval(Duration.ofMillis(50))));

      harness.observe(agentId, "hello");
      assertThat(performing.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      List<String> onTheRow =
          engine
              .jdbc()
              .sql("SELECT trace_context FROM nessy_agent_effect WHERE agent_id = ?")
              .params(agentId.value())
              .query(String.class)
              .list();

      assertThat(onTheRow)
          .as("the turn's parent was written down beside the work, not left on a thread")
          .singleElement()
          .asString()
          .contains(HEADER)
          .contains(VALUE);

      assertThat(propagation.restored)
          .as("and was handed back to whoever performed it")
          .contains(VALUE);

      release.countDown();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(engine.history().entriesFrom(type, agentId, 0)).hasSize(2));
    }
  }

  /**
   * A turn is one flat trace. Every effect of it -- the inference, the approval, the tool call, the
   * inference after -- is written with the context captured once, when the observation opened the
   * turn, and none with the context of the effect whose outcome caused it. Nesting by emitter is
   * what made the follow-up inference the child of whichever tool finished last.
   */
  @Test
  void every_effect_of_a_turn_is_written_into_the_trace_the_turn_opened() {
    ConcurrentLinkedQueue<String> parents = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> emitted = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> named = new ConcurrentLinkedQueue<>();
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStart(Observation.Context context) {
                if (context instanceof SenderContext<?>) {
                  emitted.add(context.getName());
                }
                if (context instanceof ReceiverContext<?> receiving) {
                  String parent = Propagation.parentOf(receiving);
                  parents.add(parent == null ? "" : parent);
                }
              }

              @Override
              public void onStop(Observation.Context context) {
                if (context instanceof ReceiverContext<?>) {
                  named.add(context.getContextualName());
                }
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    AtomicInteger captures = new AtomicInteger();
    TraceCarrier carrier = () -> Map.of(HEADER, "turn-" + captures.incrementAndGet());

    AgentType type = new AgentType("flat");
    AgentId agentId = new AgentId(UUID.randomUUID());
    InferenceProvider model =
        (request, _) ->
            request.context().turns().stream().anyMatch(turn -> !turn.exchanges().isEmpty())
                ? new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"))
                : new InferenceResult.Actions(
                    List.of(new Block.ToolCall("call_1", "echo", "\"hi\"")));

    try (EngineFixture engine = new EngineFixture(model, registry, carrier)) {
      engine
          .harnesses()
          .create(
              String.class,
              config ->
                  config
                      .agentType(type)
                      .systemPrompt("You are a test assistant.")
                      .tool(ECHO)
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .observe(agentId, "hello");

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> assertThat(engine.history().entriesFrom(type, agentId, 0)).hasSize(5));
    }

    assertThat(captures).as("the context was captured once, when the turn opened").hasValue(1);
    assertThat(parents)
        .as("infer, approve, call_tool, infer: all beneath the turn, none beneath each other")
        .hasSize(4)
        .containsOnly("turn-1");
    assertThat(emitted).as("and no span was opened just to write a context down").isEmpty();
    assertThat(named)
        .as("each effect named for its kind, and a tool's for the tool")
        .containsExactly(
            "nessy.effect infer",
            "nessy.effect approve echo",
            "nessy.effect call_tool echo",
            "nessy.effect infer");
  }

  private static final Tool<String> ECHO =
      new Tool<>() {
        @Override
        public ToolName name() {
          return new ToolName("echo");
        }

        @Override
        public String description() {
          return "says it back";
        }

        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public InputSchema inputSchema(InputSchemaGenerator generator) {
          return new InputSchema("{\"type\":\"string\"}");
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text(request.input())));
        }
      };

  /**
   * Switching tracing off has to cost nothing, which means writing nothing: a column full of
   * carriers nobody will ever trace is a column paying for itself forever.
   */
  @Test
  void an_engine_that_is_not_tracing_writes_no_carrier_at_all() throws InterruptedException {
    AgentType type = new AgentType("untraced");
    AgentId agentId = new AgentId(UUID.randomUUID());
    CountDownLatch performing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (EngineFixture engine =
        new EngineFixture(
            (_, _) -> {
              performing.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
              return new InferenceResult.Answer(HistoryEntry.InferenceAnswered.text("done"));
            })) {

      engine
          .harnesses()
          .create(
              String.class,
              config ->
                  config
                      .agentType(type)
                      .systemPrompt("You are a test assistant.")
                      .inference(in -> in.model("a-model"))
                      .effects(e -> e.pollInterval(Duration.ofMillis(50))))
          .observe(agentId, "hello");

      assertThat(performing.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

      assertThat(
              engine
                  .jdbc()
                  .sql(
                      "SELECT count(*) FROM nessy_agent_effect WHERE agent_id = ?"
                          + " AND trace_context IS NOT NULL")
                  .params(agentId.value())
                  .query(Integer.class)
                  .single())
          .isZero();

      release.countDown();
    }
  }
}
