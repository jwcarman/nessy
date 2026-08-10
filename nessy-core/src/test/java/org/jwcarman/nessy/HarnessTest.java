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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.SessionEvent;
import org.jwcarman.nessy.api.session.SessionId;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.session.InMemoryTranscriptStore;
import org.jwcarman.nessy.spi.session.SessionStore;
import org.jwcarman.nessy.spi.session.TranscriptStore;

/**
 * Pins the two-builder split: a {@link Harness} is infrastructure, once per application; {@link
 * Harness#agent()} grants an {@link AgentBuilder} that infrastructure, ready for identity.
 */
class HarnessTest {

  /** A model that replays one scripted text turn per call and records every request it saw. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk(replies.removeFirst()),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  @Test
  void two_agents_share_the_harness_substrate() {
    FakeProvider provider = new FakeProvider("hi from A", "hi from B");
    SessionStore store = SessionStore.inMemory();
    EventHub hub = EventHub.synchronous();
    Harness harness = Nessy.harness().provider(provider).store(store).hub(hub).build();
    List<SessionEvent> observed = new ArrayList<>();
    hub.subscribe(SessionEvent.class, observed::add);

    Agent<String> agentA = harness.agent().model("model-a").build();
    Agent<String> agentB = harness.agent().model("model-b").build();
    SessionId sessionA = agentA.converse().tell("hello").state().id();
    SessionId sessionB = agentB.converse().tell("hello").state().id();

    assertThat(observed.stream().map(SessionEvent::sessionId)).contains(sessionA, sessionB);
    assertThat(store.load(sessionA)).isPresent();
    assertThat(store.load(sessionB)).isPresent();
  }

  /**
   * {@link HarnessBuilder#transcript} is sugar that registers one inline journaling subscriber on
   * the shared hub at {@link HarnessBuilder#build()} time — not once per {@link Harness#agent()}. A
   * second, accidental registration per agent would double-journal every message; this pins that it
   * does not.
   */
  @Test
  void the_transcript_sugar_is_registered_once_per_harness_not_once_per_agent() {
    FakeProvider provider = new FakeProvider("hi from A", "hi from B");
    InMemoryTranscriptStore journal = TranscriptStore.inMemory();
    Harness harness = Nessy.harness().provider(provider).transcript(journal).build();

    Agent<String> agentA = harness.agent().model("model-a").build();
    Agent<String> agentB = harness.agent().model("model-b").build();
    SessionId sessionA = agentA.converse().tell("hello").state().id();
    SessionId sessionB = agentB.converse().tell("hello").state().id();

    // One user message plus one assistant reply per session; a duplicate registration would
    // journal each newborn message twice.
    assertThat(journal.entries(sessionA)).hasSize(2);
    assertThat(journal.entries(sessionB)).hasSize(2);
  }

  @Test
  void an_agent_may_override_the_harness_provider() {
    FakeProvider harnessProvider = new FakeProvider("from harness provider");
    FakeProvider agentProvider = new FakeProvider("from agent provider");
    Harness harness = Nessy.harness().provider(harnessProvider).build();

    Agent<String> agent = harness.agent().provider(agentProvider).model("fake-model").build();
    agent.converse().tell("hi");

    assertThat(agentProvider.requests()).hasSize(1);
    assertThat(harnessProvider.requests()).isEmpty();
  }

  @Test
  void an_agent_without_any_provider_fails_at_build() {
    Harness harness = Nessy.harness().build();

    assertThatThrownBy(() -> harness.agent().model("fake-model").build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("provider");
  }

  @Test
  void the_implicit_default_harness_keeps_the_one_liner_working() {
    FakeProvider provider = new FakeProvider("The answer is 4.");

    Agent<String> agent = Nessy.agent().provider(provider).model("fake-model").build();
    Reply reply = agent.converse().tell("what is 2+2?");

    assertThat(reply.text()).isEqualTo("The answer is 4.");
    assertThat(reply.failed()).isFalse();
  }
}
