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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferencePurpose;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObservedInferenceProviderTest {

  @Test
  void an_answer_finishes_for_stop() {
    InferenceResult answer = new InferenceResult.Answer(List.of(new Block.Text("hi")));

    assertThat(ObservedInferenceProvider.finishReasonOf(answer)).isEqualTo("stop");
  }

  @Test
  void a_truncated_reply_finishes_for_length() {
    InferenceResult truncated = new InferenceResult.Truncated(List.of(new Block.Text("par")));

    assertThat(ObservedInferenceProvider.finishReasonOf(truncated)).isEqualTo("length");
  }

  @Test
  void a_refusal_finishes_for_content_filter() {
    InferenceResult refusal = new InferenceResult.Refusal("bio");

    assertThat(ObservedInferenceProvider.finishReasonOf(refusal)).isEqualTo("content_filter");
  }

  @Test
  void a_fault_finishes_for_error() {
    InferenceResult fault = new InferenceResult.Fault(new Failure.Permanent("no"));

    assertThat(ObservedInferenceProvider.finishReasonOf(fault)).isEqualTo("error");
  }

  @Test
  void actions_finish_for_tool_calls() {
    InferenceResult actions =
        new InferenceResult.Actions(List.of(new Block.ToolCall("c1", "t", "{}")));

    assertThat(ObservedInferenceProvider.finishReasonOf(actions)).isEqualTo("tool_calls");
  }

  @Nested
  class The_model_call_span {

    private static final String PURPOSE = "nessy.inference.purpose";

    private final List<Observation.Context> recorded = new ArrayList<>();

    private InferenceRequest request() {
      return new InferenceRequest(
          new SystemPrompt("you are a test assistant"),
          InferenceContext.of(
              List.of(
                  new Turn(
                      new TurnId(1),
                      new Input(new Seq(1), List.of(new Block.Text("hello"))),
                      List.of(),
                      null,
                      0))),
          Toolset.none(),
          InferenceOptions.of("a-model"));
    }

    private void infer(InferenceRequest request) {
      ObservationRegistry observations = ObservationRegistry.create();
      observations
          .observationConfig()
          .observationHandler(
              new ObservationHandler<Observation.Context>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                  recorded.add(context);
                }
              });
      InferenceProvider provider =
          (_, _) -> new InferenceResult.Answer(List.of(new Block.Text("done")));
      ObservedInferenceProvider.wrap(provider, observations).infer(request);
    }

    @Test
    void says_what_the_call_is_for_when_it_is_an_answer() {
      infer(request());

      assertThat(recorded).hasSize(1);
      assertThat(recorded.getFirst().getLowCardinalityKeyValue(PURPOSE).getValue())
          .isEqualTo("answer");
    }

    @Test
    void says_what_the_call_is_for_when_it_is_a_summary() {
      infer(request().withPurpose(InferencePurpose.SUMMARY));

      assertThat(recorded).hasSize(1);
      assertThat(recorded.getFirst().getLowCardinalityKeyValue(PURPOSE).getValue())
          .isEqualTo("summary");
    }
  }
}
