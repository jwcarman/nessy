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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.OutcomeCodec.DeliveryDocument;
import org.jwcarman.nessy.agent.OutcomeCodec.PendingDocument;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.DeleteDocument;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.WriteDocument;

/**
 * The {@code computation} and {@code outbox} recipes, together (durable-deliveries spec §3, §4;
 * computation-identity spec §3): presence means pending — one document per computation, {@code
 * key=id.value()}, holding {@code {invocation, returnAddress, deadline?}}; one document per pending
 * result, in the SAME outbox kind, {@code key=} the completed computation's own id, holding {@code
 * {destination, outcome}}.
 *
 * <p><b>Kind-scoped, one instance per (agent type, purpose) pair</b> (spec §3): {@code
 * computationKind} is either {@code computation/<agentType>} (execution) or {@code
 * approval/<agentType>} (approval) — never both — and {@code outboxKind} is {@code
 * outbox/<agentType>}, shared by both purposes for one agent type since a completion of either kind
 * lands in the same outbox. Isolation across agent types is by construction now: two harnesses of
 * different types over one substrate never share a kind, so neither's worker or reaper ever reads
 * or skips the other's records — no runtime type filter is needed anymore.
 *
 * <p>{@link #create} is a plain CAS write at version 0 — get-or-create, no read-decide loop needed
 * since a computation is never mutated after creation. {@link #complete} is the ownership transfer
 * (spec §7 invariant 5): read the computation, then one substrate {@link Substrate#batch} deletes
 * it and creates its delivery atomically, under the completed computation's OWN id (spec §4,
 * deterministic delivery keys) — a lost race on the delete means a competitor already transferred
 * it, so this call re-reads and finds it absent — {@link CompletionResult#ALREADY_DONE}; a lost
 * race on the delivery's own creation, when a document already sits at that exact deterministic
 * key, means THIS EXACT fold already happened — convergence, not a fresh conflict to retry into
 * (spec §4: "a replayed creation under the same deterministic key converges instead of
 * duplicating"). Completing an id that was never created, or was already completed, is the same
 * benign absence (ruling 6, reversed: completion never creates records).
 *
 * <p>There is no adapter SPI above this — the {@link Substrate} beneath it is the seam a host swaps
 * (durable-dissolves spec §2). {@link OutcomeCodec} renders the payloads.
 *
 * <p><b>No public {@code Codec<T>} parameter here, by design.</b> Every other recipe's stored shape
 * is a user- or nessy-owned type the caller could reasonably swap a binding for; this recipe's
 * documents are not — {@link OutcomeCodec}'s wire vocabulary is a closed, hand-translated shape
 * over {@link Outcome}, whose {@code Success} payload is itself a closed vocabulary ({@link
 * org.jwcarman.nessy.api.tool.ToolResult}, {@link org.jwcarman.nessy.api.Decision}) validated
 * before any write. {@link OutcomeCodec} stays internal and unparameterized; only the outer shape
 * (bytes in, bytes out through the substrate) is the seam.
 */
public final class SubstrateComputations {

  private static final String ID_NULL_MESSAGE = "id must not be null";

  private final Substrate store;
  private final OutcomeCodec codec;
  private final String computationKind;
  private final String outboxKind;

