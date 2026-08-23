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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jwcarman.nessy.agent.codec.Codecs;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.durable.ToolInvocationId;

/**
 * Internal storage machinery: renders the two documents the {@code computation} and {@code outbox}
 * recipes persist (durable-deliveries spec §3, §4) to and from the JSON the byte-payload substrate
 * stores. Not API vocabulary; only {@link SubstrateComputations} calls this.
 *
 * <p>{@link Outcome}, {@link Decision}, and {@link ToolResult} live in {@code nessy-durable} and
 * {@code nessy-api} respectively and stay Jackson-free by design. This codec binds a private,
 * mapper-annotated wire shape instead and hand-translates it to and from the domain types; that
 * translation IS the closed-vocabulary door for the delivery's outcome payload — a {@code Success}
 * outcome whose payload is neither {@link ToolResult} nor {@link Decision} is rejected here, before
 * any write.
 *
 * <p>The wire vocabulary this codec owns, a compatibility surface once emitted: a {@code
 * computation} document is {@code {invocation: {responseId, callId}, returnAddress: {type, data},
 * deadline?}}; an {@code outbox} delivery is {@code {destination: {type, data}, outcome: {type,
 * ...}}}, whose {@code outcome} carries a {@code "type"} discriminator naming it {@code success},
 * {@code failure}, or {@code cancelled}, and whose {@code success} payload carries its own {@code
 * "type"} discriminator naming it {@code tool-result}, {@code allow}, or {@code deny}.
 *
 * <p>Reads are tolerant: unknown fields are ignored. Malformed JSON or an unrecognized
 * discriminator fails loudly with an {@link IllegalArgumentException} naming the offense.
 */
final class OutcomeCodec {

  private static final String TYPE = "type";
  private static final String PAYLOAD = "payload";
  private static final String OUTCOME = "outcome";
  private static final String DELIVERY = "delivery";
  private static final String COMPUTATION = "computation";
  private static final String DESTINATION = "destination";
  private static final String DATA = "data";
  private static final String AGENT_TYPE = "agentType";

  private static final String TYPE_SUCCESS = "success";
  private static final String TYPE_FAILURE = "failure";
  private static final String TYPE_CANCELLED = "cancelled";
  private static final Set<String> OUTCOME_TYPES =
      Set.of(TYPE_SUCCESS, TYPE_FAILURE, TYPE_CANCELLED);

  private static final String PAYLOAD_TOOL_RESULT = "tool-result";
  private static final String PAYLOAD_ALLOW = "allow";
  private static final String PAYLOAD_DENY = "deny";
  private static final Set<String> PAYLOAD_TYPES =
      Set.of(PAYLOAD_TOOL_RESULT, PAYLOAD_ALLOW, PAYLOAD_DENY);

  private final Codecs codecs;

  OutcomeCodec(ObjectMapper mapper) {
    this.codecs = new Codecs(mapper);
  }

  /** The {@code computation} document's shape: presence alone means pending (spec §3). */
  record PendingDocument(
      ToolInvocationId invocation, Continuation returnAddress, Optional<Instant> deadline) {

    PendingDocument {
      Objects.requireNonNull(invocation, "invocation must not be null");
      Objects.requireNonNull(returnAddress, "returnAddress must not be null");
      Objects.requireNonNull(deadline, "deadline must not be null");
    }
  }

  /** The {@code outbox} delivery's shape (spec §4): a payload plus an address label. */
  record DeliveryDocument(Continuation destination, Outcome outcome) {

    DeliveryDocument {
      Objects.requireNonNull(destination, "destination must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }

  String toJson(PendingDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    return codecs.write(PendingDocumentWire.from(document));
  }

  PendingDocument pendingDocument(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, COMPUTATION);
    return codecs.bind(root, PendingDocumentWire.class, COMPUTATION).toDomain();
  }

  /**
   * Renders {@code document} to JSON. A {@code Success} outcome whose payload is neither {@link
   * ToolResult} nor {@link Decision} throws {@link IllegalArgumentException} here — before the
   * caller writes anything.
   */
  String toJson(DeliveryDocument document) {
    Objects.requireNonNull(document, "document must not be null");
    return codecs.write(DeliveryDocumentWire.from(document));
  }

  DeliveryDocument deliveryDocument(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, DELIVERY);
    requireKnownOutcomeVocabulary(root.get(OUTCOME));
    return codecs.bind(root, DeliveryDocumentWire.class, DELIVERY).toDomain();
  }

