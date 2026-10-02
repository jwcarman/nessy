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
package org.jwcarman.nessy.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import tools.jackson.databind.json.JsonMapper;

/** What a call cost, on whichever way it ended. */
class InferenceResultUsageTest {

  @Test
  void every_result_carries_its_cost_and_can_be_given_one() {
    Usage usage = Usage.of("a-model", 10, 20);
    InferenceResult answer = new InferenceResult.Answer(List.of(new Block.Text("hi")));
    InferenceResult refusal = new InferenceResult.Refusal("bio");
    InferenceResult fault = new InferenceResult.Fault(new Failure.Permanent("no"));
    InferenceResult actions =
        new InferenceResult.Actions(List.of(new Block.ToolCall("c1", "t", "{}")));

    InferenceResult truncated = new InferenceResult.Truncated(List.of(new Block.Text("par")));

    for (InferenceResult result : List.of(answer, refusal, fault, actions, truncated)) {
      assertThat(result.usage()).isEqualTo(Usage.unreported());
      InferenceResult priced = result.withUsage(usage);
      assertThat(priced.usage()).isEqualTo(usage);
      assertThat(priced.getClass()).isEqualTo(result.getClass());
    }
  }

  @Test
  void a_truncated_result_survives_being_stored_and_read_back() {
    JsonMapper mapper = JsonMapper.builder().build();
    InferenceResult truncated =
        new InferenceResult.Truncated(List.of(new Block.Text("par")), Usage.of("a-model", 10, 20));

    InferenceResult read =
        mapper.readValue(mapper.writeValueAsString(truncated), InferenceResult.class);

    assertThat(read).isEqualTo(truncated);
  }

  @Test
  void a_result_stored_before_truncated_existed_still_reads() {
    JsonMapper mapper = JsonMapper.builder().build();
    String stored =
        """
        {"type":"answer","blocks":[{"type":"text","text":"hi"}],"usage":{"model":null}}""";
    InferenceResult.Answer expected = new InferenceResult.Answer(List.of(new Block.Text("hi")));

    InferenceResult read = mapper.readValue(stored, InferenceResult.class);

    assertThat(read.getClass()).isEqualTo(expected.getClass());
    assertThat(((InferenceResult.Answer) read).blocks()).isEqualTo(expected.blocks());
  }

  @Test
  void a_refusal_stored_before_truncated_existed_still_reads_with_its_usage() {
    JsonMapper mapper = JsonMapper.builder().build();
    String stored =
        """
        {"type":"refusal","category":"bio","usage":{"model":"a-model","inputTokens":10,"outputTokens":20,"cacheReadTokens":null,"cacheWriteTokens":null,"reasoningTokens":null}}""";

    InferenceResult read = mapper.readValue(stored, InferenceResult.class);

    assertThat(read).isEqualTo(new InferenceResult.Refusal("bio", Usage.of("a-model", 10, 20)));
  }

  @Test
  void a_fault_stored_before_truncated_existed_still_reads_with_its_usage() {
    JsonMapper mapper = JsonMapper.builder().build();
    String stored =
        """
        {"type":"fault","failure":{"type":"permanent","reason":"no"},"usage":{"model":"a-model","inputTokens":10,"outputTokens":20,"cacheReadTokens":null,"cacheWriteTokens":null,"reasoningTokens":null}}""";

    InferenceResult read = mapper.readValue(stored, InferenceResult.class);

    assertThat(read)
        .isEqualTo(
            new InferenceResult.Fault(new Failure.Permanent("no"), Usage.of("a-model", 10, 20)));
  }
}
