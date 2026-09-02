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
package org.jwcarman.nessy.approval.policy.opa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jwcarman.nessy.api.tool.ApprovalRequest;

/**
 * Builds the <b>input document</b> — what a Rego policy sees as {@code input}.
 *
 * <p>OPA's own word, and unambiguous behind this package boundary. Elsewhere in Nessy {@code input}
 * means a tool's bound arguments, which is exactly why this vocabulary does not leave here.
 *
 * <p>Named for what it DOES rather than what it produces — the document is the noun, this renders
 * one — matching {@code ActionRenderer}, which renders the sentence a person consents to. One
 * request, two audiences: a person reads the action, a policy engine reads this.
 *
 * <p><b>This is where a capability can leak.</b> {@link ApprovalRequest#replyToken()} settles the
 * call, and a policy engine logs its input and is frequently somebody else's service. Neither
 * renderer below emits one, and both build field by field rather than serializing the record — so a
 * field added to {@link ApprovalRequest} later cannot arrive in a policy engine without somebody
 * deciding it should. Minting is an explicit call, so a custom renderer only leaks a token by
 * choosing to.
 */
@FunctionalInterface
public interface InputRenderer {

  String ACTION_FIELD = "action";

  ObjectNode render(ApprovalRequest request);

  /** Everything a rule could reasonably judge on, flat, and nothing that grants authority. */
  static InputRenderer standard(ObjectMapper mapper) {
    return request -> {
      ObjectNode input = mapper.createObjectNode();
      input.put("agentType", request.agentType().name());
      input.put("agentId", request.agentId().value());
      input.put("turnId", request.turnId().value());
      input.put("callId", request.callId().value());
      input.put("toolName", request.toolName());
      input.put(ACTION_FIELD, request.action());
      input.put("askedAt", request.askedAt().toString());
      input.set("arguments", request.arguments());
      input.set("facts", request.facts());
      return input;
    };
  }

  /**
   * The shape the OpenID Foundation's Authorization API 1.0 (AuthZEN) defines, for a shop already
   * running such an endpoint.
   *
   * <p>The mapping is natural: the agent is the subject, the tool is the resource, calling it is
   * the action, and the facts other approvers deposited are context.
   *
   * <p><b>One judgement call worth knowing about.</b> AuthZEN's {@code resource} is the thing being
   * protected, and a policy usually cares about the tool's TARGET — {@code prod-eu-1} — rather than
   * the tool itself. Which argument that is cannot be known here, so this defaults to the tool. An
   * application that knows its own domain should supply its own renderer; that is the seam earning
   * its keep.
   *
   * <p>Note that AuthZEN's <em>response</em> is a boolean and cannot express {@code Delegate}. See
   * {@link DecisionInterpreter#authzen()}.
   */
  static InputRenderer authzen(ObjectMapper mapper) {
    return request -> {
      ObjectNode input = mapper.createObjectNode();
      ObjectNode subject = input.putObject("subject");
      subject.put("type", "agent");
      subject.put("id", request.agentId().value());
      subject.putObject("properties").put("agentType", request.agentType().name());

      ObjectNode resource = input.putObject("resource");
      resource.put("type", "tool");
      resource.put("id", request.toolName());

      ObjectNode action = input.putObject(ACTION_FIELD);
      action.put("name", "call");
      action.putObject("properties").set("arguments", request.arguments());

      ObjectNode context = input.putObject("context");
      context.put(ACTION_FIELD, request.action());
      context.put("askedAt", request.askedAt().toString());
      context.set("facts", request.facts());
      return input;
    };
  }
}
