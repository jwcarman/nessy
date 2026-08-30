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
package org.jwcarman.nessy.api.memory;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ToolResultMessage;
import org.jwcarman.nessy.api.message.UserMessage;

/**
 * What an agent remembers of its own conversation.
 *
 * <p><b>One method per thing that can actually happen.</b> There is no method taking an arbitrary
 * sequence of messages, and none taking a lone {@link ToolResultMessage} — so a stray tool result,
 * a scrambled group, and a half-written exchange are not things a caller can express, rather than
 * things an implementation has to refuse. The three methods below ARE the three legal shapes.
 */
public interface Memory {

  /**
   * Everything this agent has been told and has said, as a context a provider will accept.
   *
   * <p>Always valid, because nothing invalid can be remembered: an assistant turn that called tools
   * is only ever written together with the message answering it. Implementations do not need to
   * withhold a dangling exchange on the way out — there is never one to hide.
   */
  Context recall(AgentId agentId);

  /** Something happened, and the agent was told about it. */
  void remember(AgentId agentId, UserMessage message);

  /**
   * A turn that called no tools.
   *
   * <p>The one rule the type system cannot state: {@code message} must carry no tool calls. A turn
   * that called tools is written through {@link #remember(AgentId, AssistantMessage,
   * ToolResultMessage)} instead, because the call and its answer are one fact about the
   * conversation and a transcript holding only the first is not a context anything can read.
   *
   * @throws IllegalArgumentException if {@code message} carries tool calls
   */
  void remember(AgentId agentId, AssistantMessage message);

  /**
   * A turn that called tools, together with their answers — atomically.
   *
   * <p><b>The trade this makes.</b> The assistant turn is not durable until its tools finish, so a
   * crash in that window loses it and the model is called again. That is deliberate: an invariant
   * enforced by the shape of the API is worth more than one every backend has to remember, and a
   * repeated model call is cheaper than a transcript persisted in a state nothing can read.
   *
   * @throws IllegalArgumentException if {@code results} does not answer exactly the tool calls in
   *     {@code message} — every id answered, and no unknown ids
   */
  void remember(AgentId agentId, AssistantMessage message, ToolResultMessage results);
}
