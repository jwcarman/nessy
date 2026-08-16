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
package org.jwcarman.nessy.examples.newsroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PendingAnswersTest {

  @Test
  void take_returns_the_answer_recorded_for_that_call_id() {
    PendingAnswers answers = new PendingAnswers();

    answers.record("call-1", "the answer");

    assertThat(answers.take("call-1")).isEqualTo("the answer");
  }

  @Test
  void take_clears_the_answer_so_it_cannot_be_read_twice() {
    PendingAnswers answers = new PendingAnswers();
    answers.record("call-1", "the answer");
    answers.take("call-1");

    assertThatThrownBy(() -> answers.take("call-1")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void take_fails_loud_when_no_answer_was_ever_recorded_for_that_call_id() {
    PendingAnswers answers = new PendingAnswers();

    assertThatThrownBy(() -> answers.take("never-recorded"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never-recorded");
  }

  @Test
  void a_later_record_for_the_same_call_id_overwrites_the_earlier_one() {
    PendingAnswers answers = new PendingAnswers();
    answers.record("call-1", "first");
    answers.record("call-1", "second");

    assertThat(answers.take("call-1")).isEqualTo("second");
  }
}
