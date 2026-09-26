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

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.Outcome;

/**
 * A harness that narrates a scripted run of events and then returns an outcome.
 *
 * <p>Narrated on THIS thread, which is now also what the real one does: the caller is waiting, so
 * there is no other thread for a fragment to arrive on.
 */
final class FakeHarness implements DirectHarness<String, String> {

  private static final AgentType TYPE = new AgentType("chat");

  private final List<List<Narration>> answers;
  private final List<String> asked = new ArrayList<>();
  private NarrationListener narrator = NarrationListener.none();
  private Outcome<String> outcome = new Outcome.Answered<>("(already streamed)");
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
  FakeHarness answering(Outcome<String> outcome) {
    this.outcome = outcome;
    return this;
  }

  @Override
  public Outcome<String> ask(AgentId agent, String input) {
    asked.add(input);
    if (next < answers.size()) {
      answers.get(next++).forEach(event -> narrator.on(TYPE, agent, event));
    }
    return outcome;
  }

  @Override
  public void terminate(AgentId agent) {
    narrator.on(TYPE, agent, new Narration.Terminated());
  }

  List<String> observed() {
    return List.copyOf(asked);
  }
}
