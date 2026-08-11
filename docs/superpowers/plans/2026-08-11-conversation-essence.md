# Conversation Essence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the conversation-essence amendment (`docs/superpowers/specs/2026-08-11-conversation-essence-design.md`): two effects, four facts, fold-on-state, a typed executor record, the Memory subsystem, TurnEvent narration, and one invariant loop — replacing Reducer/ExecutionEngine/InProcessEngine/compaction/context-pipeline.

**Architecture:** Staged strangler. Tasks 1–8 are *additive* — the new vocabulary, Memory, fold methods, executors, and loop are built and tested beside the old machinery, which keeps compiling (two temporary scaffolds are noted where sealed exhaustiveness forces contact). Task 9 is the scripted cutover: facade rewires to the loop, old machinery and its tests are deleted, downstream modules migrate. Task 10 slims `ConversationState` and renames. Task 11 is the docs/CHANGELOG sweep and final conformance check.

**Tech Stack:** Java 25 (records, sealed interfaces, pattern-matching switch), Maven (`./mvnw`), JUnit 5 + AssertJ, Jackson, Micrometer Observation. No mocking libraries — hand-rolled fakes only (design-of-record promise).

## Global Constraints

- **Never write or edit code without the user having approved this plan's execution** (global CLAUDE.md).
- **No warning suppressions** (`@SuppressWarnings` etc.) — fix the cause. Sole exception: `@SuppressWarnings("deprecation")` with a comment naming the spec contract that mandates it.
- **No star imports.** Explicit single-symbol imports everywhere.
- **Full verification:** `./mvnw -q clean verify` from the repo root must pass with no API key and no network, after every task.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply` (license plugin adds the Apache header to new files — plan snippets omit headers on purpose).
- **Exception-assertion lambdas contain exactly ONE throwing invocation** (Sonar S5778); arrange setup outside the lambda.
- **Assert emptiness before any all/none-match predicate** on the same collection (S5841 family).
- **Core sealed switches: exhaustive, NO `default` arm.** Temporary arms for doomed variants throw `IllegalStateException` and are removed in Task 9 — they are named arms, never `default`.
- **Prose test style:** test method names read as sentences (match existing tests, e.g. `ReducerGrammarTest`).
- **Model policy (for dispatch):** implementer = Sonnet; task-reviewer = Sonnet, Opus for Tasks 5, 8, 9 (fold semantics, loop concurrency-adjacent invariants, cutover); transcription-only briefs = Haiku.
- **Commit messages:** follow the repo's evocative-but-precise house style; `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

**Package placement (locked here, per spec "plan-level"):**

| Concept | Package |
|---|---|
| `TurnEvent`, `TurnObserver` | `org.jwcarman.nessy.api.turn` (new) |
| `ToolResolution` | `org.jwcarman.nessy.api` (beside `Awaited`, `ParkToken`) |
| `ModelResponded`, `ModelCallFailed` | variants of `org.jwcarman.nessy.api.ConversationEvent` |
| `Effect`, `Step` | move to `org.jwcarman.nessy.api.conversation` (Task 9; api must not depend on spi) |
| `Memory`, `ListMemory` | `org.jwcarman.nessy.spi.memory` (new) |
| `ModelCallExecutor`, `ToolCallExecutor`, `EffectExecutors`, impls | `org.jwcarman.nessy.spi.execute` (new) |
| `ContextOverflowException` | `org.jwcarman.nessy.spi.model` |
| `ConversationLoop` | `org.jwcarman.nessy.internal` (core-owned, not implementable) |

---

### Task 1: TurnEvent + TurnObserver — the narration vocabulary

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/turn/TurnEvent.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/turn/TurnObserver.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/turn/TurnEventTest.java`

**Interfaces:**
- Consumes: `org.jwcarman.nessy.api.Decision`, `api.tool.ToolCall`, `api.tool.ToolResult` (existing).
- Produces: sealed `TurnEvent` with variants `TextDelta(String text)`, `ThinkingDelta(String text)`, `RedactedThinking(String data)`, `ToolCallRequested(ToolCall call)`, `ToolCallDecided(ToolCall call, Decision decision)`, `ToolCallCompleted(ToolCall call, ToolResult result)`; `TurnObserver { void on(TurnEvent event); static TurnObserver noop(); }`. Tasks 6, 7, 8, 9 depend on these exact names.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.api.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TurnEventTest {

  @Test
  void aNoopObserverAcceptsEveryEventWithoutComplaint() {
    TurnObserver observer = TurnObserver.noop();
    observer.on(new TurnEvent.TextDelta("hello"));
    observer.on(new TurnEvent.ThinkingDelta("hmm"));
    observer.on(new TurnEvent.RedactedThinking("opaque"));
    assertThat(observer).isNotNull();
  }

  @Test
  void textDeltaRejectsNullText() {
    assertThatThrownBy(() -> new TurnEvent.TextDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void thinkingDeltaRejectsNullText() {
    assertThatThrownBy(() -> new TurnEvent.ThinkingDelta(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void redactedThinkingRejectsNullData() {
    assertThatThrownBy(() -> new TurnEvent.RedactedThinking(null))
        .isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=TurnEventTest`
Expected: COMPILATION ERROR — `TurnEvent` does not exist.

- [ ] **Step 3: Write the implementation**

`TurnEvent.java`:

```java
package org.jwcarman.nessy.api.turn;

import java.util.Objects;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The live story of one turn, for whoever is watching it happen.
 *
 * <p>Narration, not record: none of these ever fold into conversation state. The roster is chosen
 * so a sitting consumer can tell the turn's story from these events alone — the model speaking and
 * thinking, homework requested, the gate's verdict, homework settled. Delivered to the {@link
 * TurnObserver} bound at entry ({@code tell} or {@code resume}); the observer sees the segment it
 * holds, and anything it missed is in the facts.
 *
 * <p>Sealed-grammar etiquette: core switches over this type are exhaustive with no {@code default}
 * arm; extender code is advised to include one for forward tolerance across majors.
 */
public sealed interface TurnEvent {

  /** A chunk of assistant prose arrived from the stream. */
  record TextDelta(String text) implements TurnEvent {
    public TextDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A chunk of the model's visible reasoning arrived from the stream. */
  record ThinkingDelta(String text) implements TurnEvent {
    public ThinkingDelta {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /** A complete redacted-thinking block arrived; its contents are opaque by design. */
  record RedactedThinking(String data) implements TurnEvent {
    public RedactedThinking {
      Objects.requireNonNull(data, "data must not be null");
    }
  }

  /** The model asked for homework — emitted mid-stream as the tool-use block materializes. */
  record ToolCallRequested(ToolCall call) implements TurnEvent {
    public ToolCallRequested {
      Objects.requireNonNull(call, "call must not be null");
    }
  }

  /** The gate's verdict for one call: approved, or denied with reason. */
  record ToolCallDecided(ToolCall call, Decision decision) implements TurnEvent {
    public ToolCallDecided {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(decision, "decision must not be null");
    }
  }

  /** One piece of homework settled — result in hand, success or error. */
  record ToolCallCompleted(ToolCall call, ToolResult result) implements TurnEvent {
    public ToolCallCompleted {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
```

`TurnObserver.java`:

```java
package org.jwcarman.nessy.api.turn;

/**
 * Whoever is sitting there, watching this turn happen — a REPL painting deltas, a UI narrating
 * homework. Bound per entry: the observer handed to {@code tell} or {@code resume} sees the
 * segment that call starts, and nothing after a park. The consumer may not exist at all — an
 * autonomous agent runs every turn against {@link #noop()} and loses nothing.
 */
public interface TurnObserver {

  void on(TurnEvent event);

  /** The absent audience: accepts everything, tells no one. */
  static TurnObserver noop() {
    return event -> {};
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl nessy-core test -Dtest=TurnEventTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-core/src/main/java/org/jwcarman/nessy/api/turn nessy-core/src/test/java/org/jwcarman/nessy/api/turn
git commit -m "feat: TurnEvent — the turn learns to narrate itself"
```

---

### Task 2: ToolResolution — the park-resolution grammar

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/ToolResolution.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/ToolResolutionTest.java`

**Interfaces:**
- Consumes: `api.Decision`, `api.tool.ToolResult` (existing).
- Produces: sealed `ToolResolution` with variants `Decided(Decision decision)` and `Completed(ToolResult result)`. Tasks 7 and 8 depend on these exact names.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jwcarman.nessy.api.tool.ToolResult;
import org.junit.jupiter.api.Test;

class ToolResolutionTest {

  @Test
  void aDecisionResolvesAParkedGate() {
    ToolResolution resolution = new ToolResolution.Decided(Decision.allow());
    assertThat(resolution).isInstanceOf(ToolResolution.class);
  }

  @Test
  void aResultResolvesAParkedExecution() {
    ToolResolution resolution = new ToolResolution.Completed(ToolResult.text("done"));
    assertThat(resolution).isInstanceOf(ToolResolution.class);
  }

  @Test
  void decidedRejectsNullDecision() {
    assertThatThrownBy(() -> new ToolResolution.Decided(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void completedRejectsNullResult() {
    assertThatThrownBy(() -> new ToolResolution.Completed(null))
        .isInstanceOf(NullPointerException.class);
  }
}
```

