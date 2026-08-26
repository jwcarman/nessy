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
import java.time.InstantSource;
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
 * <p><b>It is a projection, it is at-least-once, and it does NOT heal.</b> Approve and deny go
 * through {@code ApprovalDesk}; a row changes when the fold's fact arrives. If that fact is lost —
 * a restart between the fold and the insert, a {@code DataSource} blip, an exception in this class
 * — <b>it is lost, permanently</b>. Nothing replays it. Earlier versions of this paragraph, of the
 * Spring Boot guide and of the spec all said the row returns "until the staleness re-fire re-asks";
 * that was never true and is corrected here (final review, 2026-08-26). {@code
 * Phase.AwaitingTools#outstandingEffects} deliberately contributes NO effect for a call in {@code
 * AwaitingApproval} — the Continuum holds it, so there is nothing to re-fire — and this class
 * ignores {@code reFired} entirely. There is no replay door either: {@code Harness.subscribe} is
 * package-private by ruling, and no public API re-reads applied facts.
 *
 * <p>Both directions of loss are real, and the second is the nastier:
 *
 * <ul>
 *   <li><b>A lost park</b> leaves no row. The page shows one approval fewer than the phase actually
 *       holds, forever. The call is genuinely parked and genuinely answerable — by coordinates
 *       through {@code ApprovalDesk#approve(AgentId, String, String, String)} — but this table
 *       cannot tell anyone it exists.
 *   <li><b>A lost answer</b> leaves the row in {@link PendingApprovalsRepository#pending()}
 *       forever, showing a human a decision that has already been made. Answering it again is a
 *       benign no-op at the desk (the computation is already complete), so the click appears to do
 *       nothing and the row does not move. That is confusing rather than dangerous — the action is
 *       not performed twice — but it is confusing indefinitely.
 * </ul>
 *
 * <p><b>The ledger is the phase.</b> When this table and the agent disagree, the agent is right.
 * The operator's recourse is the agent's own transcript — what it actually did is in its messages
 * and, in the watchman's case, in the notes directory — not in this table.
 *
 * <p>What a self-healing rebuild would need, if it is ever wanted: a public replay door on the fact
 * stream — some way to re-read a scope's applied facts and re-fold them into the table, either from
 * the substrate or from a durable log this class does not have. Deliberately not invented here.
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
  private final InstantSource clock;

  /**
   * @param jdbc where the rows go
   * @param pinned the harness's pinned mapper — the request is stored exactly as the harness
   *     renders it, so the JSON on the page is the JSON the approver was asked about
   */
  public PendingApprovals(JdbcTemplate jdbc, ObjectMapper pinned) {
    this(jdbc, pinned, InstantSource.system());
  }

  /**
   * @param clock what stamps {@code parked_at} and {@code answered_at} — see {@link #stamp()} for
   *     what those two columns do and do not mean
   */
  public PendingApprovals(JdbcTemplate jdbc, ObjectMapper pinned, InstantSource clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.requests =
        ApprovalRequest.codec(Objects.requireNonNull(pinned, "pinned must not be null"));
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * When this projection SAW the fact — <b>not</b> when the thing happened (final review, finding
   * #7).
   *
   * <p>This was worth being explicit about because the page presents {@code parked_at} as a dwell
   * time, and a dwell measured from the wrong clock is a number that quietly means something else.
   * The honest position: <b>nothing in the fact carries a time.</b> {@code AgentEvent}'s two
   * approval variants carry the call, the computation id and the request; {@code Transition}
   * carries the next phase, the messages to commit and the effects to fire; {@code
   * ToolCallState.AwaitingApproval} carries the computation id and the request. There is no
   * timestamp on any of them to prefer over this one, so this is the best available reading and the
   * javadoc says so rather than implying the fact was consulted.
   *
   * <p>How wrong it can be: as wrong as the gap between the fold committing and this observer
   * running. On a healthy box that is milliseconds. After a backlog, a slow database or a
   * re-delivery it can be seconds — and on the out-of-order path, where an answer's fact is
   * projected before its park's, {@code parked_at} can even land AFTER {@code answered_at}. The
   * page's dwell is therefore a good indication of how long a human has left something sitting and
   * not an audit-grade measurement. For that, the ledger is the phase.
   *
   * <p>Making it exact would mean putting a timestamp on the fact itself, which is a change to
   * {@code nessy-agent}'s grammar and not something a projection may decide.
   */
  private Timestamp stamp() {
    return Timestamp.from(clock.instant());
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
        stamp());
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
        stamp());
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
