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

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * Decides whether an answer is the correct one by asking a model, because the same fact is worded
 * too many ways for a string comparison to judge: "7 May 2023", "the 7th of May, 2023" and "May 7"
 * are one answer, and "Paris, France" is a more specific and consistent version of "Paris".
 *
 * <p>The rule the model is given is in {@link LabPrompts#grade}. The reply is read after any
 * leading punctuation, quotes or markdown: it is a yes if it begins with the word yes and a no if
 * it begins with the word no. A reply that begins with neither is read by its last word instead,
 * because a model that reasons first ends on its answer. A reply that neither begins nor ends with
 * one of the two, one that begins with one and ends with the other, and a grading call that fails,
 * are {@link Verdict#NOT_UNDERSTOOD}: counted and printed, and graded as wrong. Every verdict comes
 * back with the reply it was read from, so one that was not understood can be read by a person.
 */
final class Grader {

  /** What a grading reply came to. */
  enum Verdict {
    YES,
    NO,
    NOT_UNDERSTOOD
  }

  private static final Pattern OPENING =
      Pattern.compile("^[^\\p{L}]*(yes|no)\\b", Pattern.CASE_INSENSITIVE);

  private static final Pattern CLOSING =
      Pattern.compile("(?<![\\p{L}\\p{N}])(yes|no)[^\\p{L}\\p{N}]*$", Pattern.CASE_INSENSITIVE);

  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final Duration pause;

  Grader(InferenceProvider provider, InferenceOptions options, Duration pause) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.pause = Objects.requireNonNull(pause, "pause must not be null");
  }

  /**
   * A verdict and what it was read from.
   *
   * @param verdict what the reply came to
   * @param reply the model's reply as it was written, or, for a grading call that failed, why
   */
  record Graded(Verdict verdict, String reply) {}

  /**
   * What the model says of {@code given} as the answer to {@code question}, whose key is {@code
   * correct}.
   */
  Graded grade(String question, String correct, String given) {
    try {
      String reply =
          Models.text(
              provider,
              Models.ask(
                  LabPrompts.GRADE_SYSTEM, LabPrompts.grade(question, correct, given), options),
              pause);
      return new Graded(parse(reply), reply);
    } catch (IllegalStateException gaveUp) {
      return new Graded(
          Verdict.NOT_UNDERSTOOD, "(the grading call failed: " + gaveUp.getMessage() + ")");
    }
  }

  /**
   * A grading reply read as a verdict: its first word if that is yes or no, and otherwise its last,
   * since a model that reasons before it answers ends on the answer. A reply that opens with one
   * and closes with the other ("No contradiction here ... yes") is not understood, since either
   * could be the verdict.
   */
  static Verdict parse(String reply) {
    Matcher opening = OPENING.matcher(reply);
    Matcher closing = CLOSING.matcher(reply);
    boolean opens = opening.find();
    boolean closes = closing.find();
    if (!opens && !closes) {
      return Verdict.NOT_UNDERSTOOD;
    }
    String word = opens ? opening.group(1) : closing.group(1);
    if (opens && closes && !word.equalsIgnoreCase(closing.group(1))) {
      return Verdict.NOT_UNDERSTOOD;
    }
    return word.equalsIgnoreCase("yes") ? Verdict.YES : Verdict.NO;
  }
}
