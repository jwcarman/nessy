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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;

/**
 * What a tool learns about the invocation it is serving — a RECORD again (deferral-by-callback spec
 * §7). {@code defer()} is gone: a tool that means to wait returns {@link
 * org.jwcarman.nessy.api.Awaited.Deferred} carrying a {@link ComputationCallback} and a term, and
 * the plumbing creates the computation after the fold. Nothing here reaches Continuum, so there is
 * nothing left for an interface to hide.
 *
 * @param call the call being served
 * @param events where {@link ToolEvent}s a tool narrates are heard
 * @param invocation this execution's opaque, stable idempotency key (computation-identity spec §4
 *     addendum): the execution {@link ComputationId} — stable across every redispatch and replay, a
 *     tool wants this for logging, correlation, or deduplicating an external effect under
 *     at-least-once redelivery. Carries no extractable structure (spec §1) — a tool reads it as an
 *     opaque token, never parses it. NOT the computation a deferral parks under: that one does not
 *     exist yet when a tool runs, which is the whole point of the callback.
 */
public record ToolContext(ToolCall call, ToolEventListener events, ComputationId invocation) {

  public ToolContext {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(events, "events must not be null");
    Objects.requireNonNull(invocation, "invocation must not be null");
  }

  /**
   * Reports progress from inside a long-running tool. {@link ToolEvent.Progress} carries only
   * {@code message} — no call or provider id travels with it, so a tool reporting progress has
   * nothing to distrust because there is nothing untrusted to carry (spec §2).
   */
  public void progress(String message) {
    events.on(new ToolEvent.Progress(message));
  }
}
