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

import java.util.Locale;
import java.util.Objects;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;

/**
 * Decides whether an answer is the correct one by asking a model, because the same fact is worded
 * too many ways for a string comparison to judge: "7 May 2023", "the 7th of May, 2023" and "May 7"
 * are one answer, and "Paris, France" is a more specific and consistent version of "Paris".
 *
 * <p>The rule the model is given is in {@link LabPrompts#grade}. A reply that begins with "yes" is
 * a yes; anything else, including a reply that says nothing, is a no.
 */
final class Grader {

  private final InferenceProvider provider;
  private final InferenceOptions options;

  Grader(InferenceProvider provider, InferenceOptions options) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.options = Objects.requireNonNull(options, "options must not be null");
  }

  /** Whether {@code given} says what {@code correct} says in answer to {@code question}. */
  boolean correct(String question, String correct, String given) {
    String verdict =
        Models.text(
            provider,
            Models.ask(
                LabPrompts.GRADE_SYSTEM, LabPrompts.grade(question, correct, given), options));
    return verdict.strip().toLowerCase(Locale.ROOT).startsWith("yes");
  }
}
