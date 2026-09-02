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
package org.jwcarman.nessy.examples.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * Builds the <b>input document</b> — what a Rego policy sees as {@code input}.
 *
 * <p>OPA's own word, deliberately: inside this adapter {@code input} can only mean one thing, and
 * somebody who writes Rego should not have to learn a second name for the document they already
 * write rules against. Elsewhere in Nessy {@code input} means a tool's bound arguments, which is
 * why this vocabulary stays behind the OPA package boundary.
 *
 * <p>Named for what it DOES rather than what it produces. The document is the noun; this is the
 * thing that renders one — the same shape as {@link org.jwcarman.nessy.api.tool.ActionRenderer},
 * which renders the sentence a person consents to. One request, two audiences: a person reads the
 * action, a policy engine reads this.
 *
 * <p><b>Where the reply token can leak.</b> The request can mint a {@link
 * org.jwcarman.nessy.api.tool.ReplyToken}, and whoever holds one can settle the call. A policy
 * engine logs its input and is frequently somebody else's service, so a renderer must never put one
 * in the document. {@link #standard} does not; minting is an explicit call, so a custom renderer
 * only leaks one by deciding to.
 */
@FunctionalInterface
public interface InputRenderer {

  ObjectNode render(ApprovalRequest request);

  /**
   * Everything a rule could reasonably judge on, and nothing that grants authority.
   *
   * <p>Built field by field rather than by serializing the record, so a field added to {@link
   * ApprovalRequest} later cannot arrive in somebody's policy engine without a decision here.
   */
  static InputRenderer standard(ObjectMapper mapper) {
    return request -> {
      ObjectNode input = mapper.createObjectNode();
      input.put("agentType", request.agentType().name());
      input.put("agentId", request.agentId().value());
      input.put("turnId", request.turnId().value());
      input.put("callId", request.callId().value());
      input.put("toolName", request.toolName());
      input.put("action", request.action());
      input.put("askedAt", request.askedAt().toString());
      input.set("arguments", request.arguments());
      input.set("facts", request.facts());
      return input;
    };
  }
}
