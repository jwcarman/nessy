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
import java.util.Collection;
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
import org.jwcarman.nessy.api.conversation.InboxEntry;
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
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.conversation.StaleStateException;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.execute.ModelCallExecutor;
import org.jwcarman.nessy.spi.execute.ToolCallExecutor;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;

/**
 * The fold→perform cycle, pinned end to end with fakes cheap enough to make every ordering law
 * directly assertable: a shared journal every fake appends to, read back after the run.
 */
class ConversationLoopTest {

  private static final ConversationId ID = new ConversationId("s1");
  private static final TurnObserver OBSERVER = TurnObserver.noop();

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

  /**
   * A tool executor whose calls park under a scripted token, and whose {@code resume} settles them
   * with a scripted fact — counting how many times {@code resume} actually ran, so a test can pin
   * at-least-once-delivery-but-exactly-once-effect for a token reused by a redelivered resolution.
   */
  private static final class ParkingToolCallExecutor implements ToolCallExecutor {

    private final List<String> journal;
    private final Map<String, ParkToken> tokens = new HashMap<>();
    private final Map<String, ConversationEvent> settleResults = new HashMap<>();
    private final Map<String, ConversationEvent> resumeResults = new HashMap<>();
    private final Map<String, ParkToken> reparkOnResume = new HashMap<>();
    private int resumeCalls;

    ParkingToolCallExecutor(List<String> journal) {
      this.journal = journal;
    }

    ParkToken parksWhen(String callId) {
      ParkToken token = ParkToken.generate();
      tokens.put(callId, token);
      return token;
    }

    /** A call this executor settles immediately, instead of parking — a non-parking sibling. */
    ParkingToolCallExecutor andFor(String callId, ConversationEvent fact) {
      settleResults.put(callId, fact);
      return this;
    }

    ParkingToolCallExecutor resumesTo(String callId, ConversationEvent fact) {
      resumeResults.put(callId, fact);
      return this;
    }

    /**
     * A legitimate executor outcome the loop's own re-park guard refuses this generation:
     * approval-resume invokes the tool, and the tool itself parks again.
     */
    ParkingToolCallExecutor reparksOnResume(String callId) {
      reparkOnResume.put(callId, ParkToken.generate());
      return this;
    }

    int resumeCalls() {
      return resumeCalls;
    }

    @Override
    public Awaited<ConversationEvent> execute(
        ToolCall call, ConversationState state, TurnObserver observer) {
      ConversationEvent settled = settleResults.get(call.id());
      if (settled != null) {
        journal.add("settle:" + call.id());
        return Awaited.ready(settled);
      }
      journal.add("park:" + call.id());
      ParkToken token = tokens.get(call.id());
      if (token == null) {
        throw new IllegalStateException("no scripted park for call " + call.id());
      }
      return Awaited.parked(token);
    }

    @Override
    public Awaited<ConversationEvent> resume(
        ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer) {
      journal.add("resume:" + call.id());
      resumeCalls++;
      ParkToken reparkToken = reparkOnResume.get(call.id());
      if (reparkToken != null) {
        return Awaited.parked(reparkToken);
      }
      ConversationEvent fact = resumeResults.get(call.id());
      if (fact == null) {
        throw new IllegalStateException("no scripted resume result for call " + call.id());
      }
      return Awaited.ready(fact);
    }
  }

  /**
   * Records the inbox's contents immediately after every {@code save}, so a test can pin that a
   * particular entry's id rode a particular fold's own save — not a later one — without having to
   * interrupt the drive mid-flight to look.
   */
  private static final class InboxSnapshottingStore implements ConversationStore {

    private final ConversationStore delegate = ConversationStore.inMemory();
    private final ConversationId id;
    private final List<List<InboxEntry>> inboxAfterEachSave = new ArrayList<>();

    InboxSnapshottingStore(ConversationId id) {
      this.id = id;
    }

    List<List<InboxEntry>> inboxAfterEachSave() {
      return inboxAfterEachSave;
    }

