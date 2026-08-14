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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.WrongAgentException;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
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
 * {@code Agent}'s callback doors — {@code resume}, {@code approve}, {@code deny}, {@code progress},
 * {@code peek} — moved here verbatim from the old {@code Harness}-scoped door tests (design of
 * record amendment, 2026-08-14: "the callbacks should not be coming to the harness. They should
 * always go through the agent"). Post-save discipline, quiet-drain replay protection, and the
 * at-least-once exposure these doors document all move with the code; the two {@code
 * IllegalStateException} single-agent guards do not — they are gone, and the multi-agent scenario
 * they used to refuse is now the green path this class pins instead (design §5).
 */
class AgentDoorsTest {

  /** A model that replays one scripted text turn per call and records every request it saw. */
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

  /** Counts invocations, so a redelivered resume can be pinned as not re-executing the tool. */
  private static final class CountingSearchTool implements Tool<SearchInput> {

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

  @Nested
  class Resume {

    @Test
    void resume_of_an_unknown_token_throws() {
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().name("keeper").model("m").build();
      ParkToken token = ParkToken.generate();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> agent.resume(token, decided))
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
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().name("keeper").model("m").build();
      ParkToken unknown = ParkToken.generate();
      ToolResolution.Decided resolution = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> agent.resume(unknown, resolution))
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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      assertThat(agent.peek(token))
          .isPresent()
          .get()
          .satisfies(
              parked -> {
                assertThat(parked.token()).isEqualTo(token);
                assertThat(parked.call().id()).isEqualTo(call.id());
              });
      assertThat(agent.peek(token)).isPresent();
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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      RunOutcome outcome = agent.approve(token);

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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      RunOutcome outcome = agent.deny(token, "not today");

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
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi")).build().agent().name("keeper").model("m").build();
      ParkToken token = ParkToken.generate();

      assertThatThrownBy(() -> agent.deny(token, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
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
      parks.park(new Parks.Park(id, token, call, "keeper"));
      CountingSearchTool tool = new CountingSearchTool();
      Harness harness = Nessy.harness(new FakeProvider("hi")).store(store).parks(parks).build();
      Agent<String> agent =
          harness
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(tool, UsagePolicy.requireApproval()))
              .approver(Approver.denyAll("never reached"))
              .build();

      RunOutcome outcome = agent.resume(token, new ToolResolution.Decided(Decision.allow()));

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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      RunOutcome parked = agent.converse().tell("search for x");

      assertThat(parked).isInstanceOf(RunOutcome.Parked.class);
      ParkToken token = approver.token();
      assertThat(parked.state().parkedCalls()).extracting(ToolCall::id).containsExactly(call.id());

      RunOutcome resumed = agent.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(resumed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * F16-adjacent (rides with F1's fix): the facade-level pin of the loop-level redelivery
     * contract already pinned at {@code ConversationLoopTest}'s {@code
     * a_second_resume_with_the_same_token_is_a_read_not_a_replay} — here through the real {@code
     * Agent.resume} entry point end to end. A redelivered resume (the same token presented twice —
     * every real transport is at-least-once) must not re-invoke the tool a second time; it reads
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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(tool, UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      RunOutcome first = agent.resume(token, new ToolResolution.Decided(Decision.allow()));
      RunOutcome second = agent.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(first.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(second.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(tool.invocations()).isEqualTo(1);
    }
  }

  /**
   * {@code Agent.progress} (design §5): the remote signal channel — a peek at {@code Parks.find},
   * never a consume, so the token stays fully resumable afterward.
   */
  @Nested
  class Progress {

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
      Agent<String> agent =
          Nessy.harness(provider)
              .listen(ToolProgress.class, heard::add)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();

      RunOutcome parked = agent.converse().tell("search for x");
      assertThat(parked).isInstanceOf(RunOutcome.Parked.class);
      ParkToken token = approver.token();

      boolean emitted = agent.progress(token, "halfway");

      assertThat(emitted).isTrue();
      assertThat(heard).hasSize(1);
      assertThat(heard.getFirst().toolCallId()).isEqualTo(call.id());
      assertThat(heard.getFirst().message()).isEqualTo("halfway");

      RunOutcome resumed = agent.resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(resumed.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void progress_for_an_unknown_token_reports_false_and_emits_nothing() {
      List<ToolProgress> heard = new ArrayList<>();
      Agent<String> agent =
          Nessy.harness(new FakeProvider("hi"))
              .listen(ToolProgress.class, heard::add)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .build();
      ParkToken token = ParkToken.generate();

      boolean emitted = agent.progress(token, "halfway");

      assertThat(emitted).isFalse();
      assertThat(heard).isEmpty();
    }

    /**
     * Task-5 review, Finding 1 (Important): the tee narrates {@code ToolProgress} on the built
     * agent's own extended registry — this harness's {@link
     * org.jwcarman.nessy.api.event.ListenerRegistry} plus that agent's own declared registrations —
     * so {@link Agent#progress} must emit there too, not on the bare harness registry, or an
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
      Agent<String> agent =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .listen(ToolProgress.class, heard::add)
              .build();

      agent.converse().tell("search for x");
      ParkToken token = approver.token();

      boolean emitted = agent.progress(token, "halfway");

      assertThat(emitted).isTrue();
      assertThat(heard).hasSize(1);
      assertThat(heard.getFirst().toolCallId()).isEqualTo(call.id());
      assertThat(heard.getFirst().message()).isEqualTo("halfway");
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
      Agent<String> agent =
          Nessy.harness(provider)
              .listen(ToolProgress.class, heard::add)
              .build()
              .agent()
              .name("keeper")
              .model("fake-model")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agent.converse().tell("search for x");
      ParkToken token = approver.token();
      agent.resume(token, new ToolResolution.Decided(Decision.allow()));

      boolean emitted = agent.progress(token, "too late");

      assertThat(emitted).isFalse();
      assertThat(heard).isEmpty();
    }
  }

  /**
   * The scenario the old single-agent harness could not even express (design §5): two named agents
   * built off one {@link Harness}, each parking its own call and resuming its own token, each drive
   * running under its own grants and narrating on its own {@link TurnObserver}.
   */
  @Nested
  class Two_agent_isolation {

    @Test
    void two_agents_each_resume_their_own_token_under_their_own_grants_and_observer() {
      ToolCall callA = new ToolCall("a1", "search", JsonNodeFactory.instance.objectNode());
      ToolCall callB = new ToolCall("b1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(callA),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              .turn(
                  new ModelEvent.ToolUseEmitted(callB),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              .turn(
                  new ModelEvent.TextChunk("done-a"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()))
              .turn(
                  new ModelEvent.TextChunk("done-b"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      ParkingApprover approverA = new ParkingApprover();
      ParkingApprover approverB = new ParkingApprover();
      Harness harness = Nessy.harness(provider).build();
      Agent<String> agentA =
          harness
              .agent()
              .name("agent-a")
              .model("model-a")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approverA)
              .build();
      Agent<String> agentB =
          harness
              .agent()
              .name("agent-b")
              .model("model-b")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approverB)
              .build();
      agentA.converse().tell("search for a");
      agentB.converse().tell("search for b");
      ParkToken tokenA = approverA.token();
      ParkToken tokenB = approverB.token();
      TextObserver observerA = new TextObserver();
      TextObserver observerB = new TextObserver();

      RunOutcome resumedA =
          agentA.resume(tokenA, new ToolResolution.Decided(Decision.allow()), observerA);
      RunOutcome resumedB =
          agentB.resume(tokenB, new ToolResolution.Decided(Decision.allow()), observerB);

      assertThat(resumedA.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(resumedB.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(observerA.text()).isEqualTo("done-a");
      assertThat(observerB.text()).isEqualTo("done-b");
      // The FIFO script alone can't tell A's drive from B's — both narrate through their own
      // TextObserver regardless of which loop actually ran. The request each drive actually sent
      // the provider can: it carries the resuming agent's own model, proving A's resume drove
      // A's loop (and grants) and B's resume drove B's, not the other way around.
      List<ModelRequest> requests = provider.requests();
      assertThat(requests).hasSize(4);
      assertThat(requests.get(2).model()).isEqualTo("model-a");
      assertThat(requests.get(3).model()).isEqualTo("model-b");
    }
  }

  /**
   * Design §5's fail-safe: a token minted by one agent is refused, loud and whole, by every other
   * agent's doors — before anything is appended or driven, so the owning agent's conversation is
   * left exactly as it was.
   */
  @Nested
  class Cross_agent_refusal {

    private Harness harness;
    private ConversationStore store;
    private Agent<String> agentA;
    private Agent<String> agentB;
    private ConversationId conversationA;
    private ParkToken tokenA;
    private ConversationStore.Loaded before;

    private void park_a_call_under_agent_a() {
      ToolCall call = new ToolCall("a1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(call),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));
      ParkingApprover approver = new ParkingApprover();
      store = ConversationStore.inMemory();
      harness = Nessy.harness(provider).store(store).build();
      agentA =
          harness
              .agent()
              .name("agent-a")
              .model("model-a")
              .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
      agentB = harness.agent().name("agent-b").model("model-b").build();
      conversationA = agentA.converse().tell("search for a").state().id();
      tokenA = approver.token();
      before = store.load(conversationA).orElseThrow();
    }

    @Test
    void resume_from_the_wrong_agent_is_refused_naming_both_agents_and_leaves_a_untouched() {
      park_a_call_under_agent_a();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> agentB.resume(tokenA, decided))
          .isInstanceOf(WrongAgentException.class)
          .hasMessage("park was minted by agent 'agent-a'; this agent is 'agent-b'");
      assertThat(store.load(conversationA)).contains(before);
    }

    @Test
    void progress_from_the_wrong_agent_is_refused_naming_both_agents_and_leaves_a_untouched() {
      park_a_call_under_agent_a();

      assertThatThrownBy(() -> agentB.progress(tokenA, "halfway"))
          .isInstanceOf(WrongAgentException.class)
          .hasMessageContaining("agent-a")
          .hasMessageContaining("agent-b");
      assertThat(store.load(conversationA)).contains(before);
    }

    @Test
    void peek_from_the_wrong_agent_is_refused_naming_both_agents_and_leaves_a_untouched() {
      park_a_call_under_agent_a();

      assertThatThrownBy(() -> agentB.peek(tokenA))
          .isInstanceOf(WrongAgentException.class)
          .hasMessageContaining("agent-a")
          .hasMessageContaining("agent-b");
      assertThat(store.load(conversationA)).contains(before);
    }
  }
}
