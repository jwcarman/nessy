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

import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * One call the model asked for, and everything anyone answering it needs.
 *
 * <p>This replaces two separate context objects. A tool was handed a {@code ToolCallRequest} and an
 * approver an {@code ApprovalContext}, and between them they carried the same three or four facts
 * about the same call — so the pair had to be kept in step, and an approver could not see what the
 * tool would be given. One record about one call is the whole of it.
 *
 * @param agentType what kind of agent is calling — the namespace every agent id lives in
 * @param agentId which agent
 * @param turnId the turn this call belongs to
 * @param callId the model's own id for the call, unique within ONE response and no further
 * @param toolName the tool the model named
 * @param input the arguments the model produced, already bound to the tool's own input type
 *     <p><b>The reply address is minted on demand.</b> Most calls are answered on the spot and
 *     never need one, and a token is a CAPABILITY — whoever holds it can settle this call. Minting
 *     one for every call would do the cryptography regardless and hand out an authority nobody
 *     asked for, so {@link #replyToken()} is what mints it. Ask for it only when you are about to
 *     hand it out.
 * @param replyTokens mints the address an answer comes back to; prefer {@link #replyToken()}
 * @param <I> the tool's input type
 */
public record ToolCallRequest<I>(
    AgentType agentType,
    AgentId agentId,
    String turnId,
    String callId,
    String toolName,
    I input,
    Supplier<ReplyToken> replyTokens) {

  public ToolCallRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turnId, "turnId must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Objects.requireNonNull(toolName, "toolName must not be null");
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(replyTokens, "replyTokens must not be null");
    // Memoized, so a tool that hands the address to two places hands out ONE address rather than
    // two that happen to mean the same thing.
    replyTokens = memoizing(replyTokens);
  }

  /**
   * The address an answer comes back to, minted the first time it is asked for.
   *
   * <p>Handing this out is what makes a call answerable later — by a vendor's webhook, or by a
   * person clicking Approve days from now. A tool that answers on the spot never calls this, and
   * then no token for this call ever exists.
   */
  public ReplyToken replyToken() {
    return replyTokens.get();
  }

  /** A convenience for callers that already hold an address, and for tests. */
  public ToolCallRequest(
      AgentType agentType,
      AgentId agentId,
      String turnId,
      String callId,
      String toolName,
      I input,
      ReplyToken replyToken) {
    this(agentType, agentId, turnId, callId, toolName, input, () -> replyToken);
  }

  /**
   * Equality over what the call IS, ignoring how its address would be minted.
   *
   * <p>A record's generated equality would compare the supplier by identity, so two requests naming
   * the same call would differ — the same trap an array component sets, and the reason these are
   * written out.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ToolCallRequest<?> call
        && Objects.equals(agentType, call.agentType)
        && Objects.equals(agentId, call.agentId)
        && Objects.equals(turnId, call.turnId)
        && Objects.equals(callId, call.callId)
        && Objects.equals(toolName, call.toolName)
        && Objects.equals(input, call.input);
  }

  @Override
  public int hashCode() {
    return Objects.hash(agentType, agentId, turnId, callId, toolName, input);
  }

  /** The address is deliberately absent: it is a credential, and this may reach a log. */
  @Override
  public String toString() {
    return "ToolCallRequest[agentType=%s, agentId=%s, turnId=%s, callId=%s, toolName=%s, input=%s]"
        .formatted(agentType, agentId, turnId, callId, toolName, input);
  }

  private static Supplier<ReplyToken> memoizing(Supplier<ReplyToken> mint) {
    return new Supplier<>() {
      private ReplyToken minted;

      @Override
      public synchronized ReplyToken get() {
        if (minted == null) {
          minted = mint.get();
        }
        return minted;
      }
    };
  }

  /**
   * The key a tool can make itself idempotent on.
   *
   * <p>Tool execution is at-least-once: a call whose process died is run again, because nothing
   * recorded that it had finished. A tool that cares can deduplicate on this.
   *
   * <p>It is the TURN and the call together, because a model's call id is unique only within one
   * response — two turns can each produce a "call_1". It is stable across a re-drive, because
   * recovery resumes the same turn and the claimed asking message pins the same call ids.
   */
  public String callKey() {
    return turnId + "/" + callId;
  }
}
