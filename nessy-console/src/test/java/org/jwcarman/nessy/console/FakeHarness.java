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
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentSubscriber;
import org.jwcarman.nessy.api.AgentSubscription;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;

/**
 * A harness that records what it was told and narrates whatever a test wants.
 *
 * <p>The engine is not what these tests are about: they are about a loop that posts a line and
 * waits for a turn to end. So this stands in for the engine and lets a test decide, per
 * observation, what the agent says back.
 */
final class FakeHarness implements Harness<String> {

  /** What to narrate in response to the nth observation. */
  private final List<List<AgentEvent>> answers;

  private final List<String> observed = new ArrayList<>();
  private final List<AgentSubscriber> subscribers = new CopyOnWriteArrayList<>();
  private boolean everSubscribed;
  private int next;

  @SafeVarargs
  FakeHarness(List<AgentEvent>... answers) {
    this.answers = List.of(answers);
  }

  @Override
  public AgentType type() {
    return AgentType.of("chat");
  }

  @Override
  public void observe(AgentId agentId, String observation) {
    observed.add(observation);
    if (next >= answers.size()) {
      return;
    }
    // Narrated on THIS thread, which the real engine would not do — but the loop must not care
    // which thread an event arrives on, and a test that had to start one would be racing.
    answers.get(next++).forEach(event -> subscribers.forEach(s -> s.on(event)));
  }

  @Override
  public AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber) {
    subscribers.add(subscriber);
    everSubscribed = true;
    return () -> subscribers.remove(subscriber);
  }

  List<String> observed() {
    return List.copyOf(observed);
  }

  /** Whether anyone ever listened — true even after the listener left. */
  boolean wasListenedTo() {
    return everSubscribed;
  }

  /** Whether anyone is listening NOW: a subscription left open would still be here. */
  boolean isListenedTo() {
    return !subscribers.isEmpty();
  }
}
