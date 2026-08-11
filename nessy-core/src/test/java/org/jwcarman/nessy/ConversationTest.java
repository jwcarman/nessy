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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Conversation}'s own two entry points ({@code tell(I)} and {@code tell(I, TurnObserver)}),
 * its accessors, and the renderer-failure contract they share — {@code HarnessTest} already covers
 * the dynamic {@code events()} subscription story end to end.
 */
class ConversationTest {

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
  void conversationId_reports_the_id_this_conversation_was_opened_or_resumed_with() {
    Agent<String> agent = Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();

    Conversation<String> conversation = agent.converse();

    assertThat(conversation.conversationId()).isEqualTo(conversation.tell("hi").state().id());
  }

  @Nested
  class Tell_with_an_observer {

    @Test
    void the_observer_watches_only_this_calls_turn_synchronously() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();
      Conversation<String> conversation = agent.converse();
      List<TurnEvent> observed = new ArrayList<>();

      conversation.tell("hi", observed::add);

      assertThat(observed)
          .filteredOn(TurnEvent.TextDelta.class::isInstance)
          .extracting(event -> ((TurnEvent.TextDelta) event).text())
          .containsExactly("hi");
    }

    @Test
    void a_null_observer_is_rejected() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();
      Conversation<String> conversation = agent.converse();

      assertThatThrownBy(() -> conversation.tell("hi", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("observer");
    }

    @Test
    void a_throwing_observer_propagates_and_aborts_the_call() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();
      Conversation<String> conversation = agent.converse();

      assertThatThrownBy(
              () ->
                  conversation.tell(
                      "hi",
                      e -> {
                        throw new IllegalStateException("observer blew up");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("observer blew up");
    }
  }

  @Nested
  class A_renderer_that_produces_no_blocks {

    @Test
    void an_empty_block_list_fails_tell_before_the_loop_ever_sees_it() {
      InputRenderer<String> emptyRenderer = input -> List.of();
      Agent<String> agent =
          Nessy.harness(new FakeProvider())
              .build()
              .agent()
              .model("m")
              .renderer(emptyRenderer)
              .build();

      Conversation<String> conversation = agent.converse();

      assertThatThrownBy(() -> conversation.tell("hi"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hi");
    }

    @Test
    void a_null_block_list_fails_tell_with_an_observer_before_the_loop_ever_sees_it() {
      InputRenderer<String> nullRenderer = input -> null;
      Agent<String> agent =
          Nessy.harness(new FakeProvider())
              .build()
              .agent()
              .model("m")
              .renderer(nullRenderer)
              .build();
      Conversation<String> conversation = agent.converse();

      assertThatThrownBy(() -> conversation.tell("hi", e -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hi");
    }
  }
}
