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
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentEventListener;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;

/**
 * A harness that answers each observation with a scripted run of events.
 *
 * <p>Narrated on THIS thread, which the real engine would not do -- but the loop must not care
 * which thread an event arrives on, and a test that had to start one would be racing.
 */
final class FakeHarness implements Harness<String> {

  private static final AgentType TYPE = new AgentType("chat");

  private final List<List<AgentEvent>> answers;
  private final List<String> observed = new ArrayList<>();
  private AgentEventListener narrator = AgentEventListener.none();
  private int next;

  @SafeVarargs
  FakeHarness(List<AgentEvent>... answers) {
    this.answers = List.of(answers);
  }

  /** The engine is told its narrator at construction; a fake is told afterwards. */
  void narrateTo(AgentEventListener narrator) {
    this.narrator = narrator;
  }

  @Override
  public void observe(AgentId agentId, String observation) {
    observed.add(observation);
    if (next >= answers.size()) {
      return;
    }
    answers.get(next++).forEach(event -> narrator.on(TYPE, agentId, event));
  }

  @Override
  public void terminate(AgentId agentId) {
    narrator.on(TYPE, agentId, new AgentEvent.Terminated());
  }

  List<String> observed() {
    return List.copyOf(observed);
  }
}
