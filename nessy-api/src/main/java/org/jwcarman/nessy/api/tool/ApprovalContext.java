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
 * What an approver is told beyond the question itself.
 *
 * <p>The approver is the thing that knows a human is needed and which queue, page, or pager that
 * question belongs to. This is how it says where the answer comes back — handed down, because only
 * the engine can mint an address it will honour.
 *
 * <p>Beside {@link ApprovalRequest} rather than inside it, deliberately: the request describes the
 * question and is exactly what an approvals page stores and renders, while the token is the
 * authority to settle the call. An interface rather than a parameter for the same reason {@link
 * ToolContext} is one — what an approver is offered will grow, and a parameter list cannot.
 */
@FunctionalInterface
public interface ApprovalContext {

  /** Where a person's answer goes, if this approver defers. */
  ReplyToken replyToken();
}
