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

import java.util.List;

/**
 * Every prompt the lab sends a model, in one place so a change to wording is one change.
 *
 * <p>The wording is the wording of the Python probe that first compared chapter cuts on this data:
 * what the lab measures should differ from that probe by the engine under it and nothing else.
 */
final class LabPrompts {

  /**
   * What a summary writer is told when the summariser is {@code index}: an entry written to be
   * found rather than to be complete, which names things and does not explain them.
   */
  static final String INDEX_SUMMARY =
      """
      You write the index entry for one chapter of a long conversation between two people. The \
      entry stands in for the chapter, so a reader can tell what this chapter holds.

      Write the index entry in at most 100 words: the dates covered, then who and what the chapter \
      is about as short phrases (topics, people, places, events, decisions, plans). Name things; \
      do not explain them. No preamble.""";

  /** What the model is told while it marks the natural breaks in a run of open turns. */
  static final String HINDSIGHT_SYSTEM =
      "You split a conversation into chapters. A chapter is a run of consecutive exchanges about"
          + " one topic or one piece of business, ending where it is resolved or the subject"
          + " changes.";

  /** What the model is told when it answers a question from the record. */
  static final String ANSWER_SYSTEM =
      "Answer the question from the record of a conversation given below. Reply with the answer"
          + " only, in a few words. If the record does not say, reply: unknown.";

  /** What the model is told when it grades an answer. */
  static final String GRADE_SYSTEM =
      "You grade an answer against the correct answer. Reply with only yes or no.";

  private LabPrompts() {}

  /**
   * The request that asks for the chapter ends among {@code window}, each of which is already
   * rendered. Numbers run from one; the reply is a JSON list of them.
   */
  static String hindsightAsk(List<String> window) {
    StringBuilder listing = new StringBuilder();
    for (int k = 0; k < window.size(); k++) {
      if (k > 0) {
        listing.append("\n\n");
      }
      listing.append('[').append(k + 1).append("]\n").append(window.get(k).strip());
    }
    return listing
        + "\n\nThese are exchanges 1 to "
        + window.size()
        + ". Reply with only a JSON list of the numbers of the exchanges that END a chapter, in"
        + " order. If the final exchanges look unfinished, leave them out; give at least one"
        + " number.";
  }

  /** The question put to the answering model, after the record. */
  static String question(String text) {
    return "Question: " + text;
  }

  /**
   * The request that asks whether a given answer is the correct one. The rule is the one the lab
   * grades by: the same fact is yes however it is worded, and nothing vaguer than the correct
   * answer is.
   */
  static String grade(String question, String correct, String given) {
    return """
        Question: %s
        Correct answer: %s
        Given answer: %s

        Is the given answer correct? The same fact is yes whatever the wording, order or extra \
        detail, unless the extra detail contradicts it. A date expressed differently but meaning \
        the same day or period is yes. When the correct answer is a list, the given answer must \
        contain every item. More specific and consistent with the correct answer is yes; vaguer is \
        no. "unknown" or a refusal is no. Reply with only yes or no."""
        .formatted(question, correct, given);
  }
}
