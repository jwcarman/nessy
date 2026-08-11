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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.Subscription;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Pins the two-builder split: a {@link Harness} is infrastructure, once per application, disjoint
 * from {@link AgentBuilder}'s identity (design §17's razor); {@link Harness#agent()} grants an
 * {@link AgentBuilder} that infrastructure, ready for identity. Also pins declared listening's
 * seeding order, veto semantics, and the {@link AgentConfigurationException} model-resolution
 * chain.
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
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  /**
   * Reproduces the old "one hub subscriber sees every agent's traffic" contract without a shared
   * hub instance: a harness-declared listener is seeded into every agent it builds, so the same
   * {@code Consumer} instance fires for both agents' conversations.
   */
  @Test
  void two_agents_share_the_harness_substrate() {
    FakeProvider provider = new FakeProvider("hi from A", "hi from B");
    ConversationStore store = ConversationStore.inMemory();
    List<ConversationEvent> observed = new ArrayList<>();
    Harness harness =
        Nessy.harness(provider).store(store).listen(ConversationEvent.class, observed::add).build();

    Agent<String> agentA = harness.agent().model("model-a").build();
    Agent<String> agentB = harness.agent().model("model-b").build();
    ConversationId conversationA = agentA.converse().tell("hello").state().id();
    ConversationId conversationB = agentB.converse().tell("hello").state().id();

    assertThat(observed.stream().map(ConversationEvent::conversationId))
        .contains(conversationA, conversationB);
    assertThat(store.load(conversationA)).isPresent();
    assertThat(store.load(conversationB)).isPresent();
  }

  @Test
  void the_implicit_one_liner_still_works() {
    FakeProvider provider = new FakeProvider("The answer is 4.");

    Agent<String> agent = Nessy.harness(provider).build().agent().model("fake-model").build();
    TextObserver observer = new TextObserver();
    RunOutcome reply = agent.converse().tell("what is 2+2?", observer);

    assertThat(observer.text()).isEqualTo("The answer is 4.");
    assertThat(RunOutcomes.failed(reply)).isFalse();
  }

  /** Design §17's model resolution chain: agent {@code .model(...)} wins over both. */
  @Nested
  class Model_resolution {

    @Test
    void the_agents_own_model_wins_over_the_harness_default() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider)
              .defaultModel("harness-default")
              .build()
              .agent()
              .model("agent-model")
              .build();

      agent.converse().tell("hi");

      assertThat(provider.requests().getFirst().model()).isEqualTo("agent-model");
    }

    @Test
    void the_harness_default_model_is_used_when_the_agent_declares_none() {
      FakeProvider provider = new FakeProvider("hi");
      Agent<String> agent =
          Nessy.harness(provider).defaultModel("harness-default").build().agent().build();

      agent.converse().tell("hi");

      assertThat(provider.requests().getFirst().model()).isEqualTo("harness-default");
    }

    @Test
    void neither_model_declared_throws_a_named_AgentConfigurationException() {
      FakeProvider provider = new FakeProvider("hi");
      AgentBuilder<String> agentBuilder = Nessy.harness(provider).build().agent();

      assertThatThrownBy(agentBuilder::build)
          .isInstanceOf(AgentConfigurationException.class)
          .hasMessageContaining("model");
    }
  }

  /** Design §17's declared-listening chain: seeding order, veto-stops-chain, async-never-vetoes. */
  @Nested
  class Declared_listening {

    @Test
    void harness_declarations_seed_before_the_agents_own_in_declaration_order() {
      FakeProvider provider = new FakeProvider("hi");
      List<String> order = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(provider)
              .listen(ConversationEvent.class, e -> order.add("harness-1"))
              .listen(ConversationEvent.class, e -> order.add("harness-2"))
              .build()
              .agent()
              .model("fake-model")
              .listen(ConversationEvent.class, e -> order.add("agent-1"))
              .listen(ConversationEvent.class, e -> order.add("agent-2"))
              .build();

      agent.converse().tell("hi");

      assertThat(order).startsWith("harness-1", "harness-2", "agent-1", "agent-2");
    }

    @Test
    void a_throwing_declared_listener_stops_delivery_to_later_listeners_and_the_operation() {
      FakeProvider provider = new FakeProvider("hi");
      List<String> reached = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .listen(
                  ConversationEvent.class,
                  e -> {
                    throw new IllegalStateException("listener blew up");
                  })
              .listen(ConversationEvent.class, e -> reached.add("never"))
              .build();
      Conversation<String> conversation = agent.converse();

      assertThatThrownBy(() -> conversation.tell("hi"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("listener blew up");
      assertThat(reached).isEmpty();
    }

    @Test
    void an_async_declared_listener_never_vetoes() throws InterruptedException {
      FakeProvider provider = new FakeProvider("hi");
      CountDownLatch handled = new CountDownLatch(1);
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .listenAsync(
                  ConversationEvent.class,
                  e -> {
                    handled.countDown();
                    throw new IllegalStateException("async listener blew up");
                  },
                  t -> {})
              .build();

      RunOutcome reply = agent.converse().tell("hi");

      assertThat(RunOutcomes.failed(reply)).isFalse();
      assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Design §17's one dynamic level: {@code conversation.events().subscribe(...)}. */
  @Nested
  class Conversation_local_subscription {

    @Test
    void a_conversation_local_subscription_attaches_and_detaches() {
      FakeProvider provider = new FakeProvider("hi", "there");
      Agent<String> agent = Nessy.harness(provider).build().agent().model("fake-model").build();
      Conversation<String> chat = agent.converse();
      List<ConversationEvent> observed = new ArrayList<>();

      Subscription subscription = chat.events().subscribe(ConversationEvent.class, observed::add);
      chat.tell("hi");
      assertThat(observed).isNotEmpty();

      subscription.close();
      observed.clear();
      chat.tell("still there?");
      assertThat(observed).isEmpty();
    }

    @Test
    void a_conversation_local_subscription_never_sees_another_conversations_events() {
      FakeProvider provider = new FakeProvider("hi", "there");
      Agent<String> agent = Nessy.harness(provider).build().agent().model("fake-model").build();
      Conversation<String> chatA = agent.converse();
      Conversation<String> chatB = agent.converse();
      List<ConversationEvent> observedByA = new ArrayList<>();
      chatA.events().subscribe(ConversationEvent.class, observedByA::add);

      chatB.tell("hi");

      assertThat(observedByA).isEmpty();
    }
  }
}
