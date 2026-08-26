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
 * What an approver learns about the invocation it is serving — the mirror of {@code ToolContext}
 * (spec §1.3), collapsed to one accessor by the deferral-by-callback reform (spec §7): parking is a
 * returned {@link ApprovalOutcome.Deferred} now, so the context has no plumbing left to expose and
 * reaches Continuum not at all.
 */
@FunctionalInterface
public interface ApprovalContext {

  /** The question, enriched and frozen. */
  ApprovalRequest request();
}
