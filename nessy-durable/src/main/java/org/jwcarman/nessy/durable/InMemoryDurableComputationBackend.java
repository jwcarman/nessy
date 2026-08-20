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
package org.jwcarman.nessy.durable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The reference in-memory backend. Each slot's monitor plays the SQL row lock of the reference
 * schema (durable spec §20-21): await and complete serialize on it, so the §12 contract holds —
 * either the outcome is returned, or the continuation is registered before completion can proceed.
 */
public final class InMemoryDurableComputationBackend implements DurableComputationBackend {

  private static final class Slot {
    private ComputationStatus status = ComputationStatus.PENDING;
    private Outcome outcome;
    private final Set<Continuation> continuations = new LinkedHashSet<>();
  }

  private final ConcurrentMap<ComputationId, Slot> slots = new ConcurrentHashMap<>();

  @Override
  public CreateResult create(ComputationId id) {
    Objects.requireNonNull(id, "id must not be null");
    Slot fresh = new Slot();
    Slot prior = slots.putIfAbsent(id, fresh);
    return new CreateResult(id, prior == null);
  }

  @Override
  public AwaitResult await(ComputationId id, Continuation continuation) {
    Objects.requireNonNull(continuation, "continuation must not be null");
    Slot slot = required(id);
    synchronized (slot) {
      if (slot.status != ComputationStatus.PENDING) {
        return new AwaitResult.AlreadyCompleted(slot.outcome);
      }
      slot.continuations.add(continuation);
      return new AwaitResult.Registered();
    }
  }

  @Override
  public CompletionResult complete(ComputationId id, Outcome outcome) {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Slot slot = required(id);
    synchronized (slot) {
      if (slot.status != ComputationStatus.PENDING) {
        return CompletionResult.ALREADY_TERMINAL;
      }
      slot.status = statusOf(outcome);
      slot.outcome = outcome;
      return CompletionResult.COMPLETED;
    }
  }

  /** An unknown id returns empty rather than throwing — contrast {@link #continuationsOf}. */
  @Override
  public Optional<ComputationStatus> status(ComputationId id) {
    Objects.requireNonNull(id, "id must not be null");
    Slot slot = slots.get(id);
    if (slot == null) {
      return Optional.empty();
    }
    synchronized (slot) {
      return Optional.of(slot.status);
    }
  }

  /** An unknown id throws {@link IllegalArgumentException} — contrast {@link #status}. */
  @Override
  public List<Continuation> continuationsOf(ComputationId id) {
    Slot slot = required(id);
    synchronized (slot) {
      return List.copyOf(slot.continuations);
    }
  }

  private Slot required(ComputationId id) {
    Objects.requireNonNull(id, "id must not be null");
    Slot slot = slots.get(id);
    if (slot == null) {
      throw new IllegalArgumentException("unknown computation: " + id.value());
    }
    return slot;
  }

  private static ComputationStatus statusOf(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Success ignored -> ComputationStatus.SUCCEEDED;
      case Outcome.Failure ignored -> ComputationStatus.FAILED;
      case Outcome.Cancelled ignored -> ComputationStatus.CANCELLED;
    };
  }
}
