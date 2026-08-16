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
package org.jwcarman.nessy.api.approval;

import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * The question put to a human — adjudication parity (design of record 2026-08-16-authorization §9):
 * the approver sees exactly what the policy saw, the final {@code context} any enrichers extended
 * and the tool's own rendered {@code effect}, because the approval UI is exactly where those matter
 * most.
 *
 * @param context the final context the chokepoint assembled — every enricher's deposit, including
 *     any principal or intent an enricher lifted in, is reachable through it
 * @param effect the tool's own rendering of the call, from {@code Tool.effect} — this is what a
 *     person actually reads via {@link #description()}
 */
public record ApprovalRequest(
    ConversationId conversationId, ToolCall call, AuthzContext context, Object effect) {

  public ApprovalRequest {
    Objects.requireNonNull(conversationId, "conversationId must not be null");
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(effect, "effect must not be null");
  }

  /**
   * {@link #effect}'s own {@code toString()} — unchanged from what every approver already reads;
   * existing implementations (console, examples) migrate mechanically, with no source change at
   * all.
   */
  public String description() {
    return String.valueOf(effect);
  }
}
