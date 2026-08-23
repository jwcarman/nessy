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
package org.jwcarman.nessy.agent.spi;

import java.time.Duration;
import java.util.Optional;
import org.jwcarman.nessy.agent.CallAddress;
import org.jwcarman.nessy.agent.ToolInvocationId;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.RetrySemantics;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * What a wiring does when a tool defers (spec §4.3). {@link ToolExecution.Deferred} means the call
 * is suspended into its durable computation: the executor delivers nothing and narrates nothing —
 * deferral is invisible, but the suspension carries its reference. {@link ToolExecution.Immediate}
 * means an outcome to deliver now — the loud in-band failure of a non-durable wiring, or a durable
 * computation's already-terminal answer.
 *
 * <p>{@code invocation}, {@code retrySemantics}, and {@code timeout} are the tool's registration-
 * time facts (durable-deliveries spec §6) a durable wiring needs to create the computation and
 * stamp its deadline: {@code invocation} carries the real, committed {@code ModelResponseId} paired
 * with the call's id; {@code retrySemantics} and {@code timeout} come straight from the tool's
 * {@code Tool#retrySemantics()}/{@code Tool#timeout()}.
 *
 * <p>{@code alsoCommit} is the grant arm's transfer-then-dispatch door (spec §5a invariant 5): when
 * present, an implementation that can (a {@link Substrate}-backed one) must commit it in the SAME
 * atomic batch as the computation's own creation — a grant delivery's own removal, riding alongside
 * so the two either both land or neither does. Every non-grant dispatch passes {@link
 * Optional#empty()}; an implementation with nothing to batch into (a foreign engine) is free to
 * ignore it, at the cost of the atomicity guarantee this door exists to close.
 */
@FunctionalInterface
public interface DeferredToolCallPolicy {

  ToolExecution onDeferred(
      ToolCall call,
      CallAddress address,
      ToolInvocationId invocation,
      RetrySemantics retrySemantics,
      Optional<Duration> timeout,
      Optional<Substrate.Op> alsoCommit);

  /**
   * Ownership-split absorption (durable-deliveries spec §5a, §6): the id of whichever computation —
   * approval or execution — is already durably pending for {@code address}, if either is. The gate
   * checks this BEFORE running the tool, assembling enrichers, or asking the policy at all, so a
   * staleness redrive that reaches a call whose ask is still pending, or whose work has already
   * gone durable, absorbs silently: no re-run of enrichers or policy (a non-constant policy that
   * would now decide {@code Allow} never gets the chance to double-execute), no re-run of the
   * tool's own side effect, no second approval ask (the approver is never even reached). The
   * default answers empty — a wiring with nothing durable to check has nothing to absorb; {@link
   * org.jwcarman.nessy.agent.ComputationDeferredToolCallPolicy} is the one implementation that
   * answers meaningfully.
   */
  default Optional<ComputationId> pendingComputation(CallAddress address) {
    return Optional.empty();
  }
}
