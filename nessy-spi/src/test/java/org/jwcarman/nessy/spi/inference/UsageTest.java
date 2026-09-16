package org.jwcarman.nessy.spi.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;

class UsageTest {

  @Test
  void unknown_is_not_zero_and_a_half_known_count_is_refused() {
    assertThat(Usage.unknown().known()).isFalse();
    assertThat(Usage.unknown().totalTokens()).isEqualTo(-1);
    assertThat(new Usage(0, 0).known()).isTrue();
    assertThat(new Usage(3, 4).totalTokens()).isEqualTo(7);
    assertThatThrownBy(() -> new Usage(3, -1)).isInstanceOf(IllegalArgumentException.class);
  }

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
