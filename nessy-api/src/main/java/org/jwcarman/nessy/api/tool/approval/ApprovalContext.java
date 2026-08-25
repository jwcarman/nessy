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
package org.jwcarman.nessy.api.tool.approval;

/**
 * What an approver learns about the invocation it is serving, plus what it can do with it — the
 * mirror of {@code ToolContext} (spec §1.3). {@link #defer()} does the plumbing: it parks the
 * question, records the fact in the scope, waits for that record to commit, and only then hands
 * back the id. By the time an approver can tell anyone, the phase already names the ask.
 * Idempotent: a second call returns the same outcome.
 */
public interface ApprovalContext {

  /** The question, enriched and frozen. */
  ApprovalRequest request();

  /** "I'll get back to you": the outcome to return, carrying the parked computation's id. */
  ApprovalOutcome defer();
}
