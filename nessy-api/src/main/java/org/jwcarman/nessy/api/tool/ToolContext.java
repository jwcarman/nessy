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

/**
 * What a tool learns about the invocation it is serving, plus what it can do with it — the mirror
 * of {@code ApprovalContext} (tool-context-defer spec §1.1). {@link #defer()} does the plumbing: it
 * creates the durable computation, records the wait in the scope, waits for that record to commit,
 * and only then hands back the id. By the time a tool can give the id to anyone, the phase already
 * names the wait.
 */
public interface ToolContext {

  /** The call being served. */
  ToolCall call();

  /**
   * This execution's opaque, stable idempotency key — deterministic from the call's coordinates,
   * identical across every redispatch and replay. NOT the computation id: a tool deduplicates an
   * external side effect under this, and hands out the id {@link #defer()} returns as the callback
   * address.
   */
  ComputationId invocation();

  /** Reports progress from inside a long-running tool ({@link ToolEvent.Progress}). */
  void progress(String message);

  /**
   * "The answer arrives later": creates this call's durable computation with the tool's declared
   * timeout as its deadline, folds {@code ToolDeferred}, commits, and returns the computation's id.
   * Idempotent: a second call returns the same id and creates nothing. Throws if the wait could not
   * be recorded — nothing was parked, and the tool should let the exception propagate.
   */
  ComputationId defer();
}
