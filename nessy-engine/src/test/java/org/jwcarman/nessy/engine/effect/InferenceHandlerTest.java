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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.observability.CacheWatch;
import org.jwcarman.nessy.engine.tool.SettledLines;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InferenceHandlerTest {

  private static final AgentType TYPE = new AgentType("support");
  private static final AgentId AGENT = AgentId.random();

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final Deque<InferenceResult> script = new ArrayDeque<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  private final List<List<? extends Block>> stored = new ArrayList<>();

  private final InferenceHandler handler;
  private final InferenceHandler handlerWithTools;

  record Refund(String order, int cents) {}

  record Search(String query) {}

  private static <I> ToolBinding<I> bind(String name, Class<I> inputType, Stringifier<I> action) {
    Tool<I> tool =
        new Tool<>() {
          @Override
          public ToolName name() {
            return new ToolName(name);
          }

          @Override
          public String description() {
            return name;
          }

          @Override
          public Class<I> inputType() {
            return inputType;
          }

          @Override
          public Awaited<ToolResult> call(ToolCallRequest<I> request) {
            return Awaited.ready(ToolResult.ok(new Block.Text("ok")));
          }
        };
    return new ToolBinding<>(
        tool,
        JsonMapper.builder().build(),
        new JsonSchema("{\"type\":\"object\"}"),
        Duration.ofSeconds(30),
        new RetryPolicy.Never(),
        SettledLines.action(Optional.ofNullable(action)),
        SettledLines.result(Optional.empty()),
        List.of(),
        Approver.allow(),
        Duration.ofMinutes(10),
        new RetryPolicy.Never());
  }

  private InferenceHandler handlerOver(Tools tools, Payloads payloads) {
    return new InferenceHandler(
        TYPE,
        invocation -> script.removeFirst(),
        InferenceOptions.of("model"),
        new EffectTermsSource(
            tools,
            Duration.ofSeconds(1),
            new RetryPolicy.Never(),
            Duration.ofSeconds(1),
            new RetryPolicy.Never(),
            Duration.ofSeconds(1),
            new RetryPolicy.Never()),
        payloads,
        (type, id, event) -> {},
        new CacheWatch(registry),
        tools);
  }

  InferenceHandlerTest() {
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                stopped.add(context);
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    Payloads payloads =
        new Payloads() {
          @Override
          public PayloadRef put(List<? extends Block> content) {
            stored.add(content);
            return PayloadRef.of("p");
          }

          @Override
          public Resolved get(PayloadRef ref) {
            throw new UnsupportedOperationException();
          }
        };
    handler = handlerOver(Tools.none(), payloads);
    handlerWithTools =
        handlerOver(
            new Tools(
                List.of(
                    bind("refund", Refund.class, order -> "refund " + order.order()),
                    bind("search", Search.class, null),
                    bind(
                        "shout",
                        Search.class,
                        query -> {
                          throw new IllegalStateException("boom");
                        }))),
            payloads);
  }

  private static Block.ToolCall call() {
    return new Block.ToolCall(CallId.of("c1"), ToolName.of("search"), "{}");
  }

  private static Usage reading(int cached) {
    return Usage.of("model", 10, 10).withCacheRead(cached);
  }

  private void inferTwice(InferenceResult first, InferenceResult second, long secondTurn) {
    script.add(first);
    script.add(second);
    handler.handle(AGENT, new AgentEffect.Infer(TurnId.of(1)));
    handler.handle(AGENT, new AgentEffect.Infer(TurnId.of(secondTurn)));
  }

  @Nested
  class Tells_the_watch_the_usage_of_each_kind_of_result {

    @Test
    void an_answer() {
      inferTwice(
          new InferenceResult.Answer(List.of(new Block.Text("a")), reading(100)),
          new InferenceResult.Answer(List.of(new Block.Text("b")), reading(10)),
          1);

      assertThat(stopped).hasSize(1);
    }

    @Test
    void a_refusal() {
      inferTwice(
          new InferenceResult.Refusal("policy", reading(100)),
          new InferenceResult.Refusal("policy", reading(10)),
          1);

      assertThat(stopped).hasSize(1);
    }

    @Test
    void a_request_for_actions() {
      inferTwice(
          new InferenceResult.Actions(List.of(call()), reading(100)),
          new InferenceResult.Actions(List.of(call()), reading(10)),
          1);

      assertThat(stopped).hasSize(1);
    }

    @Test
    void a_fault() {
      inferTwice(
          new InferenceResult.Fault(new Failure.Permanent("no"), reading(100)),
          new InferenceResult.Fault(new Failure.Permanent("no"), reading(10)),
          1);

      assertThat(stopped).hasSize(1);
    }
  }

  @Nested
  class Tells_the_watch_the_turn {

    @Test
    void so_a_new_turn_reading_less_is_not_a_fall() {
      inferTwice(
          new InferenceResult.Refusal("policy", reading(100)),
          new InferenceResult.Refusal("policy", reading(10)),
          2);

      assertThat(stopped).isEmpty();
    }
  }

  @Nested
  class Delivers {

    @Test
    void a_truncated_reply_is_delivered_as_the_answer_it_is() {
      List<Block.AnswerContent> written = List.of(new Block.Text("The answer begins"));
      script.add(new InferenceResult.Truncated(written, reading(0)));

      Awaited<EffectOutcome> outcome = handler.handle(AGENT, new AgentEffect.Infer(TurnId.of(1)));

      assertThat(outcome)
          .isEqualTo(
              Awaited.ready(new EffectOutcome.InferenceAnswered(PayloadRef.of("p"), reading(0))));
      assertThat(stored).containsExactly(written);
    }
  }

  @Nested
  class Recording_what_each_call_would_do {

    private List<ActionRequest> requestedFor(Block.ToolCall... calls) {
      script.add(new InferenceResult.Actions(List.of(calls), reading(0)));

      Awaited<EffectOutcome> outcome =
          handlerWithTools.handle(AGENT, new AgentEffect.Infer(TurnId.of(1)));

      assertThat(outcome).isInstanceOf(Awaited.Ready.class);
      EffectOutcome value = ((Awaited.Ready<EffectOutcome>) outcome).value();
      return ((EffectOutcome.InferenceRequestedActions) value).actions();
    }

    private List<String> actionsOf(List<ActionRequest> requested) {
      return requested.stream()
          .map(ActionRequest.ToolCall.class::cast)
          .map(ActionRequest.ToolCall::action)
          .toList();
    }

    @Test
    void each_requested_call_carries_its_action() {
      List<ActionRequest> requested =
          requestedFor(
              new Block.ToolCall(
                  CallId.of("c1"), ToolName.of("refund"), "{\"order\":\"ord_88\",\"cents\":4200}"),
              new Block.ToolCall(CallId.of("c2"), ToolName.of("search"), "{\"query\":\"loch\"}"));

      assertThat(actionsOf(requested)).containsExactly("refund ord_88", "Search[query=loch]");
    }

    @Test
    void a_call_to_a_tool_that_is_not_bound_says_so() {
      List<ActionRequest> requested =
          requestedFor(new Block.ToolCall(CallId.of("c1"), ToolName.of("nope"), "{}"));

      assertThat(actionsOf(requested)).containsExactly("nope (no such tool)");
    }

    @Test
    void arguments_that_cannot_be_read_say_so() {
      List<ActionRequest> requested =
          requestedFor(new Block.ToolCall(CallId.of("c1"), ToolName.of("refund"), "not json"));

      assertThat(actionsOf(requested)).containsExactly("refund (its arguments could not be read)");
    }

    @Test
    void a_stringifier_that_throws_does_not_fail_the_request() {
      script.add(
          new InferenceResult.Actions(
              List.of(
                  new Block.ToolCall(CallId.of("c1"), ToolName.of("shout"), "{\"query\":\"x\"}")),
              reading(0)));

      Awaited<EffectOutcome> outcome =
          handlerWithTools.handle(AGENT, new AgentEffect.Infer(TurnId.of(1)));

      assertThat(outcome)
          .isEqualTo(
              Awaited.ready(
                  new EffectOutcome.InferenceRequestedActions(
                      PayloadRef.of("p"),
                      List.of(
                          new ActionRequest.ToolCall(
                              CallId.of("c1"),
                              ToolName.of("shout"),
                              "shout (its arguments could not be read)")),
                      reading(0))));
    }
  }
}
