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
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.observability.CacheWatch;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceResult;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InferenceHandlerTest {

  private static final AgentType TYPE = new AgentType("support");
  private static final AgentId AGENT = AgentId.random();

  private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
  private final Deque<InferenceResult> script = new ArrayDeque<>();
  private final ObservationRegistry registry = ObservationRegistry.create();

  private final InferenceHandler handler;

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
            return PayloadRef.of("p");
          }

          @Override
          public Resolved get(PayloadRef ref) {
            throw new UnsupportedOperationException();
          }
        };
    handler =
        new InferenceHandler(
            TYPE,
            invocation -> script.removeFirst(),
            InferenceOptions.of("model"),
            new EffectTermsSource(
                Tools.none(),
                Duration.ofSeconds(1),
                new RetryPolicy.Never(),
                Duration.ofSeconds(1),
                new RetryPolicy.Never(),
                Duration.ofSeconds(1),
                new RetryPolicy.Never()),
            payloads,
            (type, id, event) -> {},
            new CacheWatch(registry));
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
}
