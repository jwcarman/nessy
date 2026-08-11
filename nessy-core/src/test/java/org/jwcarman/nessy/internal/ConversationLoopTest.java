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
package org.jwcarman.nessy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.execute.ModelCallExecutor;
import org.jwcarman.nessy.spi.execute.ToolCallExecutor;
import org.jwcarman.nessy.spi.memory.ListMemory;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The fold→perform cycle, pinned end to end with fakes cheap enough to make every ordering law
 * directly assertable: a shared journal every fake appends to, read back after the run.
 */
class ConversationLoopTest {

  private static final ConversationId ID = new ConversationId("s1");

  private static ToolCall toolCall(String id, String name) {
    return new ToolCall(id, name, JsonNodeFactory.instance.objectNode());
  }

  private static ConversationEvent.ModelResponded plainAnswer(String text) {
    return new ConversationEvent.ModelResponded(
        ID, Message.assistant(List.of(new TextBlock(text))), StopReason.END_TURN, Usage.zero());
  }

  private static ConversationEvent.ModelResponded homework(ToolCall... calls) {
    List<ContentBlock> blocks =
        List.of(calls).stream().map(call -> (ContentBlock) new ToolUseBlock(call)).toList();
    return new ConversationEvent.ModelResponded(
        ID, Message.assistant(blocks), StopReason.TOOL_USE, Usage.zero());
  }

  /** Scripts a queue of facts (or exceptions) for {@code executors.callModel()}, in call order. */
  private static final class ScriptedModelCallExecutor implements ModelCallExecutor {

    private final List<String> journal;
    private final Deque<Supplier<ConversationEvent>> scripts = new ArrayDeque<>();
    private int calls;

    ScriptedModelCallExecutor(List<String> journal, ConversationEvent... facts) {
      this.journal = journal;
      for (ConversationEvent fact : facts) {
        scripts.addLast(() -> fact);
      }
    }

    void thenThrow(RuntimeException exception) {
      scripts.addLast(
          () -> {
            throw exception;
          });
    }

    int calls() {
      return calls;
    }

