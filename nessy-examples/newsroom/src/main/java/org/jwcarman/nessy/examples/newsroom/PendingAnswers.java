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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The narrow relay between {@link NewsroomRepl}'s console prompt and {@link AskQuestionTool}'s own
 * {@code execute}: {@link org.jwcarman.nessy.api.approval.Approver#parkAll()} gates {@code
 * ask_question} on approval alone (a bare {@link org.jwcarman.nessy.api.Decision}, yes or no), so
 * there is no channel in the harness itself for a human's free-text answer to ride back into the
 * tool call it approved. This map is that channel: the console records the operator's answer, keyed
 * by the parked {@link org.jwcarman.nessy.api.tool.ToolCall#id()}, immediately before driving
 * {@code researcher.approve(token)} — the same call id {@code execute} reads back once the gate
 * lets the call proceed.
 *
 * <p>One entry per outstanding question; {@link #take} both reads and clears it, so a call id is
 * never answered twice.
 */
final class PendingAnswers {

  private final Map<String, String> answers = new ConcurrentHashMap<>();

  /** Records {@code answer} for {@code callId}, overwriting whatever was recorded before. */
  void record(String callId, String answer) {
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(answer, "answer must not be null");
    answers.put(callId, answer);
  }

  /**
   * Reads and clears the answer recorded for {@code callId}.
   *
   * @throws IllegalStateException if no answer was recorded for {@code callId} — {@link
   *     AskQuestionTool#execute} only ever runs once the approval gate already let the call
   *     through, and the console always records an answer before approving, so a miss here means
   *     the two sides fell out of step.
   */
  String take(String callId) {
    Objects.requireNonNull(callId, "callId must not be null");
    String answer = answers.remove(callId);
    if (answer == null) {
      throw new IllegalStateException(
          "no answer recorded for call "
              + callId
              + " — ask_question ran before an answer was"
              + " recorded for it");
    }
    return answer;
  }
}
