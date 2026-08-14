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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Agent}'s own surface: {@code converse()} versus {@code conversation(id)}, {@code
 * contextFor(...)}'s both branches (an unknown id, and the same assembly a live {@code tell} would
 * see), and {@code snapshot(...)}'s both branches (an unknown, never-stored id, and a stored
 * conversation's status, parks, and recall).
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

  @Nested
  class Resuming_a_conversation {

    @Test
    void resume_reopens_the_same_conversation_id_a_prior_converse_produced() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("first", "second")).build().agent().model("m").build();
      ConversationId id = agent.converse().tell("first").state().id();
      TextObserver observer = new TextObserver();

      RunOutcome reply = agent.conversation(id).tell("second", observer);

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

  @Nested
  class Snapshot {

    @Test
    void snapshot_of_an_unknown_conversation_is_idle_and_empty() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().model("m").build();

      ConversationSnapshot snap = agent.snapshot(new ConversationId("never-seen"));

      assertThat(snap.status()).isEqualTo(ConversationStatus.IDLE);
      assertThat(snap.parkedCalls()).isEmpty();
      assertThat(snap.context().messages()).isEmpty();
    }

    @Test
    void snapshot_of_a_stored_conversation_carries_status_parks_and_recall() {
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(call),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
      ParkingApprover approver = new ParkingApprover();
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      RunOutcome parked = agent.converse().tell("search for x");
      ConversationId id = parked.state().id();

      ConversationSnapshot snap = agent.snapshot(id);

      assertThat(snap.status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(snap.parkedCalls()).hasSize(1);
      assertThat(snap.context().messages()).isNotEmpty();
    }

    /**
     * Opus review, Finding 1 (Important): {@code Agent.snapshot}'s filter down to calls {@code
     * state.parkedCalls()} still lists outstanding is untested on its own — delete the filter and
     * every other test still passes, since none of them resolve a park before reading the snapshot.
     * The {@link org.jwcarman.nessy.spi.conversation.Parks} registry remembers a wait forever
     * (design §5), so without the filter a settled call would still render as a pending approval
     * card. This pins that resolving the park makes it disappear from the snapshot even though the
     * registry itself never forgets it.
     */
    @Test
    void a_settled_park_no_longer_appears_in_the_snapshot_though_the_registry_remembers_it() {
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(call),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              .turn(
                  new ModelEvent.TextChunk("done"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      ParkingApprover approver = new ParkingApprover();
      Harness harness = Nessy.harness(provider).build();
      Agent<String> agent =
          harness
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      ConversationId id = agent.converse().tell("search for x").state().id();
      assertThat(agent.snapshot(id).parkedCalls()).hasSize(1);

      harness.resume(approver.token(), new ToolResolution.Decided(Decision.allow()));

      assertThat(agent.snapshot(id).parkedCalls()).isEmpty();
      assertThat(harness.peek(approver.token())).isPresent();
    }

    /**
     * Should-fix 8 (final review): {@code Agent.cards} used to order the snapshot's cards by
     * registry iteration order, not the park order {@code state.parkedCalls()} itself records. Two
     * outstanding calls pin that the cards come back in the order they were parked in, not whatever
     * order {@link org.jwcarman.nessy.spi.conversation.Parks#forConversation} happens to hand back.
     */
    @Test
    void two_parked_calls_cards_come_back_in_the_order_they_were_parked() {
      ToolCall first = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ToolCall second = new ToolCall("c2", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(first),
                  new ModelEvent.ToolUseEmitted(second),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
      ParkingApprover approver = new ParkingApprover();
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      ConversationId id = agent.converse().tell("search twice").state().id();

      ConversationSnapshot snap = agent.snapshot(id);

      assertThat(snap.parkedCalls()).isNotEmpty();
      assertThat(snap.parkedCalls())
          .extracting(card -> card.call().id())
          .containsExactly("c1", "c2");
    }
  }

  /** A model that replays one scripted turn per call, one script entry per {@code stream} call. */
  private static final class ScriptedProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

    ScriptedProvider turn(ModelEvent... events) {
      turns.addLast(List.of(events));
      return this;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
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

  record SearchInput(String query) {}

  /** A tool that always succeeds once invoked — the gate is what parks, not the tool itself. */
  private static final class SearchTool implements Tool<SearchInput> {

    @Override
    public String name() {
      return "search";
    }

    @Override
    public String description() {
      return "Searches for something";
    }

    @Override
    public Class<SearchInput> inputType() {
      return SearchInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(SearchInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("found:" + input.query()));
    }
  }

  /** Parks the first call it is asked, remembering the token it handed out. */
  private static final class ParkingApprover implements Approver {

    private ParkToken token;

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      token = ParkToken.generate();
      return Awaited.parked(token);
    }

    ParkToken token() {
      return token;
    }
  }
}
