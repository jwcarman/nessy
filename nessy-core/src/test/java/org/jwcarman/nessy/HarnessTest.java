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
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.Subscription;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
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

  /**
   * {@code Harness.resume} (design §5): the facade over {@code Parks.find} + {@code append} +
   * {@code drive} — pinned end to end through a real {@link Agent}, wired the way an application
   * actually would.
   */
  @Nested
  class Resume {

    /** A provider that replays one scripted turn (a list of {@link ModelEvent}) per call. */
    private static final class ScriptedProvider implements ModelProvider {

      private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
      private final List<ModelRequest> requests = new ArrayList<>();

      ScriptedProvider turn(ModelEvent... events) {
        turns.addLast(List.of(events));
        return this;
      }

      List<ModelRequest> requests() {
        return List.copyOf(requests);
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        requests.add(request);
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
    static final class SearchTool implements Tool<SearchInput> {

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
    static final class ParkingApprover implements Approver {

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

    @Test
    void resume_of_an_unknown_token_throws() {
      Harness harness = Nessy.harness(new FakeProvider("hi")).build();
      ParkToken token = ParkToken.generate();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> harness.resume(token, decided))
          .isInstanceOf(UnknownParkTokenException.class)
          .hasMessageContaining("unknown");
    }

    /**
     * Task-4: {@code UnknownParkTokenException} is a named rejection over the raw {@code
     * IllegalArgumentException} an unknown or already-settled token used to throw, and its message
     * names the offending token.
     */
    @Test
    void an_unknown_token_is_a_typed_rejection() {
      Harness harness = Nessy.harness(new FakeProvider("hi")).build();
      ParkToken unknown = ParkToken.generate();
      ToolResolution.Decided resolution = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> harness.resume(unknown, resolution))
          .isInstanceOf(UnknownParkTokenException.class)
          .hasMessageContaining(unknown.value());
    }

    /**
     * Task-4: {@code peek} reads the same {@code Parks.find} entry {@code progress} narrates
     * against, without consuming it — a second peek still finds the park exactly where the first
     * left it.
     */
    @Test
    void peek_reads_a_park_without_consuming_it() {
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(call),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
      ParkingApprover approver = new ParkingApprover();
      Harness harness = Nessy.harness(provider).build();
      harness
          .agent()
          .model("fake-model")
          .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
          .approver(approver)
          .build()
          .converse()
          .tell("search for x");
      ParkToken token = approver.token();

      assertThat(harness.peek(token))
          .isPresent()
          .get()
          .satisfies(
              parked -> {
                assertThat(parked.token()).isEqualTo(token);
                assertThat(parked.call().id()).isEqualTo(call.id());
              });
      assertThat(harness.peek(token)).isPresent();
    }

    /** Task-4: {@code approve} is sugar over {@code resume} with an unconditional allow verdict. */
    @Test
    void approve_is_resume_with_an_allow_verdict() {
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
      harness
          .agent()
          .model("fake-model")
          .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
          .approver(approver)
          .build()
          .converse()
          .tell("search for x");
      ParkToken token = approver.token();

      RunOutcome outcome = harness.approve(token);

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      List<ToolResultBlock> results =
          provider.requests().getLast().context().messages().stream()
              .flatMap(message -> message.content().stream())
              .filter(ToolResultBlock.class::isInstance)
              .map(ToolResultBlock.class::cast)
              .toList();
      assertThat(results).isNotEmpty();
      assertThat(results.getFirst().isError()).isFalse();
      assertThat(results.getFirst().content()).startsWith("found:");
    }

    /**
     * Task-4: {@code deny} is sugar over {@code resume} with a {@code Decision.Deny} verdict, and
     * the reason it carries lands in the tool result the same way {@code
     * GatedToolCallExecutorTest}'s {@code decided_deny_yields_a_denial} pins for the lower layer.
     */
    @Test
    void deny_carries_its_reason_into_the_tool_result() {
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
      harness
          .agent()
          .model("fake-model")
          .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
          .approver(approver)
          .build()
          .converse()
          .tell("search for x");
      ParkToken token = approver.token();

      RunOutcome outcome = harness.deny(token, "not today");

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      List<ToolResultBlock> denials =
          provider.requests().getLast().context().messages().stream()
              .flatMap(message -> message.content().stream())
              .filter(ToolResultBlock.class::isInstance)
              .map(ToolResultBlock.class::cast)
              .filter(ToolResultBlock::isError)
              .toList();
      assertThat(denials).isNotEmpty();
      assertThat(denials.getFirst().content()).isEqualTo("Denied: not today");
    }

    /** F6: {@code deny} validates {@code reason} up front, like every sibling parameter. */
    @Test
    void deny_rejects_a_null_reason() {
      Harness harness = Nessy.harness(new FakeProvider("hi")).build();
      ParkToken token = ParkToken.generate();

      assertThatThrownBy(() -> harness.deny(token, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
    }

    /**
     * Opus final review, Finding F10: a durable store can carry parks left behind by a prior
     * process — this harness never built an agent of its own, so {@code loop} is still {@code
     * null}, but the token names a park the store genuinely still has. Before the fix, driving that
     * park NPE'd bare, past the token lookup, once {@code resume} finally touched the (unset) loop.
     * It must instead refuse loud, the same {@link IllegalStateException} style as the multi-agent
     * guard, once it knows the token is real but has nothing built to drive it with.
     */
    @Test
    void resume_of_a_known_token_with_no_agent_built_refuses_loud_not_npe() {
      ConversationStore store = ConversationStore.inMemory();
      ConversationId id = ConversationId.generate();
      ParkToken token = ParkToken.generate();
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ConversationState seeded =
          ConversationState.newConversation(id)
              .withParkedCalls(List.of(call))
              .with(ConversationStatus.PARKED);
      store.save(seeded, List.of());
      Parks parks = Parks.inMemory();
      parks.park(new Parks.Park(id, token, call));
      Harness harness = Nessy.harness(new FakeProvider("hi")).store(store).parks(parks).build();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> harness.resume(token, decided))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no agent");
    }

    /**
     * Opus review, Finding 4 (Minor, closes Task-4's own flagged orphan-tolerance gap): design §5
     * tolerates a registry entry whose save lost the fence (or never landed) — the wait is still
     * findable by token, but the reloaded state never lists its call as outstanding. Seeded
     * directly here rather than induced through a sabotaged save (the shape the original report
     * judged not cleanly reproducible with in-process test doubles): a standalone {@code Parks}
     * carries a {@code Park} for a call the saved state simply never parked. Resuming that token
     * must not throw and must not invoke the tool — the resolution translates fine, appends mail
     * addressed to a call that isn't outstanding, and the routing loop drains it as stale, the same
     * mechanism {@code ConversationLoopTest}'s {@code
     * a_resolution_for_a_settled_call_drains_quietly} pins at the loop level.
     */
    @Test
    void resume_of_an_orphaned_park_neither_throws_nor_invokes_the_tool() {
      ConversationId id = ConversationId.generate();
      ParkToken token = ParkToken.generate();
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ConversationState seeded =
          ConversationState.newConversation(id).with(ConversationStatus.IDLE);
      ConversationStore store = ConversationStore.inMemory();
      store.save(seeded, List.of());
      Parks parks = Parks.inMemory();
      parks.park(new Parks.Park(id, token, call));
      CountingSearchTool tool = new CountingSearchTool();
      Harness harness = Nessy.harness(new FakeProvider("hi")).store(store).parks(parks).build();
      harness
          .agent()
          .model("fake-model")
          .tools(ToolGrant.grant(tool, UsagePolicy.requireApproval()))
          .approver(Approver.denyAll("never reached"))
          .build();

      RunOutcome outcome = harness.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(outcome.state().id()).isEqualTo(id);
      assertThat(outcome.state().status()).isNotEqualTo(ConversationStatus.PARKED);
      assertThat(tool.invocations()).isZero();
    }

    @Test
    void resume_answers_a_parked_call_and_finishes_the_turn() {
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

      RunOutcome parked = agent.converse().tell("search for x");

      assertThat(parked).isInstanceOf(RunOutcome.Parked.class);
      ParkToken token = approver.token();
      assertThat(parked.state().parkedCalls()).extracting(ToolCall::id).containsExactly(call.id());

      RunOutcome resumed = harness.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(resumed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * Opus fix round 1, Finding 4 (Important): a park token carries no agent identity, so a harness
     * that has built more than one agent can no longer tell which agent's loop — which tools,
     * grants, policy — a token belongs to. Rather than silently routing through whichever agent
     * happened to build last, {@code resume} refuses outright. (The proper fix — parks carrying
     * agent identity — is a design escalation outside this generation's scope.)
     */
    @Test
    void resume_on_a_multi_agent_harness_refuses_naming_the_agent_count() {
      Harness harness = Nessy.harness(new FakeProvider("hi", "there")).build();
      harness.agent().model("model-a").build();
      harness.agent().model("model-b").build();
      ParkToken token = ParkToken.generate();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> harness.resume(token, decided))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("single-agent")
          .hasMessageContaining("2");
    }

    /** Counts invocations, so a redelivered resume can be pinned as not re-executing the tool. */
    static final class CountingSearchTool implements Tool<SearchInput> {

      private int invocations;

      int invocations() {
        return invocations;
      }

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
        invocations++;
        return Awaited.ready(ToolResult.ok("found:" + input.query()));
      }
    }

    /**
     * F16-adjacent (rides with F1's fix): the facade-level pin of the loop-level redelivery
     * contract already pinned at {@code ConversationLoopTest}'s {@code
     * a_second_resume_with_the_same_token_is_a_read_not_a_replay} — here through the real {@code
     * Harness.resume} entry point end to end. A redelivered resume (the same token presented twice
     * — every real transport is at-least-once) must not re-invoke the tool a second time; it reads
     * whatever the first delivery already produced. Task-4: the registry entry survives resolution
     * on its own (design §5 — {@code Parks} never deletes), so the default in-memory {@code Parks}
     * is enough; no bespoke sticky store is needed to keep the token findable for the second call.
     */
    @Test
    void resume_with_an_already_consumed_token_drives_current_truth_without_reinvoking_the_tool() {
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
      CountingSearchTool tool = new CountingSearchTool();
      Harness harness = Nessy.harness(provider).build();
      Agent<String> agent =
          harness
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(tool, UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      RunOutcome first = harness.resume(token, new ToolResolution.Decided(Decision.allow()));
      RunOutcome second = harness.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(first.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(second.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(tool.invocations()).isEqualTo(1);
    }
  }

  /**
   * {@code Harness.progress} (design §5): the remote signal channel — a peek at {@code Parks.find},
   * never a consume, so the token stays fully resumable afterward.
   */
  @Nested
  class Progress {

    /** A provider that replays one scripted turn (a list of {@link ModelEvent}) per call. */
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
    static final class SearchTool implements Tool<SearchInput> {

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
    static final class ParkingApprover implements Approver {

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

    @Test
    void progress_peeks_the_park_and_emits_tool_progress() {
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
      List<ToolProgress> heard = new ArrayList<>();
      Harness harness = Nessy.harness(provider).listen(ToolProgress.class, heard::add).build();
      Agent<String> agent =
          harness
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      RunOutcome parked = agent.converse().tell("search for x");
      assertThat(parked).isInstanceOf(RunOutcome.Parked.class);
      ParkToken token = approver.token();

      boolean emitted = harness.progress(token, "halfway");

      assertThat(emitted).isTrue();
      assertThat(heard).hasSize(1);
      assertThat(heard.getFirst().toolCallId()).isEqualTo(call.id());
      assertThat(heard.getFirst().message()).isEqualTo("halfway");

      RunOutcome resumed = harness.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(resumed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void progress_for_an_unknown_token_reports_false_and_emits_nothing() {
      List<ToolProgress> heard = new ArrayList<>();
      Harness harness =
          Nessy.harness(new FakeProvider("hi")).listen(ToolProgress.class, heard::add).build();
      ParkToken token = ParkToken.generate();

      boolean emitted = harness.progress(token, "halfway");

      assertThat(emitted).isFalse();
      assertThat(heard).isEmpty();
    }

    /**
     * Task-5 review, Finding 1 (Important): the tee narrates {@code ToolProgress} on the built
     * agent's own extended registry — this harness's {@link
     * org.jwcarman.nessy.api.event.ListenerRegistry} plus that agent's own declared registrations —
     * so {@link #progress} must emit there too, not on the bare harness registry, or an
     * agent-declared listener never hears a remote signal the in-process tee would have delivered.
     */
    @Test
    void progress_reaches_an_agent_declared_listener_the_same_as_the_in_process_tee() {
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
      List<ToolProgress> heard = new ArrayList<>();
      Harness harness = Nessy.harness(provider).build();
      Agent<String> agent =
          harness
              .agent()
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .listen(ToolProgress.class, heard::add)
              .build();

      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      boolean emitted = harness.progress(token, "halfway");

      assertThat(emitted).isTrue();
      assertThat(heard).hasSize(1);
      assertThat(heard.getFirst().toolCallId()).isEqualTo(call.id());
      assertThat(heard.getFirst().message()).isEqualTo("halfway");
    }

    /**
     * Task-5 review, Finding 4 (mirrors {@code resume}'s existing multi-agent refusal): a park
     * token carries no agent identity, so a harness that has built more than one agent can no
     * longer tell which agent's registry a token's progress belongs to.
     */
    @Test
    void progress_on_a_multi_agent_harness_refuses_naming_the_agent_count() {
      Harness harness = Nessy.harness(new FakeProvider("hi", "there")).build();
      harness.agent().model("model-a").build();
      harness.agent().model("model-b").build();
      ParkToken token = ParkToken.generate();

      assertThatThrownBy(() -> harness.progress(token, "halfway"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("single-agent")
          .hasMessageContaining("2");
    }

    /**
     * Opus final review, Finding F10 (mirrors {@code resume}'s own zero-agent guard): a durable
     * store can carry parks left behind by a prior process — this harness never built an agent of
     * its own, so {@code agentRegistry} is still {@code null}. Before the fix, a live token found
     * both peeks and then NPE'd bare on {@code agentRegistry.emit}. It must instead refuse loud.
     */
    @Test
    void progress_of_a_known_token_with_no_agent_built_refuses_loud_not_npe() {
      ConversationStore store = ConversationStore.inMemory();
      ConversationId id = ConversationId.generate();
      ParkToken token = ParkToken.generate();
      ToolCall call = new ToolCall("c1", "search", JsonNodeFactory.instance.objectNode());
      ConversationState seeded =
          ConversationState.newConversation(id)
              .withParkedCalls(List.of(call))
              .with(ConversationStatus.PARKED);
      store.save(seeded, List.of());
      Parks parks = Parks.inMemory();
      parks.park(new Parks.Park(id, token, call));
      Harness harness = Nessy.harness(new FakeProvider("hi")).store(store).parks(parks).build();

      assertThatThrownBy(() -> harness.progress(token, "halfway"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no agent");
    }

    /**
     * Spec §5: {@code progress} checks not just that the registry still recognizes the token, but
     * that the conversation's own state still lists the call as outstanding. Registry entries
     * survive resolution forever (design §5 — nothing ever deletes a {@code Parks} entry), so
     * without this second check a stale progress signal arriving after the call already settled
     * would emit narration nobody is still waiting to hear — exactly today's contract, re-targeted
     * at the new seam.
     */
    @Test
    void progress_on_a_settled_wait_returns_false() {
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
      List<ToolProgress> heard = new ArrayList<>();
      Harness harness = Nessy.harness(provider).listen(ToolProgress.class, heard::add).build();
      harness
          .agent()
          .model("fake-model")
          .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
          .approver(approver)
          .build()
          .converse()
          .tell("search for x");
      ParkToken token = approver.token();
      harness.resume(token, new ToolResolution.Decided(Decision.allow()));

      boolean emitted = harness.progress(token, "too late");

      assertThat(emitted).isFalse();
      assertThat(heard).isEmpty();
    }
  }
}
