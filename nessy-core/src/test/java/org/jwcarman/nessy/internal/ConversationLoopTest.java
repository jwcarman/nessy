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
import org.jwcarman.nessy.api.ConversationSettled;
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
import org.jwcarman.nessy.spi.transcript.Transcript;

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

    /** Seeds initial state directly at the delegate, bypassing the one-shot sabotage. */
    void seed(ConversationState state) {
      delegate.save(state, List.of());
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
   * Sabotages exactly the {@code save}-th call to {@code save} (1-indexed, counted across the whole
   * fixture, retries included) — the same steal-the-version trick {@link StaleOnceStore} uses, but
   * keyed on call order rather than "the first ever", so a fixture can target a save that lands
   * <em>after</em> some earlier save in the same {@code driveOnce} attempt already committed (S1: a
   * {@link TurnEvent.TurnEnded} narrated by an earlier {@code applyParked} in the same attempt,
   * then a later, unrelated save in that same attempt loses the fence).
   */
  private static final class SabotagesTheNthSaveOnceStore implements ConversationStore {

    private final List<String> journal;
    private final int sabotageAt;
    private final ConversationStore delegate = ConversationStore.inMemory();
    private int saveCount;
    private boolean sabotaged;

    SabotagesTheNthSaveOnceStore(List<String> journal, int sabotageAt) {
      this.journal = journal;
      this.sabotageAt = sabotageAt;
    }

    @Override
    public Optional<Loaded> load(ConversationId id) {
      journal.add("load");
      return delegate.load(id);
    }

    @Override
    public ConversationState save(ConversationState state, Collection<String> drainedInboxIds) {
      saveCount++;
      if (saveCount == sabotageAt && !sabotaged) {
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

    /** Seeds initial state directly at the delegate, bypassing the counted sabotage. */
    void seed(ConversationState state) {
      delegate.save(state, List.of());
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
   * Records every message it is told, in birth order, on top of a pipeline {@link Memory} floor.
   */
  private static final class RecordingMemory implements Memory {

    private final List<String> journal;
    private final Memory delegate = Memory.pipeline(Transcript.inMemory()).build();
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

    private final Memory delegate = Memory.pipeline(Transcript.inMemory()).build();
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
    private final List<Object> events = new ArrayList<>();

    RecordingEmitter(List<String> journal) {
      this.journal = journal;
    }

    List<Object> events() {
      return events;
    }

    @Override
    public void emit(Object event) {
      journal.add("emit:" + event.getClass().getSimpleName());
      events.add(event);
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  memory,
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  emitter),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  memory,
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  termination,
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  memory,
                  TerminationPolicy.maxConsecutiveErrors(1),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  memory,
                  TerminationPolicy.maxModelCalls(1),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(new ArrayList<>())),
                  memory,
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  EventEmitter.noop()),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  memory,
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  memory,
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  parks,
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

      RunOutcome outcome = loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(outcome.state().parkedCalls()).containsExactly(c1);
      assertThat(store.load(ID).orElseThrow().state().status())
          .isEqualTo(ConversationStatus.PARKED);
      // Design §5: the registry write is the loop's own responsibility, not the tool's — the
      // token the tool minted must actually be findable in the Parks it was handed.
      // Design §3: the registered Park carries the loop's own agent name, the stamp a callback
      // door later verifies a resolution against.
      assertThat(parks.find(token)).contains(new Parks.Park(ID, token, c1, "loop-test-agent"));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  ConversationStore.inMemory(),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), events::add);

      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.ToolCallParked)
          .containsExactly(new TurnEvent.ToolCallParked(c1, token));
    }

    @Test
    void a_park_ends_the_segment_exactly_once_right_after_its_own_narration() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  ConversationStore.inMemory(),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), events::add);

      assertThat(events)
          .filteredOn(
              e -> e instanceof TurnEvent.ToolCallParked || e instanceof TurnEvent.TurnEnded)
          .containsExactly(
              new TurnEvent.ToolCallParked(c1, token),
              new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  memory,
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
     * Opus fix round 1, Finding 1 (Critical): the same fan-out shape as the test above, but this
     * time watching the narration rather than the flush. {@code applyParked} is not the one that
     * closes this cycle to {@code PARKED} — c1 parks first while c2 is still pending
     * (EXECUTING_TOOL, no emission yet), and it is c2's own settling fold, not a park, that empties
     * {@code pendingCalls} and flips status to PARKED. A {@code TurnEnded} keyed on "did
     * applyParked just close it" misses this path entirely; the fix keys it on "has anything
     * narrated this attempt's ending yet" instead.
     */
    @Test
    void a_parking_call_whose_sibling_settles_afterward_still_ends_the_segment_exactly_once() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ToolCall c2 = toolCall("c2", "fetch");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1, c2));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      ParkToken token = tools.parksWhen("c1");
      tools.andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.ok("b")));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  ConversationStore.inMemory(),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "search and fetch"), events::add);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      List<TurnEvent> parkedAndEnded =
          events.stream()
              .filter(
                  e -> e instanceof TurnEvent.ToolCallParked || e instanceof TurnEvent.TurnEnded)
              .toList();
      assertThat(parkedAndEnded)
          .containsExactly(
              new TurnEvent.ToolCallParked(c1, token),
              new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));
    }

    /**
     * Opus fix round 1, Finding 1 (Critical), the second reachable miss: a tell against a
     * conversation that is already PARKED on entry. {@code continueByStatus} no-ops for PARKED (not
     * quiescent, not AWAITING_MODEL, not EXECUTING_TOOL), so {@code applyParked} is never called at
     * all this attempt — the settled-return site is the only one that can possibly narrate, and
     * before the fix it explicitly skipped PARKED.
     */
    @Test
    void a_tell_against_an_already_parked_conversation_still_narrates_its_ending() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ConversationStore store = ConversationStore.inMemory();
      store.save(
          ConversationState.newConversation(ID)
              .withParkedCalls(List.of(c1))
              .with(ConversationStatus.PARKED),
          List.of());
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(
                      new ScriptedModelCallExecutor(journal),
                      new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "any news?"), events::add);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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

    /**
     * Opus review, Finding 2 (Important): at-least-once delivery can land two {@code Resolved}
     * entries for the same call in the inbox before either is ever drained — the store is
     * append-only and unconditional, so nothing stops two redeliveries from arriving before a
     * single {@code drive} even starts. The routing loop reads {@code progress.get().parkedCalls()}
     * fresh on every iteration, so the first entry's own fold already clears {@code c1} from the
     * outstanding set before the second entry is ever considered — a plausible mis-write that
     * hoists {@code loaded.state().parkedCalls()} once, outside the loop, would instead route both
     * entries and re-invoke the executor's {@code resume} twice, silently double-executing the
     * tool. This test seeds both entries at once, drives exactly once, and pins the executor sees
     * exactly one resume and the turn still completes.
     */
    @Test
    void two_resolved_entries_for_the_same_call_in_one_inbox_resume_the_tool_exactly_once() {
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);

      // Both entries land before drive ever runs — the at-least-once-delivery-lands-both shape.
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("found"))));
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("found"))));
      RunOutcome outcome = loop.drive(ID, OBSERVER);

      assertThat(tools.resumeCalls()).isEqualTo(1);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(
                      new ScriptedModelCallExecutor(journal),
                      new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome = loop.drive(ID, events::add);

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(outcome.state().parkedCalls()).extracting(ToolCall::id).containsExactly(c2.id());
      assertThat(store.load(ID).orElseThrow().inbox()).isEmpty();
      // Opus fix round 1, Finding 1 (Critical): this attempt drains a stale resolution and never
      // touches applyParked at all (the conversation was already PARKED on entry) — the
      // settled-return site must still narrate the ending exactly once.
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  memory,
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome = loop.drive(ID, events::add);

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
      // Opus fix round 1, Finding 1 (Critical): the resumed park's own routing (not applyParked,
      // which never runs here — c1 was already parked on entry) drives this segment all the way to
      // COMPLETE; the ending must still narrate exactly once.
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(
                      new ScriptedModelCallExecutor(journal),
                      new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome = loop.drive(ID, events::add);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(store.load(ID).orElseThrow().inbox()).isEmpty();
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), events::add);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(journal.stream().filter("load"::equals)).hasSize(2);
      // The first attempt's own tail save is the one that loses the fence race (it never
      // commits), so it must narrate no ending at all — only the winning retry's landed state
      // gets a TurnEnded, and exactly one of them.
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
      // In this fixture the sabotaged save is the note fold's own (step 1 runs first and has
      // nothing else ahead of it), so the losing attempt dies before the model is ever called —
      // model.calls() and AssistantSaid both land exactly once, from the winning retry alone. The
      // test below (a_model_fold_that_loses_the_fence_race_re_narrates_assistant_said_on_retry)
      // seeds a fixture where the sabotaged save instead belongs to the ModelResponded fold, which
      // is what actually exercises AssistantSaid's pre-save, at-least-once re-narration.
      assertThat(model.calls()).isEqualTo(1);
      assertThat(events).filteredOn(e -> e instanceof TurnEvent.AssistantSaid).hasSize(1);
    }

    /**
     * Opus fix round 1, Finding 4 (should-fix, spec §9): {@link TurnEvent.AssistantSaid} shares the
     * fact emitter's own placement inside {@code fold()} — narrated before that fold's own save —
     * so a losing attempt that reaches the {@code ModelResponded} fold before losing the fence race
     * has already said it once for nothing; the winning retry re-folds the same response and says
     * it again. The test above does not exercise this: its very first save belongs to the note's
     * own fold, which dies before the model is ever called. Seeding the conversation already {@code
     * AWAITING_MODEL} (no {@code Told} entry to drain first) puts the model-call fold's own save
     * first in line for {@link StaleOnceStore}'s one-shot sabotage instead.
     */
    @Test
    void a_model_fold_that_loses_the_fence_race_re_narrates_assistant_said_on_retry() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."), plainAnswer("Four."));
      StaleOnceStore store = new StaleOnceStore(journal);
      store.seed(ConversationState.newConversation(ID).with(ConversationStatus.AWAITING_MODEL));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome = loop.drive(ID, events::add);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(model.calls()).isEqualTo(2); // re-performed on retry — at-least-once
      // The losing attempt's own pre-save narration, plus the winning retry's — documented and
      // asserted tolerable per spec §9, the same at-least-once rule TurnEvent's type-level javadoc
      // states for the whole roster.
      assertThat(events).filteredOn(e -> e instanceof TurnEvent.AssistantSaid).hasSize(2);
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
    }

    @Test
    void five_consecutive_stale_saves_surface_the_exception() {
      List<String> journal = new ArrayList<>();
      AlwaysStaleStore store = new AlwaysStaleStore(journal);
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      ConversationEvent.AgentTold whatIs2Plus2 = ConversationEvent.AgentTold.of(ID, "what is 2+2?");

      assertThatThrownBy(() -> loop.run(ID, whatIs2Plus2, OBSERVER))
          .isInstanceOf(StaleStateException.class);

      assertThat(journal.stream().filter("load"::equals)).hasSize(5);
    }

    /**
     * Opus final review, S1: {@code endingNarrated} is attempt-scoped (a fresh flag per {@code
     * driveOnce} call), so a segment that narrates {@link TurnEvent.TurnEnded} mid-attempt via
     * {@code applyParked} and then loses a <em>later</em> save in that same attempt is retried with
     * a fresh flag — and the winning retry, finding nothing left to fold but a drained-inbox tail
     * save to make, narrates its own {@code TurnEnded} too. Built the way this bug actually
     * reaches: {@code c1} is already parked (seeded); resolving it flushes straight into a new
     * model call that asks for {@code d1}, which itself parks and — being the sole outstanding call
     * — closes the cycle, narrating {@code TurnEnded(PARKED)} from inside {@code applyParked}. A
     * second, stale {@code Resolved} entry for the now-settled {@code c1} (a redelivered duplicate)
     * drains quietly afterward, but its drain still owes the attempt's tail save — save #4 by this
     * fixture's count, the one {@link SabotagesTheNthSaveOnceStore} is aimed at. That save losing
     * the fence is the at-least-once rule {@link TurnEvent}'s type-level javadoc already states for
     * {@link TurnEvent.ToolCallParked} and {@link TurnEvent.AssistantSaid}, extended to {@code
     * TurnEnded}: this pins two narrations, one per attempt, never more than one from either
     * attempt alone, and a drive that still lands on the correct final state.
     */
    @Test
    void a_tail_save_that_loses_the_fence_after_turn_ended_was_narrated_re_narrates_it_on_retry() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ToolCall d1 = toolCall("d1", "fetch");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(d1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.resumesTo("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      tools.parksWhen("d1");
      // Save order this attempt owes: #1 c1's resume-fold (AWAITING_MODEL), #2 the model's homework
      // fold (EXECUTING_TOOL), #3 applyParked's own parked-closure save (PARKED, narrates
      // TurnEnded), #4 the tail save draining the second, stale Resolved entry — sabotage #4, the
      // one that lands strictly after the narration in #3.
      SabotagesTheNthSaveOnceStore store = new SabotagesTheNthSaveOnceStore(journal, 4);
      store.seed(
          ConversationState.newConversation(ID)
              .withParkedCalls(List.of(c1))
              .with(ConversationStatus.PARKED));
      store.append(
          ID, InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("a"))));
      store.append(
          ID,
          InboxEntry.resolved(c1.id(), new ToolResolution.Completed(ToolResult.ok("duplicate"))));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      RunOutcome outcome = loop.drive(ID, events::add);

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(outcome.state().parkedCalls()).extracting(ToolCall::id).containsExactly(d1.id());
      List<TurnEvent> endings =
          events.stream().filter(e -> e instanceof TurnEvent.TurnEnded).toList();
      assertThat(endings)
          .isNotEmpty()
          .containsOnly(new TurnEvent.TurnEnded(ConversationStatus.PARKED, null));
      long attempts = journal.stream().filter("load"::equals).count();
      // No more than one TurnEnded per attempt: the CAS inside one driveOnce call structurally
      // forbids it, so the total narrated can never exceed the number of attempts this drive took.
      // The bug this test exists to pin: the fenced-out attempt's narration was not suppressed, so
      // this drive really did narrate the ending twice — once per attempt, never more (the CAS
      // inside one driveOnce call structurally forbids exceeding the attempt count).
      assertThat(endings).hasSizeLessThanOrEqualTo((int) attempts).hasSize(2);
      assertThat(attempts).isEqualTo(2);
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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  store,
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

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
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");

      RunOutcome outcome =
          loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.FAILED);
      assertThat(outcome.state().failureReason()).isEqualTo("boom");
      assertThat(model.calls()).isEqualTo(1);
    }
  }

  /** The emission contract for {@link ConversationSettled}, the loop's wake-up signal. */
  @Nested
  class Conversation_settled {

    @Test
    void a_clean_scripted_turn_publishes_one_settled_fact_with_the_assistants_text() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      RecordingEmitter emitter = new RecordingEmitter(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  emitter),
              ObservationRegistry.NOOP,
              "loop-test-agent");

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      List<ConversationSettled> settled =
          emitter.events().stream()
              .filter(ConversationSettled.class::isInstance)
              .map(ConversationSettled.class::cast)
              .toList();
      assertThat(settled).isNotEmpty().hasSize(1);
      assertThat(settled.getFirst())
          .isEqualTo(new ConversationSettled(ID, ConversationStatus.COMPLETE, null, "Four."));
    }

    @Test
    void a_failed_model_call_publishes_a_settled_fact_with_the_failure_reason() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, new ConversationEvent.ModelCallFailed(ID, "boom"));
      RecordingEmitter emitter = new RecordingEmitter(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  emitter),
              ObservationRegistry.NOOP,
              "loop-test-agent");

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), OBSERVER);

      List<ConversationSettled> settled =
          emitter.events().stream()
              .filter(ConversationSettled.class::isInstance)
              .map(ConversationSettled.class::cast)
              .toList();
      assertThat(settled).isNotEmpty().hasSize(1);
      assertThat(settled.getFirst())
          .isEqualTo(new ConversationSettled(ID, ConversationStatus.FAILED, "boom", ""));
    }

    @Test
    void a_parked_drive_publishes_no_settled_fact() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "search");
      ScriptedModelCallExecutor model = new ScriptedModelCallExecutor(journal, homework(c1));
      ParkingToolCallExecutor tools = new ParkingToolCallExecutor(journal);
      tools.parksWhen("c1");
      RecordingEmitter emitter = new RecordingEmitter(journal);
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  emitter),
              ObservationRegistry.NOOP,
              "loop-test-agent");

      RunOutcome outcome = loop.run(ID, ConversationEvent.AgentTold.of(ID, "search x"), OBSERVER);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.PARKED);
      assertThat(emitter.events()).isNotEmpty();
      assertThat(emitter.events().stream().noneMatch(ConversationSettled.class::isInstance))
          .isTrue();
    }
  }

  /** The emission contract for {@link TurnEvent.AssistantSaid} and {@link TurnEvent.TurnEnded}. */
  @Nested
  class Assistant_and_turn_narration {

    @Test
    void assistant_said_is_emitted_once_per_model_response_including_a_tool_use_only_response() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1), plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a"), events::add);

      List<TurnEvent.AssistantSaid> said =
          events.stream()
              .filter(e -> e instanceof TurnEvent.AssistantSaid)
              .map(TurnEvent.AssistantSaid.class::cast)
              .toList();
      // Two model responses fold in this run — the tool-use-only homework and the plain
      // answer — and both must be said, not just the one carrying prose.
      assertThat(said).isNotEmpty().hasSize(2);
      assertThat(said.get(0).message()).isEqualTo(Message.assistant(List.of(new ToolUseBlock(c1))));
      assertThat(said.get(1).message())
          .isEqualTo(Message.assistant(List.of(new TextBlock("Done."))));
    }

    @Test
    void turn_ended_is_emitted_exactly_once_for_a_complete_segment() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, plainAnswer("Four."));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), events::add);

      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
    }

    @Test
    void turn_ended_carries_the_failure_reason_for_a_failed_segment() {
      List<String> journal = new ArrayList<>();
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, new ConversationEvent.ModelCallFailed(ID, "boom"));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, new ScriptedToolCallExecutor(journal)),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "what is 2+2?"), events::add);

      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "boom"));
    }

    @Test
    void a_multi_call_tool_loop_segment_says_once_per_call_and_ends_exactly_once() {
      List<String> journal = new ArrayList<>();
      ToolCall c1 = toolCall("c1", "echo");
      ToolCall c2 = toolCall("c2", "echo");
      ScriptedModelCallExecutor model =
          new ScriptedModelCallExecutor(journal, homework(c1), homework(c2), plainAnswer("Done."));
      ScriptedToolCallExecutor tools =
          new ScriptedToolCallExecutor(journal)
              .andFor("c1", new ConversationEvent.ToolFinished(ID, c1, ToolResult.ok("a")))
              .andFor("c2", new ConversationEvent.ToolFinished(ID, c2, ToolResult.ok("b")));
      ConversationLoop loop =
          new ConversationLoop(
              new ConversationLoop.Collaborators(
                  new EffectExecutors(model, tools),
                  new RecordingMemory(journal),
                  TerminationPolicy.never(),
                  new RecordingStore(journal),
                  Parks.inMemory(),
                  new RecordingEmitter(journal)),
              ObservationRegistry.NOOP,
              "loop-test-agent");
      List<TurnEvent> events = new ArrayList<>();

      loop.run(ID, ConversationEvent.AgentTold.of(ID, "echo a then b"), events::add);

      assertThat(events).filteredOn(e -> e instanceof TurnEvent.AssistantSaid).hasSize(3);
      assertThat(events)
          .filteredOn(e -> e instanceof TurnEvent.TurnEnded)
          .containsExactly(new TurnEvent.TurnEnded(ConversationStatus.COMPLETE, null));
    }
  }
}
