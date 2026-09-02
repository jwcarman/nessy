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
package org.jwcarman.nessy.engine.agent;

import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;

/**
 * What to do. Executed by the shell, never by the logic.
 *
 * <p>There is no READ instruction. Reads happen in the shell before an input is fed, which is what
 * keeps {@link AgentLogic#decide} pure and testable without a database, a model or a cluster.
 */
public sealed interface Instruction {

  /** Ask the backlog store for the next row. Answers with {@code WorkTaken} or {@code NoWork}. */
  record TakeWork() implements Instruction {}

  /**
   * Send the exchange to the model. Answers with a {@code ModelAnswered} or {@code ModelFailed}.
   */
  record CallModel() implements Instruction {}

  /** Ask the approver about one call. */
  record AskApprover(CallId callId, String toolName) implements Instruction {}

  /** Run one tool. */
  record RunTool(CallId callId, String toolName) implements Instruction {}

  /**
   * Write to the transcript. Three different writes, because they are three different moments.
   *
   * <p>An exchange goes in WHOLE — the asking message and the results answering it, in one write —
   * so a transcript never holds half of one. That is what makes re-driving after a crash always
   * safe: whatever the turn was doing, asking the model again from what IS recorded is a correct
   * continuation.
   */
  sealed interface Remember extends Instruction {

    /** The observation that started this turn, redeemed from its claim. */
    record Input() implements Remember {}

    /** What the model said, redeemed from its claim. */
    record Answer() implements Remember {}

    /** The asking message and every result, together. */
    record Exchange() implements Remember {}
  }

  /** Release everything this turn claimed. */
  record Release() implements Instruction {}

  /** Arm a durable deadline for one call, so it outlives the process that set it. */
  record SetAlarm(CallId callId, java.time.Instant expiresAt) implements Instruction {}

  /** Disarm it. */
  record CancelAlarm(CallId callId) implements Instruction {}

  /** Go to sleep. */
  record Sleep() implements Instruction {}

  /** Tell the narrator. The shell redeems whatever claim an event needs before it narrates. */
  sealed interface Narrate extends Instruction {

    record TurnStarted(TurnId turnId) implements Narrate {}

    record TurnEnded(TurnResult result, Usage usage) implements Narrate {}

    // There is deliberately no ToolCallRequested or ApprovalRequested here. An ungated tool is
    // approved on the spot, so
    // the only moment a person is actually being ASKED is when the approver defers — and that is
    // known in the shell, along with the deadline the event has to carry.
    //
    // ToolCallRequested is the same story: it carries the renderer's sentence, which is a shell
    // concern. Emitting it from here meant the shell re-derived that sentence — reading the asking
    // claim back and running the renderer a second time, per narrated call.

    record ApprovalDecided(CallId callId, ApprovalResult result) implements Narrate {}

    record ToolCallCompleted(CallId callId) implements Narrate {}
  }
}
