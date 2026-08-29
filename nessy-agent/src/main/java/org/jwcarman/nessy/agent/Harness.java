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

import org.jwcarman.nessy.api.agent.AgentType;

/**
 * The application's whole surface onto a running Nessy: bind an agent, answer an approval, settle a
 * completion, and shut the thing down (harness-first spec §4).
 *
 * <p>An interface rather than a class because the engine underneath it is replaceable. That is the
 * entire point of the engine-extraction design: a caller holds a {@code Harness}, and whether the
 * work happens on a scheduler and a thread pool ({@link DefaultHarness}) or on an actor system
 * ({@code nessy-engine}) is not visible from here and does not change a single call site.
 *
 * <p>A harness is <b>kept, never closed</b>, by application code. {@link #shutdown()} is
 * deliberately not {@link AutoCloseable}, so nothing reaches for it by accident through
 * try-with-resources.
 *
 * @param <O> the observation type this harness's agents accept
 */
public interface Harness<O> {

  /** The type every agent bound here carries — the persistence and kind-name prefix. */
  AgentType type();

  /**
   * The application door (harness-first spec §4): a fresh, thin, transient handle for {@code id}.
   * Never closeable, never cached on the harness's behalf.
   */
  Agent<O> bind(AgentId id);

  /** The approve/deny door (harness-first spec §4). */
  ApprovalDesk approvals();

  /** The completion door (harness-first spec §4). */
  CompletionDesk completions();

  /**
   * Infrastructure-only (harness-first spec §4): releases what this harness started. Exists for a
   * container's destroy callback or a test's teardown, never for application hygiene.
   *
   * <p>Implementations stop their own machinery and nothing else. Work already in flight is neither
   * awaited nor cancelled, and anything the caller supplied — an executor, an actor system —
   * belongs to the caller and is left running.
   */
  void shutdown();
}
