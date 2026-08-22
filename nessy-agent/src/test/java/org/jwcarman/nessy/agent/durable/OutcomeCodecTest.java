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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.durable.OutcomeCodec.SlotDocument;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.Outcome;

class OutcomeCodecTest {

  @Nested
  class ClosedVocabularyRoundTrips {

    @Test
    void aToolResultSuccessRoundTripsEqual() {
      var outcome = new Outcome.Success(ToolResult.ok("42"));
      var document = new SlotDocument(ComputationStatus.SUCCEEDED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void anErroredToolResultSuccessRoundTripsEqual() {
      var outcome = new Outcome.Success(ToolResult.error("boom"));
      var document = new SlotDocument(ComputationStatus.SUCCEEDED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void anAllowDecisionSuccessRoundTripsEqual() {
      var outcome = new Outcome.Success(Decision.allow());
      var document = new SlotDocument(ComputationStatus.SUCCEEDED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aDenyDecisionSuccessRoundTripsEqual() {
      var outcome = new Outcome.Success(new Decision.Deny("not today"));
      var document = new SlotDocument(ComputationStatus.SUCCEEDED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aFailureRoundTripsEqual() {
      var outcome = new Outcome.Failure("it broke");
      var document = new SlotDocument(ComputationStatus.FAILED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aCancellationRoundTripsEqual() {
      var outcome = new Outcome.Cancelled("nobody cares");
      var document = new SlotDocument(ComputationStatus.CANCELLED, outcome, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isEqualTo(outcome);
    }

    @Test
    void aPendingDocumentHasNoOutcome() {
      var document = new SlotDocument(ComputationStatus.PENDING, null, List.of());

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.outcome()).isNull();
    }

    @Test
    void continuationsRoundTripInOrder() {
      var continuations =
          List.of(new Continuation("RESUME_SCOPE", "{\"a\":1}"), new Continuation("TIMER", "{}"));
      var document = new SlotDocument(ComputationStatus.PENDING, null, continuations);

      var roundTripped = OutcomeCodec.document(OutcomeCodec.toJson(document));

      assertThat(roundTripped.continuations()).containsExactlyElementsOf(continuations);
    }
  }

  @Nested
  class RejectedPayloads {

    @Test
    void aSuccessPayloadOutsideTheClosedVocabularyIsRejected() {
      var document =
          new SlotDocument(
              ComputationStatus.SUCCEEDED, new Outcome.Success("a bare string"), List.of());

      assertThatThrownBy(() -> OutcomeCodec.toJson(document))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unsupported success payload type");
    }
  }

  @Nested
  class MalformedInput {

    @Test
    void malformedJsonIsRejected() {
      assertThatThrownBy(() -> OutcomeCodec.document("not json"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownOutcomeTypeIsRejected() {
      String json =
          "{\"status\":\"FAILED\",\"outcome\":{\"type\":\"mystery\"},\"continuations\":[]}";
      assertThatThrownBy(() -> OutcomeCodec.document(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown outcome type");
    }

    @Test
    void anUnknownSuccessPayloadTypeIsRejected() {
      String json =
          "{\"status\":\"SUCCEEDED\",\"outcome\":{\"type\":\"success\",\"payload\":{\"type\":\"mystery\"}},"
              + "\"continuations\":[]}";
      assertThatThrownBy(() -> OutcomeCodec.document(json))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown success payload type");
    }
  }
}