Note: if `ToolResult.text(...)` does not exist, check `ToolResult` for its actual success factory (`ToolResult.of`, constructor, etc.) and use that — do not add a new factory.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=ToolResolutionTest`
Expected: COMPILATION ERROR — `ToolResolution` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package org.jwcarman.nessy.api;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * What a parked tool call was waiting for, now arrived.
 *
 * <p>The grammar is two variants because the scope is (design 2026-08-11, ruling 3): a parked call
 * awaits either the gate's verdict — {@link Decided}, the HITL approval case — or its slow
 * completion — {@link Completed}, the sub-agent / long-running-process case. The parked executor
 * receives its resolution and finishes its yield; the fold never learns time passed.
 *
 * <p>Sealed-grammar etiquette: core switches are exhaustive with no {@code default} arm.
 */
public sealed interface ToolResolution {

  /** The gate's verdict arrived. */
  record Decided(Decision decision) implements ToolResolution {
    public Decided {
      Objects.requireNonNull(decision, "decision must not be null");
    }
  }

  /** The slow completion arrived. */
  record Completed(ToolResult result) implements ToolResolution {
    public Completed {
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl nessy-core test -Dtest=ToolResolutionTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-core/src/main/java/org/jwcarman/nessy/api/ToolResolution.java nessy-core/src/test/java/org/jwcarman/nessy/api/ToolResolutionTest.java
git commit -m "feat: ToolResolution — two ways a parked call wakes up"
```

---

### Task 3: Memory + ListMemory — the content jurisdiction

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/Memory.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/ListMemory.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/memory/ListMemoryTest.java`

**Interfaces:**
- Consumes: `api.conversation.ConversationId`, `api.message.Message`, `api.message.Context` (existing; `Context.of(List<Message>)` validates wire legality).
- Produces: `Memory { void remember(ConversationId id, Message message); Context recall(ConversationId id); }` and `ListMemory implements Memory` (public no-arg constructor). Tasks 6, 8, 9 depend on these exact signatures.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.spi.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.junit.jupiter.api.Test;

class ListMemoryTest {

  private final ListMemory memory = new ListMemory();

  @Test
  void recallsExactlyWhatItWasToldInOrder() {
    ConversationId id = ConversationId.generate();
    Message first = Message.user("hello");
    Message second = Message.assistant("hi there");
    memory.remember(id, first);
    memory.remember(id, second);

    Context recalled = memory.recall(id);

    assertThat(recalled.messages()).containsExactly(first, second);
  }

  @Test
  void recallsNothingForAConversationNeverToldAnything() {
    Context recalled = memory.recall(ConversationId.generate());
    assertThat(recalled.messages()).isEmpty();
  }

  @Test
  void keepsConversationsApart() {
    ConversationId one = ConversationId.generate();
    ConversationId other = ConversationId.generate();
    memory.remember(one, Message.user("for one"));
    memory.remember(other, Message.user("for the other"));

    assertThat(memory.recall(one).messages()).containsExactly(Message.user("for one"));
    assertThat(memory.recall(other).messages()).containsExactly(Message.user("for the other"));
  }

  @Test
  void toleratesTheSameMessageToldTwiceInARow() {
    // At-least-once tellings (design 2026-08-11, ruling 6): a crash between telling
    // Memory and persisting state re-tells the same message. remember is idempotent.
    ConversationId id = ConversationId.generate();
    Message told = Message.user("once only, please");
    memory.remember(id, told);
    memory.remember(id, told);

    assertThat(memory.recall(id).messages()).containsExactly(told);
  }
}
```

Note: check `Message` for its actual factories — `Message.user(String)` / `Message.assistant(String)` may instead be `Message.user(List<ContentBlock>)` etc. Use what exists (see `MessageTest`); wrap with `new TextBlock(...)` lists if needed. Same for `ConversationId.generate()` — use the real factory from `ConversationId`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=ListMemoryTest`
Expected: COMPILATION ERROR — `Memory` / `ListMemory` do not exist.

- [ ] **Step 3: Write the implementation**

`Memory.java`:

```java
package org.jwcarman.nessy.spi.memory;

