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
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Agent}'s own surface: {@code converse()} versus {@code resume(...)}, the {@code loop()}
 * escape hatch, and {@code contextFor(...)}'s both branches (an unknown id, and the same assembly a
 * live {@code tell} would see).
 */
class AgentTest {

  /** A model that replays one scripted text turn per call. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    @Override
    public ModelStream stream(ModelRequest request) {
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

  @Test
  void loop_exposes_the_same_loop_the_facade_runs_calls_through() {
    Agent<String> agent = Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();

    assertThat(agent.loop()).isNotNull();
  }

  @Nested
  class Resuming_a_conversation {

    @Test
    void resume_reopens_the_same_conversation_id_a_prior_converse_produced() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("first", "second")).build().agent().model("m").build();
      ConversationId id = agent.converse().tell("first").state().id();
      TextObserver observer = new TextObserver();

      RunOutcome reply = agent.resume(id).tell("second", observer);

      assertThat(reply.state().id()).isEqualTo(id);
      assertThat(observer.text()).isEqualTo("second");
    }
  }

  @Nested
  class ContextFor {

    @Test
    void an_unknown_conversation_id_is_rejected() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();

      var unknownId = new ConversationId("never-stored");

      assertThatThrownBy(() -> agent.contextFor(unknownId))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("never-stored");
    }

    @Test
    void a_stored_conversation_yields_the_same_assembly_a_live_call_would_see() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();
      Conversation<String> conversation = agent.converse();
      conversation.tell("hi");

      var context = agent.contextFor(conversation.conversationId());

      assertThat(context.messages())
          .containsExactly(Message.user("hi"), Message.assistant(List.of(new TextBlock("hi"))));
    }
  }
}