    @Override
    public Optional<Loaded> load(ConversationId conversationId) {
      return delegate.load(conversationId);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      ConversationState saved = delegate.save(state, drainedInboxIds);
      inboxAfterEachSave.add(delegate.load(id).map(Loaded::inbox).orElse(List.of()));
      return saved;
    }

    @Override
    public void append(ConversationId conversationId, InboxEntry entry) {
      delegate.append(conversationId, entry);
    }
  }

  /**
   * Sabotages exactly its first {@code save}: before that attempt lands, it re-saves whatever the
   * delegate already holds (unchanged content, bumped version) — a genuine concurrent write, not a
   * scripted throw — so the attempt's own (now stale) base fails for real, and so does any later
   * attempt still holding that same unreloaded base (the drive's own finally-holder among them).
   * Only a fresh {@link #load} — which the retrying {@code drive()} performs — reads the winning
   * version and lets the next attempt actually land.
   */
  private static final class StaleOnceStore implements ConversationStore {

    private final List<String> journal;
    private final ConversationStore delegate = ConversationStore.inMemory();
    private boolean sabotaged;

    StaleOnceStore(List<String> journal) {
      this.journal = journal;
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      journal.add("load");
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      if (!sabotaged) {
        sabotaged = true;
        ConversationState stolen = delegate.load(state.id()).orElseThrow().state();
        delegate.save(stolen, List.of());
      }
      return delegate.save(state, drainedInboxIds);
    }

    @Override
    public void append(ConversationId id, InboxEntry entry) {
      delegate.append(id, entry);
    }
  }

  /**
   * Sabotages exactly the save that would persist the {@code parked} closure transition — the one
   * whose {@code state.status()} is {@link ConversationStatus#PARKED} — the same steal-the-version
   * trick {@link StaleOnceStore} uses, but keyed on the state being saved rather than call order,
   * so every earlier save in the same drive (the note's own fold, the model-call fold that opens
   * the homework) lands normally. Sabotages only the first parked-state save it sees; a retry's own
   * parked-state save lands clean. Built to catch what {@link StaleOnceStore} and {@link
   * AlwaysStaleStore} both miss: neither ever fails specifically the save that would commit a park,
   * so neither can tell an emit-before-save mutant of {@code applyParked} from the real
   * emit-after-save order.
   */
  private static final class SabotagesTheParkedSaveOnceStore implements ConversationStore {

    private final List<String> journal;
    private final ConversationStore delegate = ConversationStore.inMemory();
    private boolean sabotagedParkedSave;

    SabotagesTheParkedSaveOnceStore(List<String> journal) {
      this.journal = journal;
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      journal.add("load");
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      if (state.status() == ConversationStatus.PARKED && !sabotagedParkedSave) {
        sabotagedParkedSave = true;
        journal.add("sabotage:parked-save");
        ConversationState stolen = delegate.load(state.id()).orElseThrow().state();
        delegate.save(stolen, List.of());
      }
      return delegate.save(state, drainedInboxIds);
    }

    @Override
    public void append(ConversationId id, InboxEntry entry) {
      delegate.append(id, entry);
    }
  }

  /**
   * Every {@code save} fails, forever — the permanently-outrun driver {@code drive()} must give up
   * on.
   */
  private static final class AlwaysStaleStore implements ConversationStore {

    private final ConversationStore delegate = ConversationStore.inMemory();
    private final List<String> journal;

    AlwaysStaleStore(List<String> journal) {
      this.journal = journal;
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      journal.add("load");
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      journal.add("save");
      throw new StaleStateException(state.id(), state.version(), state.version() + 1);
    }

    @Override
    public void append(ConversationId id, InboxEntry entry) {
      delegate.append(id, entry);
    }
  }

  /**
   * Records every message it is told, in birth order, on top of a {@link TranscriptMemory} floor.
   */
  private static final class RecordingMemory implements Memory {

    private final List<String> journal;
    private final Memory delegate = new TranscriptMemory(Transcript.inMemory());
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

