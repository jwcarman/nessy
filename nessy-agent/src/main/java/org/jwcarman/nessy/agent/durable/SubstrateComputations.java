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
package org.jwcarman.nessy.agent.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.DeliveryDocument;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.PendingDocument;
import org.jwcarman.nessy.api.Identifiers;
import org.jwcarman.nessy.durable.CompletionResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.CreateResult;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.durable.PendingComputation;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.DeleteDocument;
import org.jwcarman.nessy.spi.substrate.Substrate.Op.WriteDocument;

/**
 * The {@code computation} and {@code outbox} recipes, together (durable-deliveries spec §3, §4):
 * presence means pending — one document per computation, {@code kind=computation}, {@code
 * key=id.value()}, holding {@code {invocation, returnAddress, deadline?}}; one document per pending
 * result, {@code kind=outbox}, a UUIDv7 key, holding {@code {destination, outcome}}.
 *
 * <p>{@link #create} is a plain CAS write at version 0 — get-or-create, no read-decide loop needed
 * since a computation is never mutated after creation. {@link #complete} is the ownership transfer
 * (spec §7 invariant 5): read the computation, then one substrate {@link Substrate#batch} deletes
 * it and creates its delivery atomically; a lost race on the delete means a competitor already
 * transferred it, so this call re-reads and finds it absent — {@link
 * CompletionResult#ALREADY_DONE}. Completing an id that was never created, or was already
 * completed, is the same benign absence (ruling 6, reversed: completion never creates records).
 *
 * <p>{@code DurableComputationBackend} is no longer an adapter SPI; this is its default and only
 * shipped implementation. {@link OutcomeCodec} renders the payloads.
 *
 * <p><b>No public {@code Codec<T>} parameter here, by design.</b> Every other recipe's stored shape
 * is a user- or nessy-owned type the caller could reasonably swap a binding for; this recipe's
 * documents are not — {@link OutcomeCodec}'s wire vocabulary is a closed, hand-translated shape
 * over {@link Outcome}, whose {@code Success} payload is itself a closed vocabulary ({@link
 * org.jwcarman.nessy.api.tool.ToolResult}, {@link org.jwcarman.nessy.api.Decision}) validated
 * before any write. {@link OutcomeCodec} stays internal and unparameterized; only the outer shape
 * (bytes in, bytes out through the substrate) is the seam.
 */
public final class SubstrateComputations implements DurableComputationBackend {

  private static final String COMPUTATION_KIND = "computation";
  private static final String OUTBOX_KIND = "outbox";
  private static final String ID_NULL_MESSAGE = "id must not be null";

  private final Substrate store;
  private final OutcomeCodec codec;

  public SubstrateComputations(Substrate store, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.codec = new OutcomeCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  @Override
  public CreateResult create(
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
   * the two either both land or neither does. Package-visible only: {@code alsoCommit} carries raw
   * {@link Substrate.Op}s, meaningless to a foreign {@link DurableComputationBackend}, so this
   * never becomes part of that public seam.
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
        COMPUTATION_KIND, id.value(), payload.getBytes(StandardCharsets.UTF_8), 0);
  }

  @Override
  public CompletionResult complete(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    Objects.requireNonNull(outcome, "outcome must not be null");
    // Validate before touching the store: a foreign Success payload throws here (spec §7),
    // leaving the store untouched.
    codec.toJson(new DeliveryDocument(new Continuation("VALIDATION", "{}"), outcome));
    while (true) {
      Optional<Substrate.Document> doc = store.read(COMPUTATION_KIND, id.value());
      if (doc.isEmpty()) {
        return CompletionResult.ALREADY_DONE;
      }
      PendingDocument pending =
          codec.pendingDocument(new String(doc.get().payload(), StandardCharsets.UTF_8));
      byte[] deliveryPayload =
          codec
              .toJson(new DeliveryDocument(pending.returnAddress(), outcome))
              .getBytes(StandardCharsets.UTF_8);
      List<Substrate.Op> ops =
          List.of(
              new DeleteDocument(COMPUTATION_KIND, id.value(), doc.get().version()),
              new WriteDocument(OUTBOX_KIND, Identifiers.next(), deliveryPayload, 0));
      try {
        store.batch(ops);
        return CompletionResult.TRANSFERRED;
      } catch (ConflictException _) {
        // lost the race to another completer or the computation was concurrently mutated;
        // re-read and re-evaluate (spec §7 invariant 5 — the one-flip law survives as CAS)
      }
    }
  }

  @Override
  public Optional<PendingComputation> find(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    return store
        .read(COMPUTATION_KIND, id.value())
        .map(doc -> codec.pendingDocument(new String(doc.payload(), StandardCharsets.UTF_8)))
        .map(
            pending ->
                new PendingComputation(
                    id, pending.invocation(), pending.returnAddress(), pending.deadline()));
  }
}
