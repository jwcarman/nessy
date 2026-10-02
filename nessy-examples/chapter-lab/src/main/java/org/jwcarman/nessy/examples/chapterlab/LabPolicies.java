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
package org.jwcarman.nessy.examples.chapterlab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * The chapter policies a lab run can choose between, besides {@link ChapterPolicy#every(int)}.
 *
 * <p>Both of them know something an engine policy does not: where the recorded sessions end, which
 * the lab knows because it drove the turns, and what the open turns say, which a policy may read
 * because it is asked off the agent's own thread.
 */
final class LabPolicies {

  /** How many open turns the hindsight policy shows the model at once. */
  static final int WINDOW = 40;

  /** Where a chapter ends when the model named no usable break. */
  private static final int FALLBACK_LENGTH = 20;

  /** A longer run of digits is not an exchange number, and would not fit an int. */
  private static final int MAX_DIGITS = 6;

  private static final Pattern GROUP = Pattern.compile("\\[([^\\[\\]]*)\\]");

  private static final Pattern NUMBER = Pattern.compile("\\d+");

  private LabPolicies() {}

  /**
   * A chapter for each recorded session: a chapter ends at every open turn that is the last of its
   * session. {@code sessionEnds} is read when the policy is asked, so the lab may add a turn to it
   * until the turn ends.
   */
  static ChapterPolicy session(Set<TurnId> sessionEnds) {
    return open -> open.turns().stream().filter(sessionEnds::contains).toList();
  }

  /**
   * Chapters cut in hindsight: once {@link #WINDOW} turns are open, a model reads them and names
   * the turns that end a chapter; until then nothing closes, and what is left when the conversation
   * stops stays open as the tail.
   *
   * <p>Whatever the model names, the policy closes at least one chapter when it closes any, and
   * never names a turn outside the window it showed.
   */
  static ChapterPolicy hindsight(
      TurnHistories histories, InferenceProvider provider, InferenceOptions options) {
    return open -> {
      if (open.turns().size() < WINDOW) {
        return List.of();
      }
      List<TurnId> window = open.turns().subList(0, WINDOW);
      List<Turn> turns =
          histories
              .forAgent(open.agentType(), open.agentId())
              .turnsBetween(window.getFirst(), window.getLast());
      List<String> rendered =
          turns.stream().map(turn -> Transcripts.render(List.of(turn))).toList();
      String reply =
          Models.text(
              provider,
              Models.ask(LabPrompts.HINDSIGHT_SYSTEM, LabPrompts.hindsightAsk(rendered), options));
      return ends(numbers(reply), window);
    };
  }

  /** The turns named by {@code numbers} (from one) among {@code window}, ascending and distinct. */
  static List<TurnId> ends(List<Integer> numbers, List<TurnId> window) {
    Set<Integer> sorted = new TreeSet<>();
    for (int number : numbers) {
      if (number >= 1 && number <= window.size()) {
        sorted.add(number);
      }
    }
    if (sorted.isEmpty()) {
      sorted.add(Math.min(FALLBACK_LENGTH, window.size()));
    }
    List<TurnId> ends = new ArrayList<>();
    for (int number : sorted) {
      ends.add(window.get(number - 1));
    }
    return ends;
  }

  /**
   * The whole numbers in the last bracketed group of a model's reply, in the order it wrote them:
   * "exchanges 1 to 40: [12, 25]" names 12 and 25. A reply with no bracketed group names none.
   */
  static List<Integer> numbers(String reply) {
    List<Integer> numbers = new ArrayList<>();
    Matcher group = GROUP.matcher(reply);
    String last = null;
    while (group.find()) {
      last = group.group(1);
    }
    if (last == null) {
      return numbers;
    }
    Matcher found = NUMBER.matcher(last);
    while (found.find()) {
      if (found.group().length() <= MAX_DIGITS) {
        numbers.add(Integer.parseInt(found.group()));
      }
    }
    return numbers;
  }
}