  /**
   * A {@link Memory} that remembers normally until its {@code throwOnCall}-th telling, which it
   * throws instead of recording — placed to land between the loop's {@code progress.set(state)} and
   * that fold's own {@code store.save(state)}, so only {@code run}'s {@code finally} can still
   * persist the state that was folded on the way to the throw.
   */
  private static final class ThrowOnNthRememberMemory implements Memory {

    private final Memory delegate = new TranscriptMemory(Transcript.inMemory());
    private final List<Message> remembered = new ArrayList<>();
    private final int throwOnCall;
    private final RuntimeException exception;
    private int calls;

    ThrowOnNthRememberMemory(int throwOnCall, RuntimeException exception) {
      this.throwOnCall = throwOnCall;
      this.exception = exception;
    }

    List<Message> remembered() {
      return remembered;
    }

    @Override
    public void remember(ConversationId id, Message message) {
      calls++;
      if (calls == throwOnCall) {
        throw exception;
      }
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
    public Optional<Loaded> load(ConversationId id) {
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      journal.add("save");
      return delegate.save(state, drainedInboxIds);
    }

    @Override
    public void append(ConversationId id, InboxEntry entry) {
      journal.add("append");
      delegate.append(id, entry);
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
              Parks.inMemory(),
              emitter,
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(journal).isNotEmpty().containsSubsequence("emit:AgentTold", "emit:ModelResponded");
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
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a and b"), OBSERVER);

      // "save" between tool:c1 and tool:c2 proves c1's ToolFinished fact folded (and was
      // persisted) before c2 was performed — fold-between-performances, not a batch drain of
      // the whole effect queue followed by folding both results at once.
      assertThat(journal)
          .isNotEmpty()
          .containsSubsequence("model", "tool:c1", "save", "tool:c2", "model");
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
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

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
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a and b"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(outcome.state().failureReason())
          .isEqualTo("hit the error ceiling (1 consecutive tool errors)");
      assertThat(model.calls()).isEqualTo(1);
      assertThat(journal).contains("tool:c1").doesNotContain("tool:c2");
      assertThat(memory.remembered()).isNotEmpty();
      Message flush = memory.remembered().getLast();
      assertThat(flush.content())
          .containsExactly(
              new ToolResultBlock("c1", "boom", true),
              new ToolResultBlock(
                  "c2", "Abandoned: the conversation failed before this tool ran.", true));
    }

    /**
     * A halt that lands on the very fold that opened the homework — before any effect for it is
     * ever performed — has two message births to tell Memory: the folding step's own birth ({@code
     * modelResponded}'s assistant message) and the closure's birth ({@code halted}'s
     * abandoned-results flush). The loop must tell both, in that order; it must not drop the step's
     * own birth in favor of only the closure's.
     */
    @Test
    void the_folds_own_birth_is_remembered_before_the_closures_abandoned_flush() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              memory,
              TerminationPolicy.maxModelCalls(1),
              new RecordingStore(journal),
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      // The homework's own effect is never performed: the halt fires on the fold that opened it.
      assertThat(journal).doesNotContain("tool:c1");
      assertThat(memory.remembered())
          .containsExactly(
              Message.user("echo a"),
              Message.assistant(List.of(new ToolUseBlock(c1))),
              Message.toolResults(
                  List.of(
                      new ToolResultBlock(
                          "c1",
                          "Abandoned: the conversation failed before this tool ran.",
                          true))));
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
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a"), OBSERVER);

      // The unified drive folds the AgentTold note first (step 1: no remember, noted() is
      // effect-free) and only then opens the turn (step 3), so the note's own emit now precedes
      // its merged message's remember — the reverse of the retired Task-3 scaffold, which merged
      // openTurn into the same cycle as the note's own fold.
      assertThat(journal)
          .isNotEmpty()
          .containsSubsequence(
              "emit:AgentTold",
              "remember:user",
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

    /**
     * A model executor that throws mid-turn does not pin the progress-holder contract on its own:
     * the loop already saves after every successful fold, so the store would hold the same state
     * whether or not {@code run}'s {@code try}/{@code finally} exists — the prior fold's own
     * in-loop {@code store.save} already got there first. To actually exercise the {@code finally},
     * the throw has to land <em>between</em> {@code progress.set(state)} and that fold's own {@code
     * store.save} — inside {@code remember}, which the loop calls first. A memory that throws on
     * its second telling does exactly that: the first telling (the {@code AgentTold} fold's user
     * message) succeeds and is saved normally; the second telling (the {@code ModelResponded}
     * fold's assistant message) throws after {@code progress} has already been advanced to the
     * newly-folded state but before that fold's own save runs. Only the run-level {@code finally}
     * persists it after that — delete the {@code finally} and this assertion fails, because the
     * store would still hold the {@code AgentTold} fold's state.
     */
    @Test
    void the_finally_block_saves_the_just_folded_state_when_remembering_it_throws() {
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(new ArrayList<>(), homework(c1));
      ThrowOnNthRememberMemory memory =
          new ThrowOnNthRememberMemory(2, new IllegalStateException("remember blew up"));
      RecordingStore store = new RecordingStore(new ArrayList<>());
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(new ArrayList<>())),
              memory,
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              EventEmitter.noop(),
              ObservationRegistry.NOOP);
      ConversationEvent.AgentTold echoA = ConversationEvent.AgentTold.of(ID, "echo a");

      assertThatThrownBy(() -> loop.run(ID, echoA, OBSERVER))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("remember blew up");

      // The just-folded state (ModelResponded's homework fold: EXECUTING_TOOL, c1 pending) is
      // what the finally block must have saved — not the AgentTold fold's earlier AWAITING_MODEL
      // state, which is all that would remain without it.
      ConversationState saved = store.load(ID).orElseThrow().state();
      assertThat(saved.status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);
      assertThat(saved.pendingCalls()).containsExactly(c1);
      assertThat(memory.remembered()).containsExactly(Message.user("echo a"));
    }
  }