import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * The content jurisdiction: told everything that was said, and decides what the model is reminded
 * of.
 *
 * <p>Two duties. It is <em>told</em> — every message-grade happening, in order, for the
 * conversation's whole life: the user message when {@code AgentTold} folds, the assistant message
 * when {@code ModelResponded} folds, the batched results message when the tool debt clears. That
 * list is closed: the wire dialogue has exactly three message producers. And it is <em>asked</em> —
 * {@link #recall} builds the finished context for the next model call.
 *
 * <p>Freedom of retention, rule of law at the border: inside, an implementation may transcribe,
 * summarize, checkpoint, embed, or discard — the harness never audits how it thinks. At the
 * border, {@code recall} must return a legal {@code Context}; the unit of retention is the
 * <em>transaction</em> (an assistant message carrying tool-use blocks and the results message
 * answering it are one atomic unit — keep both or drop both, never split, never reorder across).
 *
 * <p>Tellings are at-least-once: a crash between telling and persisting re-tells the same message
 * on recovery, so {@link #remember} must be idempotent. The implementation is wired per agent —
 * different agents carry different memory systems — while the contract is keyed by conversation,
 * one instance serving all of that agent's conversations.
 */
public interface Memory {

  void remember(ConversationId id, Message message);

  Context recall(ConversationId id);
}
```

`ListMemory.java`:

```java
package org.jwcarman.nessy.spi.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * The floor: remembers everything verbatim, recalls it whole.
 *
 * <p>Safe by construction — legal messages went in, so the returned context cannot be illegal.
 * Idempotency is the consecutive-duplicate rule: a message equal to the last one remembered is the
 * at-least-once re-telling of crash recovery, not new speech, and is dropped.
 */
public final class ListMemory implements Memory {

  private final Map<ConversationId, List<Message>> conversations = new ConcurrentHashMap<>();

  @Override
  public void remember(ConversationId id, Message message) {
    conversations.compute(
        id,
        (key, existing) -> {
          List<Message> messages = existing == null ? new ArrayList<>() : existing;
          if (messages.isEmpty() || !messages.getLast().equals(message)) {
            messages.add(message);
          }
          return messages;
        });
  }

  @Override
  public Context recall(ConversationId id) {
    List<Message> messages = conversations.get(id);
    return Context.of(messages == null ? List.of() : List.copyOf(messages));
  }
}
```

Note: check `Context.of` accepts an empty list; if it rejects emptiness, use whatever "empty context" construction `Context` offers (see `ContextTest`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl nessy-core test -Dtest=ListMemoryTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-core/src/main/java/org/jwcarman/nessy/spi/memory nessy-core/src/test/java/org/jwcarman/nessy/spi/memory
git commit -m "feat: Memory — told everything, asked for the context, free in between"
```

---

### Task 4: The two new facts — ModelResponded and ModelCallFailed

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/ConversationEvent.java` (add two variants; remove nothing yet)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/Reducer.java` (two temporary throwing arms — sealed exhaustiveness demands them; deleted whole in Task 9)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/ConversationEventTest.java` (extend)

**Interfaces:**
- Produces: `ConversationEvent.ModelResponded(ConversationId conversationId, Message message, StopReason reason, Usage usage)` and `ConversationEvent.ModelCallFailed(ConversationId conversationId, String reason)`. Tasks 5, 6, 8, 9 depend on these exact shapes.

- [ ] **Step 1: Write the failing test** (add to `ConversationEventTest`, matching its existing prose style)

```java
@Test
void modelRespondedCarriesTheSettledMessageWhole() {
  ConversationId id = ConversationId.generate();
  Message message = Message.assistant("the answer");
  ConversationEvent.ModelResponded fact =
      new ConversationEvent.ModelResponded(id, message, StopReason.END_TURN, Usage.zero());
  assertThat(fact.conversationId()).isEqualTo(id);
  assertThat(fact.message()).isEqualTo(message);
}

@Test
void modelCallFailedNamesItsReason() {
  ConversationEvent.ModelCallFailed fact =
      new ConversationEvent.ModelCallFailed(ConversationId.generate(), "context window exceeded");
  assertThat(fact.reason()).isEqualTo("context window exceeded");
}

@Test
void modelRespondedRejectsNullMessage() {
  ConversationId id = ConversationId.generate();
  assertThatThrownBy(
          () -> new ConversationEvent.ModelResponded(id, null, StopReason.END_TURN, Usage.zero()))
      .isInstanceOf(NullPointerException.class);
}
```

(Adapt `Message.assistant(...)` to the real factory as in Task 3.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=ConversationEventTest`
Expected: COMPILATION ERROR — no such variants.

- [ ] **Step 3: Add the variants** to `ConversationEvent` (after `AgentTold`, before the doomed streaming variants):

```java
/**
 * The model's settled contribution: one assistant message — text, thinking, and any tool-use
 * blocks (the homework) as its content — plus how the call stopped and what it cost. One fact per
 * call; the fold unpacks the homework into effects.
 */
record ModelResponded(ConversationId conversationId, Message message, StopReason reason, Usage usage)
    implements ConversationEvent {

  public ModelResponded {
    Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(usage, "usage must not be null");
  }
}

/**
 * The model call failed in a way re-performing cannot fix — canonically, the context outgrew the
 * window. There is no party left in the dialogue to show this to (the model is the party that
 * failed), so it is fate, not data: the fold answers it with {@code FAILED}. Transient failures
 * (socket resets, retries exhausted) are exceptions, not facts — status still points at the work
 * and re-driving is the recovery.
 */
record ModelCallFailed(ConversationId conversationId, String reason) implements ConversationEvent {

  public ModelCallFailed {
    Objects.requireNonNull(conversationId, CONVERSATION_ID_MUST_NOT_BE_NULL);
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
```

Then make `Reducer.reduce`'s switch compile again by adding two **named** (not `default`) scaffold arms, marked for Task 9 deletion:

```java
// Scaffolding until the cutover (plan 2026-08-11, Task 9): the old reducer never
// receives the new facts — only the new loop feeds them, and it never feeds this class.
case ConversationEvent.ModelResponded e ->
    throw new IllegalStateException("new-grammar fact fed to legacy reducer: " + e);
case ConversationEvent.ModelCallFailed e ->
    throw new IllegalStateException("new-grammar fact fed to legacy reducer: " + e);
```

Check whether `ValidationTest` (null-guard sweep) or any other test enumerates `ConversationEvent` variants reflectively; if it does, the new records' `Objects.requireNonNull` guards satisfy it — run it and fix any enumeration lists it keeps.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q -pl nessy-core test`
Expected: PASS (whole module — the scaffold arms keep every existing reducer test green).

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-core
git commit -m "feat: ModelResponded and ModelCallFailed — the call's two settled endings"
```

---

### Task 5: Fold-on-state — Step grows remember, ConversationState learns its own transitions

The heart of the amendment. The fold handles ONLY the four facts (`AgentTold`, `ModelResponded`, `ModelCallFailed`, `ToolFinished`); the seven doomed variants get named throwing arms (removed with the variants in Task 9). The fold does not touch the legacy fields (`messages`, `pendingBlocks`, `generation`, `lastInputTokens`) — they ride along unchanged until Task 10 removes them.

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/Step.java` (add `remember` component, keep old factories compiling)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationState.java` (add `fold`, `halted`, private transition helpers)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/conversation/ConversationStateFoldTest.java` (new)

**Interfaces:**
- Consumes: Task 4's facts; existing `Effect.callModel()`, `Effect.ExecuteTool(ToolCall)`, `Message`, `ToolResultBlock`, `ToolUseBlock`, `ConversationStatus`.
- Produces: `Step(ConversationState state, List<Message> remember, List<Effect> effects)` with factories `Step.of(ConversationState, Effect...)` (empty remember — keeps old `Reducer` compiling) and `Step.of(ConversationState, List<Message> remember, Effect...)`; `ConversationState.fold(ConversationEvent) → Step`; `ConversationState.halted(String reason) → Step`. Task 8's loop depends on these exact signatures.

- [ ] **Step 1: Reshape Step** (small, do first so the test compiles):

```java
package org.jwcarman.nessy.spi;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.Message;

/**
 * What one fold produced: the next state, what to remember, and what to do about it.
 *
 * <p>{@code remember} is the fold's message births — the user message a tell rendered, the
 * assistant message a response carried, the results message a cleared debt flushed — in birth
 * order, for the loop to tell Memory before performing any effect.
 */
public record Step(ConversationState state, List<Message> remember, List<Effect> effects) {

  public Step {
    Objects.requireNonNull(state, "state must not be null");
    remember = List.copyOf(remember);
    effects = List.copyOf(effects);
  }

  public static Step of(ConversationState state, Effect... effects) {
    return new Step(state, List.of(), List.of(effects));
  }

  public static Step of(ConversationState state, List<Message> remember, Effect... effects) {
    return new Step(state, remember, List.of(effects));
  }
}
```

(Old `Reducer` only builds via `Step.of(state, effects...)` — it keeps compiling. Any old test constructing `new Step(state, effects)` directly must switch to `Step.of`; check `ReducerGrammarTest` and friends and adjust construction sites only.)

- [ ] **Step 2: Write the failing tests** — this is the semantic core; the tests ARE the spec's §2/§6 made executable:

```java
package org.jwcarman.nessy.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.Effect;
import org.jwcarman.nessy.spi.Step;
import org.junit.jupiter.api.Test;

class ConversationStateFoldTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState fresh = ConversationState.newConversation(id);

  // --- AgentTold ---

  @Test
  void aTellBirthsTheUserMessageAndAsksForTheModel() {
    Step step = fresh.fold(ConversationEvent.AgentTold.of(id, "hello"));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(step.remember()).containsExactly(Message.user(List.of(new TextBlock("hello"))));
    assertThat(step.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void aTellStartsAFreshErrorStreak() {
    ConversationState scarred = fresh.withConsecutiveErrors(2);
    Step step = scarred.fold(ConversationEvent.AgentTold.of(id, "again"));
    assertThat(step.state().consecutiveErrors()).isZero();
  }

  @Test
  void aMisdeliveredFactFailsLoudly() {
    ConversationEvent stray = ConversationEvent.AgentTold.of(ConversationId.generate(), "lost");
    assertThatThrownBy(() -> fresh.fold(stray))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("misdelivered");
  }

  // --- ModelResponded ---

  @Test
  void aCleanResponseCompletesTheTurn() {
    Message answer = Message.assistant(List.of(new TextBlock("done")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, answer, StopReason.END_TURN, usage(7)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    assertThat(step.remember()).containsExactly(answer);
    assertThat(step.effects()).isEmpty();
    assertThat(step.state().turns()).isEqualTo(1);
    assertThat(step.state().usage().inputTokens()).isEqualTo(7);
  }

  @Test
  void homeworkFansOutOneEffectPerCall() {
    ToolCall first = new ToolCall("call-1", "search", "{}");
    ToolCall second = new ToolCall("call-2", "fetch", "{}");
    Message homework =
        Message.assistant(List.of(new ToolUseBlock(first), new ToolUseBlock(second)));
    Step step =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(id, homework, StopReason.TOOL_USE, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.EXECUTING_TOOL);
    assertThat(step.state().pendingCalls()).containsExactly(first, second);
    assertThat(step.effects())
        .containsExactly(new Effect.ExecuteTool(first), new Effect.ExecuteTool(second));
  }

  @Test
  void aTokenCeilingResponseFailsTheConversationAndAnswersItsOwnHomework() {
    ToolCall orphan = new ToolCall("call-1", "search", "{}");
    Message truncated = Message.assistant(List.of(new ToolUseBlock(orphan)));
    Step step =
        awaitingModel()
            .fold(
                new ConversationEvent.ModelResponded(
                    id, truncated, StopReason.MAX_TOKENS, usage(3)));

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("MAX_TOKENS");
    assertThat(step.state().pendingCalls()).isEmpty();
    // the truncated message AND the abandoned-results flush are both remembered,
    // so the record never holds a tool_use without its tool_result
    assertThat(step.remember()).hasSize(2);
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void aRefusalFailsTheConversation() {
    Message refusal = Message.assistant(List.of(new TextBlock("no")));
    Step step =
        awaitingModel()
            .fold(new ConversationEvent.ModelResponded(id, refusal, StopReason.REFUSAL, usage(1)));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).contains("REFUSAL");
  }

  // --- ToolFinished ---

  @Test
  void resultsFoldInAnyOrderAndTheFlushWaitsForTheLastOne() {
    ToolCall first = new ToolCall("call-1", "search", "{}");
    ToolCall second = new ToolCall("call-2", "fetch", "{}");
    ConversationState owing = midHomework(first, second);

    Step afterSecond =
        owing.fold(new ConversationEvent.ToolFinished(id, second, ToolResult.text("b")));
    assertThat(afterSecond.remember()).isEmpty();
    assertThat(afterSecond.effects()).isEmpty();
    assertThat(afterSecond.state().pendingCalls()).containsExactly(first);

    Step afterFirst =
        afterSecond
            .state()
            .fold(new ConversationEvent.ToolFinished(id, first, ToolResult.text("a")));
    assertThat(afterFirst.state().pendingCalls()).isEmpty();
    assertThat(afterFirst.state().status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(afterFirst.remember()).hasSize(1); // the batched results message
    assertThat(afterFirst.effects()).containsExactly(Effect.callModel());
  }

  @Test
  void anErroredResultGrowsTheStreakAndASuccessResetsIt() {
    ToolCall first = new ToolCall("call-1", "search", "{}");
    ToolCall second = new ToolCall("call-2", "fetch", "{}");
    ConversationState owing = midHomework(first, second);

    ConversationState afterError =
        owing.fold(new ConversationEvent.ToolFinished(id, first, ToolResult.error("boom")))
            .state();
    assertThat(afterError.consecutiveErrors()).isEqualTo(1);

    ConversationState afterSuccess =
        afterError
            .fold(new ConversationEvent.ToolFinished(id, second, ToolResult.text("ok")))
            .state();
    assertThat(afterSuccess.consecutiveErrors()).isZero();
  }

  // --- ModelCallFailed ---

  @Test
  void aFailedCallIsFateNotData() {
    Step step =
        awaitingModel().fold(new ConversationEvent.ModelCallFailed(id, "context window exceeded"));
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).isEqualTo("context window exceeded");
    assertThat(step.remember()).isEmpty();
    assertThat(step.effects()).isEmpty();
  }

  // --- halted ---

  @Test
  void haltingMidHomeworkAnswersEveryOutstandingCall() {
    ToolCall owed = new ToolCall("call-1", "search", "{}");
    ConversationState owing = midHomework(owed);

    Step step = owing.halted("hit the error ceiling");

    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.state().failureReason()).isEqualTo("hit the error ceiling");
    assertThat(step.state().pendingCalls()).isEmpty();
    assertThat(step.remember()).hasSize(1); // abandoned-results flush
    assertThat(step.effects()).isEmpty();
  }

  @Test
  void haltingWithNoDebtRemembersNothing() {
    Step step = awaitingModel().halted("turn ceiling");
    assertThat(step.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(step.remember()).isEmpty();
  }

  // --- helpers ---

  private ConversationState awaitingModel() {
    return fresh.fold(ConversationEvent.AgentTold.of(id, "go")).state();
  }

  private ConversationState midHomework(ToolCall... calls) {
    List<org.jwcarman.nessy.api.message.ContentBlock> blocks =
        java.util.Arrays.stream(calls)
            .map(call -> (org.jwcarman.nessy.api.message.ContentBlock) new ToolUseBlock(call))
            .toList();
    return awaitingModel()
        .fold(
            new ConversationEvent.ModelResponded(
                id, Message.assistant(blocks), StopReason.TOOL_USE, usage(1)))
        .state();
  }

  private static Usage usage(long inputTokens) {
    return new Usage(inputTokens, 0);
  }
}
```

(Adapt `ToolCall`, `Usage`, `Message`, `ToolResult` construction to the real record shapes — read each briefly; the semantics asserted are fixed, the constructors are whatever exists. Inline imports shown awkwardly qualified should become normal single-symbol imports.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw -q -pl nessy-core test -Dtest=ConversationStateFoldTest`
Expected: COMPILATION ERROR — no `fold` on `ConversationState`.

- [ ] **Step 4: Implement fold on ConversationState.** Port semantics from `Reducer` (`agentTold`, `modelTurnEnded`, `toolFinished`, `abandonPendingCalls`, `flushResults`, `removeFirstMatch`) minus deltas/compaction/approval/message-list maintenance. Add to `ConversationState` (imports: `ConversationEvent`, `StopReason`, `Effect`, `Step`, `ToolUseBlock`, `ToolResultBlock`, `Optional`):

```java
/**
 * The fold: one fact in, the next state plus its message births and effects out. The whole of the
 * agent's semantics — pure, deterministic, exhaustive over the fact grammar. The misdelivery
 * guard runs first: a fact addressed to one conversation can never fold into another's state.
 */
public Step fold(ConversationEvent event) {
  if (!event.conversationId().equals(id)) {
    throw new IllegalArgumentException(
        "misdelivered fact: event for " + event.conversationId() + " folded into " + id);
  }
  return switch (event) {
    case ConversationEvent.AgentTold e -> told(e);
    case ConversationEvent.ModelResponded e -> modelResponded(e);
    case ConversationEvent.ModelCallFailed e -> modelCallFailed(e);
    case ConversationEvent.ToolFinished e -> toolFinished(e);
    // Scaffolding until the cutover (plan 2026-08-11, Task 9): legacy variants are
    // never fed to the fold — only the legacy reducer ever sees them.
    case ConversationEvent.TextDelta e -> throw legacy(e);
    case ConversationEvent.ThinkingDelta e -> throw legacy(e);
    case ConversationEvent.ThinkingSigned e -> throw legacy(e);
    case ConversationEvent.RedactedThinkingArrived e -> throw legacy(e);
    case ConversationEvent.ToolCallRequested e -> throw legacy(e);
    case ConversationEvent.ModelTurnEnded e -> throw legacy(e);
    case ConversationEvent.ApprovalDecided e -> throw legacy(e);
    case ConversationEvent.Compacted e -> throw legacy(e);
    case ConversationEvent.CompactionSkipped e -> throw legacy(e);
  };
}

private static IllegalStateException legacy(ConversationEvent event) {
  return new IllegalStateException("legacy event fed to the fold: " + event);
}

/** A tell starts a fresh error streak: a new instruction is not part of the previous failure. */
private Step told(ConversationEvent.AgentTold event) {
  ConversationState next =
      withConsecutiveErrors(0).withFailureReason(null).with(ConversationStatus.AWAITING_MODEL);
  return Step.of(next, List.of(Message.user(event.content())), Effect.callModel());
}

/**
 * The model's settled contribution folds in: account the call, remember the message, then decide
 * — fatal stop reason fails (answering any homework the truncated message opened), no homework
 * completes, homework fans out one effect per call.
 */
private Step modelResponded(ConversationEvent.ModelResponded event) {
  List<ToolCall> calls =
      event.message().content().stream()
          .filter(ToolUseBlock.class::isInstance)
          .map(block -> ((ToolUseBlock) block).call())
          .toList();
  ConversationState accounted =
      withTurns(turns + 1)
          .withUsage(usage.plus(event.usage()))
          .withPendingCalls(calls);
  Optional<String> fatal = fatalStop(event.reason());
  if (fatal.isPresent()) {
    Step closed = accounted.halted(fatal.get());
    List<Message> remember = new ArrayList<>();
    remember.add(event.message());
    remember.addAll(closed.remember());
    return new Step(closed.state(), remember, List.of());
  }
  if (calls.isEmpty()) {
    return Step.of(
        accounted.with(ConversationStatus.COMPLETE), List.of(event.message()));
  }
  List<Effect> effects = calls.stream().map(call -> (Effect) new Effect.ExecuteTool(call)).toList();
  return new Step(
      accounted.with(ConversationStatus.EXECUTING_TOOL), List.of(event.message()), effects);
}

/** Fate, not data: no party remains in the dialogue to read a failed call. */
private Step modelCallFailed(ConversationEvent.ModelCallFailed event) {
  return Step.of(withFailureReason(event.reason()).with(ConversationStatus.FAILED));
}

/**
 * One piece of homework settles. Results arrive in any order; the flush waits for the last one,
 * because providers require every result for a turn to arrive together in the following message.
 */
private Step toolFinished(ConversationEvent.ToolFinished event) {
  List<ToolResultBlock> results = new ArrayList<>(pendingResults);
  results.add(
      new ToolResultBlock(event.call().id(), event.result().content(), event.result().isError()));
  List<ToolCall> remaining = new ArrayList<>(pendingCalls);
  removeFirstMatch(remaining, event.call().id());
  int errors = event.result().isError() ? consecutiveErrors + 1 : 0;
  ConversationState next =
      withPendingResults(results).withPendingCalls(remaining).withConsecutiveErrors(errors);
  if (!remaining.isEmpty()) {
    return Step.of(next);
  }
  Message flush = Message.toolResults(List.copyOf(results));
  return Step.of(
      next.withPendingResults(List.of()).with(ConversationStatus.AWAITING_MODEL),
      List.of(flush),
      Effect.callModel());
}

/**
 * The closure every failing path owes: answer outstanding homework with abandoned-error results
 * and flush, so the record never holds a tool_use without its tool_result, then fail with the
 * reason. Consulted by the loop when the termination policy halts, and reused by fatal stop
 * reasons.
 */
public Step halted(String reason) {
  ConversationState failed = withFailureReason(reason).with(ConversationStatus.FAILED);
  if (pendingCalls.isEmpty() && pendingResults.isEmpty()) {
    return Step.of(failed);
  }
  List<ToolResultBlock> results = new ArrayList<>(pendingResults);
  for (ToolCall pending : pendingCalls) {
    results.add(
        new ToolResultBlock(
            pending.id(), "Abandoned: the conversation failed before this tool ran.", true));
  }
  Message flush = Message.toolResults(List.copyOf(results));
  return Step.of(
      failed.withPendingCalls(List.of()).withPendingResults(List.of()), List.of(flush));
}

private static Optional<String> fatalStop(StopReason reason) {
  return switch (reason) {
    case MAX_TOKENS -> Optional.of("model hit the token ceiling (MAX_TOKENS)");
    case REFUSAL -> Optional.of("model refused to continue (REFUSAL)");
    case END_TURN, TOOL_USE -> Optional.empty();
  };
}

private static void removeFirstMatch(List<ToolCall> calls, String callId) {
  for (int i = 0; i < calls.size(); i++) {
    if (calls.get(i).id().equals(callId)) {
      calls.remove(i);
      return;
    }
  }
}
```

Note: `ToolUseBlock` — confirm its component is `call` (`new ToolUseBlock(event.call())` in the old reducer says yes). `api` must not import from `spi`: `Effect` and `Step` are still in `spi` until Task 9 moves them — **if `ZoneBoundariesTest` forbids api→spi now, move `Effect.java` and `Step.java` to `org.jwcarman.nessy.api.conversation` in THIS task instead** (mechanical move; old `Reducer`/`InProcessEngine` just update imports). Check `ZoneBoundariesTest` first and do whichever keeps it green — the end state (Task 9+) has them in `api.conversation` regardless.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -q -pl nessy-core test`
Expected: PASS (fold tests green; every legacy test untouched and green).

- [ ] **Step 6: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-core
git commit -m "feat: the fold comes home — ConversationState learns its own transitions"
```

---

### Task 6: ModelCallExecutor — stream in, one fact out

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/model/ContextOverflowException.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ModelCallExecutor.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ProviderModelCallExecutor.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/execute/ProviderModelCallExecutorTest.java`

**Interfaces:**
- Consumes: `Memory.recall(id)` (Task 3), `TurnEvent`/`TurnObserver` (Task 1), `ModelResponded`/`ModelCallFailed` (Task 4); existing `ModelProvider.stream(ModelRequest)`, `ModelEvent` (variants `TextChunk`, `ThinkingChunk`, `ThinkingSigned`, `RedactedThinkingEmitted`, `ToolUseEmitted`, `TurnEnded`), `ModelSettings`, `ToolRegistry.specs()`, `Awaited`.
- Produces: `ModelCallExecutor { Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer); }`; `ProviderModelCallExecutor(ModelProvider provider, ModelSettings config, ToolRegistry tools, Memory memory)`; `ContextOverflowException extends RuntimeException`. Tasks 8, 9 depend on these.

- [ ] **Step 1: Write the failing test.** Build a small scripted fake provider inline (core cannot depend on nessy-testing — that would cycle):

```java
package org.jwcarman.nessy.spi.execute;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.tool.DefaultToolRegistry;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.memory.ListMemory;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.junit.jupiter.api.Test;

class ProviderModelCallExecutorTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState state = ConversationState.newConversation(id);
  private final ListMemory memory = new ListMemory();
  private final List<TurnEvent> observed = new ArrayList<>();

  @Test
  void mergesDeltasIntoOneSettledMessageAndYieldsOneFact() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ThinkingChunk("let me"),
            new ModelEvent.ThinkingChunk(" think"),
            new ModelEvent.TextChunk("hel"),
            new ModelEvent.TextChunk("lo"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2)));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content())
        .containsExactly(new ThinkingBlock("let me think", ""), new TextBlock("hello"));
    assertThat(fact.reason()).isEqualTo(StopReason.END_TURN);
  }

  @Test
  void narratesDeltasToTheObserverAsTheyArrive() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.TextChunk("hi"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));

    executor.execute(state, observed::add);

    assertThat(observed).isNotEmpty();
    assertThat(observed.getFirst()).isEqualTo(new TurnEvent.TextDelta("hi"));
  }

  @Test
  void narratesRequestedHomeworkMidStream() {
    ToolCall call = new ToolCall("call-1", "search", "{}");
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ToolUseEmitted(call),
            new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    assertThat(observed).contains(new TurnEvent.ToolCallRequested(call));
    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content()).hasSize(1); // the tool-use block rides the message
  }

  @Test
  void recallsTheContextFromMemoryNotFromState() {
    // remember something; the fake provider asserts the request context matches the recall
    memory.remember(id, org.jwcarman.nessy.api.message.Message.user(List.of(new TextBlock("hi"))));
    List<ModelRequest> seen = new ArrayList<>();
    ProviderModelCallExecutor executor =
        new ProviderModelCallExecutor(
            recordingProvider(
                seen,
                new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
            settings(),
            new DefaultToolRegistry(List.of()),
            memory);

    executor.execute(state, observed::add);

    assertThat(seen).hasSize(1);
    assertThat(seen.getFirst().context().messages())
        .isEqualTo(memory.recall(id).messages());
  }

  @Test
  void contextOverflowBecomesTheFailureFactNotAnException() {
    ProviderModelCallExecutor executor =
        new ProviderModelCallExecutor(
            request -> {
              throw new org.jwcarman.nessy.spi.model.ContextOverflowException("too long");
            },
            settings(),
            new DefaultToolRegistry(List.of()),
            memory);

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelCallFailed fact =
        (ConversationEvent.ModelCallFailed) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.reason()).contains("too long");
  }

  // --- fakes ---

  private ProviderModelCallExecutor executorStreaming(ModelEvent... events) {
    return new ProviderModelCallExecutor(
        recordingProvider(new ArrayList<>(), events),
        settings(),
        new DefaultToolRegistry(List.of()),
        memory);
  }

  private static ModelProvider recordingProvider(List<ModelRequest> seen, ModelEvent... events) {
    return request -> {
      seen.add(request);
      Iterator<ModelEvent> iterator = List.of(events).iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return iterator;
        }

        @Override
        public void close() {}
      };
    };
  }

  private static ModelSettings settings() {
    return ModelSettings.defaults(); // use the real construction — see ModelSettings/AgentBuilder
  }
}
```

(Adapt fakes to the real `ModelProvider`/`ModelStream`/`ModelSettings`/`DefaultToolRegistry` shapes — read each first; `EngineFixtures` in `nessy-core/src/test` shows exactly how existing tests build them. Reuse its patterns, not nessy-testing.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=ProviderModelCallExecutorTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement.** `ContextOverflowException`:

```java
package org.jwcarman.nessy.spi.model;

/**
 * The request cannot fit the model's context window — a conversation-shaped, permanent rejection.
 * Providers throw this (in place of their raw 400) when the wire says the prompt is too long; the
 * model-call executor converts it into the {@code ModelCallFailed} fact. Every other provider
 * failure stays an ordinary exception: transient, re-drivable, telemetry's business.
 */
public class ContextOverflowException extends RuntimeException {

  public ContextOverflowException(String message) {
    super(message);
  }
}
```

`ModelCallExecutor`:

```java
package org.jwcarman.nessy.spi.execute;

import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Performs one {@code CallModel} effect: recalls the context, calls the model, narrates the
 * stream's texture to the observer, and yields exactly one fact — {@code ModelResponded} or
 * {@code ModelCallFailed}. This generation never parks ({@code Awaited.Parked} is reserved for a
 * future batch-call executor); implementations return {@code Ready}.
 */
public interface ModelCallExecutor {

  Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer);
}
```

`ProviderModelCallExecutor` — port stream-consumption from `InProcessEngine.streamModelTurn` and delta-merging from the old reducer's `textDelta`/`thinkingDelta`/`thinkingSigned`/`redactedThinkingArrived` into a private accumulator:

```java
package org.jwcarman.nessy.spi.execute;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ContextOverflowException;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The default {@code CallModel} performance: recall from Memory, stream from the provider, merge
 * deltas into settled blocks (a hundred chunks become one block), narrate texture as it arrives,
 * and yield the one settled fact. Message construction lives here and nowhere else on the model
 * side: facts are what happened; this is where what happened is assembled.
 */
public final class ProviderModelCallExecutor implements ModelCallExecutor {

  private final ModelProvider provider;
  private final ModelSettings config;
  private final ToolRegistry tools;
  private final Memory memory;

  public ProviderModelCallExecutor(
      ModelProvider provider, ModelSettings config, ToolRegistry tools, Memory memory) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
  }

  @Override
  public Awaited<ConversationEvent> execute(ConversationState state, TurnObserver observer) {
    ModelRequest request;
    try {
      request =
          new ModelRequest(
              memory.recall(state.id()),
              config.systemPrompt(),
              config.model(),
              config.maxTokens(),
              tools.specs(),
              config.capabilities(),
              null);
    } catch (ContextOverflowException e) {
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), e.getMessage()));
    }
    List<ContentBlock> blocks = new ArrayList<>();
    try (ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String text) -> {
            observer.on(new TurnEvent.TextDelta(text));
            mergeText(blocks, text);
          }
          case ModelEvent.ThinkingChunk(String text) -> {
            observer.on(new TurnEvent.ThinkingDelta(text));
            mergeThinking(blocks, text);
          }
          case ModelEvent.ThinkingSigned(String signature) -> sign(blocks, signature);
          case ModelEvent.RedactedThinkingEmitted(String data) -> {
            observer.on(new TurnEvent.RedactedThinking(data));
            blocks.add(new RedactedThinkingBlock(data));
          }
          case ModelEvent.ToolUseEmitted(var call) -> {
            observer.on(new TurnEvent.ToolCallRequested(call));
            blocks.add(new ToolUseBlock(call));
          }
          case ModelEvent.TurnEnded(var reason, var usage) -> {
            return Awaited.ready(
                new ConversationEvent.ModelResponded(
                    state.id(), Message.assistant(List.copyOf(blocks)), reason, usage));
          }
        }
      }
    } catch (ContextOverflowException e) {
      return Awaited.ready(new ConversationEvent.ModelCallFailed(state.id(), e.getMessage()));
    }
    throw new IllegalStateException("model stream ended without a TurnEnded event");
  }

  /** Merges a chunk into the trailing text block: a hundred deltas become one block. */
  private static void mergeText(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock(String existing)) {
      blocks.set(blocks.size() - 1, new TextBlock(existing + text));
    } else {
      blocks.add(new TextBlock(text));
    }
  }

  /**
   * Merges a chunk into the trailing unsigned thinking block. A signed block is closed: its
   * signature covers its exact text, so a later delta starts a fresh block.
   */
  private static void mergeThinking(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty()
        && blocks.getLast() instanceof ThinkingBlock(String existing, String signature)
        && signature.isEmpty()) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(existing + text, ""));
    } else {
      blocks.add(new ThinkingBlock(text, ""));
    }
  }

  /** Lands a signature on the trailing thinking block; a no-op when nothing trails to sign. */
  private static void sign(List<ContentBlock> blocks, String signature) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock(String text, String _)) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(text, signature));
    }
  }
}
```

(Adapt `ModelRequest` construction to the real constructor — `InProcessEngine.requestFor` shows the argument order. `ModelEvent.ToolUseEmitted`'s component type is `ToolCall`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q -pl nessy-core test -Dtest=ProviderModelCallExecutorTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-core
git commit -m "feat: ModelCallExecutor — stream in, texture sideways, one fact out"
```

---

### Task 7: ToolCallExecutor + EffectExecutors — the gate travels with the act

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ToolCallExecutor.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/GatedToolCallExecutor.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/EffectExecutors.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/execute/GatedToolCallExecutorTest.java`

**Interfaces:**
- Consumes: `ToolResolution` (Task 2), `TurnEvent`/`TurnObserver` (Task 1); existing `ToolRegistry`, `ToolGrant`, `UsagePolicy`, `PolicyDecision`, `Approver` (`Awaited<Decision> approve(ApprovalRequest)`), `internal.ToolInvoker`, `Decision`, `ToolResult`, `api.event.EventEmitter` (for `ApprovalRequested` — see step 3 note).
- Produces:
  - `ToolCallExecutor { Awaited<ConversationEvent> execute(ToolCall call, ConversationState state, TurnObserver observer); Awaited<ConversationEvent> resume(ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer); }`
  - `GatedToolCallExecutor(ToolRegistry tools, Map<String, ToolGrant> grants, Approver approver, ObjectMapper mapper, EventEmitter emitter)` — constructor validates every registered tool has a grant (port `requireEveryRegisteredToolIsGranted` from `InProcessEngine`).
  - `record EffectExecutors(ModelCallExecutor callModel, ToolCallExecutor toolCall)` with null-checking compact constructor.
  Tasks 8, 9 depend on these.

- [ ] **Step 1: Write the failing test.** Cover, in prose style: policy-allow runs the tool without consulting the approver; policy-deny yields a denial `ToolFinished` (errored, reason in content) without consulting the approver; `RequireApproval` consults the approver — allow runs, deny yields denial; an unknown tool yields the one model-visible "No such tool" error; a registered-but-ungranted call (exotic registry) is denied; a throwing tool becomes an errored result, not an exception; a throwing policy fails closed to denial; the observer hears `ToolCallDecided` then `ToolCallCompleted` in order; an approver returning `Awaited.parked(token)` makes `execute` return `Awaited.Parked`; `resume` with `Decided(allow)` invokes and yields the result, `Decided(deny)` yields denial, `Completed(result)` yields that result directly. Build fakes by hand: an `Approver` recording invocations, a trivial `Tool` (copy the pattern from `EngineFixtures` / `ToolRegistryTest`). Port assertion scenarios from `InProcessEngineTest`'s approval/tool sections — same semantics, new seam.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=GatedToolCallExecutorTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement.** Port from `InProcessEngine`: `decide` (grant lookup + `evaluate` fail-closed policy), `requestApproval` (approval flow — keep the `EngineObservations.approvalWait` span), `executeTool`/`invokeAndRecord` (invoke + Factor-9 error capture + `describe`), `describeForApproval`. Shape:

```java
public Awaited<ConversationEvent> execute(
    ToolCall call, ConversationState state, TurnObserver observer) {
  ToolGrant grant = grants.get(call.name());
  if (grant == null) {
    if (tools.find(call.name()).isPresent()) {
      return finished(call, state, ToolResult.error("no grant for tool: " + call.name()), observer,
          new Decision.Deny("no grant for tool: " + call.name()));
    }
    return invoke(call, state, observer); // unknown tool: invoke() yields "No such tool" error
  }
  PolicyDecision decision = evaluate(grant.policy(), call, state);
  return switch (decision) {
    case PolicyDecision.Allow _ -> {
      observer.on(new TurnEvent.ToolCallDecided(call, Decision.allow()));
      yield invoke(call, state, observer);
    }
    case PolicyDecision.Deny(String reason) ->
        finished(call, state, ToolResult.error("Denied: " + reason), observer,
            new Decision.Deny(reason));
    case PolicyDecision.RequireApproval _ -> gate(grant, call, state, observer);
  };
}
```

where `gate` emits an `ApprovalRequested` system event on the emitter, consults the approver inside the `approvalWait` observation, and on `Awaited.Ready(decision)` proceeds like Allow/Deny above; on `Awaited.Parked(token)` returns `Awaited.parked(token)`. `finished(...)` narrates `ToolCallDecided` (when a gate verdict exists) and `ToolCallCompleted`, then returns `Awaited.ready(new ConversationEvent.ToolFinished(state.id(), call, result))`. `invoke(...)` runs `ToolInvoker.invoke` inside the `toolCall` observation with the old engine's catch-and-describe, narrates `ToolCallCompleted`, and returns the `ToolFinished` fact. `resume(...)`:

```java
public Awaited<ConversationEvent> resume(
    ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer) {
  return switch (resolution) {
    case ToolResolution.Decided(Decision decision) ->
        switch (decision) {
          case Decision.Allow _ -> {
            observer.on(new TurnEvent.ToolCallDecided(call, decision));
            yield invoke(call, state, observer);
          }
          case Decision.Deny(String reason) ->
              finished(call, state, ToolResult.error("Denied: " + reason), observer, decision);
        };
    case ToolResolution.Completed(ToolResult result) ->
        finished(call, state, result, observer, null);
  };
}
```

`ApprovalRequested` system event: check `api/approval/ApprovalRequest` and the listener family — if no `ApprovalRequested` event record exists in `api.event`, create one (`record ApprovalRequested(ConversationId conversationId, ApprovalRequest request) implements ConversationScoped`) beside `ToolProgress`, and emit it. A tool that parks is NOT an error at this seam anymore — parking is the executor contract; the *loop* decides tolerance (Task 8).

`EffectExecutors`:

```java
package org.jwcarman.nessy.spi.execute;

import java.util.Objects;

/**
 * The "registry" of effect performers — typed, one slot per effect family, completeness enforced
 * by the compiler. No map, no class keys, no runtime lookup that can miss: adding an Effect
 * variant breaks this record's construction sites, which is the point.
 */
public record EffectExecutors(ModelCallExecutor callModel, ToolCallExecutor toolCall) {

  public EffectExecutors {
    Objects.requireNonNull(callModel, "callModel must not be null");
    Objects.requireNonNull(toolCall, "toolCall must not be null");
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q -pl nessy-core test -Dtest=GatedToolCallExecutorTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-core
git commit -m "feat: GatedToolCallExecutor — there is no door that isn't the gate"
```

---

### Task 8: ConversationLoop — the invariant loop, written once

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/internal/ConversationLoopTest.java`

**Interfaces:**
- Consumes: `ConversationState.fold`/`halted` (Task 5), `EffectExecutors` (Task 7), `Memory` (Task 3), `TurnObserver` (Task 1), `ToolResolution` (Task 2); existing `TerminationPolicy`, `ConversationStore`, `EventEmitter`, `ObservationRegistry`, `EngineObservations.run`, `RunOutcome`, `ParkToken`, `Effect`, `Step`.
- Produces:
  - `ConversationLoop(EffectExecutors executors, Memory memory, TerminationPolicy termination, ConversationStore store, EventEmitter emitter, ObservationRegistry observations)`
  - `RunOutcome run(ConversationId id, ConversationEvent.AgentTold input, TurnObserver observer)`
  - `RunOutcome resume(ConversationId id, ParkToken token, ToolResolution resolution, TurnObserver observer)` — throws `UnsupportedOperationException` this generation (the in-process assembly refuses parks; the signature is the seam for the durable generation).
  Task 9 depends on these exact signatures.

- [ ] **Step 1: Write the failing test.** Fake executors and an in-memory store make every ordering law directly assertable. Record a global event journal (strings like `"fold"`, `"remember:user"`, `"emit:AgentTold"`, `"save"`, `"execute:call-1"`) via fakes that append to one list. Cover, in prose style:

  - a tell with a clean scripted response completes: facts emitted in order (`AgentTold`, `ModelResponded`), Memory told the user message then the assistant message, final status `COMPLETE`, `RunOutcome.Completed`;
  - homework round-trip: `ModelResponded` with two calls → tool executor invoked for each in order → results fold → flush message remembered once, after both → second model call → `COMPLETE`;
  - **consult-after-every-fold**: a `TerminationPolicy` counting consultations equals the number of folds;
  - a halting policy (e.g. `maxConsecutiveErrors(1)` with an erroring fake tool executor) leaves `FAILED` with the reason, unperformed effects discarded (the fake model executor is NOT called again after the halt), abandoned-results flush remembered;
  - **remember-before-emit-before-save-before-perform**: assert the journal's relative order for one fold cycle;
  - **persist-on-every-exit**: a fake model executor that throws mid-turn still leaves the store holding the last folded state (try/finally law, port of the old progress-holder contract);
  - §6 refusal: `run` on a store whose state has status `EXECUTING_TOOL` throws, naming the status; `run` on `COMPLETE`/`FAILED`/`IDLE` proceeds;
  - a parking tool executor (returns `Awaited.parked(token)`) makes `run` throw `UnsupportedOperationException` naming the tool — the in-process refusal, ported from the old engine;
  - `resume` throws `UnsupportedOperationException`;
  - `ModelCallFailed` from the executor folds to `FAILED` and the loop stops.

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl nessy-core test -Dtest=ConversationLoopTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement:**

```java
package org.jwcarman.nessy.internal;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.TerminationPolicy;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Effect;
import org.jwcarman.nessy.spi.Step;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.execute.EffectExecutors;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * The invariant loop — the fold→perform cycle, written once, owned by the core. Engines do not
 * exist anymore; this is the machinery every assembly shares, varying only in the executors,
 * memory, store, and policy handed to it.
 *
 * <p>The cycle, per fact: ask the state to fold it; consult the termination policy (after every
 * fold — a law, not a list of check sites); tell Memory the fold's message births; emit the fact
 * on the system channel; persist; then perform the emitted effects, each yielding the next fact.
 * A halt discards unperformed effects — intents, not obligations — and applies the closure
 * transition {@code halted(reason)}.
 *
 * <p>Durability: the most recent state is saved on every exit path, including exceptions — the
 * progress-holder contract. Parks: this generation refuses them loudly (there is nowhere to park
 * to); {@link #resume} is the seam where the durable generation lands.
 */
public final class ConversationLoop {

  private static final Set<ConversationStatus> RESUMABLE =
      Set.of(ConversationStatus.IDLE, ConversationStatus.COMPLETE, ConversationStatus.FAILED);

  private final EffectExecutors executors;
  private final Memory memory;
  private final TerminationPolicy termination;
  private final ConversationStore store;
  private final EventEmitter emitter;
  private final ObservationRegistry observations;

  public ConversationLoop(
      EffectExecutors executors,
      Memory memory,
      TerminationPolicy termination,
      ConversationStore store,
      EventEmitter emitter,
      ObservationRegistry observations) {
    this.executors = Objects.requireNonNull(executors, "executors must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.termination = Objects.requireNonNull(termination, "termination must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /** Runs one segment to completion. See the §6 resume-refusal contract for the status guard. */
  public RunOutcome run(
      ConversationId id, ConversationEvent.AgentTold input, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    Observation observation = EngineObservations.run(observations, id);
    try (var _ = observation.openScope()) {
      ConversationState loaded =
          store.load(id).orElseGet(() -> ConversationState.newConversation(id));
      if (!RESUMABLE.contains(loaded.status())) {
        throw new IllegalStateException(
            "conversation " + id + " is in flight (" + loaded.status() + "); refusing to run");
      }
      AtomicReference<ConversationState> progress = new AtomicReference<>(loaded);
      try {
        return new RunOutcome.Completed(drive(progress, input, observer));
      } finally {
        store.save(progress.get());
      }
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  public RunOutcome resume(
      ConversationId id, ParkToken token, ToolResolution resolution, TurnObserver observer) {
    throw new UnsupportedOperationException(
        "this assembly never parks, so there is nothing to resume");
  }

  private ConversationState drive(
      AtomicReference<ConversationState> progress,
      ConversationEvent first,
      TurnObserver observer) {
    ConversationState state = progress.get();
    Deque<Effect> queue = new ArrayDeque<>();
    ConversationEvent fact = first;
    while (true) {
      Step step = state.fold(fact);
      state = step.state();
      Optional<String> halt = termination.shouldHalt(state);
      if (halt.isPresent()) {
        Step closed = state.halted(halt.get());
        state = closed.state();
        progress.set(state);
        remember(state.id(), step.remember());
        remember(state.id(), closed.remember());
        emitter.emit(fact);
        store.save(state);
        return state;
      }
      progress.set(state);
      remember(state.id(), step.remember());
      emitter.emit(fact);
      store.save(state);
      step.effects().forEach(queue::addLast);
      if (queue.isEmpty()) {
        return state;
      }
      fact = perform(queue.pollFirst(), state, observer);
    }
  }

  private void remember(ConversationId id, java.util.List<Message> births) {
    births.forEach(message -> memory.remember(id, message));
  }

  private ConversationEvent perform(
      Effect effect, ConversationState state, TurnObserver observer) {
    Awaited<ConversationEvent> outcome =
        switch (effect) {
          case Effect.CallModel _ -> executors.callModel().execute(state, observer);
          case Effect.ExecuteTool(var call) -> executors.toolCall().execute(call, state, observer);
          // Scaffolding until the cutover (Task 9): the fold never emits these.
          case Effect.RequestApproval e ->
              throw new IllegalStateException("legacy effect reached the loop: " + e);
          case Effect.Compact e ->
              throw new IllegalStateException("legacy effect reached the loop: " + e);
        };
    return switch (outcome) {
      case Awaited.Ready<ConversationEvent>(ConversationEvent value) -> value;
      case Awaited.Parked<ConversationEvent> _ ->
          throw new UnsupportedOperationException(
              "this assembly cannot park, but performing " + effect + " asked to");
    };
  }
}
```

(Adjust the two scaffold arms to whatever `Effect`'s legacy variants are still named; qualify the `List` import properly. If `EngineObservations.run` has a different name/signature, use the real one.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q -pl nessy-core test -Dtest=ConversationLoopTest`
Expected: PASS

- [ ] **Step 5: Full verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-core
git commit -m "feat: ConversationLoop — the fold-perform cycle, written once"
```

---

### Task 9: The cutover — facade rewires, old machinery dies

Everything new exists and is tested; this task swaps the wiring and deletes the old world. Large but almost entirely deletion and rewiring; run `./mvnw -q clean verify` at the REACTOR ROOT before committing.

**Files (delete, main):**
- `nessy-core/.../spi/ExecutionEngine.java`, `spi/InProcessEngine.java`, `spi/Reducer.java`
- `spi/compaction/` (whole package: `Compactor`, `Compactors`, `ProviderSummarizer`, `Summarizer`, `SummarizingCompaction`, `WindowCompaction`)
- `spi/context/` (whole package: `ContextEnricher`, `ContextPipeline`, `Projection`)
- `api/event/MessageAppended.java`, `api/event/CompactionFailed.java`, `api/event/EnrichmentFailed.java`
- `Reply.java`

**Files (delete, test):**
- `spi/ReducerGrammarTest`, `ReducerTextTest`, `ReducerToolCallTest`, `ReducerToolResultTest`, `ReducerCompactionTest`, `InProcessEngineTest`, `InProcessEngineCompactionTest`, `InProcessEngineEnrichmentTest`, `InProcessEngineObservationTest`, `EngineFixtures` (fold any still-useful fixture helpers into the new executor/loop tests first), `spi/compaction/*Test` (4), `spi/context/*Test` (2), `ReplyTest`
- `nessy-testing/.../ScriptedSummarizer.java`

**Files (modify):**
- `api/ConversationEvent.java` — delete variants `TextDelta`, `ThinkingDelta`, `ThinkingSigned`, `RedactedThinkingArrived`, `ToolCallRequested`, `ModelTurnEnded`, `ApprovalDecided`, `Compacted`, `CompactionSkipped`; delete the fold's nine legacy scaffold arms in `ConversationState.fold`.
- `spi/Effect.java` — delete `RequestApproval` and `Compact` (+ singleton + factory); delete the loop's two scaffold arms. Move `Effect.java` and `Step.java` to `org.jwcarman.nessy.api.conversation` (if not already moved in Task 5); update every import.
- `Agent.java`, `AgentBuilder.java` — replace engine assembly with: default `Memory` = `new ListMemory()` (add `.memory(Memory)` builder option); build `ProviderModelCallExecutor` + `GatedToolCallExecutor` + `EffectExecutors` + `ConversationLoop`. Remove `.compactor`/`.summarizer`/enrichment/projection builder options and their default assembly (`Compactors.window`, `Summarizer.usingProvider`). Keep `.termination`, `.approver`, tool/grant registration, listener registration, renderer.
- `Conversation.java` — now wraps `ConversationLoop`: `RunOutcome tell(I input)` (observer = `TurnObserver.noop()`) and `RunOutcome tell(I input, TurnObserver observer)`; delete the `Consumer<ConversationEvent> tap` overload (fact-tapping stays available via `events()`); `events()` unchanged.
- `Harness.java`, `HarnessBuilder.java`, `Nessy.java` — replace `ExecutionEngine` references with `ConversationLoop`; `HarnessBuilder` gains/keeps nothing else.
- `internal/EngineObservations.java` — delete the `compaction` observation factory; keep `run`, `turn`, `modelCall`, `toolCall`, `approvalWait`, `recordUsage`, `recordOutcome` (executors and loop use them).
- `spi/conversation/JsonMessageCodec.java` / `MessageCodec.java` — grep usages; if only dead code referenced them, delete + their test; if `InMemoryConversationStore` or serialization tests use them, keep.

**Test migrations (modify):**
- `ConversationTest`, `AgentTest`, `AgentBuilderTest`, `HarnessTest`, `HarnessBuilderTest`, `BuildSmokeTest`, `ConversationEventTest`, `ValidationTest`, `ZoneBoundariesTest`, `ObservationDependencyTest` — mechanical: `Reply reply = conversation.tell(x); reply.text()` becomes capturing the last assistant text either from a `TurnObserver` accumulating `TextDelta`s, or from a `RecordingSubscriber`/`events()` subscription on `ConversationEvent.ModelResponded` reading `fact.message()`. Fact-grammar assertions move from old event names to the four facts.
- `nessy-testing/.../AgentFacadeTest`, `EndToEndTest` — same mapping; scripted providers (`ScriptedModelProvider`) emit `ModelEvent`s and are unaffected.
- `nessy-examples/.../AnthropicChat.java`, `OpenAiChat.java` — replace `Reply.text()` printing with a `TurnObserver` that prints `TextDelta`s as they stream (better demo) and a blank line on return.

**Interfaces:**
- Consumes: everything Tasks 1–8 produced.
- Produces: the post-amendment public API — `Conversation.tell(I) → RunOutcome`, `Conversation.tell(I, TurnObserver) → RunOutcome`, `AgentBuilder.memory(Memory)`.

- [ ] **Step 1:** Rewire `AgentBuilder`/`Agent`/`Conversation`/`Harness`/`HarnessBuilder` (list above). Compile: `./mvnw -q -pl nessy-core compile` — fix references until clean.
- [ ] **Step 2:** Delete old main files (list above); delete grammar variants and scaffold arms. Compile again; chase stragglers (`grep -rn "Reducer\|InProcessEngine\|ExecutionEngine\|Compactor\|ContextPipeline\|MessageAppended\|Reply" nessy-core/src/main`).
- [ ] **Step 3:** Delete/migrate core tests (lists above). Run `./mvnw -q -pl nessy-core test` until green.
- [ ] **Step 4:** Migrate nessy-testing and nessy-examples (`grep -rn "Reply\|Compactor\|Summarizer\|MessageAppended" nessy-testing nessy-examples`). Live tests (`AnthropicLiveTest`, `OpenAiLiveTest`) stay excluded by default but must compile.
- [ ] **Step 5:** Reactor verify: `./mvnw -q clean verify` — must pass offline. Fix `ZoneBoundariesTest`/`ObservationDependencyTest` package rules for the new packages (`api.turn`, `spi.memory`, `spi.execute` — api never imports spi; `Effect`/`Step` now live in api).
- [ ] **Step 6: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "feat!: the cutover — two effects, four facts, one loop; the engine retires"
```

---

### Task 10: Slim the state — the control block earns its name

**Files:**
- Modify: `api/conversation/ConversationState.java` — remove components `messages`, `pendingBlocks`, `generation`, `lastInputTokens` and their withers; rename `turns` → `modelCalls` (component, accessor, wither).
- Modify: `api/conversation/ConversationStatus.java` — remove `AWAITING_APPROVAL`, `COMPACTING`.
- Modify: `api/conversation/TerminationPolicy.java` — rename `maxTurns` → `maxModelCalls`, reading `state.modelCalls()`; `defaults()` = `anyOf(maxConsecutiveErrors(3), maxModelCalls(100))`.
- Modify: `api/conversation/ConversationStateTest.java`, `TerminationPolicyTest.java`, `ConversationStateFoldTest.java` — mechanical renames; delete tests of removed withers.
- Grep-and-fix: `grep -rn "\.turns()\|withTurns\|maxTurns\|pendingBlocks\|withMessages\|\.generation()\|lastInputTokens\|AWAITING_APPROVAL\|COMPACTING" --include="*.java" nessy-core nessy-testing nessy-examples` — every hit is either dead (delete) or a rename site.

**Interfaces:**
- Produces: `ConversationState(ConversationId id, List<ToolCall> pendingCalls, List<ToolResultBlock> pendingResults, int consecutiveErrors, int modelCalls, Usage usage, String failureReason, ConversationStatus status)` — the debt lane, the dials, the markers, nothing else.

- [ ] **Step 1:** Update `ConversationStateTest` expectations first (fields gone, renames) — run, verify failures are the expected compile errors.
- [ ] **Step 2:** Apply the record slimming + renames; chase the grep list.
- [ ] **Step 3:** `./mvnw -q clean verify` at reactor root — green, offline.
- [ ] **Step 4: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "refactor!: the control block — state sheds its messages and keeps the debt"
```

---

### Task 11: Docs, CHANGELOG, and conformance sweep

**Files:**
- Modify: `CHANGELOG.md` — entry for the amendment under Unreleased: the two-effect/four-fact grammar, Memory, TurnEvent narration, `tell → RunOutcome`, removals (Reducer, engines, compaction/context seams, Reply, MessageAppended), renames (`turns` → `modelCalls`).
- Modify: `README.md` — update any quickstart snippet that uses `Reply` or removed builder options (grep `Reply\|compactor\|summarizer` in README).
- Modify: `docs/superpowers/specs/2026-08-11-conversation-essence-design.md` — flip **Status: DRAFT** to **Status: IMPLEMENTED (see plan 2026-08-11-conversation-essence)**.

**Steps:**
- [ ] **Step 1:** Write the CHANGELOG entry (match the file's existing voice and structure).
- [ ] **Step 2:** Conformance sweep — for each spec section §2–§10, confirm the code now matches; the checklist:
  - four facts, sealed, no default arms anywhere (`grep -rn "default ->" nessy-core/src/main` → only extender-documented spots, expected none);
  - two effects; `EffectExecutors` record; per-slot signatures;
  - fold on state, parameter-free; `halted` closure; policy consulted by loop after every fold;
  - Memory told exactly three message kinds (assert via `ConversationLoopTest` journal);
  - `TurnEvent` roster matches §8; observer per-entry with noop default;
  - `tell → RunOutcome`; no `ask`; no enqueue semantics;
  - spec's §12 ledger dispositions all true in code.
  Fix any drift found (small diffs expected, e.g. javadoc wording).
- [ ] **Step 3:** Final `./mvnw -q clean verify` at reactor root, offline. 
- [ ] **Step 4: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "docs: the essence ships — changelog, readme, and the spec goes green"
```

---

## Self-review notes (performed at plan time)

- **Spec coverage:** §1 loop/executors → Tasks 7, 8; §2 facts → Tasks 4, 5; §3 effects → Tasks 5, 9; §4 approval fold → Task 7; §5 executor seam + resolution grammar + fan-out → Tasks 2, 5, 7, 8; §6 fold-on-state, termination-as-brake, failure law → Tasks 5, 6, 8; §7 Memory (absorption, idempotency, agent-choice) → Tasks 3, 9 (wiring); §8 TurnEvent narration → Tasks 1, 6, 7; §9 slim state → Task 10; §10 facade `tell` → Task 9; §11/§12 removals → Task 9; §14 testing posture → each task's tests. Park-with-fan-out mechanics and durable resume are explicitly deferred by the spec (plan-level note honored: `resume` signature exists, refuses).
- **Known adaptation points** are marked inline ("adapt to the real factory/constructor") — these are instructions to read a named existing file, never invitations to invent API.
- **Type consistency:** `Awaited<ConversationEvent>` is the executor return everywhere; `Step(state, remember, effects)` construction always via factories; `tell` returns `RunOutcome` in Task 9 and stays that way in Tasks 10–11.
