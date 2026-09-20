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
package org.jwcarman.nessy.spi.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;

/** What a call cost, on whichever way it ended. */
class InferenceResultUsageTest {

  @Test
  void every_result_carries_its_cost_and_can_be_given_one() {
    Usage usage = new Usage(10, 20);
    InferenceResult answer = new InferenceResult.Answer(List.of(new Block.Text("hi")));
    InferenceResult refusal = new InferenceResult.Refusal("bio");
    InferenceResult fault = new InferenceResult.Fault(new Failure.Permanent("no"));
    InferenceResult actions =
        new InferenceResult.Actions(List.of(new Block.ToolCall("c1", "t", "{}")));

    for (InferenceResult result : List.of(answer, refusal, fault, actions)) {
      assertThat(result.usage()).isEqualTo(Usage.unknown());
      InferenceResult priced = result.withUsage(usage);
      assertThat(priced.usage()).isEqualTo(usage);
      assertThat(priced.getClass()).isEqualTo(result.getClass());
    }
  }
}