  /**
   * The unified entry (design 2026-08-12): a tell is an append followed by a drive, and driving is
   * re-entrant from any status — the retired §6 refusal contract had no successor test class of its
   * own; {@code Crash_recovery} below is its replacement, pinning re-drive instead of rejection.
   */
  @Nested
  class Unified_entry {

    @Test
    void a_tell_is_an_append_and_a_drive() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              new RecordingStore(journal),
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      assertThat(journal)
          .isNotEmpty()
          .containsSubsequence("append", "emit:AgentTold", "remember:user", "model");
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void three_queued_tells_open_one_turn_with_three_voices() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, plainAnswer("Ok."));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationStore store = ConversationStore.inMemory();
      store.append(ID, InboxEntry.told(List.of(new TextBlock("one"))));
      store.append(ID, InboxEntry.told(List.of(new TextBlock("two"))));
      store.append(ID, InboxEntry.told(List.of(new TextBlock("three"))));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              memory,
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(journal.stream().filter("emit:AgentTold"::equals)).hasSize(3);
      assertThat(journal.stream().filter("remember:user"::equals)).hasSize(1);
      assertThat(memory.remembered().getFirst().content())
          .containsExactly(new TextBlock("one"), new TextBlock("two"), new TextBlock("three"));
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * Opus fix round 1, Finding 3 (Important, design §4 governs): a note's id must ride its own
     * fold's save, transactionally — not linger on the inbox until some later save happens to flush
     * it. Two notes, so the first note's own save is directly observable before the second's fold
     * ever runs.
     */
    @Test
    void a_notes_id_rides_its_own_folds_save_not_a_later_one() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, plainAnswer("Ok."));
      InboxSnapshottingStore store = new InboxSnapshottingStore(ID);
      InboxEntry.Told first = InboxEntry.told(List.of(new TextBlock("one")));
      InboxEntry.Told second = InboxEntry.told(List.of(new TextBlock("two")));
      store.append(ID, first);
      store.append(ID, second);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      loop.drive(ID, OBSERVER);

