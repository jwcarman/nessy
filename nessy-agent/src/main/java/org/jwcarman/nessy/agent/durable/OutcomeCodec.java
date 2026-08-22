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
package org.jwcarman.nessy.agent.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;

/**
 * Internal storage machinery: renders the {@code computation} document — {@code {status, outcome?,
 * continuations[]}} (substrate spec §6.5) — to and from the JSON the string-payload substrate
 * persists. Not API vocabulary; only {@link StoredComputations} calls this.
 *
 * <p>{@code outcome} carries a {@code "type"} discriminator (mirroring the {@code Schemas}/{@code
 * SealedInputs}/{@code MessageCodec} convention): {@code success}, {@code failure}, {@code
 * cancelled}. A {@code success} outcome's payload is itself discriminated as the closed vocabulary
 * substrate spec §7 mandates: {@code tool-result} ({@link ToolResult}) or {@code decision} ({@link
 * Decision}) — any other payload type is out of contract and rejected here, before any write.
 * Continuations serialize as {@code {type, data}} pairs, already opaque strings.
 *
 * <p>Reads are tolerant: unknown fields are ignored. Malformed JSON or an unrecognized
 * discriminator fails loudly with an {@link IllegalArgumentException} naming the offense.
 */
final class OutcomeCodec {

  private static final String TYPE = "type";
  private static final String STATUS = "status";
  private static final String OUTCOME = "outcome";
  private static final String CONTINUATIONS = "continuations";

  private static final String TYPE_SUCCESS = "success";
  private static final String TYPE_FAILURE = "failure";
  private static final String TYPE_CANCELLED = "cancelled";

  private static final String PAYLOAD = "payload";
  private static final String PAYLOAD_TOOL_RESULT = "tool-result";
  private static final String PAYLOAD_DECISION = "decision";

  private static final String DECISION_TYPE = "decisionType";
  private static final String DECISION_ALLOW = "allow";
  private static final String DECISION_DENY = "deny";

  private OutcomeCodec() {}

  /**
   * The {@code computation} document's shape: {@code status} always present, {@code outcome} null
   * while {@code PENDING}.
   */
  record SlotDocument(ComputationStatus status, Outcome outcome, List<Continuation> continuations) {

    SlotDocument {
      Objects.requireNonNull(status, "status must not be null");
      Objects.requireNonNull(continuations, "continuations must not be null");
      continuations = List.copyOf(continuations);
    }
  }

