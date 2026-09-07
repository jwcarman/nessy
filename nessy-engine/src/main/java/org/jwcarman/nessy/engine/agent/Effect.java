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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;

/**
 * What to do. Executed by the shell, never by the logic.
 *
 * <p>There is no READ effect. Reads happen in the shell before an input is fed, which is what keeps
 * {@link AgentLogic#decide} pure and testable without a database, a model or a cluster.
 *
 * <p><b>Wire names are a compatibility surface.</b> An effect outlives the process that decided it
 * -- it is a row in nessy_effect until the work is done -- so a rename here orphans every
 * obligation already committed under the old name. Never change one.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "do")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Effect.TakeWork.class, name = "take-work"),
  @JsonSubTypes.Type(value = Effect.CallModel.class, name = "call-model"),
  @JsonSubTypes.Type(value = Effect.AskApprover.class, name = "ask-approver"),
  @JsonSubTypes.Type(value = Effect.RunTool.class, name = "run-tool"),
  @JsonSubTypes.Type(value = Effect.Remember.Input.class, name = "remember-input"),
  @JsonSubTypes.Type(value = Effect.Remember.Answer.class, name = "remember-answer"),
  @JsonSubTypes.Type(value = Effect.Remember.Exchange.class, name = "remember-exchange"),
  @JsonSubTypes.Type(value = Effect.Release.class, name = "release"),
  @JsonSubTypes.Type(value = Effect.Forget.class, name = "forget"),
  @JsonSubTypes.Type(value = Effect.Narrate.TurnStarted.class, name = "narrate-turn-started"),
  @JsonSubTypes.Type(value = Effect.Narrate.Answered.class, name = "narrate-answered"),
  @JsonSubTypes.Type(value = Effect.Narrate.TurnEnded.class, name = "narrate-turn-ended"),
  @JsonSubTypes.Type(
      value = Effect.Narrate.ApprovalDecided.class,
      name = "narrate-approval-decided"),
  @JsonSubTypes.Type(
      value = Effect.Narrate.ToolCallCompleted.class,
      name = "narrate-tool-call-completed")
})
public sealed interface Effect {

  /** Ask the backlog store for the next row. Answers with {@code WorkTaken} or {@code NoWork}. */
  record TakeWork() implements Effect {}

  /**
   * Send the exchange to the model. Answers with a {@code ModelAnswered} or {@code ModelFailed}.
   */
  record CallModel() implements Effect {}

  /** Ask the approver about one call. */
  record AskApprover(CallId callId, String toolName) implements Effect {}

  /** Run one tool. */
  record RunTool(CallId callId, String toolName) implements Effect {}

  /**
   * Write to the transcript. Three different writes, because they are three different moments.
   *
   * <p>An exchange goes in WHOLE — the asking message and the results answering it, in one write —
   * so a transcript never holds half of one. That is what makes re-driving after a crash always
   * safe: whatever the turn was doing, asking the model again from what IS recorded is a correct
   * continuation.
   */
  sealed interface Remember extends Effect {

    /** The observation that started this turn, redeemed from its claim. */
    record Input() implements Remember {}

    /** What the model said, redeemed from its claim. */
    record Answer() implements Remember {}

    /** The asking message and every result, together. */
    record Exchange() implements Remember {}
  }

  /** Release everything this turn claimed. */
  record Release() implements Effect {}

  /**
   * Erase this agent: its memory, its backlog rows, its claims, and the state that records it
   * existed at all.
   *
   * <p>Issued only when the agent is idle — {@code AgentLogic} holds a busy agent's request until
   * its turn ends — so nothing is deleted from under work in flight.
   */
  record Forget() implements Effect {}

  /** Tell the narrator. The shell redeems whatever claim an event needs before it narrates. */
  sealed interface Narrate extends Effect {

    record TurnStarted(TurnId turnId) implements Narrate {}

    /**
     * The model settled on an answer -- narrated as its own effect, ordered ahead of {@link
     * TurnEnded}, because it is decided at the same moment {@code TurnEnded} is and must not wait
     * on the durable write {@link Remember.Answer} makes of the same fact.
     *
     * <p>Carries nothing: the shell redeems the same claim {@link Remember.Answer} redeems, which
     * is already written by the time this effect is decided (see {@code EffectWorker#answerOf}) --
     * so narrating never depends on whether the durable write has run, only on whether the model
     * actually answered.
     */
    record Answered() implements Narrate {}

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
