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

import org.jwcarman.nessy.agent.ModelResponseId;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.ApprovalContext;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Plumbing, not application vocabulary: the seam the tool executor builds one call's {@link
 * ApprovalContext} through, so the Continuum-backed {@code defer()} door can live in the agent
 * package while the executor stays ignorant of computations. Wiring supplies it; nothing in an
 * application ever implements one.
 */
@FunctionalInterface
public interface ApprovalContexts {

  /**
   * @param call the call being judged
   * @param responseId the committed model response that produced {@code call}
   * @param request the frozen question the approver reads
   * @param sink where this dispatch's events are delivered
   * @return the context for this one call
   */
  ApprovalContext contextFor(
      ToolCall call, ModelResponseId responseId, ApprovalRequest request, Sink sink);
}