  /**
   * The outbox arm's cheap peek (harness-first spec §5, new law): reads only {@code
   * destination.data}'s {@code agentType} field, stopping well short of {@link #deliveryDocument}'s
   * full bind (which also validates the outcome vocabulary) or {@link ScopeRouting}'s full routing
   * decode (which also validates the call shape) — neither of those a type filter needs. Empty when
   * the JSON does not carry a recognizable {@code destination.data.agentType} shape; the caller's
   * own full decode then surfaces the real problem.
   */
  Optional<String> peekDestinationAgentType(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, DELIVERY);
    JsonNode destination = root.get(DESTINATION);
    if (destination == null) {
      return Optional.empty();
    }
    JsonNode dataNode = destination.get(DATA);
    if (dataNode == null || !dataNode.isTextual()) {
      return Optional.empty();
    }
    JsonNode inner = codecs.readTree(dataNode.asText(), DESTINATION);
    JsonNode agentType = inner.get(AGENT_TYPE);
    return agentType == null || agentType.isNull()
        ? Optional.empty()
        : Optional.of(agentType.asText());
  }

  /**
   * The outcome and (for a success) payload discriminators, checked by hand before binding: the
   * mapper's own unresolved-subtype message does not carry the "unknown outcome type"/"unknown
   * success payload type" wording this codec's contract pins.
   */
  private static void requireKnownOutcomeVocabulary(JsonNode outcomeNode) {
    if (outcomeNode == null || outcomeNode.isNull()) {
      return;
    }
    String outcomeType = textOrNull(outcomeNode, TYPE);
    if (!OUTCOME_TYPES.contains(outcomeType)) {
      throw new IllegalArgumentException(
          "unknown outcome type: " + outcomeType + "; expected one of: " + OUTCOME_TYPES);
    }
    if (TYPE_SUCCESS.equals(outcomeType)) {
      String payloadType = textOrNull(outcomeNode.get(PAYLOAD), TYPE);
      if (!PAYLOAD_TYPES.contains(payloadType)) {
        throw new IllegalArgumentException(
            "unknown success payload type: " + payloadType + "; expected one of: " + PAYLOAD_TYPES);
      }
    }
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  /**
   * {@code deadlineEpochMilli} rather than a Jackson-bound {@link Instant} directly: the pinned
   * mapper carries no JSR-310 module (spec §7 pins lower-camel naming and tolerant reads, not a
   * date/time module a caller may not want), so the wire shape stays a plain {@code Long}.
   */
  private record PendingDocumentWire(
      ToolInvocationIdWire invocation,
      ContinuationWire returnAddress,
      @JsonInclude(JsonInclude.Include.NON_NULL) Long deadlineEpochMilli) {

    PendingDocumentWire {
      Objects.requireNonNull(invocation, "invocation must not be null");
      Objects.requireNonNull(returnAddress, "returnAddress must not be null");
    }

    static PendingDocumentWire from(PendingDocument document) {
      return new PendingDocumentWire(
          ToolInvocationIdWire.from(document.invocation()),
          ContinuationWire.from(document.returnAddress()),
          document.deadline().map(Instant::toEpochMilli).orElse(null));
    }

    PendingDocument toDomain() {
      return new PendingDocument(
          invocation.toDomain(),
          returnAddress.toDomain(),
          Optional.ofNullable(deadlineEpochMilli).map(Instant::ofEpochMilli));
    }
  }

  private record ToolInvocationIdWire(String responseId, String callId) {

    ToolInvocationIdWire {
      Objects.requireNonNull(responseId, "responseId must not be null");
      Objects.requireNonNull(callId, "callId must not be null");
    }

    static ToolInvocationIdWire from(ToolInvocationId invocation) {
      return new ToolInvocationIdWire(invocation.responseId(), invocation.callId());
    }

    ToolInvocationId toDomain() {
      return new ToolInvocationId(responseId, callId);
    }
  }

  private record DeliveryDocumentWire(ContinuationWire destination, OutcomeWire outcome) {

    DeliveryDocumentWire {
      Objects.requireNonNull(destination, "destination must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }

    static DeliveryDocumentWire from(DeliveryDocument document) {
      return new DeliveryDocumentWire(
          ContinuationWire.from(document.destination()), OutcomeWire.from(document.outcome()));
    }

    DeliveryDocument toDomain() {
      return new DeliveryDocument(destination.toDomain(), outcome.toDomain());
    }
  }

  private record ContinuationWire(String type, String data) {

    ContinuationWire {
      Objects.requireNonNull(type, "type must not be null");
      Objects.requireNonNull(data, "data must not be null");
    }

    static ContinuationWire from(Continuation continuation) {
      return new ContinuationWire(continuation.type(), continuation.data());
    }

    Continuation toDomain() {
      return new Continuation(type, data);
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = TYPE)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = SuccessWire.class, name = TYPE_SUCCESS),
    @JsonSubTypes.Type(value = FailureWire.class, name = TYPE_FAILURE),
    @JsonSubTypes.Type(value = CancelledWire.class, name = TYPE_CANCELLED)
  })
  private sealed interface OutcomeWire permits SuccessWire, FailureWire, CancelledWire {

    Outcome toDomain();

    static OutcomeWire from(Outcome outcome) {
      return switch (outcome) {
        case Outcome.Success(Object value) -> new SuccessWire(PayloadWire.from(value));
        case Outcome.Failure(String message) -> new FailureWire(message);
        case Outcome.Cancelled(String reason) -> new CancelledWire(reason);
      };
    }
  }

  private record SuccessWire(PayloadWire payload) implements OutcomeWire {

    SuccessWire {
      Objects.requireNonNull(payload, "payload must not be null");
    }

    @Override
    public Outcome toDomain() {
      return new Outcome.Success(payload.toDomain());
    }
  }

  private record FailureWire(String message) implements OutcomeWire {

    FailureWire {
      Objects.requireNonNull(message, "message must not be null");
    }

    @Override
    public Outcome toDomain() {
      return new Outcome.Failure(message);
    }
  }

  private record CancelledWire(String reason) implements OutcomeWire {

    CancelledWire {
      Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public Outcome toDomain() {
      return new Outcome.Cancelled(reason);
    }
  }

  /** The closed vocabulary a {@code Success} payload may hold — checked before any write. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = TYPE)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResultWire.class, name = PAYLOAD_TOOL_RESULT),
    @JsonSubTypes.Type(value = AllowWire.class, name = PAYLOAD_ALLOW),
    @JsonSubTypes.Type(value = DenyWire.class, name = PAYLOAD_DENY)
  })
  private sealed interface PayloadWire permits ToolResultWire, AllowWire, DenyWire {

    Object toDomain();

    static PayloadWire from(Object value) {
      return switch (value) {
        case ToolResult(String content, boolean isError) -> new ToolResultWire(content, isError);
        case Decision.Allow _ -> new AllowWire();
        case Decision.Deny(String reason) -> new DenyWire(reason);
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
  }

  private record ToolResultWire(String content, boolean isError) implements PayloadWire {

    ToolResultWire {
      Objects.requireNonNull(content, "content must not be null");
    }

    @Override
    public Object toDomain() {
      return new ToolResult(content, isError);
    }
  }

  private record AllowWire() implements PayloadWire {
    @Override
    public Object toDomain() {
      return Decision.allow();
    }
  }

  private record DenyWire(String reason) implements PayloadWire {

    DenyWire {
      Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public Object toDomain() {
      return new Decision.Deny(reason);
    }
  }
}
