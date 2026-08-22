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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.durable.ComputationId;
import org.jwcarman.nessy.durable.ComputationStatus;
import org.jwcarman.nessy.durable.Continuation;
import org.jwcarman.nessy.durable.ContinuationDispatcher;
import org.jwcarman.nessy.durable.Outcome;
import org.jwcarman.nessy.spi.approval.Adjudication;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The approve/deny door: two decisions, one vocabulary — {@code Decision} (spec §4.3 amendment).
 */
class ApprovalDeskTest {

  private record Fired(Continuation continuation, Outcome outcome) {}

  private final StoredComputations backend = new StoredComputations(new InMemorySubstrate());
  private final ContinuationDispatcher dispatcher = new ContinuationDispatcher();
  private final List<Fired> fired = new ArrayList<>();
  private final ApprovalDesk desk = new ApprovalDesk(backend, dispatcher);

  private static final ComputationId SLOT = ComputationId.of("approval:t:a:c1");
  private static final Continuation REDRIVE = new Continuation("REDRIVE_SCOPE", "{}");

  private void park() {
    dispatcher.register("REDRIVE_SCOPE", (c, o) -> fired.add(new Fired(c, o)));
    backend.create(SLOT);
    backend.await(SLOT, REDRIVE);
  }

  @Test
  void approvingCompletesTheSlotWithAllowAndFiresContinuations() {
    park();
    desk.approve(SLOT);
    assertThat(backend.status(SLOT)).contains(ComputationStatus.SUCCEEDED);
    assertThat(fired).hasSize(1);
    assertThat(DurableDecisions.toAdjudication(fired.get(0).outcome(), SLOT))
        .isEqualTo(new Adjudication.Granted());
  }

  @Test
  void denyingCompletesTheSlotWithTheDenyDecision() {
    park();
    desk.deny(SLOT, "no");
    assertThat(fired).hasSize(1);
    assertThat(DurableDecisions.toAdjudication(fired.get(0).outcome(), SLOT))
        .isEqualTo(new Adjudication.Refused("no"));
  }

  @Test
  void aSecondDecisionIsRefusedLoudly() {
    park();
    desk.approve(SLOT);
    assertThatThrownBy(() -> desk.deny(SLOT, "no"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already decided");
  }
}