    @Override
    public Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer) {
      journal.add("model");
      calls++;
      Supplier<ConversationEvent> script = scripts.pollFirst();
      if (script == null) {
        throw new IllegalStateException("no more scripted model responses");
      }
      return Awaited.ready(script.get());
    }
  }

  /** Scripts one fact per tool-call id for {@code executors.toolCall()}. */
  private static final class ScriptedToolCallExecutor implements ToolCallExecutor {

    private final List<String> journal;
    private final Map<String, Supplier<ConversationEvent>> scripts = new HashMap<>();

    ScriptedToolCallExecutor(List<String> journal) {
      this.journal = journal;
    }

    ScriptedToolCallExecutor andFor(String callId, ConversationEvent fact) {
      scripts.put(callId, () -> fact);
      return this;
    }

    @Override
    public Awaited<ConversationEvent> execute(
        ToolCall call, ConversationState state, TurnObserver observer) {
      journal.add("tool:" + call.id());
      Supplier<ConversationEvent> script = scripts.get(call.id());
      if (script == null) {
        throw new IllegalStateException("no script for call " + call.id());
      }
      return Awaited.ready(script.get());
    }

    @Override
    public Awaited<ConversationEvent> resume(
        ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer) {
      throw new UnsupportedOperationException("scripted executor never resumes");
    }
  }

  /** A tool executor that always parks, to prove the loop refuses rather than swallows it. */
  private static final class ParkingToolCallExecutor implements ToolCallExecutor {

    @Override
    public Awaited<ConversationEvent> execute(
        ToolCall call, ConversationState state, TurnObserver observer) {
      return Awaited.parked(ParkToken.generate());
    }

    @Override
    public Awaited<ConversationEvent> resume(
        ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer) {
      throw new UnsupportedOperationException("parking executor never resumes");
    }
  }

  /** Records every message it is told, in birth order, on top of a {@link ListMemory} floor. */
  private static final class RecordingMemory implements Memory {

    private final List<String> journal;
    private final Memory delegate = new ListMemory();
    private final List<Message> remembered = new ArrayList<>();

    RecordingMemory(List<String> journal) {
      this.journal = journal;
    }

    List<Message> remembered() {
      return remembered;
    }

    @Override
    public void remember(ConversationId id, Message message) {
      journal.add("remember:" + message.role().name().toLowerCase(Locale.ROOT));
      remembered.add(message);
      delegate.remember(id, message);
    }

    @Override
    public Context recall(ConversationId id) {
      return delegate.recall(id);
    }
  }

  /** Records every fact emitted, by its simple class name, in emission order. */
  private static final class RecordingEmitter implements EventEmitter {

    private final List<String> journal;

    RecordingEmitter(List<String> journal) {
      this.journal = journal;
    }

    @Override
    public void emit(Object event) {
      journal.add("emit:" + event.getClass().getSimpleName());
    }
  }

  /** Counts how many times it is consulted, delegating the verdict itself. */
  private static final class CountingTerminationPolicy implements TerminationPolicy {

    private final List<String> journal;
    private final TerminationPolicy delegate;
    private int consultations;

    CountingTerminationPolicy(List<String> journal, TerminationPolicy delegate) {
      this.journal = journal;
      this.delegate = delegate;
    }

    int consultations() {
      return consultations;
    }

    @Override
    public Optional<String> shouldHalt(ConversationState state) {
      journal.add("consult");
      consultations++;
      return delegate.shouldHalt(state);
    }
  }

  /** Logs every save, delegating storage itself to the in-memory default. */
  private static final class RecordingStore implements ConversationStore {

    private final List<String> journal;
    private final ConversationStore delegate = ConversationStore.inMemory();

    RecordingStore(List<String> journal) {
      this.journal = journal;
    }

    @Override
    public Optional<ConversationState> load(ConversationId id) {
      return delegate.load(id);
    }

    @Override
    public void save(ConversationState state) {
      journal.add("save");
      delegate.save(state);
    }

    @Override
    public boolean consumeToken(ParkToken token) {
      return delegate.consumeToken(token);
    }
  }

  @Nested
  class Clean_response {

    @Test
    void a_tell_with_a_clean_scripted_response_completes() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      RecordingMemory memory = new RecordingMemory(journal);
      RecordingEmitter emitter = new RecordingEmitter(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              memory,
              TerminationPolicy.never(),
              new RecordingStore(journal),
              emitter,
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), TurnObserver.noop());

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(journal).isNotEmpty();
      assertThat(journal).containsSubsequence("emit:AgentTold", "emit:ModelResponded");
      assertThat(memory.remembered())
          .containsExactly(
              Message.user("what is 2+2?"), Message.assistant(List.of(new TextBlock("Four."))));
    }
  }

  @Nested
  class Homework_round_trip {

    @Test
    void two_calls_execute_in_order_flush_once_then_the_model_is_called_again() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ToolCall c2 = toolCall("c2", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1, c2), plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")))
              .andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.ok("b")));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.never(),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a and b"), TurnObserver.noop());

      assertThat(journal).isNotEmpty();
      assertThat(journal).containsSubsequence("model", "tool:c1", "tool:c2", "model");
      assertThat(memory.remembered()).hasSize(4);
      assertThat(memory.remembered().get(2))
          .isEqualTo(
              Message.toolResults(
                  List.of(
                      new ToolResultBlock("c1", "a", false),
                      new ToolResultBlock("c2", "b", false))));
      assertThat(model.calls()).isEqualTo(2);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }
  }

  @Nested
  class Termination_consultation {

    @Test
    void the_policy_is_consulted_once_per_fold() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      CountingTerminationPolicy termination =
          new CountingTerminationPolicy(journal, TerminationPolicy.never());
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              termination,
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), TurnObserver.noop());

      // Two facts fold in this run: AgentTold, then ModelResponded.
      assertThat(termination.consultations()).isEqualTo(2);
    }
  }

  @Nested
  class Halting_policy {

    @Test
    void a_halt_discards_unperformed_effects_and_fails_the_session() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ToolCall c2 = toolCall("c2", "echo");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1, c2));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.error("boom")))
              .andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.error("boom")));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.maxConsecutiveErrors(1),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a and b"), TurnObserver.noop());

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(outcome.state().failureReason())
          .isEqualTo("hit the error ceiling (1 consecutive tool errors)");
      assertThat(model.calls()).isEqualTo(1);
      assertThat(journal).contains("tool:c1");
      assertThat(journal).doesNotContain("tool:c2");
      assertThat(memory.remembered()).isNotEmpty();
      Message flush = memory.remembered().getLast();
      assertThat(flush.content())
          .containsExactly(
              new ToolResultBlock("c1", "boom", true),
              new ToolResultBlock(
                  "c2", "Abandoned: the conversation failed before this tool ran.", true));
    }
  }

  @Nested
  class Fold_cycle_order {

    @Test
    void remember_precedes_emit_precedes_save_precedes_perform() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1), plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a"), TurnObserver.noop());

      assertThat(journal).isNotEmpty();
      assertThat(journal)
          .containsSubsequence(
              "remember:user",
              "emit:AgentTold",
              "save",
              "model",
              "remember:assistant",
              "emit:ModelResponded",
              "save",
              "tool:c1");
    }
  }

  @Nested
  class Durability {

    @Test
    void the_last_folded_state_is_saved_even_when_a_later_perform_throws() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      model.thenThrow(new IllegalStateException("model blew up mid-turn"));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      RecordingStore store = new RecordingStore(journal);
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.never(),
              store,
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      ConversationEvent.AgentTold echoA = ConversationEvent.AgentTold.of(ID, "echo a");

      assertThatThrownBy(() -> loop.run(ID, echoA, TurnObserver.noop()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("model blew up mid-turn");

      // The last fold to complete before the throw was the flush that answered c1's tool_use,
      // which put the state back into AWAITING_MODEL with no homework left outstanding — the
      // progress-holder contract: the exception loses no folded progress.
      ConversationState saved = store.load(ID).orElseThrow();
      assertThat(saved.status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
      assertThat(saved.pendingCalls()).isEmpty();
      assertThat(saved.pendingResults()).isEmpty();
      assertThat(memory.remembered())
          .isNotEmpty()
          .last()
          .isEqualTo(Message.toolResults(List.of(new ToolResultBlock("c1", "a", false))));
    }
  }

  @Nested
  class Section_6_refusal {

    @Test
    void a_run_in_flight_as_executing_tool_is_refused_naming_the_status() {
      List<String> journal = new ArrayList<>();
      RecordingStore store = new RecordingStore(journal);
      store.save(ConversationState.newConversation(ID).with(ConversationStatus.EXECUTING_TOOL));
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      ConversationEvent.AgentTold whatIs2Plus2 = ConversationEvent.AgentTold.of(ID, "what is 2+2?");

      assertThatThrownBy(() -> loop.run(ID, whatIs2Plus2, TurnObserver.noop()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("EXECUTING_TOOL");
    }

    @Test
    void a_run_against_idle_complete_or_failed_proceeds() {
      for (ConversationStatus resumable :
          List.of(
              ConversationStatus.IDLE, ConversationStatus.COMPLETE, ConversationStatus.FAILED)) {
        List<String> journal = new ArrayList<>();
        RecordingStore store = new RecordingStore(journal);
        store.save(ConversationState.newConversation(ID).with(resumable));
        ScriptedModelCallExecutor model =
            new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
        ConversationLoop loop =
            new ConversationLoop(
                new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                new RecordingMemory(journal),
                TerminationPolicy.never(),
                store,
                new RecordingEmitter(journal),
                ObservationRegistry.NOOP);

        RunOutcome outcome =
            loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), TurnObserver.noop());

        assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      }
    }
  }

  @Nested
  class Parking_refusal {

    @Test
    void a_parking_tool_executor_is_refused_naming_the_tool() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ParkingToolCallExecutor()),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      ConversationEvent.AgentTold echoA = ConversationEvent.AgentTold.of(ID, "echo a");

      assertThatThrownBy(() -> loop.run(ID, echoA, TurnObserver.noop()))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("echo");
    }
  }

  @Nested
  class Resume_refusal {

    @Test
    void resume_always_throws_because_this_assembly_never_parks() {
      List<String> journal = new ArrayList<>();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(
                  new ScriptedModelCallExecutor(journal), new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      ParkToken token = ParkToken.generate();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> loop.resume(ID, token, decided, TurnObserver.noop()))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  class Model_call_failure {

    @Test
    void a_model_call_failed_fact_fails_the_session_and_stops_the_loop() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, new ConversationEvent.ModelCallFailed(ID, "boom"));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              new RecordingStore(journal),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), TurnObserver.noop());

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(outcome.state().failureReason()).isEqualTo("boom");
      assertThat(model.calls()).isEqualTo(1);
    }
  }
}
