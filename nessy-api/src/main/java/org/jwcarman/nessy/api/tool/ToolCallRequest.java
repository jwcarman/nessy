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
package org.jwcarman.nessy.api.tool;

import java.time.Instant;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;

/**
 * One call of a tool: whose it is, which call it is, its arguments, how long the answer is worth
 * having, and where a late one goes.
 *
 * <p><b>The same coordinates an approver is given.</b> A tool acts where an approver only decides,
 * so anything the approver needed to judge a call the tool needs to perform it -- and a tool
 * knowing less than the thing that authorised it was the wrong way round.
 *
 * @param <I> the type this tool's arguments were bound to
 */
public interface ToolCallRequest<I> {

  /**
   * Which agent this call is for.
   *
   * <p><b>A tool that keeps anything has to know whose it is.</b> A notebook, a plan, a scratchpad
   * -- everything an agent writes down is written down per agent, and a tool is bound once to a
   * harness that serves every agent of its type. Without this, such a tool could only be built by
   * standing up a harness per agent, which is not what a harness is.
   *
   * <p>It is also exactly what {@link org.jwcarman.nessy.api.AmbientSource} is handed, which is
   * what lets the two halves of one feature meet: a tool writes under this id, and an ambient
   * source reads back under it on the next turn.
   *
   * <p>An approver has always been told this. A tool acts where an approver only decides, so a tool
   * knowing less than its own approver was the wrong way round.
   */
  AgentType agentType();

  /**
   * @see #agentType()
   */
  AgentId agentId();

  /**
   * The turn this call belongs to.
   *
   * <p>The unit of work an agent is actually doing: one question, however many calls it takes to
   * answer. A tool keeping anything scoped to the work in hand -- a scratchpad for one
   * investigation, a claim held for the length of a job -- needs this rather than the agent, which
   * outlives it.
   */
  TurnId turn();

  /**
   * Which call this is.
   *
   * <p>Unique within one model reply and no further, so it is an identity only alongside the agent
   * and the turn -- which is exactly how the engine keys it, and why all four travel together.
   */
  CallId callId();

  /**
   * The name this tool was called by.
   *
   * <p>Not necessarily the tool's own {@link Tool#name()}: one implementation may be bound under
   * several names, and a tool that wants to know which the model reached for can only learn it
   * here.
   */
  ToolName toolName();

  /** The call's arguments, already bound to {@link Tool#inputType()}. */
  I input();

  /**
   * When this call stops being worth finishing.
   *
   * <p>An instant rather than a duration, because a duration is a fresh budget every time it is
   * handed over and a deadline is the same fact however many times a call is attempted. Under a
   * retrying policy that difference is the whole point: five attempts of thirty seconds is a
   * hundred and fifty seconds nobody agreed to, whereas five attempts before one deadline is the
   * number that was configured. An attempt late in that sequence correctly sees less time left than
   * the first one did.
   *
   * <p>Configured as a duration -- {@code ToolConfig.timeout} -- and turned into an instant when
   * the call is written down, so an application says "tools get thirty seconds" and a tool is told
   * when thirty seconds is up.
   *
   * <p><b>Not the lease.</b> How long a lost call may hold its row before the engine presumes the
   * worker died and makes it due again is the engine's own number, per attempt, and no business of
   * the tool.
   *
   * <p>For a deferred answer this is when the question stops standing: the tool has told the
   * outside world where to reply, and this is how long that reply is still wanted.
   */
  Instant deadline();

  /**
   * Where an answer goes when it does not come back from {@link Tool#call}.
   *
   * <p>Only meaningful to a tool that returns {@link org.jwcarman.nessy.api.Awaited .Deferred}: it
   * hands this to whatever will eventually answer, and the engine matches the reply to the call
   * that is waiting for it.
   */
  ReplyToken replyToken();
}