  /**
   * Renders {@code document} to JSON. A {@code Success} outcome whose payload is neither {@link
   * ToolResult} nor {@link Decision} throws {@link IllegalArgumentException} here — before the
   * caller writes anything.
   */
  static String toJson(SlotDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    ObjectNode root = Codecs.MAPPER.createObjectNode();
    root.put(STATUS, document.status().name());
    if (document.outcome() != null) {
      root.set(OUTCOME, writeOutcome(document.outcome()));
    }
    ArrayNode continuations = root.putArray(CONTINUATIONS);
    for (Continuation continuation : document.continuations()) {
      continuations.add(
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, continuation.type())
              .put("data", continuation.data()));
    }
    return root.toString();
  }

  static SlotDocument document(String json) {
    Objects.requireNonNull(json, "json must not be null");
    ObjectNode root = Codecs.readObject(json, "computation slot");
    ComputationStatus status = readStatus(Codecs.requireText(root, STATUS, "computation slot"));
    JsonNode outcomeNode = root.get(OUTCOME);
    Outcome outcome =
        outcomeNode == null || outcomeNode.isNull()
            ? null
            : readOutcome(Codecs.requireObject(outcomeNode, "outcome"));
    ArrayNode continuationsNode = Codecs.requireArray(root, CONTINUATIONS, "computation slot");
    List<Continuation> continuations = new ArrayList<>();
    for (JsonNode node : continuationsNode) {
      ObjectNode continuationNode = Codecs.requireObject(node, "continuation");
      continuations.add(
          new Continuation(
              Codecs.requireText(continuationNode, TYPE, "continuation"),
              Codecs.requireText(continuationNode, "data", "continuation")));
    }
    return new SlotDocument(status, outcome, continuations);
  }

  private static ComputationStatus readStatus(String statusText) {
    try {
      return ComputationStatus.valueOf(statusText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("unknown computation status: " + statusText, e);
    }
  }

  private static ObjectNode writeOutcome(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Success(Object value) -> writeSuccess(value);
      case Outcome.Failure(String message) ->
          Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_FAILURE).put("message", message);
      case Outcome.Cancelled(String reason) ->
          Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_CANCELLED).put("reason", reason);
    };
  }

  private static ObjectNode writeSuccess(Object value) {
    ObjectNode node = Codecs.MAPPER.createObjectNode().put(TYPE, TYPE_SUCCESS);
    node.set(PAYLOAD, writePayload(value));
    return node;
  }

  private static ObjectNode writePayload(Object value) {
    return switch (value) {
      case ToolResult(String content, boolean isError) ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, PAYLOAD_TOOL_RESULT)
              .put("content", content)
              .put("isError", isError);
      case Decision decision -> writeDecision(decision);
      default ->
          throw new IllegalArgumentException(
              "unsupported success payload type: "
                  + value.getClass().getName()
                  + "; expected one of: "
                  + ToolResult.class.getSimpleName()
                  + ", "
                  + Decision.class.getSimpleName());
    };
  }

  private static ObjectNode writeDecision(Decision decision) {
    return switch (decision) {
      case Decision.Allow ignored ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, PAYLOAD_DECISION)
              .put(DECISION_TYPE, DECISION_ALLOW);
      case Decision.Deny(String reason) ->
          Codecs.MAPPER
              .createObjectNode()
              .put(TYPE, PAYLOAD_DECISION)
              .put(DECISION_TYPE, DECISION_DENY)
              .put("reason", reason);
    };
  }

  private static Outcome readOutcome(ObjectNode node) {
    String type = Codecs.requireText(node, TYPE, "outcome");
    return switch (type) {
      case TYPE_SUCCESS -> readSuccess(node);
      case TYPE_FAILURE ->
          new Outcome.Failure(Codecs.requireText(node, "message", "failure outcome"));
      case TYPE_CANCELLED ->
          new Outcome.Cancelled(Codecs.requireText(node, "reason", "cancelled outcome"));
      default ->
          throw new IllegalArgumentException(
              "unknown outcome type: "
                  + type
                  + "; expected one of: "
                  + TYPE_SUCCESS
                  + ", "
                  + TYPE_FAILURE
                  + ", "
                  + TYPE_CANCELLED);
    };
  }

  private static Outcome readSuccess(ObjectNode node) {
    ObjectNode payloadNode =
        Codecs.requireObject(Codecs.requireField(node, PAYLOAD, "success outcome"), PAYLOAD);
    String payloadType = Codecs.requireText(payloadNode, TYPE, "success payload");
    Object payload =
        switch (payloadType) {
          case PAYLOAD_TOOL_RESULT ->
              new ToolResult(
                  Codecs.requireText(payloadNode, "content", "tool-result payload"),
                  Codecs.requireBoolean(payloadNode, "isError", "tool-result payload"));
          case PAYLOAD_DECISION -> readDecision(payloadNode);
          default ->
              throw new IllegalArgumentException(
                  "unknown success payload type: "
                      + payloadType
                      + "; expected one of: "
                      + PAYLOAD_TOOL_RESULT
                      + ", "
                      + PAYLOAD_DECISION);
        };
    return new Outcome.Success(payload);
  }

  private static Decision readDecision(ObjectNode node) {
    String decisionType = Codecs.requireText(node, DECISION_TYPE, "decision payload");
    return switch (decisionType) {
      case DECISION_ALLOW -> Decision.allow();
      case DECISION_DENY -> new Decision.Deny(Codecs.requireText(node, "reason", "deny decision"));
      default ->
          throw new IllegalArgumentException(
              "unknown decision type: "
                  + decisionType
                  + "; expected one of: "
                  + DECISION_ALLOW
                  + ", "
                  + DECISION_DENY);
    };
  }
}
