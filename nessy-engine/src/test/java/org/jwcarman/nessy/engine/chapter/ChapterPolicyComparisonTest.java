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
package org.jwcarman.nessy.engine.chapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.engine.chapter.ContextReplay.answer;
import static org.jwcarman.nessy.engine.chapter.ContextReplay.calls;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.engine.chapter.ContextReplay.Call;
import org.jwcarman.nessy.engine.chapter.ContextReplay.Report;
import org.jwcarman.nessy.engine.chapter.ContextReplay.Totals;

/**
 * One scripted conversation of sixty turns, every third of which makes a tool call, replayed
 * through the real direct harness under different chapter policies. No model is called and no
 * database is used; what is compared is what each policy sends the model and how much of it is
 * unchanged from the call before, which is what a provider's prompt cache could reuse.
 *
 * <p>Each run's totals print as one line, and the per-call table prints for one run, so the output
 * of {@code ./mvnw -q -pl :nessy-engine -am test -Dtest=ChapterPolicyComparisonTest} is the
 * comparison.
 *
 * <p>Reading the three numbers: <em>sent</em> is the size of the context; <em>changed</em> is the
 * text that differs from the call before, which a cache cannot serve and a provider reads uncached;
 * the <em>share</em> is the part that did not change, and is comparable only between runs that send
 * about the same amount.
 *
 * <p>Only the policy differs between runs: the maximum tail (40) is the same everywhere, and the
 * maximum chapter length (30) is the same wherever there are chapters, so every run builds.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterPolicyComparisonTest {

  private static final int TURNS = 60;
  private static final int MAX_CHAPTER_LENGTH = 30;
  private static final int MAX_TAIL = 40;

  private static ContextReplay conversation() {
    ContextReplay.Builder builder = ContextReplay.conversation();
    for (int turn = 1; turn <= TURNS; turn++) {
      if (turn % 3 == 0) {
        builder.turn(
            "question " + turn + " needs a record",
            calls("lookup", "{\"key\":\"K" + turn + "\"}"),
            answer("the record for question " + turn + " says what was expected"));
      } else {
        builder.turn("question " + turn, answer("the answer to question " + turn));
      }
    }
    return builder.build();
  }

  private static Customizer<DirectHarnessConfig<String>> sizes(
      Customizer<DirectHarnessConfig<String>> more) {
    return c -> {
      c.inference(
          in -> in.context(ctx -> ctx.maxChapterLength(MAX_CHAPTER_LENGTH).maxTail(MAX_TAIL)));
      more.customize(c);
    };
  }

  private static Report chaptersOff() {
    return conversation()
        .run(c -> c.inference(in -> in.context(ctx -> ctx.withoutChapters().maxTail(MAX_TAIL))));
  }

  private static Report every(int turns) {
    return conversation().run(ChapterPolicy.every(turns), sizes(c -> {}));
  }

  private static void print(String label, Report report) {
    System.out.println(report.totals().line(label));
  }

  @Nested
  @DisplayName("One conversation under four policies")
  class FourPolicies {

    private static final Report off = chaptersOff();
    private static final Report every20 = every(20);
    private static final Report every10 = every(10);
    private static final Report every5 = every(5);

    @Test
    void the_totals_of_each_run_print_as_one_line() {
      // sent = the context's size; changed = what a cache cannot serve; the share is comparable
      // only between runs that send about the same amount.
      System.out.println();
      System.out.println(
          "Sixty turns, every third calling a tool; characters sent to the model, and how many of"
              + " them were unchanged from the call before.");
      print("chapters off", off);
      print("every(20)", every20);
      print("every(10)", every10);
      print("every(5)", every5);
      System.out.println();
      System.out.println("Per call, every(20):");
      System.out.println(every20.table());

      for (Report run : List.of(off, every20, every10, every5)) {
        assertThat(run.totals().calls())
            .as("one call per turn plus one per tool call")
            .isEqualTo(80);
      }
    }

    @Test
    void with_chapters_off_every_call_after_the_fortieth_turn_has_a_full_tail_and_no_summaries() {
      List<Call> late = off.after(40);

      assertThat(late).isNotEmpty();
      assertThat(late).allSatisfy(call -> assertThat(call.tail()).isEqualTo(40));
      assertThat(late).allSatisfy(call -> assertThat(call.summaries()).isZero());
    }

    @Test
    void with_every_20_turn_45_has_two_summaries_and_a_tail_of_four() {
      // every(20) closes a chapter after the 20th and the 40th turn, so the 45th is shown two
      // summaries (turns 1-20 and 21-40) and the four ended turns after them: 41, 42, 43 and 44.
      List<Call> turn45 = every20.onTurn(45);

      assertThat(turn45).isNotEmpty();
      assertThat(turn45).allSatisfy(call -> assertThat(call.summaries()).isEqualTo(2));
      assertThat(turn45).allSatisfy(call -> assertThat(call.tail()).isEqualTo(4));
    }

    @Test
    void every_20_keeps_a_larger_share_of_the_text_stable_than_chapters_off_after_turn_40() {
      Totals withChapters = every20.totalsAfter(40);
      Totals without = off.totalsAfter(40);

      assertThat(withChapters.calls()).isEqualTo(without.calls()).isPositive();
      assertThat(withChapters.ratio()).isGreaterThan(without.ratio());
    }

    @Test
    void every_20_changes_less_text_than_chapters_off_after_turn_40() {
      Totals withChapters = every20.totalsAfter(40);
      Totals without = off.totalsAfter(40);

      assertThat(withChapters.calls()).isEqualTo(without.calls()).isPositive();
      assertThat(withChapters.changed()).isLessThan(without.changed());
    }

    @Test
    void no_run_shows_a_turn_both_in_a_summary_and_verbatim() {
      for (Report run : List.of(off, every20, every10, every5)) {
        assertThat(run.calls()).isNotEmpty();
        assertThat(run.calls())
            .allSatisfy(
                call -> assertThat(call.summarizedThrough()).isLessThan(call.firstVerbatim()));
      }
    }
  }

  @Nested
  @DisplayName("Ambient text that changes on every call")
  class ChangingAmbient {

    @Test
    void the_stable_prefix_is_the_same_as_without_it() {
      AtomicInteger ticks = new AtomicInteger();
      AmbientSource clock =
          AmbientSource.of(
              source ->
                  source
                      .kind("clock")
                      .offering(
                          _ ->
                              Optional.of(
                                  Ambient.text("clock", "tick " + ticks.incrementAndGet()))));
      Report with =
          conversation()
              .run(
                  ChapterPolicy.every(20),
                  sizes(c -> c.inference(in -> in.context(ctx -> ctx.ambient(clock)))));
      Report without = every(20);

      assertThat(with.calls()).hasSameSizeAs(without.calls()).isNotEmpty();
      assertThat(with.calls()).allSatisfy(call -> assertThat(call.ambientChars()).isPositive());
      assertThat(with.calls().stream().map(Call::stablePrefix).toList())
          .isEqualTo(without.calls().stream().map(Call::stablePrefix).toList());
    }
  }
}
