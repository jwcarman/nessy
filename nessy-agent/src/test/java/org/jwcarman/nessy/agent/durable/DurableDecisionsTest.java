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

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Adjudication;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.Outcome;

/** The one mapping from a durable outcome to the approval grammar (spec §4.3 amendment). */
class DurableDecisionsTest {

  private static final ComputationId SLOT = ComputationId.of("approval:t:a:c1");

  @Test
  void deniedRejectsABlankReason() {
    assertThatThrownBy(() -> DurableDecisions.denied(""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSuccessfulAllowIsGranted() {
    var outcome = new Outcome.Success(Decision.allow());
    assertThat(DurableDecisions.toAdjudication(outcome, SLOT))
        .isEqualTo(new Adjudication.Granted());
  }

  @Test
  void aSuccessfulDenyIsRefusedWithTheDenialReason() {
    var outcome = new Outcome.Success(new Decision.Deny("r"));
    assertThat(DurableDecisions.toAdjudication(outcome, SLOT))
        .isEqualTo(new Adjudication.Refused("r"));
  }

  @Test
  void anUnexpectedSuccessPayloadIsRefusedNamingTheClass() {
    var outcome = new Outcome.Success("garbage");
    var adjudication = (Adjudication.Refused) DurableDecisions.toAdjudication(outcome, SLOT);
    assertThat(adjudication.reason()).startsWith("unexpected approval payload: ");
  }

  @Test
  void aFailureIsRefusedWithTheFailureMessage() {
    var outcome = new Outcome.Failure("m");
    assertThat(DurableDecisions.toAdjudication(outcome, SLOT))
        .isEqualTo(new Adjudication.Refused("m"));
  }

  @Test
  void aCancellationIsRefusedNamingItself() {
    var outcome = new Outcome.Cancelled("r");
    assertThat(DurableDecisions.toAdjudication(outcome, SLOT))
        .isEqualTo(new Adjudication.Refused("cancelled: r"));
  }
}
