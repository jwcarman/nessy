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

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * Where an answer arrives when it did not come back from the call that asked for it.
 *
 * <p>The other half of {@link org.jwcarman.nessy.api.Awaited.Deferred}: that says "later", and this
 * is where later happens. An approver posted an approval request to a person and returned; a tool
 * queued a job and returned. Hours or days on, something has the answer, and this is the door it
 * comes through.
 *
 * <p><b>A call is addressed by its agent and its key.</b> The agent is an agent type and an agent
 * id, and the key is the call's {@link IdempotencyKey}, which an {@link ApprovalRequest} and a
 * {@link ToolCallRequest} both carry. Nothing else is needed, and nothing needs to be kept apart
 * from those three values. The key, not the call id, finds the call: a model may repeat a call id
 * in a later request of the same turn, and the key is what tells the two calls apart.
 *
 * <p><b>Not on a harness, and not because of tidiness.</b> The agent type names the harness, and
 * this resolves it. It is also nothing to do with an application's input type, and a webhook
 * answering an approval should not have to name one.
 *
 * <p><b>Nessy does not check who is answering.</b> Anyone who has the three values and can reach
 * the code that calls this can answer the call. Guarding that code is the application's job: it
 * sends the values only to the party who should answer, and its endpoint checks that the caller is
 * that party.
 *
 * <p>A reply writes on the calling thread. On a JDBC backend it joins a transaction the caller has
 * open, and narration and the dispatcher's nudge happen when the answer returns, which can be
 * before the caller commits.
 *
 * <p><b>The caller is rarely the approver or the tool.</b> It is a webhook controller, a queue
 * consumer, an admin page -- code somewhere else entirely. That is why this is injected.
 *
 * <p>Every argument is required; a null is refused with a {@link NullPointerException}. Answering
 * is idempotent in the only way that matters: a second answer for the same call is {@link
 * ReplyOutcome.Ignored}, never folded twice. The caller is told {@link ReplyOutcome.Applied} only
 * when the answer changed the agent's state.
 */
public interface Replies {

  /**
   * A verdict on a call that was waiting for one.
   *
   * <p>Approving does not run the tool here -- it records the permission and lets the agent
   * dispatch the call as its own piece of durable work. So this returns as soon as the agent has
   * been told, not when the tool has finished, and a slow tool never holds a webhook open.
   *
   * <p>On a JDBC backend this joins a transaction the caller has open, so the answer commits or
   * rolls back with the caller's own writes. The in-memory backend has no transaction to join.
   *
   * @param type the agent type of the call's agent
   * @param id the call's agent
   * @param key the call's idempotency key
   * @param result approved or denied, optionally naming who or what decided
   */
  ReplyOutcome approve(AgentType type, AgentId id, IdempotencyKey key, ApprovalResult result);

  /**
   * A result for a call whose tool deferred.
   *
   * <p>Kept apart from {@link #approve} because the two answer different questions: a call still
   * awaiting permission cannot be settled with a result, which would run past the gate rather than
   * through it, and a call already running cannot be given a verdict.
   *
   * <p>Joins a transaction the caller has open on a JDBC backend, as {@link #approve} does.
   *
   * @param type the agent type of the call's agent
   * @param id the call's agent
   * @param key the call's idempotency key
   * @param result what the tool produced
   */
  ReplyOutcome complete(AgentType type, AgentId id, IdempotencyKey key, ToolResult result);
}
