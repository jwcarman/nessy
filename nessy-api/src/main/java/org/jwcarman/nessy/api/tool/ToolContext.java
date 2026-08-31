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
 * What a running tool is told about the call it is serving, beyond its own arguments.
 *
 * <p>An interface rather than a parameter, so this can grow without breaking every tool ever
 * written: a future engine that wants to offer a progress channel, a cancellation signal, or a
 * deadline adds a default method here and nothing already compiled stops working. That is not
 * hypothetical — this type has carried more before and was narrowed back.
 */
public interface ToolContext {

  /**
   * Where an answer should be sent if this tool defers.
   *
   * <p>Available BEFORE the tool runs, so a tool can hand it to the outside world and only then
   * return {@link org.jwcarman.nessy.api.Awaited.Deferred}.
   *
   * <p>Holding it is the authority to settle this call, which is why it lives here rather than on
   * anything describing the work: a tool that logs or stores its own input cannot leak the power to
   * answer for itself.
   */
  ReplyToken replyToken();

  /** What kind of agent this is — the namespace every agent id lives in. */
  AgentType agentType();

  /**
   * Which agent this call is being made for.
   *
   * <p>A tool is granted to a KIND of agent and shared by every agent of that kind, so a tool that
   * keeps anything per agent — notes, a scratchpad, a per-tenant connection — cannot close over the
   * id and has to be told. {@code ApprovalRequest} has carried this pair from the start: an
   * approver knows whose call it is deciding, and it would be strange for the tool actually doing
   * the work to know less.
   */
  AgentId agentId();
}
