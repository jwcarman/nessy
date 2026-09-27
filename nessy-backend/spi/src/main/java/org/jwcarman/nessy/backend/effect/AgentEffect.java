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
package org.jwcarman.nessy.backend.effect;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * A durable obligation to do work outside the transition. An effect is never proof that the work
 * happened -- only that it is owed.
 *
 * <p>An effect says what to do and nothing about how to run it: no retry policy, no timeout. Those
 * are terms of the binding it came from and travel in an {@link EffectRequest} alongside it.
 *
 * <p><b>Every effect names the turn that emitted it</b>, and it is the first thing each one says.
 * The fold always knows its own turn when it decides an effect is owed, and an outcome delivered
 * back has to be able to say which turn it answers -- otherwise a late answer for a turn that has
 * already closed is written down as the current turn's, and the caller driving that current turn is
 * handed somebody else's answer. The turn rides inside the serialized effect, so the queued door's
 * round trip through the outbox brings it back with no column of its own.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEffect.Infer.class, name = "infer"),
  @JsonSubTypes.Type(value = AgentEffect.Approve.class, name = "approve"),
  @JsonSubTypes.Type(value = AgentEffect.CallTool.class, name = "call-tool")
})
public sealed interface AgentEffect {

  /** The turn this effect was emitted in, and the only turn an answer to it may settle. */
  TurnId turn();

  /**
   * Every arm rejects a missing turn, and the reason is a deploy rather than a bug.
   *
   * <p>An effect row is bytes written by whichever build was running when the fold emitted it, so a
   * row queued before the turn existed decodes with a null one. Letting that through would carry
   * the null to the fold's own guard, where {@code done.turn().equals(turn)} throws -- inside the
   * lock, inside the transaction, on a path with nothing to catch it, leaving the agent waiting
   * forever on an effect nobody will retire.
   *
   * <p>Failing here instead makes such a row fail to DECODE, which is a case the dispatcher already
   * handles: it delivers the failure blob stored beside the payload and retires the row, so the
   * agent is told the effect is undispatchable rather than stranded. {@code nessy_agent_effect}'s
   * own schema anticipates exactly this -- "a rolled-back deploy leaves rows naming an effect type
   * the running build has never heard of" -- and a row missing its turn is the same situation
   * arriving by a different route.
   */
  private static void requireTurn(TurnId turn) {
    Objects.requireNonNull(turn, "turn must not be null");
  }

  /**
   * Ask the model to answer, given the conversation so far.
   *
   * <p>The messages travel in the effect rather than being looked up when it runs, so whatever
   * executes this needs nothing but the row: the request is exactly what the fold decided it should
   * be, even if the agent has moved on since.
   */
  record Infer(TurnId turn) implements AgentEffect {
    public Infer {
      requireTurn(turn);
    }
  }

  /**
   * Run one tool the model asked for.
   *
   * <p><b>An address, not a copy.</b> The call itself -- its name, its arguments -- is already
   * written down in the entry at {@code requestSeq}, and duplicating it into the effect row would
   * make two records of the same fact that a migration or a fix could put out of step. Whatever
   * performs this resolves the address against history, which is the one copy.
   *
   * <p>That is the opposite of {@link Infer}'s reasoning, and the difference is what each one
   * needs. An inference needs a window of the conversation that only makes sense as of now; a tool
   * call needs one immutable row that will read the same forever.
   *
   * <p><b>The name is the one thing copied, and it earns it.</b> What a call of this tool is worth
   * -- its timeout, how hard it is worth retrying, who must approve it -- is stated per tool, and
   * those terms are read while the row is being written, inside the fold's transaction, before
   * anything has been decoded or looked up. A row that named only its address would have to go and
   * read the story to find out what it is worth, which is a query inside the one transaction that
   * must not grow. Everything else about the call stays where it was written once.
   *
   * @param turn the turn that asked for this call
   * @param requestSeq the seq of the {@code InferenceRequestedActions} entry holding the call
   * @param callId which call within it, by the id the model gave it
   * @param toolName what the model asked for, which may no longer be bound to anything
   */
  record CallTool(TurnId turn, Seq requestSeq, CallId callId, ToolName toolName)
      implements AgentEffect {
    public CallTool {
      requireTurn(turn);
    }
  }

  /**
   * Find out whether one call may run.
   *
   * <p><b>Every call goes through this, including the ones nothing is gating.</b> An unbound
   * approver answers yes immediately and the row is gone in a millisecond, which is a real cost and
   * a small one -- and it buys a lifecycle with no second shape. A design that skipped the question
   * when no approver was configured would have two paths into a running tool, and the one that was
   * never exercised is the one a misconfiguration would silently take.
   *
   * <p>Its own effect rather than a step inside {@link CallTool}, because asking is work that can
   * fail on its own terms and has its own budget: an approval service is a different thing to reach
   * than the tool, a person takes minutes where a lookup takes seconds, and a failure to
   * <em>ask</em> is worth repeating where a failure to run a tool that may already have run is not.
   * Folded into the call, one retry policy would have had to mean both.
   *
   * <p>Never written to the story on its own. Permission granted is not something that happened in
   * the conversation, and the model has no use for it; permission refused is, and that is what
   * {@code ToolDenied} is for.
   */
  record Approve(TurnId turn, Seq requestSeq, CallId callId, ToolName toolName)
      implements AgentEffect {
    public Approve {
      requireTurn(turn);
    }
  }
}
