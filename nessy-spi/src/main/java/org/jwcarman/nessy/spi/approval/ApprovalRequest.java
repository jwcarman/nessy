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
package org.jwcarman.nessy.spi.approval;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthzContext;

/**
 * Everything the adjudicating party sees (authorization design §9): the assembled context, never
 * less. The rendered action is not a component of its own — it lives IN {@code context} (§8,
 * amended 2026-08-21: "two paths to one fact, closed"), so the approver reads it, and everything
 * else an enricher deposited (principal, risk, declared intent), off {@code context} — {@link
 * AuthzContext#action()}, {@link AuthzContext#principal()}, {@link AuthzContext#risk()}, {@link
 * AuthzContext#declaredIntent()}.
 *
 * <p>{@code id} is the ticket (computation-identity spec §1, §4 addendum): the approval's own
 * opaque {@link ComputationId}, already derived by the caller — the taught path back is {@code
 * harness.approvals().approve(request.id())} (or {@code deny}). {@code CallAddress} no longer
 * travels here — it stayed a wiring-internal detail ({@code CallAddress} moved into {@code
 * nessy-agent}) rather than crossing this SPI boundary. {@code agentType}/{@code agentId} are plain
 * strings, for display only. {@code responseId} is the one addition beyond that display pair: a
 * computation-backed approver (see {@code ComputationApprover}) must still persist a resumable
 * return address keyed to the exact model response that produced {@code call} — the same
 * provider-uniqueness reason {@code CallAddress} itself carries {@code responseId} — and there is
 * no other channel back to it once {@code CallAddress} leaves the record.
 */
public record ApprovalRequest(
    ComputationId id,
    ToolCall call,
    String agentType,
    String agentId,
    String responseId,
    AuthzContext context) {

  public ApprovalRequest {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(responseId, "responseId must not be null");
    Objects.requireNonNull(context, "context must not be null");
  }
}