  /**
   * @param computationKind this instance's own kind — {@code computation/<agentType>} for an
   *     execution-purposed instance, {@code approval/<agentType>} for an approval-purposed one
   *     (computation-identity spec §3); never shared between the two purposes for one agent type
   * @param outboxKind {@code outbox/<agentType>} — shared by both purposes for one agent type,
   *     since either kind of computation's completion lands in the same outbox
   */
  public SubstrateComputations(
      Substrate store, ObjectMapper mapper, String computationKind, String outboxKind) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.codec = new OutcomeCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.computationKind =
        requireNonBlank(computationKind, "computationKind must not be null or blank");
    this.outboxKind = requireNonBlank(outboxKind, "outboxKind must not be null or blank");
  }

  private static String requireNonBlank(String value, String message) {
    Objects.requireNonNull(value, message);
    if (value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value;
  }

  CreateResult create(
      ComputationId id,
      ToolInvocationId invocation,
      Continuation returnAddress,
      Optional<Instant> deadline) {
    return create(id, invocation, returnAddress, deadline, List.of());
  }

  /**
   * The T2/T3-sanctioned ops seam (durable-deliveries spec §5a invariant 5): {@code alsoCommit}
   * rides the SAME {@link Substrate#batch} as the computation's own creation — the grant arm's
   * transfer-then-dispatch shape composes this with a grant delivery's {@code DeleteDocument} so
   * the two either both land or neither does. {@code alsoCommit} carries raw {@link Substrate.Op}s,
   * an internal wiring detail the 4-arg {@link #create} overload above never exposes.
   */
  CreateResult create(
      ComputationId id,
      ToolInvocationId invocation,
      Continuation returnAddress,
      Optional<Instant> deadline,
      List<Substrate.Op> alsoCommit) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(returnAddress, "returnAddress must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    Objects.requireNonNull(alsoCommit, "alsoCommit must not be null");
    List<Substrate.Op> ops = new ArrayList<>(alsoCommit.size() + 1);
    ops.add(createOp(id, invocation, returnAddress, deadline));
    ops.addAll(alsoCommit);
    try {
      store.batch(ops);
      return new CreateResult(id, true);
    } catch (ConflictException _) {
      return new CreateResult(id, false);
    }
  }

  /**
   * The bare {@code WriteDocument} op for {@code id}'s creation, unexecuted — the other half of the
   * ops seam: a caller composing its own batch (spec §5a's transfer-then-dispatch) reads this, adds
   * whatever else must land atomically alongside it, and commits the batch itself.
   */
  Substrate.Op createOp(
      ComputationId id,
      ToolInvocationId invocation,
      Continuation returnAddress,
      Optional<Instant> deadline) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    Objects.requireNonNull(invocation, "invocation must not be null");
    Objects.requireNonNull(returnAddress, "returnAddress must not be null");
    Objects.requireNonNull(deadline, "deadline must not be null");
    String payload = codec.toJson(new PendingDocument(invocation, returnAddress, deadline));
    return new WriteDocument(
        computationKind, id.value(), payload.getBytes(StandardCharsets.UTF_8), 0);
  }

  CompletionResult complete(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    Objects.requireNonNull(outcome, "outcome must not be null");
    // Validate before touching the store: a foreign Success payload throws here (spec §7),
    // leaving the store untouched.
    codec.toJson(new DeliveryDocument(new Continuation("VALIDATION", "{}"), outcome));
    while (true) {
      Optional<Substrate.Document> doc = store.read(computationKind, id.value());
      if (doc.isEmpty()) {
        return CompletionResult.ALREADY_DONE;
      }
      if (deliveryPending(id)) {
        // the deterministic delivery key already carries a document for THIS id, even though the
        // computation is (still, or again) present — this exact fold already happened (a replayed
        // completion, or a redrive that re-created the computation after an earlier grant already
        // transferred it); converge rather than attempt a write that can never succeed against a
        // delivery that is never going to vanish on its own (spec §4). Checked BEFORE attempting
        // the batch — not merely on a caught conflict — so an ordinary race between two live
        // completers of the SAME still-pending computation is unaffected: neither sees a delivery
        // yet, so both proceed to race the batch itself, and the loser's next iteration finds the
        // computation genuinely absent (ALREADY_DONE), not a stray delivery to converge on.
        // The stray computation this branch found (recreated after the real transfer already
        // happened) is best-effort cleaned up here too, so presence-means-pending is not left
        // permanently violated by a redrive's own re-create; a lost race on the delete just means
        // another racer (or a later reap/redrive) already did or will — never this call's problem
        // to retry over, since the fold itself already converged.
        try {
          store.delete(computationKind, id.value(), doc.get().version());
        } catch (ConflictException _) {
          // already deleted by another racer, or mutated further — not this call's concern once
          // the fold has converged
        }
        return CompletionResult.TRANSFERRED;
      }
      PendingDocument pending =
          codec.pendingDocument(new String(doc.get().payload(), StandardCharsets.UTF_8));
      byte[] deliveryPayload =
          codec
              .toJson(new DeliveryDocument(pending.returnAddress(), outcome))
              .getBytes(StandardCharsets.UTF_8);
      List<Substrate.Op> ops =
          List.of(
              new DeleteDocument(computationKind, id.value(), doc.get().version()),
              new WriteDocument(outboxKind, id.value(), deliveryPayload, 0));
      try {
        store.batch(ops);
        return CompletionResult.TRANSFERRED;
      } catch (ConflictException _) {
        // lost the race to another completer or the computation was concurrently mutated;
        // re-read and re-evaluate (spec §7 invariant 5 — the one-flip law survives as CAS)
      }
    }
  }

  Optional<PendingComputation> find(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    return store
        .read(computationKind, id.value())
        .map(doc -> codec.pendingDocument(new String(doc.payload(), StandardCharsets.UTF_8)))
        .map(
            pending ->
                new PendingComputation(
                    id, pending.invocation(), pending.returnAddress(), pending.deadline()));
  }

  /**
   * Whether a delivery already sits at {@code id}'s own deterministic outbox key (computation-
   * identity spec §4) — the ownership-split absorption gate's other half ({@link
   * ComputationDeferredToolCallPolicy#pendingComputation}): a computation kind lookup alone misses
   * a grant that has already folded into its delivery but has not yet drained, since
   * presence-means- pending leaves no residue in the computation kind once the transfer batch
   * commits.
   */
  boolean deliveryPending(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    return store.read(outboxKind, id.value()).isPresent();
  }

  /**
   * The closed-vocabulary encode door, exposed to this package's desks (computation-identity spec
   * §2 addendum): {@link ApprovalDesk} and {@link CompletionDesk} hold a backend already, so they
   * reach the pinned mapper's encoding through it rather than each carrying their own {@link
   * ObjectMapper}.
   */
  JsonNode encodeSuccess(Object value) {
    return codec.encodeSuccess(value);
  }
}
