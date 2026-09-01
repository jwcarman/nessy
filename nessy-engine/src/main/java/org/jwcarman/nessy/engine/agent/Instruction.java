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

import org.jwcarman.nessy.api.TurnResult;
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
  record AskApprover(String callId, String toolName) implements Instruction {}

  /** Run one tool. */
  record RunTool(String callId, String toolName) implements Instruction {}

  /** Write the exchange to memory. */
  record Remember() implements Instruction {}

  /** Release everything this turn claimed. */
  record Release() implements Instruction {}

  /** Arm a durable deadline for one call, so it outlives the process that set it. */
  record SetAlarm(String callId) implements Instruction {}

  /** Disarm it. */
  record CancelAlarm(String callId) implements Instruction {}

  /** Go to sleep. */
  record Sleep() implements Instruction {}

  /** Tell the narrator. The shell redeems whatever claim an event needs before it narrates. */
  sealed interface Narrate extends Instruction {

    record TurnStarted(String turnId) implements Narrate {}

    record TurnEnded(TurnResult result) implements Narrate {}

    record ToolCallRequested(String callId, String toolName) implements Narrate {}

    record ApprovalRequested(String callId) implements Narrate {}

    record ApprovalDecided(String callId, ApprovalResult result) implements Narrate {}

    record ToolCallCompleted(String callId) implements Narrate {}
  }
}
