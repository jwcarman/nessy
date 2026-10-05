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

package org.jwcarman.nessy.console;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AskOutcome;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.TerminationOutcome;
import org.jwcarman.nessy.api.TurnStats;

/**
 * A harness that narrates a scripted run of events and then returns an outcome.
 *
 * <p>Narrated on THIS thread, which is now also what the real one does: the caller is waiting, so
 * there is no other thread for a fragment to arrive on.
 */
final class FakeHarness implements DirectHarness<String, String> {

  /** Stands in for a tally nothing here is measuring. */
  static final TurnStats ANY_STATS = TurnStats.opened(Instant.EPOCH);

  private static final AgentType TYPE = new AgentType("chat");

  private final List<List<Narration>> answers;
  private final List<String> asked = new ArrayList<>();
  private final List<AgentId> askedOf = new ArrayList<>();
  private final List<AgentId> terminated = new ArrayList<>();
  private NarrationListener narrator = NarrationListener.none();
  private AskOutcome<String> outcome = new AskOutcome.Answered<>("(already streamed)", ANY_STATS);
  private int next;

  @SafeVarargs
  FakeHarness(List<Narration>... answers) {
    this.answers = List.of(answers);
  }

  /** The engine is told its listeners at construction; a fake is told afterwards. */
  void narrateTo(NarrationListener narrator) {
    this.narrator = narrator;
  }

  /** What ask should hand back, for the cases a terminal has to report rather than print. */
  FakeHarness answering(AskOutcome<String> outcome) {
    this.outcome = outcome;
    return this;
  }

  @Override
  public AskOutcome<String> ask(AgentId agent, String input) {
    asked.add(input);
    askedOf.add(agent);
    if (next < answers.size()) {
      answers.get(next++).forEach(event -> narrator.on(Envelopes.of(TYPE, agent, event)));
    }
    return outcome;
  }

  @Override
  public TerminationOutcome terminate(AgentId agent) {
    terminated.add(agent);
    narrator.on(Envelopes.of(TYPE, agent, new Narration.Terminated()));
    return new TerminationOutcome.Terminated();
  }

  /** The agent each question was put to, in order. */
  List<AgentId> askedOf() {
    return List.copyOf(askedOf);
  }

  List<AgentId> terminated() {
    return List.copyOf(terminated);
  }

  List<String> observed() {
    return List.copyOf(asked);
  }
}
