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
 * it begins with the word no. A reply that begins with neither, and a grading call that fails, are
 * {@link Verdict#NOT_UNDERSTOOD}: counted and printed, and graded as wrong.
 */
final class Grader {

  /** What a grading reply came to. */
  enum Verdict {
    YES,
    NO,
    NOT_UNDERSTOOD
  }

  private static final Pattern VERDICT =
      Pattern.compile("^[^\\p{L}]*(yes|no)\\b", Pattern.CASE_INSENSITIVE);

  private final InferenceProvider provider;
  private final InferenceOptions options;
  private final Duration pause;

  Grader(InferenceProvider provider, InferenceOptions options, Duration pause) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
    this.pause = Objects.requireNonNull(pause, "pause must not be null");
  }

  /**
   * What the model says of {@code given} as the answer to {@code question}, whose key is {@code
   * correct}.
   */
  Verdict grade(String question, String correct, String given) {
    return Models.tryText(
            provider,
            Models.ask(
                LabPrompts.GRADE_SYSTEM, LabPrompts.grade(question, correct, given), options),
            pause)
        .map(Grader::parse)
        .orElse(Verdict.NOT_UNDERSTOOD);
  }

  /** A grading reply read as a verdict. */
  static Verdict parse(String reply) {
    Matcher found = VERDICT.matcher(reply);
    if (!found.find()) {
      return Verdict.NOT_UNDERSTOOD;
    }
    return found.group(1).equalsIgnoreCase("yes") ? Verdict.YES : Verdict.NO;
  }
}
