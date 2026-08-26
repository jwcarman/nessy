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
package org.jwcarman.nessy.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The pending-approvals projection (watchman spec §1.3): a {@link HarnessObserver} that writes one
 * table row per parked approval, so that an application can ask what is waiting.
 *
 * <p>Nothing in Nessy can enumerate parked approvals — the desk answers by id or coordinates, the
 * Continuum client has no read door, and a phase is per agent. The harness fact stream is exactly
 * the thing to project from, and this is its first real consumer.
 *
 * <p><b>It is a projection.</b> At-least-once, rebuildable from the stream, never the source of
 * truth. Approve and deny go through {@code ApprovalDesk}; a row changes when the fold's fact
 * arrives. A restart between the fold and the insert loses a row, and the page then shows one
 * approval fewer than the phase holds until the staleness re-fire re-asks. Documented and accepted:
 * the ledger is the phase.
 *
 * <p><b>PostgreSQL only.</b> Every statement here is written in PostgreSQL's dialect — {@code
 * INSERT … ON CONFLICT … DO UPDATE} for the two idempotent upserts, and a {@code jsonb} column for
 * the frozen request — and the DDL beside this class is named for it: {@code
 * pending-approvals-postgresql.sql}, matching {@code nessy-substrate-jdbc}'s own {@code
 * nessy-postgresql.sql}. The auto-configuration's condition is dialect-blind on purpose: it asks
 * only whether a {@code DataSource} and {@code JdbcTemplate} exist, because Boot offers no honest
 * way to ask a {@code DataSource} what dialect it speaks without opening a connection at condition
 * time. An application on another database declares its own {@link PendingApprovals}-shaped bean —
 * or none at all — rather than getting a silent syntax error at the first park. A second dialect's
 * DDL and statements are a straightforward follow-on; today there is one.
 *
 * <p>Two consequences of the stream's contract shape every statement here:
 *
 * <ul>
 *   <li><b>At-least-once</b> — a fact may arrive twice, so both writes are idempotent.
 *   <li><b>Not in commit order</b> — {@code HarnessObserver} states plainly that publishes for one
 *       scope can reach an observer in either order, so an answer may arrive before the park it
 *       answers. Both directions therefore UPSERT, and neither ever touches the other's columns: a
 *       park fills the park columns only while they are empty, an answer fills the answer columns
 *       only while they are empty. A row can briefly hold an answer and no request; {@code
 *       PendingApprovalsRepository#pending()} filters those out, and the park's own fact completes
 *       it moments later.
 * </ul>
 */
public class PendingApprovals implements HarnessObserver {

  private static final String PARKED =
      """
      INSERT INTO nessy_pending_approvals
        (computation_id, agent_type, agent_id, call_id, action, request_json, parked_at)
      VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
      ON CONFLICT (computation_id) DO UPDATE SET
        agent_type = EXCLUDED.agent_type,
        agent_id = EXCLUDED.agent_id,
        call_id = EXCLUDED.call_id,
        action = EXCLUDED.action,
        request_json = EXCLUDED.request_json,
        parked_at = EXCLUDED.parked_at
      WHERE nessy_pending_approvals.request_json IS NULL
      """;

  private static final String ANSWERED =
      """
      INSERT INTO nessy_pending_approvals
        (computation_id, answer, reference, note, answered_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (computation_id) DO UPDATE SET
        answer = EXCLUDED.answer,
        reference = EXCLUDED.reference,
        note = EXCLUDED.note,
        answered_at = EXCLUDED.answered_at
      WHERE nessy_pending_approvals.answer IS NULL
      """;

  private final JdbcTemplate jdbc;
  private final Codec<ApprovalRequest> requests;

  /**
   * @param jdbc where the rows go
   * @param pinned the harness's pinned mapper — the request is stored exactly as the harness
   *     renders it, so the JSON on the page is the JSON the approver was asked about
   */
  public PendingApprovals(JdbcTemplate jdbc, ObjectMapper pinned) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.requests =
        ApprovalRequest.codec(Objects.requireNonNull(pinned, "pinned must not be null"));
  }

  /**
   * The projection's whole input. Only the two approval facts are projected; the rest of the
   * grammar is listed explicitly rather than swept up by a {@code default}, so that adding a
   * variant to {@code AgentEvent} makes this file fail to compile and someone decide.
   */
  @Override
  public void applied(AgentId id, AgentEvent event, Transition transition) {
    switch (event) {
      case AgentEvent.ApprovalDeferred(var call, var approval, var request) ->
          parked(approval.value(), request, call.id());
      case AgentEvent.ApprovalAnswered(var call, var approval, var answer) ->
          approval.ifPresent(computation -> answered(computation.value(), answer));
      case AgentEvent.Observed _ -> noProjection();
      case AgentEvent.ModelFinished _ -> noProjection();
      case AgentEvent.ToolDeferred _ -> noProjection();
      case AgentEvent.ToolFinished _ -> noProjection();
    }
  }

  private void parked(String computationId, ApprovalRequest request, String callId) {
    jdbc.update(
        PARKED,
        computationId,
        request.agentType(),
        request.agentId(),
        callId,
        request.action(),
        new String(requests.encode(request), StandardCharsets.UTF_8),
        Timestamp.from(Instant.now()));
  }

  private void answered(String computationId, Approval answer) {
    Decision decision =
        switch (answer) {
          case Approval.Approved(var pointer) ->
              new Decision("approved", pointer.orElse(null), null);
          case Approval.Denied(var reason, var pointer) ->
              new Decision("denied", pointer.orElse(null), reason);
        };
    jdbc.update(
        ANSWERED,
        computationId,
        decision.verdict(),
        decision.reference(),
        decision.note(),
        Timestamp.from(Instant.now()));
  }

  /**
   * The three answer columns, flattened out of the sealed {@link Approval} grammar by one
   * exhaustive switch. A denial's reason is the row's {@code note}: the spec's §7 audit division
   * says the approver subsystem owns the evidence, and the reason is the only piece of it the fold
   * carries.
   */
  private record Decision(String verdict, String reference, String note) {}

  /** Every other fact in the grammar: this projection is about approvals and nothing else. */
  private void noProjection() {
    // deliberately empty — see applied(...)
  }

  @Override
  public void ignored(AgentId id, AgentEvent event) {
    // a discarded event changed no phase, so it changes no row either
  }

  @Override
  public void renderFailed(AgentId id, Object observation, RuntimeException error) {
    // an observation that never became a fact parks nothing
  }

  @Override
  public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
    // the phase is unchanged, so the projection must stay unchanged too
  }

  @Override
  public void reFired(AgentId id, List<Effect> effects) {
    // a re-fire re-dispatches effects; the park it belongs to is already a row
  }

  @Override
  public void observationRequeued(AgentId id, Object observation) {
    // a requeued observation parks nothing
  }
}
