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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.SlotDocument;
import org.jwcarman.nessy.durable.AwaitResult;
import org.jwcarman.nessy.durable.CompletionResult;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.CreateResult;
import org.jwcarman.nessy.durable.DurableComputationBackend;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * The {@code computation} recipe (substrate spec §6.5): one document per computation, {@code
 * kind=computation}, {@code key=id.value()}, holding {@code {status, outcome?, continuations[]}}.
 * The durable spec's semantic law (one flip §10, atomic await §12, idempotent completion §23,
 * ruling 6) maps onto the substrate's CAS: every mutation is read-decide-CAS, and a lost race
 * retries the whole decision — the document version is the row lock the reference in-memory backend
 * played with a monitor.
 *
 * <p>{@code DurableComputationBackend} is no longer an adapter SPI (substrate spec §6.5); this is
 * its default and only shipped implementation. {@link OutcomeCodec} renders the payloads.
 *
 * <p><b>No public {@code Codec<T>} parameter here, by design.</b> Every other recipe's stored shape
 * is a user- or nessy-owned type the caller could reasonably swap a binding for; this recipe's
 * document is not — {@link OutcomeCodec}'s wire vocabulary is a closed, hand-translated shape over
 * {@link Outcome}, whose {@code Success} payload is itself a closed vocabulary ({@link
 * org.jwcarman.nessy.api.tool.ToolResult}, {@link org.jwcarman.nessy.api.Decision}) validated
 * before any write (spec §7). Opening a codec seam here would let a caller substitute a binding
 * that disagrees with that closed-vocabulary check, or that the desks and the durable spec's
 * semantic law (one flip, atomic await, idempotent completion) never anticipated. {@link
 * OutcomeCodec} stays internal and unparameterized; only the outer shape (bytes in, bytes out
 * through the substrate) is the seam.
 */
public final class SubstrateComputations implements DurableComputationBackend {

  private static final String KIND = "computation";
  private static final String ID_NULL_MESSAGE = "id must not be null";

  private final Substrate store;
  private final OutcomeCodec codec;

  public SubstrateComputations(Substrate store, ObjectMapper mapper) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.codec = new OutcomeCodec(Objects.requireNonNull(mapper, "mapper must not be null"));
  }

  @Override
  public CreateResult create(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    String payload = codec.toJson(new SlotDocument(ComputationStatus.PENDING, null, List.of()));
    try {
      store.write(KIND, id.value(), payload.getBytes(StandardCharsets.UTF_8), 0);
      return new CreateResult(id, true);
    } catch (ConflictException _) {
      return new CreateResult(id, false);
    }
  }

  @Override
  public AwaitResult await(ComputationId id, Continuation continuation) {
    Objects.requireNonNull(continuation, "continuation must not be null");
    while (true) {
      Substrate.Document doc = requiredDocument(id);
      SlotDocument slot = codec.document(new String(doc.payload(), StandardCharsets.UTF_8));
      if (slot.status() != ComputationStatus.PENDING) {
        return new AwaitResult.AlreadyCompleted(slot.outcome());
      }
      if (slot.continuations().contains(continuation)) {
        return new AwaitResult.Registered();
      }
      List<Continuation> registered = new ArrayList<>(slot.continuations());
      registered.add(continuation);
      String payload = codec.toJson(new SlotDocument(slot.status(), slot.outcome(), registered));
      try {
        store.write(KIND, id.value(), payload.getBytes(StandardCharsets.UTF_8), doc.version());
        return new AwaitResult.Registered();
      } catch (ConflictException _) {
        // lost the race to another writer; re-read and retry the whole decision (spec §12)
      }
    }
  }

  @Override
  public CompletionResult complete(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    Objects.requireNonNull(outcome, "outcome must not be null");
    ComputationStatus terminalStatus = statusOf(outcome);
    // Validate before touching the store: a foreign Success payload throws here (spec §7),
    // leaving the document untouched — absent if it never existed.
    String createPayload = codec.toJson(new SlotDocument(terminalStatus, outcome, List.of()));
    while (true) {
      Optional<Substrate.Document> doc = store.read(KIND, id.value());
      if (doc.isEmpty()) {
        // Ruling 6: completion creates the slot when it must — one flip, at birth.
        try {
          store.write(KIND, id.value(), createPayload.getBytes(StandardCharsets.UTF_8), 0);
          return CompletionResult.COMPLETED;
        } catch (ConflictException _) {
          continue; // the slot was created concurrently; re-read and re-evaluate
        }
      }
      SlotDocument slot = codec.document(new String(doc.get().payload(), StandardCharsets.UTF_8));
      if (slot.status() != ComputationStatus.PENDING) {
        return CompletionResult.ALREADY_TERMINAL;
      }
      String payload =
          codec.toJson(new SlotDocument(terminalStatus, outcome, slot.continuations()));
      try {
        store.write(
            KIND, id.value(), payload.getBytes(StandardCharsets.UTF_8), doc.get().version());
        return CompletionResult.COMPLETED;
      } catch (ConflictException _) {
        // lost the race to another completer; re-read — it may now be ALREADY_TERMINAL
      }
    }
  }

  @Override
  public Optional<ComputationStatus> status(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    return store
        .read(KIND, id.value())
        .map(doc -> codec.document(new String(doc.payload(), StandardCharsets.UTF_8)).status());
  }

  @Override
  public List<Continuation> continuationsOf(ComputationId id) {
    return codec
        .document(new String(requiredDocument(id).payload(), StandardCharsets.UTF_8))
        .continuations();
  }

  private Substrate.Document requiredDocument(ComputationId id) {
    Objects.requireNonNull(id, ID_NULL_MESSAGE);
    return store
        .read(KIND, id.value())
        .orElseThrow(() -> new IllegalArgumentException("unknown computation: " + id.value()));
  }

  private static ComputationStatus statusOf(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Success _ -> ComputationStatus.SUCCEEDED;
      case Outcome.Failure _ -> ComputationStatus.FAILED;
      case Outcome.Cancelled _ -> ComputationStatus.CANCELLED;
    };
  }
}
