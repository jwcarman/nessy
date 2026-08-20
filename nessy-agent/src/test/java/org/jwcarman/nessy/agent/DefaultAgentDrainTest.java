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

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;

class DefaultAgentDrainTest {

  @Test
  void aFailingRendererDiscardsTheObservationAndKeepsDraining() {
    // A second agent over the SAME collaborators: instances are stateless views (§3.5), so the
    // fixture's sink delivering to f.agent while we drive `poisoned` is correct by design —
    // both apply against the shared store, and this test quietly proves interchangeability.
    var f = new AgentFixture();
    var poisoned =
        new DefaultAgent<String>(
            new AgentWiring<>(
                f.memory,
                f.store,
                f.backlog,
                text -> {
                  if (text.startsWith("bad")) {
                    throw new IllegalArgumentException("unrenderable");
                  }
                  return List.of(new TextBlock(text));
                },
                f.model,
                f.tools,
                f.observer,
                false,
                Duration.ofMinutes(5),
                Clock.systemUTC()));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.backlogQueue.add("bad-observation");
    f.backlogQueue.add("good-observation");
    poisoned.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.renderFailures()).containsExactly("bad-observation");
    assertThat(f.memory.remembered())
        .contains(Message.user(List.of(new TextBlock("good-observation"))));
  }

  @Test
  void anObservationThatLosesTheRaceReturnsToTheBacklog() {
    // Competitor moves the scope off Idle just before our save; benign duplicate in memory is
    // the accepted §5.2 class — the assertion is the re-add, not memory purity.
    var inner = new InMemoryAgentStateStore();
    var competitorState = new State(new Phase.AwaitingModel(), 0L);
    var f = new AgentFixture(new RaceOnceStore(inner, competitorState), false);
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("hello");
    assertThat(f.store.load().phase()).isEqualTo(new Phase.AwaitingModel());
  }

  @Test
  void autonomousWiringDrainsTheNextObservationWhenTheTurnEnds() {
    var f = new AgentFixture(new InMemoryAgentStateStore(), true);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("one")), List.of()));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("two")), List.of()));
    f.agent.observe("first");
    f.agent.observe("second"); // arrives mid-turn; queues
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    assertThat(f.model.callCount()).isEqualTo(2);
  }

  @Test
  void interactiveWiringLeavesTheBacklogForTheNextDrive() {
    var f = new AgentFixture(new InMemoryAgentStateStore(), false);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("one")), List.of()));
    f.agent.observe("first");
    f.agent.observe("second");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("second"); // waits for the client's next stream
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("two")), List.of()));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
  }
}