      List<InboxEntry> inboxAfterFirstNotesSave = store.inboxAfterEachSave().getFirst();
      assertThat(inboxAfterFirstNotesSave).containsExactly(second);
    }
  }

  @Nested
  class Mid_turn_and_continuation {

    @Test
    void a_mid_turn_tell_rides_the_flush() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("result")));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationStore store = ConversationStore.inMemory();
      store.save(
          ConversationState.newConversation(ID)
              .withPendingCalls(List.of(c1))
              .with(ConversationStatus.EXECUTING_TOOL),
          List.of());
      store.append(ID, InboxEntry.told(List.of(new TextBlock("also check y"))));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      Message flush = memory.remembered().getFirst();
      assertThat(flush.content())
          .containsExactly(
              new ToolResultBlock("c1", "result", false), new TextBlock("also check y"));
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void a_clean_response_with_queued_mail_keeps_driving() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("First."), plainAnswer("Second."));
      ConversationStore store = ConversationStore.inMemory();
      store.save(
          ConversationState.newConversation(ID).with(ConversationStatus.AWAITING_MODEL), List.of());
      store.append(ID, InboxEntry.told(List.of(new TextBlock("more context"))));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(model.calls()).isEqualTo(2);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }
  }

  @Nested
  class Park_and_resume {

    @Test
    void a_parking_tool_parks_the_conversation_and_registers_the_token() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      RecordingStore store = new RecordingStore(journal);
      Parks parks = Parks.inMemory();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              parks,
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(outcome.state().parkedCalls()).containsExactly(c1);
      assertThat(store.load(ID).orElseThrow().state().status())
          .isEqualTo(ConversationStatus.PARKED);
      // Design §5: the registry write is the loop's own responsibility, not the tool's — the
      // token the tool minted must actually be findable in the Parks it was handed.
      assertThat(parks.find(token)).contains(new Parks.Park(ID, token, c1));
    }

    @Test
    void a_park_is_narrated_to_the_observer_with_its_token() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              ConversationStore.inMemory(),
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), events::add);

      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.ToolCallParked)
          .containsExactly(new TurnEvent.ToolCallParked(c1, token));
    }

    /**
     * Opus review, Task 3 Finding 3: pins the emit-after-commit placement choice — the narration
     * test above passes identically whether {@code applyParked} emits before or after its own
     * {@code save}, so it alone does not prove the ordering. {@link AlwaysStaleStore} fails every
     * save this drive attempts, permanently, so no fold — not even the note's own — ever lands;
     * {@code drive} exhausts its retries and the {@link StaleStateException} surfaces. Since no
     * commit of any kind ever happened, the narration must not have either: this is the "never
     * narrates a park state itself never confirms" half of the contract, exercised at its coarsest
     * (nothing at all commits, so nothing at all is narrated).
     */
    @Test
    void a_park_that_never_commits_is_never_narrated() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      AlwaysStaleStore store = new AlwaysStaleStore(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      List<TurnEvent> events = new ArrayList<>();
      ConversationEvent.AgentTold searchX = ConversationEvent.AgentTold.of(ID, "search x");

      assertThatThrownBy(() -> loop.run(ID, searchX, events::add))
          .isInstanceOf(StaleStateException.class);

      assertThat(events).filteredOn(e -> e instanceof TurnEvent.ToolCallParked).isEmpty();
    }

    /**
     * Opus review, Task 3 Finding 3, the other half of the contract: {@link StaleOnceStore}
     * sabotages only the drive's very first {@code save} (proven by {@code
     * a_stale_save_makes_the_drive_reload_and_retry}'s two {@code load}s) — the first attempt's
     * note-fold save is the one that loses the race, the retry reloads and lands cleanly all the
     * way through the park. The narration fires exactly once, on the attempt that actually
     * committed the park — not on the failed first attempt, which never got there.
     */
    @Test
    void a_park_that_lands_on_a_retried_attempt_is_narrated_exactly_once() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      StaleOnceStore store = new StaleOnceStore(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), events::add);

      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.ToolCallParked)
          .containsExactly(new TurnEvent.ToolCallParked(c1, token));
    }

    /**
     * Opus re-review, Task 3 Finding 3 (the gap the prior two tests missed): both of them only ever
     * sabotage the note-fold's own save, never {@code applyParked}'s own terminal save — so a
     * mutant that swaps {@code applyParked}'s two lines (emit before save, instead of after) would
     * pass every test above undetected. {@link SabotagesTheParkedSaveOnceStore} targets exactly the
     * save that would persist the {@code parked} transition, leaving every earlier save in the
     * drive untouched. If the emit ever preceded that save, the failed first attempt would already
     * have narrated before its own save threw — and the landed retry would narrate again, leaving
     * two events for one park instead of one. This is the placement law itself: the park's own save
     * failing must cost the attempt its narration, not just its persistence.
     */
    @Test
    void a_park_whose_own_save_fails_is_not_narrated() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      SabotagesTheParkedSaveOnceStore store = new SabotagesTheParkedSaveOnceStore(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), events::add);

      // The sabotaged first parked-save attempt narrated nothing; the landed retry narrated once —
      // not twice. A single containsExactly proves both halves at once: two entries would mean the
      // failed attempt leaked a narration before its own save threw.
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.ToolCallParked)
          .containsExactly(new TurnEvent.ToolCallParked(c1, token));
    }

    /**
     * Opus fix round 1, Finding 1 (Critical), the reviewer's exact trace: two calls fan out, one
     * parks, its sibling settles. The turn must not flush on the sibling's completion alone (that
     * would answer the wire with one result and an unanswered {@code tool_use}); it holds, PARKED,
     * until the resume brings the parked call's own result — then one flush carries both, riders
     * included, and the turn completes.
     */
    @Test
    void a_parked_call_with_a_settling_sibling_holds_the_flush_until_resume() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ToolCall c2 = toolCall("c2", "fetch");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1, c2), plainAnswer("Both in."));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      tools.andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.ok("b")));
      tools.resumesTo("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationStore store = ConversationStore.inMemory();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome parked =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "search and fetch"), OBSERVER);

      assertThat(parked.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(parked.state().parkedCalls()).extracting(ToolCall::id).containsExactly(c1.id());
      assertThat(parked.state().pendingResults()).hasSize(1); // c2's result, held — not flushed
      assertThat(model.calls()).isEqualTo(1); // no second model call: the turn never continued

      // Resolution is keyed by call id now (design §5), not token — resume routes by matching
      // parkedCalls, so appending against c1's own id is what the loop's fold actually consults.
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("a"))));
      RunOutcome finished = loop.drive(ID, OBSERVER);

      assertThat(finished.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(model.calls()).isEqualTo(2);
      Message flush =
          memory.remembered().stream()
              .filter(m -> m.content().stream().anyMatch(ToolResultBlock.class::isInstance))
              .reduce((first, last) -> last)
              .orElseThrow();
      assertThat(flush.content())
          .containsExactly(
              new ToolResultBlock("c2", "b", false), new ToolResultBlock("c1", "a", false));
    }

    /**
     * Stands in for {@code Harness.resume}'s own steps ({@code parks.find}, {@code append} + {@code
     * drive}) at the store the loop itself uses — {@code HarnessTest} pins the facade that wraps
     * this same sequence end to end.
     */
    @Test
    void resume_routes_the_executor_and_finishes_the_turn() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1), plainAnswer("Found it."));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      tools.resumesTo("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("found")));
      ConversationStore store = ConversationStore.inMemory();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);

      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("found"))));
      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(outcome.state().parkedCalls()).isEmpty();
      assertThat(tools.resumeCalls()).isEqualTo(1);
    }

    /**
     * Task-4 (design §5): the store's old single-use-token-claim method dissolved — replay
     * protection is now the fold's own is-this-call-still-outstanding check. A redelivered
     * resolution (the same call id arriving a second time, as every real transport is
     * at-least-once) must not re-invoke the executor's {@code resume} a second time; the second
     * drive simply finds nothing left outstanding for that call and reads current truth.
     */
    @Test
    void a_redelivered_resolution_re_drives_and_reads_current_truth_without_reinvoking_resume() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1), plainAnswer("Found it."));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      tools.resumesTo("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("found")));
      ConversationStore store = ConversationStore.inMemory();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("found"))));
      loop.drive(ID, OBSERVER);

      // Redelivery: another Resolved entry for the same, now-settled call id arrives again.
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("found"))));
      RunOutcome second = loop.drive(ID, OBSERVER);

      assertThat(tools.resumeCalls()).isEqualTo(1);
      assertThat(second.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void a_resolution_for_a_settled_call_drains_quietly() {
      List<String> journal = new ArrayList<>();
      ConversationStore store = ConversationStore.inMemory();
      ToolCall c2 = toolCall("c2", "echo");
      ConversationState seeded =
          ConversationState.newConversation(ID)
              .withParkedCalls(List.of(c2))
              .with(ConversationStatus.PARKED);
      store.save(seeded, List.of());
      // "settled-call" names no call this conversation still lists as outstanding — the retired
      // single-use-token-claim's replay case, re-keyed by call id (design §5).
      store.append(
          ID, InboxEntry.resolved("settled-call", new ToolResolution.Decided(Decision.allow())));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(
                  new ScriptedModelCallExecutor(journal), new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(outcome.state().parkedCalls()).extracting(ToolCall::id).containsExactly(c2.id());
      assertThat(store.load(ID).orElseThrow().inbox()).isEmpty();
    }

    /**
     * Opus final review, Finding F1 (Major, the composition bug): the routing predicate used to
     * gate on {@code status == PARKED}, but a resolution can legitimately arrive while a fan-out
     * sibling is still unsettled — crash mid-fan-out, {@code EXECUTING_TOOL} with c1 already parked
     * and c2 still pending. The old gate stranded a resolution that arrived in that window: resume
     * consumed the token and appended {@code Resolved}, but the status-gated pass skipped it
     * (status was {@code EXECUTING_TOOL}, not {@code PARKED}), and the pointer pass re-performed
     * only the pending sibling — wedging the conversation forever with an un-routed resolution
     * sitting on the inbox. Routing by park membership instead of status fixes it: this seeds
     * exactly that crash-shaped state directly at the store (as if a crash landed right after c1's
     * park was applied and right before c2 was ever performed) and pins that a drive still lands —
     * c2 re-performs, c1's resolution routes, and one combined flush carries both results.
     */
    @Test
    void a_resolution_for_a_park_with_a_still_pending_sibling_routes_while_executing_tool() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ToolCall c2 = toolCall("c2", "fetch");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Both in."));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.ok("b")));
      tools.resumesTo("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      RecordingMemory memory = new RecordingMemory(journal);
      ConversationStore store = ConversationStore.inMemory();
      // Crash-shaped seed: c1 already parked, c2 still pending, status EXECUTING_TOOL — exactly
      // what applyParked leaves behind mid fan-out, before c2's own effect is ever performed.
      ConversationState seeded =
          ConversationState.newConversation(ID)
              .withPendingCalls(List.of(c2))
              .withParkedCalls(List.of(c1))
              .with(ConversationStatus.EXECUTING_TOOL);
      store.save(seeded, List.of());
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("a"))));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              memory,
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(tools.resumeCalls()).isEqualTo(1);
      Message flush =
          memory.remembered().stream()
              .filter(m -> m.content().stream().anyMatch(ToolResultBlock.class::isInstance))
              .reduce((first, last) -> last)
              .orElseThrow();
      assertThat(flush.content())
          .containsExactly(
              new ToolResultBlock("c1", "a", false), new ToolResultBlock("c2", "b", false));
    }

    /**
     * Opus final review, Finding F1/F3: the old gate also left a stale {@code Resolved} entry stuck
     * forever in a conversation that had already finished — status {@code COMPLETE} is not {@code
     * PARKED}, so the old predicate never even looked at the entry. Routing by park membership
     * drains it quietly instead, the same way {@code
     * a_resolution_for_a_settled_call_drains_quietly} pins it for a {@code PARKED} conversation,
     * but here for one that is not, and will never again be, {@code PARKED}.
     */
    @Test
    void a_stale_resolution_in_a_complete_conversations_inbox_drains_quietly() {
      List<String> journal = new ArrayList<>();
      ConversationStore store = ConversationStore.inMemory();
      ConversationState seeded =
          ConversationState.newConversation(ID).with(ConversationStatus.COMPLETE);
      store.save(seeded, List.of());
      store.append(
          ID, InboxEntry.resolved("settled-call", new ToolResolution.Decided(Decision.allow())));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(
                  new ScriptedModelCallExecutor(journal), new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(store.load(ID).orElseThrow().inbox()).isEmpty();
    }

    /**
     * Opus fix round 1, Finding 2 (Important): a throw between accepting a resolution and folding
     * its fact must not destroy the only copy of that resolution. The re-park guard is a real,
     * reachable throw (approval-resume invokes the tool, and the tool parks again) — this pins that
     * the Resolved entry survives it, on the inbox, for a future retry.
     */
    @Test
    void a_throwing_resume_leaves_the_resolution_on_the_inbox_for_retry() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      tools.reparksOnResume("c1");
      ConversationStore store = ConversationStore.inMemory();
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);
      InboxEntry.Resolved resolvedEntry =
          InboxEntry.resolved(c1.id(), new ToolResolution.Decided(Decision.allow()));
      store.append(ID, resolvedEntry);

      assertThatThrownBy(() -> loop.drive(ID, OBSERVER)).isInstanceOf(IllegalStateException.class);

      assertThat(store.load(ID).orElseThrow().inbox()).containsExactly(resolvedEntry);
    }
  }

  @Nested
  class Stale_save_retry {

    @Test
    void a_stale_save_makes_the_drive_reload_and_retry() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      StaleOnceStore store = new StaleOnceStore(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(journal.stream().filter("load"::equals)).hasSize(2);
    }

    @Test
    void five_consecutive_stale_saves_surface_the_exception() {
      List<String> journal = new ArrayList<>();
      AlwaysStaleStore store = new AlwaysStaleStore(journal);
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);
      ConversationEvent.AgentTold whatIs2Plus2 = ConversationEvent.AgentTold.of(ID, "what is 2+2?");

      assertThatThrownBy(() -> loop.run(ID, whatIs2Plus2, OBSERVER))
          .isInstanceOf(StaleStateException.class);

      assertThat(journal.stream().filter("load"::equals)).hasSize(5);
    }
  }

  /** The retired §6 refusal contract's replacement: re-drive, not rejection. */
  @Nested
  class Crash_recovery {

    @Test
    void a_crashed_awaiting_model_conversation_is_re_driven_by_the_next_entry() {
      List<String> journal = new ArrayList<>();
      ConversationStore store = ConversationStore.inMemory();
      store.save(
          ConversationState.newConversation(ID).with(ConversationStatus.AWAITING_MODEL), List.of());
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("First."), plainAnswer("Second."));
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      // Re-driven, not refused: the crashed call is re-performed, and the queued tell that woke it
      // rides the clean-with-notes continuation into a second model call before settling.
      assertThat(model.calls()).isEqualTo(2);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void a_crashed_executing_tool_conversation_re_performs_its_debt() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      ConversationStore store = ConversationStore.inMemory();
      store.save(
          ConversationState.newConversation(ID)
              .withPendingCalls(List.of(c1))
              .with(ConversationStatus.EXECUTING_TOOL),
          List.of());
      ConversationLoop loop =
          new ConversationLoop(
              new EffectExecutors(model, tools),
              new RecordingMemory(journal),
              TerminationPolicy.never(),
              store,
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(journal).contains("tool:c1");
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
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
              Parks.inMemory(),
              new RecordingEmitter(journal),
              ObservationRegistry.NOOP);

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(outcome.state().failureReason()).isEqualTo("boom");
      assertThat(model.calls()).isEqualTo(1);
    }
  }
}
