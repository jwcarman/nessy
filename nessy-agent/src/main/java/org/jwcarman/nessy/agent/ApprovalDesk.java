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
package org.jwcarman.nessy.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.continuum.ContinuumClient;
import org.jwcarman.nessy.agent.store.AgentPhaseStore;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * The approve/deny door (approval-lifecycle spec §1.6), reachable two ways: by the computation's
 * own opaque id, for whoever was handed one — a Slack message, a webhook, a test — and by
 * coordinates, for whoever has only the question. Coordinates resolve through the scope's phase,
 * which names the parked computation for that call: <b>the phase is the map</b>. A caller who
 * answers before the harness has folded the park finds the call {@code Pending} and is refused
 * loudly, rather than losing the answer.
 *
 * <p>The desk takes a principal and a note because it is the one door with no subsystem behind it:
 * when a person answers here directly, nobody else is collecting evidence, so it refuses to be the
 * place a yes can enter anonymously (spec §7). Both fold into the answer's {@code reference}.
 *
 * <p>Complete, then nudge the delivery worker: an already-completed or genuinely absent id is
 * equally benign under at-least-once delivery. A structurally malformed id (one whose {@code
 * value()} is not a UUID at all) is a caller bug and throws {@link IllegalArgumentException} via
 * {@link ContinuumIds#continuumId} before ever reaching Continuum.
 */
public final class ApprovalDesk {

  private final ContinuumClient<Approval, ApprovalRouting> client;
  private final Function<String, AgentPhaseStore> stores;
  private final Runnable nudge;

  /**
   * @param client the approval kind's Continuum client
   * @param stores the scope state stores the by-coordinates doors read the phase from
   * @param nudge run after every answer — submits a drain pass to the shared {@code
   *     ComputationScheduler} rather than blocking the caller (continuum-adoption spec §7)
   */
  public ApprovalDesk(
      ContinuumClient<Approval, ApprovalRouting> client,
      Function<String, AgentPhaseStore> stores,
      Runnable nudge) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.stores = Objects.requireNonNull(stores, "stores must not be null");
    this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
  }

  /**
   * Approves by id, on behalf of {@code principal}; {@code note} may be empty.
   *
   * @param id the approval's own opaque id
   * @param principal who is answering — never blank
   * @param note free text folded into the reference, or empty
   */
  public void approve(ComputationId id, String principal, String note) {
    answer(id, new Approval.Approved(Optional.of(reference(principal, note))));
  }

  /**
   * Denies by id, on behalf of {@code principal}.
   *
   * @param id the approval's own opaque id
   * @param principal who is answering — never blank
   * @param reason why — folds into the call's in-band failure so the model reads it
   */
  public void deny(ComputationId id, String principal, String reason) {
    answer(id, new Approval.Denied(reason, Optional.of(reference(principal, ""))));
  }

  /**
   * Approves the call {@code toolCallId} the scope {@code id} is awaiting approval of.
   *
   * @param id the scope
   * @param toolCallId the tool call id
   * @param principal who is answering — never blank
   * @param note free text folded into the reference, or empty
   * @throws IllegalStateException if that call is not awaiting approval
   */
  public void approve(AgentId id, String toolCallId, String principal, String note) {
    approve(awaiting(id, toolCallId).approval(), principal, note);
  }

  /**
   * Denies the call {@code toolCallId} the scope {@code id} is awaiting approval of.
   *
   * @param id the scope
   * @param toolCallId the tool call id
   * @param principal who is answering — never blank
   * @param reason why
   * @throws IllegalStateException if that call is not awaiting approval
   */
  public void deny(AgentId id, String toolCallId, String principal, String reason) {
    deny(awaiting(id, toolCallId).approval(), principal, reason);
  }

  /**
   * Abandons a parked ask: folds as a denial the model reads, referenced "withdrawn".
   *
   * @param id the approval's own opaque id
   * @param reason why it was abandoned
   */
  public void withdraw(ComputationId id, String reason) {
    answer(id, new Approval.Denied("withdrawn: " + reason, Optional.of("withdrawn")));
  }

  /**
   * The parked question for {@code toolCallId} on {@code id} — the document the approver saw.
   *
   * @param id the scope
   * @param toolCallId the tool call id
   * @return the frozen request
   * @throws IllegalStateException if that call is not awaiting approval
   */
  public ApprovalRequest request(AgentId id, String toolCallId) {
    return awaiting(id, toolCallId).request();
  }

  private ToolCallPhase.AwaitingApproval awaiting(AgentId id, String toolCallId) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(toolCallId, "toolCallId must not be null");
    AgentPhase phase = stores.apply(id.value()).load().value();
    if (phase instanceof AgentPhase.AwaitingTools awaiting
        && awaiting.calls().get(toolCallId) instanceof ToolCallPhase.AwaitingApproval parked) {
      return parked;
    }
    throw new IllegalStateException(
        "call "
            + toolCallId
            + " on "
            + id.value()
            + " is not awaiting approval (phase: "
            + phase
            + ")");
  }

  private void answer(ComputationId id, Approval approval) {
    Objects.requireNonNull(id, "id must not be null");
    client.complete(ContinuumIds.continuumId(id.value()), approval);
    nudge.run();
  }

  private static String reference(String principal, String note) {
    Objects.requireNonNull(principal, "principal must not be null");
    if (principal.isBlank()) {
      throw new IllegalArgumentException(
          "principal must not be blank — the desk does not take an anonymous answer");
    }
    Objects.requireNonNull(note, "note must not be null");
    return note.isBlank() ? "desk:" + principal : "desk:" + principal + ":" + note;
  }
}
