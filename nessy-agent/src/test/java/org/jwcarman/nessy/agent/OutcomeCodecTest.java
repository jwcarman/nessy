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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.OutcomeCodec.DeliveryDocument;
import org.jwcarman.nessy.agent.OutcomeCodec.PendingDocument;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolResult;

class OutcomeCodecTest {

  private static final OutcomeCodec CODEC = new OutcomeCodec(new ObjectMapper());
  private static final ToolInvocationId INVOCATION = new ToolInvocationId("response-1", "call-1");
  private static final Continuation RETURN_ADDRESS = new Continuation("SCOPE_RESUME", "{\"a\":1}");

  private static Outcome.Success success(Object domainPayload) {
    return new Outcome.Success(CODEC.encodeSuccess(domainPayload));
  }

  @Nested
  class PendingComputationRoundTrips {

    @Test
    void aDeadlinelessPendingDocumentRoundTripsEqual() {
      var document = new PendingDocument(INVOCATION, RETURN_ADDRESS, Optional.empty());

      var roundTripped = CODEC.pendingDocument(CODEC.toJson(document));

      assertThat(roundTripped).isEqualTo(document);
    }

    @Test
    void aDeadlinedPendingDocumentRoundTripsEqual() {
      var deadline = Instant.parse("2026-08-22T12:00:00Z");
      var document = new PendingDocument(INVOCATION, RETURN_ADDRESS, Optional.of(deadline));

      var roundTripped = CODEC.pendingDocument(CODEC.toJson(document));

      assertThat(roundTripped).isEqualTo(document);
    }

    @Test
    void aDeadlinelessPendingDocumentEmitsTheExactGoldenShapeWithNoDeadlineKey() {
      var document = new PendingDocument(INVOCATION, RETURN_ADDRESS, Optional.empty());

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"invocation\":{\"responseId\":\"response-1\",\"callId\":\"call-1\"},"
                  + "\"returnAddress\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"}}");
    }

    @Test
    void aDeadlinedPendingDocumentEmitsTheExactGoldenShapeWithThePinnedDeadlineKey() {
      var deadline = Instant.parse("2026-08-22T12:00:00Z");
      var document = new PendingDocument(INVOCATION, RETURN_ADDRESS, Optional.of(deadline));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"invocation\":{\"responseId\":\"response-1\",\"callId\":\"call-1\"},"
                  + "\"returnAddress\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"deadlineEpochMilli\":1787400000000}");
    }
  }

  @Nested
  class DeliveryClosedVocabularyRoundTrips {

    @Test
    void aToolResultSuccessRoundTripsEqual() {
      var outcome = success(ToolResult.ok("42"));
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped).isEqualTo(document);
    }

    @Test
    void anErroredToolResultSuccessRoundTripsEqual() {
      var outcome = success(ToolResult.error("boom"));
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void anAllowDecisionSuccessRoundTripsEqual() {
      var outcome = success(Decision.allow());
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aDenyDecisionSuccessRoundTripsEqual() {
      var outcome = success(new Decision.Deny("not today"));
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aFailureRoundTripsEqual() {
      var outcome = new Outcome.Failure("it broke");
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aCancellationRoundTripsEqual() {
      var outcome = new Outcome.Cancelled("nobody cares");
      var document = new DeliveryDocument(RETURN_ADDRESS, outcome);

      var roundTripped = CODEC.deliveryDocument(CODEC.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }
  }

  @Nested
  class DeliveryGoldenShapes {

    @Test
    void aToolResultSuccessEmitsTheExactGoldenShape() {
      var document = new DeliveryDocument(RETURN_ADDRESS, success(ToolResult.ok("42")));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"outcome\":{\"type\":\"success\",\"payload\":"
                  + "{\"type\":\"tool-result\",\"content\":\"42\",\"isError\":false}}}");
    }

    @Test
    void anAllowDecisionSuccessEmitsTheExactGoldenShape() {
      var document = new DeliveryDocument(RETURN_ADDRESS, success(Decision.allow()));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"outcome\":{\"type\":\"success\",\"payload\":{\"type\":\"allow\"}}}");
    }

    @Test
    void aDenyDecisionSuccessEmitsTheExactGoldenShape() {
      var document = new DeliveryDocument(RETURN_ADDRESS, success(new Decision.Deny("no")));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"outcome\":{\"type\":\"success\",\"payload\":{\"type\":\"deny\",\"reason\":\"no\"}}}");
    }

    @Test
    void aFailureEmitsTheExactGoldenShape() {
      var document = new DeliveryDocument(RETURN_ADDRESS, new Outcome.Failure("boom"));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"outcome\":{\"type\":\"failure\",\"message\":\"boom\"}}");
    }

    @Test
    void aCancellationEmitsTheExactGoldenShape() {
      var document = new DeliveryDocument(RETURN_ADDRESS, new Outcome.Cancelled("meh"));

      assertThat(CODEC.toJson(document))
          .isEqualTo(
              "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{\\\"a\\\":1}\"},"
                  + "\"outcome\":{\"type\":\"cancelled\",\"reason\":\"meh\"}}");
    }
  }

  @Nested
  class RejectedPayloads {

    @Test
    void encodingASuccessPayloadOutsideTheClosedVocabularyIsRejected() {
      assertThatThrownBy(() -> CODEC.encodeSuccess("a bare string"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported success payload type");
    }

    @Test
    void decodingAnUnrecognizedSuccessPayloadShapeIsRejected() {
      var mystery = JsonNodeFactory.instance.objectNode().put("type", "mystery");

      assertThatThrownBy(() -> CODEC.decodeSuccess(mystery))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class MalformedInput {

    @Test
    void malformedPendingJsonIsRejected() {
      assertThatThrownBy(() -> CODEC.pendingDocument("not json"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedDeliveryJsonIsRejected() {
      assertThatThrownBy(() -> CODEC.deliveryDocument("not json"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownOutcomeTypeIsRejected() {
      String json =
          "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{}\"},"
              + "\"outcome\":{\"type\":\"mystery\"}}";
      assertThatThrownBy(() -> CODEC.deliveryDocument(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown outcome type");
    }

    @Test
    void anUnknownSuccessPayloadTypeIsRejected() {
      String json =
          "{\"destination\":{\"type\":\"SCOPE_RESUME\",\"data\":\"{}\"},"
              + "\"outcome\":{\"type\":\"success\",\"payload\":{\"type\":\"mystery\"}}}";
      assertThatThrownBy(() -> CODEC.deliveryDocument(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown success payload type");
    }
  }
}
