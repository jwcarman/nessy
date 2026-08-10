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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

class ScriptedSummarizerTest {

  private static Context head(String text) {
    return Context.of(List.of(Message.user(text)));
  }

  @Test
  void returns_the_scripted_summary_in_order() {
    ScriptedSummarizer summarizer =
        ScriptedSummarizer.builder().summary("first gist").summary("second gist").build();

    assertThat(summarizer.summarize(head("turn one"))).isEqualTo("first gist");
    assertThat(summarizer.summarize(head("turn two"))).isEqualTo("second gist");
  }

  @Test
  void records_every_head_it_was_handed_in_order() {
    ScriptedSummarizer summarizer =
        ScriptedSummarizer.builder().summary("first").summary("second").build();
    Context firstHead = head("turn one");
    Context secondHead = head("turn two");

    summarizer.summarize(firstHead);
    summarizer.summarize(secondHead);

    assertThat(summarizer.heads()).containsExactly(firstHead, secondHead);
  }

  @Test
  void heads_is_a_snapshot_rather_than_a_live_view() {
    ScriptedSummarizer summarizer =
        ScriptedSummarizer.builder().summary("first").summary("second").build();
    summarizer.summarize(head("turn one"));
    List<Context> snapshot = summarizer.heads();

    summarizer.summarize(head("turn two"));

    assertThat(snapshot).hasSize(1);
    assertThat(summarizer.heads()).hasSize(2);
  }

  @Test
  void a_scripted_throwing_outcome_throws_instead_of_returning() {
    RuntimeException scripted = new IllegalArgumentException("summarizer unavailable");
    ScriptedSummarizer summarizer = ScriptedSummarizer.builder().throwing(scripted).build();

    assertThatThrownBy(() -> summarizer.summarize(head("turn one"))).isSameAs(scripted);
  }

  @Test
  void a_throwing_outcome_still_records_the_head_it_was_handed() {
    RuntimeException scripted = new IllegalStateException("boom");
    ScriptedSummarizer summarizer = ScriptedSummarizer.builder().throwing(scripted).build();
    Context offeredHead = head("turn one");

    assertThatThrownBy(() -> summarizer.summarize(offeredHead)).isSameAs(scripted);

    assertThat(summarizer.heads()).containsExactly(offeredHead);
  }

  @Test
  void running_out_of_script_is_a_loud_failure() {
    ScriptedSummarizer summarizer = ScriptedSummarizer.builder().summary("only one").build();
    summarizer.summarize(head("turn one"));
    Context secondHead = head("turn two");

    assertThatThrownBy(() -> summarizer.summarize(secondHead))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("script exhausted");
  }
}
