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
package org.jwcarman.nessy.engine.effect;

import org.jwcarman.nessy.api.tool.ApprovalRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The question an approver was asked, as a document that can be stored.
 *
 * <p><b>Built field by field, never by serializing the request.</b> The request holds the reply
 * token, which settles the call, and a stored document is read by far more than the one who may
 * reply. A field added to {@link ApprovalRequest} later cannot reach storage unless somebody adds
 * it here.
 */
final class ApprovalQuestions {

  private static final JsonMapper READER = JsonMapper.builder().build();

  private ApprovalQuestions() {}

  /**
   * The question as a document, with the fields in a fixed order.
   *
   * <p>The facts are copied: the request is mutable, and an approver adds facts while it decides.
   * The arguments are parsed, so a reader walks a document rather than a string; by the time a
   * question exists they have been read into the tool's input type and do parse, and if they do not
   * the text the model wrote is kept as a string rather than losing the question.
   *
   * <p>A stored document reads numbers back in the narrowest type, so compare documents by
   * serialized text or field by field, never by {@code JsonNode} equality.
   */
  static JsonNode document(ApprovalRequest request) {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode document = nodes.objectNode();
    document.put("agentType", request.agentType().value());
    document.put("agentId", request.agentId().value().toString());
    document.put("turn", request.turn().value());
    document.put("callId", request.callId().value());
    document.put("idempotencyKey", request.idempotencyKey().value().toString());
    document.put("toolName", request.toolName().value());
    document.set("arguments", arguments(request.arguments()));
    document.put("action", request.action());
    document.put("askedAt", request.askedAt().toString());
    document.put("deadline", request.deadline().toString());
    document.set("facts", request.facts().deepCopy());
    return document;
  }

  private static JsonNode arguments(String text) {
    try {
      JsonNode parsed = READER.readTree(text);
      // Empty or blank text parses to a missing node, which cannot be stored; it means no
      // arguments, so it is an empty object.
      return parsed.isMissingNode() ? JsonNodeFactory.instance.objectNode() : parsed;
    } catch (JacksonException _) {
      return JsonNodeFactory.instance.stringNode(text);
    }
  }
}
