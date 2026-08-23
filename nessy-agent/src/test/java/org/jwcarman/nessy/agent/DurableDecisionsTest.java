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
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.spi.approval.Adjudication;

/** The one mapping from a durable outcome to the approval grammar (spec §4.3 amendment). */
class DurableDecisionsTest {

  private static final ComputationId COMPUTATION = ComputationId.of("approval:t:a:c1");
  private static final ObjectMapper MAPPER = TestMappers.plainlyPinned();

  @Test
  void deniedRejectsABlankReason() {
    assertThatThrownBy(() -> DurableDecisions.denied(MAPPER, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSuccessfulAllowIsGranted() {
    var outcome = DurableDecisions.granted(MAPPER);
    assertThat(DurableDecisions.toAdjudication(MAPPER, outcome, COMPUTATION))
        .isEqualTo(new Adjudication.Granted());
  }

  @Test
  void aSuccessfulDenyIsRefusedWithTheDenialReason() {
    var outcome = DurableDecisions.denied(MAPPER, "r");
    assertThat(DurableDecisions.toAdjudication(MAPPER, outcome, COMPUTATION))
        .isEqualTo(new Adjudication.Refused("r"));
  }

  @Test
  void anUnexpectedSuccessPayloadIsRefusedNamingTheOffendingPayload() {
    var mystery = JsonNodeFactory.instance.objectNode().put("type", "mystery");
    var outcome = new Outcome.Success(mystery);
    var adjudication =
        (Adjudication.Refused) DurableDecisions.toAdjudication(MAPPER, outcome, COMPUTATION);
    assertThat(adjudication.reason()).startsWith("unexpected approval payload: ");
  }

  @Test
  void aFailureIsRefusedWithTheFailureMessage() {
    var outcome = new Outcome.Failure("m");
    assertThat(DurableDecisions.toAdjudication(MAPPER, outcome, COMPUTATION))
        .isEqualTo(new Adjudication.Refused("m"));
  }

  @Test
  void aCancellationIsRefusedNamingItself() {
    var outcome = new Outcome.Cancelled("r");
    assertThat(DurableDecisions.toAdjudication(MAPPER, outcome, COMPUTATION))
        .isEqualTo(new Adjudication.Refused("cancelled: r"));
  }
}
