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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.TurnResult;

/** The value types refuse what would fail later and further from its cause. */
@DisplayName("A value that is checked where it is written")
class ValueValidationTest {

  private static final List<Block.ToolResultContent> NO_RESULT = List.of();
  private static final List<Block.AnswerContent> NO_ANSWER = List.of();
  private static final List<Block.ObservationContent> NO_OBSERVATION = List.of();

  @Test
  void identifiers_and_prompts_refuse_blanks_and_negatives() {
    assertThatThrownBy(() -> new Seq(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentType(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SystemPrompt("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReplyToken(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThat(AgentId.random().value()).isInstanceOf(UUID.class);
  }

  @Test
  void content_must_say_something() {
    CallId call = new CallId("c1");
    Seq seq = new Seq(1);
    assertThatThrownBy(() -> new Block.Text("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Block.Commentary("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Block.Provider(" ", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ToolOutcome.Succeeded(call, NO_RESULT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TurnResult.Answered(NO_ANSWER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Observation(seq, NO_OBSERVATION))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_retry_policy_without_jitter_has_none() {
    assertThat(new RetryPolicy.FixedDelay(3, Duration.ofSeconds(1), null).jitter())
        .isEqualTo(Duration.ZERO);
    assertThat(
            new RetryPolicy.Exponential(3, Duration.ofSeconds(1), 2.0, null, Duration.ofMinutes(1))
                .jitter())
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void the_default_summarizer_and_the_default_reach() {
    AgentId agent = AgentId.random();
    assertThat(Summarizer.none().forAgent(agent)).isEmpty();
    assertThat(Summarizer.none().summarizedThrough(agent)).isEmpty();

    Summarizer two =
        _ ->
            List.of(
                Summary.text(new TurnId(1), new TurnId(3), "a"),
                Summary.text(new TurnId(5), new TurnId(9), "b"));
    assertThat(two.summarizedThrough(agent)).contains(new TurnId(9));
  }

  @Test
  void a_constant_ambient_is_a_source_that_always_answers() {
    List<AmbientSource> added = new ArrayList<>();
    ContextConfig config =
        new ContextConfig() {
          @Override
          public ContextConfig summaries(Summarizer source) {
            return this;
          }

          @Override
          public ContextConfig maxTail(int turns) {
            return this;
          }

          @Override
          public ContextConfig ambient(AmbientSource source) {
            added.add(source);
            return this;
          }
        };

    config.ambient(Ambient.text("clock", "Tuesday"));

    assertThat(added).hasSize(1);
    assertThat(added.getFirst().forAgent(AgentId.random()))
        .isEqualTo(Optional.of(Ambient.text("clock", "Tuesday")));
  }
}
