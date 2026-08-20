# The Agent Speaks (Plan 3 of 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `nessy-agent` talk: a verbatim `Memory`, the completed narration surface (`TurnObserver` adaptation + the parked re-fire/requeue narration), the sync adapter, a real `ModelProvider`-backed model executor, a registry-backed (non-parking) tool executor, and `Nessy.cli().converse("hello")` working end to end.

**Architecture:** Everything lives in `nessy-agent`; **`nessy-core` is not touched** — the bridge executors consume core's existing SPIs (`ModelProvider`/`ModelStream`/`ModelEvent`, `Tool`/`ToolRegistry`, `TurnObserver`/`TurnEvent`) as vocabulary. Executors honor the dispatch-time `Sink` contract via an injected `Executor` (virtual threads in production, pumped in tests).

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ, Jackson. No mocking libraries.

**Spec:** `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` (§4.1 executor-owns-memory, §4.3 non-parking rule, §7 sync adapter, §7.1 cli(), §8 narration)

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no model-provider network access, always. Live tests carry `@Tag("live")` and are excluded by default (`nessy.excludedGroups`).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, then re-stage.
- No `@SuppressWarnings`; no star imports. S5778 (one throwing invocation per `assertThatThrownBy`, setup outside) and S5841 (emptiness before match-predicates).
- Prose test names. No mocking libraries — hand-written doubles only. No thread-based waiting in unit tests (pumped executor); the one integration test (Task 6) may block on `converse` over virtual threads with a fake provider.
- `nessy-core` files must not change. Any diff hunk under `nessy-core/` is a defect.

## Plan-level design decisions (deliberate — do not "fix")

1. **Core untouched; `TurnEvent` reshape deferred to Plan 5.** `TurnEnded` still carries `ConversationStatus` (a §9-deletion type); the adapter emits `COMPLETED`/`FAILED` as interim vocabulary. `ToolCallParked` exists in the grammar but is never emitted by the new executors; its deletion rides the distillation.
2. **This plan's tool executor is §4.3's non-parking interactive executor.** A `Tool` returning `Awaited.Parked` gets `ToolOutcome.Failed("parking is unavailable in this wiring...")` — loud, in-band. The desk arrives in Plan 4.
3. **`ToolContext` is bridged with `new ConversationId(id.value())`** — a temporary crossing into deprecated vocabulary (core's `Tool` SPI demands it); removed when Plan 5 adapts the tool SPI. Ledger-tracked.
4. **`AgentObserver` gains the two parked narration methods** (final-review F3/F9): `reFired(List<Effect>)` and `observationRequeued(Object)`. `DefaultAgent` emits them at the recovery re-fire and the lost-race re-add. This closes the "silent duplicate work" parking from Plan 2.
5. **`RelayTurnObserver` is app-side outlet management, not core machinery** — the CLI's per-turn waiter attaches through a relay because the wiring's observer composition is fixed at construction (§3.5). This is the design's own "the app owns its outlets" pattern.
6. **Executors take an injected `java.util.concurrent.Executor`** (never spawn threads) and deliver via `pump.execute(() -> sink.deliver(...))`-shaped bodies — the §4 async contract, testable with `PumpedExecutor`.
7. **`ModelEvent.TurnEnded(StopReason, Usage)` is consumed and dropped** by the model executor for now — usage metrics ride the observability design, not this plan.

## File Structure

```
nessy-agent/src/main/java/org/jwcarman/nessy/agent/
  memory/VerbatimMemory.java        — remember-all, recall-all in-memory Memory
  narrate/TurnNarrationAdapter.java — AgentObserver → TurnObserver
  narrate/AwaitingReply.java        — sync adapter: last AssistantSaid text, completes on TurnEnded
  model/ProviderModelCallExecutor.java — recall → ModelRequest → stream-accumulate → ModelFinished
  tool/RegistryToolCallExecutor.java   — find → bind args → execute → ToolFinished (non-parking)
  host/Nessy.java                   — cli() builder front door
  host/CliAgent.java                — constant-id instance + blocking converse(String)
  host/RelayTurnObserver.java       — settable per-turn outlet
  spi/AgentObserver.java            — MODIFIED: + reFired, + observationRequeued
  DefaultAgent.java                 — MODIFIED: emits the two new narrations
nessy-agent/src/test/java/org/jwcarman/nessy/agent/
  memory/VerbatimMemoryTest.java
  narrate/TurnNarrationAdapterTest.java
  narrate/AwaitingReplyTest.java
  model/ProviderModelCallExecutorTest.java  (+ support/ScriptedModelProvider.java)
  tool/RegistryToolCallExecutorTest.java
  host/CliAgentTest.java            — fake provider, real virtual threads, real converse
  host/CliLiveSmokeTest.java        — @Tag("live"), env-resolved provider
  support/RecordingTurnObserver.java
  (MODIFIED: support/RecordingObserver.java, DefaultAgentDrainTest.java, DefaultAgentRecoveryTest.java)
nessy-agent/pom.xml                 — MODIFIED: test dep on nessy-model-env (live smoke only)
```

---

### Task 1: `VerbatimMemory`

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/memory/VerbatimMemory.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/memory/VerbatimMemoryTest.java`

**Interfaces:**
- Consumes: `spi.Memory`, core `Message`, `Context` (`Context.of(List<Message>)`, `Context.empty()`).
- Produces: `VerbatimMemory implements Memory` — thread-safe, remembers everything in order, recalls all of it. The cli() builder's default memory.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Message;

class VerbatimMemoryTest {

  @Test
  void aFreshMemoryRecallsAnEmptyContext() {
    assertThat(new VerbatimMemory().recall().messages()).isEmpty();
  }

  @Test
  void rememberedMessagesRecallInOrder() {
    var memory = new VerbatimMemory();
    memory.remember(Message.user("first"));
    memory.remember(Message.user("second"));
    assertThat(memory.recall().messages())
        .containsExactly(Message.user("first"), Message.user("second"));
  }

  @Test
  void recallReturnsASnapshotNotALiveView() {
    var memory = new VerbatimMemory();
    memory.remember(Message.user("one"));
    List<Message> snapshot = memory.recall().messages();
    memory.remember(Message.user("two"));
    assertThat(snapshot).hasSize(1);
  }
}
```

- [ ] **Step 2: Run to verify it fails** — `./mvnw -q -pl nessy-agent test`, COMPILE FAILURE.

- [ ] **Step 3: Implement**

```java
package org.jwcarman.nessy.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * Remembers everything, verbatim, in order — the cli() default (§7.1). Thread-safe because
 * completions arrive on executor threads while the shell commits on others; a synchronized list
 * is entirely adequate at conversation cadence.
 */
public final class VerbatimMemory implements Memory {

  private final List<Message> messages = new ArrayList<>();

  @Override
  public synchronized void remember(Message message) {
    messages.add(Objects.requireNonNull(message, "message must not be null"));
  }

  @Override
  public synchronized Context recall() {
    return Context.of(List.copyOf(messages));
  }
}
```

- [ ] **Step 4: Run to verify it passes.**
- [ ] **Step 5: Format and commit** — `git commit -m "feat: VerbatimMemory — the cli default remembers everything"`

---

### Task 2: The narration surface completes — `reFired`, `observationRequeued`, and the `TurnObserver` adapter

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/AgentObserver.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DefaultAgent.java`
- Modify: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/RecordingObserver.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/narrate/TurnNarrationAdapter.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/RecordingTurnObserver.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/narrate/TurnNarrationAdapterTest.java`
- Modify (add one test each): `DefaultAgentRecoveryTest.java`, `DefaultAgentDrainTest.java`

**Interfaces:**
- Produces: `AgentObserver` gains `void reFired(List<Effect> effects)` and `void observationRequeued(Object observation)` (both added to `noop()` and `RecordingObserver` with accessors `reFires()`, `requeued()`); `TurnNarrationAdapter implements AgentObserver` wrapping a `TurnObserver`; `RecordingTurnObserver implements TurnObserver` collecting `TurnEvent`s (`events()` accessor).

- [ ] **Step 1: Extend `AgentObserver`**

Add to the interface (and as empty bodies in `noop()`):

```java
  /** The recovery arm re-dispatched a stalled phase's outstanding effects (§6.1). */
  void reFired(List<Effect> effects);

  /** An observation lost the idle race and went back to the backlog (§3.3). */
  void observationRequeued(Object observation);
```

(import `java.util.List`.) Extend `RecordingObserver` with two lists + copy-returning accessors, same style as its existing ones.

- [ ] **Step 2: Emit from `DefaultAgent`**

In `drive()`'s stale arm, replace the bare forEach with:

```java
    if (isStale()) {
      List<Effect> outstanding = state.phase().outstandingEffects();
      wiring.observer().reFired(outstanding);
      outstanding.forEach(this::dispatch);
    }
```

In `drain()`'s catch: after `wiring.backlog().add(observation);` add `wiring.observer().observationRequeued(observation);`.

- [ ] **Step 3: Pin both emissions**

Append to `DefaultAgentRecoveryTest`:

```java
  @Test
  void aReFireIsNarrated() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.reFires()).containsExactly(List.of(new Effect.CallModel()));
  }
```

Append to `DefaultAgentDrainTest` (reusing the existing lost-race fixture shape):

```java
  @Test
  void aRequeueIsNarrated() {
    var inner = new InMemoryAgentStateStore();
    var competitorState = new State(new Phase.AwaitingModel(), 0L);
    var f = new AgentFixture(new RaceOnceStore(inner, competitorState), false);
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.requeued()).containsExactly("hello");
  }
```

Run: module tests all green (existing suites compile against the widened interface via `RecordingObserver`/`noop` updates; the `WiringDemo` inline observer needs the two new empty methods — add them).

- [ ] **Step 4: Write the adapter test**

```java
package org.jwcarman.nessy.agent.narrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;

class TurnNarrationAdapterTest {

  private final RecordingTurnObserver turn = new RecordingTurnObserver();
  private final TurnNarrationAdapter adapter = new TurnNarrationAdapter(turn);

  @Test
  void anAssistantCommitBecomesAssistantSaid() {
    var said = Message.assistant(List.of(new TextBlock("hi")));
    var t = Transition.to(new Phase.Idle()).commit(said);
    adapter.applied(new AgentEvent.ModelFinished(new ModelOutcome.Responded(said.content(), List.of())), t);
    assertThat(turn.events()).isNotEmpty();
    assertThat(turn.events().getFirst()).isEqualTo(new TurnEvent.AssistantSaid(said));
  }

  @Test
  void reachingIdleEndsTheTurnCompleted() {
    var t = Transition.to(new Phase.Idle());
    adapter.applied(new AgentEvent.ModelFinished(new ModelOutcome.Responded(List.of(), List.of())), t);
    assertThat(turn.events())
        .contains(new TurnEvent.TurnEnded(ConversationStatus.COMPLETED, null));
  }

  @Test
  void aModelFailureEndsTheTurnFailedWithItsReason() {
    var t = Transition.to(new Phase.Idle());
    adapter.applied(new AgentEvent.ModelFinished(new ModelOutcome.Failed("overloaded")), t);
    assertThat(turn.events())
        .contains(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "overloaded"));
  }

  @Test
  void aUserCommitIsNotNarratedAsAssistantSaid() {
    var t = Transition.to(new Phase.AwaitingModel(), new Effect.CallModel())
        .commit(Message.user("hello"));
    adapter.applied(new AgentEvent.Observed(List.of(new TextBlock("hello"))), t);
    assertThat(turn.events()).isEmpty();
  }

  @Test
  void midTurnTransitionsEndNothing() {
    var t = Transition.to(new Phase.AwaitingModel(), new Effect.CallModel())
        .commit(Message.user("hello"));
    adapter.applied(new AgentEvent.Observed(List.of(new TextBlock("hello"))), t);
    boolean anyEnded =
        turn.events().stream().anyMatch(e -> e instanceof TurnEvent.TurnEnded);
    assertThat(anyEnded).isFalse();
  }
}
```

`RecordingTurnObserver`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/** Collects TurnEvents in order. */
public final class RecordingTurnObserver implements TurnObserver {

  private final List<TurnEvent> events = new ArrayList<>();

  @Override
  public void on(TurnEvent event) {
    events.add(event);
  }

  public List<TurnEvent> events() {
    return List.copyOf(events);
  }
}
```

NOTE: verify `TurnEvent.TurnEnded`'s exact component order/types against core (`org.jwcarman.nessy.api.turn.TurnEvent`) — it carries `(ConversationStatus status, String failureReason)`; if `failureReason` on the COMPLETED path must be non-null in its compact constructor, use the empty string instead of null and mirror that in the adapter and tests.

- [ ] **Step 5: Implement the adapter**

```java
package org.jwcarman.nessy.agent.narrate;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Effect;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * Machine narration → human narration (§8). The transition carries everything synthesis needs:
 * assistant commits become AssistantSaid; landing on Idle ends the turn, with the failure reason
 * taken from ModelFinished(Failed) when that is what ended it. ConversationStatus is interim
 * vocabulary until the Plan-5 distillation reshapes TurnEnded.
 */
public final class TurnNarrationAdapter implements AgentObserver {

  private final TurnObserver turn;

  public TurnNarrationAdapter(TurnObserver turn) {
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
  }

  @Override
  public void applied(AgentEvent event, Transition transition) {
    for (Message committed : transition.commit()) {
      if (committed.role() == Role.ASSISTANT) {
        turn.on(new TurnEvent.AssistantSaid(committed));
      }
    }
    if (transition.next() instanceof Phase.Idle) {
      if (event instanceof AgentEvent.ModelFinished(ModelOutcome.Failed(String reason))) {
        turn.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, reason));
      } else {
        turn.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETED, null));
      }
    }
  }

  @Override
  public void ignored(AgentEvent event) {}

  @Override
  public void renderFailed(Object observation, RuntimeException error) {}

  @Override
  public void applyFailed(AgentEvent event, RuntimeException error) {}

  @Override
  public void reFired(List<Effect> effects) {}

  @Override
  public void observationRequeued(Object observation) {}
}
```

- [ ] **Step 6: Run, format, commit** — `git commit -m "feat: the narration surface completes — re-fires and requeues speak, and the adapter translates"`

---

### Task 3: `AwaitingReply` — the sync adapter

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/narrate/AwaitingReply.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/narrate/AwaitingReplyTest.java`

**Interfaces:**
- Produces: `AwaitingReply implements TurnObserver` — collects the last `AssistantSaid`'s text; `String await(Duration timeout)` blocks until `TurnEnded` (COMPLETED → joined TextBlock text; FAILED → throws `ModelCallFailedException`-free plain `IllegalStateException` carrying the reason; timeout → `java.util.concurrent.TimeoutException` wrapped in `IllegalStateException`).

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.agent.narrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;

class AwaitingReplyTest {

  @Test
  void theLastAssistantTextIsTheReply() {
    var waiter = new AwaitingReply();
    waiter.on(new TurnEvent.AssistantSaid(Message.assistant(List.of(new TextBlock("hello back")))));
    waiter.on(new TurnEvent.TurnEnded(ConversationStatus.COMPLETED, null));
    assertThat(waiter.await(Duration.ofSeconds(1))).isEqualTo("hello back");
  }

  @Test
  void aFailedTurnThrowsWithItsReason() {
    var waiter = new AwaitingReply();
    waiter.on(new TurnEvent.TurnEnded(ConversationStatus.FAILED, "overloaded"));
    assertThatThrownBy(() -> waiter.await(Duration.ofSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("overloaded");
  }

  @Test
  void aTurnThatNeverEndsTimesOut() {
    var waiter = new AwaitingReply();
    assertThatThrownBy(() -> waiter.await(Duration.ofMillis(50)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");
  }
}
```

(TurnEnded null-reason caveat from Task 2 applies here too.)

- [ ] **Step 2: fails to compile.**

- [ ] **Step 3: Implement**

```java
package org.jwcarman.nessy.agent.narrate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The synchronous adapter (§7): one observer, one future, no second code path. The caller's
 * thread parks on the future while executor threads do the work; a parked or slow turn is just a
 * timeout, uniform with every other slow tool.
 */
public final class AwaitingReply implements TurnObserver {

  private final CompletableFuture<String> reply = new CompletableFuture<>();
  private volatile String lastAssistantText = "";

  @Override
  public void on(TurnEvent event) {
    switch (event) {
      case TurnEvent.AssistantSaid said -> lastAssistantText = textOf(said);
      case TurnEvent.TurnEnded(ConversationStatus status, String reason) -> {
        if (status == ConversationStatus.FAILED) {
          reply.completeExceptionally(new IllegalStateException("turn failed: " + reason));
        } else {
          reply.complete(lastAssistantText);
        }
      }
      default -> {}
    }
  }

  public String await(Duration timeout) {
    try {
      return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new IllegalStateException("turn timed out after " + timeout, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw new IllegalStateException("turn failed", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting the turn", e);
    }
  }

  private static String textOf(TurnEvent.AssistantSaid said) {
    StringBuilder joined = new StringBuilder();
    for (ContentBlock block : said.message().content()) {
      if (block instanceof TextBlock(String text)) {
        joined.append(text);
      }
    }
    return joined.toString();
  }
}
```

NOTE: `TurnEvent` is sealed in core — if the switch requires exhaustiveness over all variants, replace `default -> {}` with the explicit remaining variants or an `if/else instanceof` chain; do not add a `default` arm to a sealed switch if house style forbids it — an if-chain is fine here.

- [ ] **Step 4: pass; Step 5: format and commit** — `git commit -m "feat: AwaitingReply — the caller parks, the narration answers"`

---

### Task 4: `ProviderModelCallExecutor` — the model bridge

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/model/ProviderModelCallExecutor.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/ScriptedModelProvider.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/model/ProviderModelCallExecutorTest.java`

**Interfaces:**
- Consumes: core `ModelProvider { ModelStream stream(ModelRequest) }`, `ModelRequest(Context, String systemPrompt, String model, int maxTokens, List<ToolSpec> tools, Set<Capability> requested, ObjectNode responseSchema)`, `ModelEvent` variants (`TextChunk`, `ThinkingChunk`, `ThinkingSigned`, `RedactedThinkingEmitted`, `ToolUseEmitted(ToolCall, String signature)`, `TurnEnded(StopReason, Usage)`), `ModelSettings` (`systemPrompt()`, `model()`, `maxTokens()`, `capabilities()`), `ToolRegistry.specs()`, `TurnObserver`/`TurnEvent`. Agent: `spi.Memory`, `spi.ModelCallExecutor`, `spi.Sink`, grammar.
- Produces: `ProviderModelCallExecutor implements ModelCallExecutor`, constructor `(ModelProvider provider, ModelSettings settings, ToolRegistry tools, Memory memory, TurnObserver turn, Executor executor)`. `callModel(Sink)` submits to the executor; the task recalls, streams, accumulates, narrates deltas, and delivers exactly one `ModelFinished`.

**Reference:** the old `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ProviderModelCallExecutor.java` — adapt its accumulation (`mergeText`/`mergeThinking`/`sign` semantics) but do NOT import from it or modify it.

- [ ] **Step 1: Write the scripted provider double**

```java
package org.jwcarman.nessy.agent.support;

import java.util.Iterator;
import java.util.List;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/** Replays a scripted event list per stream() call; records each request. */
public final class ScriptedModelProvider implements ModelProvider {

  private final List<List<ModelEvent>> scripts;
  private final java.util.List<ModelRequest> requests = new java.util.ArrayList<>();
  private int next;

  public ScriptedModelProvider(List<List<ModelEvent>> scripts) {
    this.scripts = scripts;
  }

  @Override
  public ModelStream stream(ModelRequest request) {
    requests.add(request);
    List<ModelEvent> script = scripts.get(next++);
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return script.iterator();
      }

      @Override
      public void close() {}
    };
  }

  public List<ModelRequest> requests() {
    return List.copyOf(requests);
  }
}
```

(Convert FQNs to imports. If `ModelProvider` declares more members — check the interface — implement them minimally; if it is `AutoCloseable`, add an empty `close()`.)

- [ ] **Step 2: Write the failing tests**

```java
package org.jwcarman.nessy.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.model.ModelEvent;

class ProviderModelCallExecutorTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "lookup", JsonNodeFactory.instance.objectNode());

  private ModelOutcome run(List<ModelEvent> script, VerbatimMemory memory,
      RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var provider = new ScriptedModelProvider(List.of(script));
    var executor =
        new ProviderModelCallExecutor(
            provider, TestSettings.settings(), TestSettings.emptyRegistry(), memory, turn, pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.callModel(delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return ((AgentEvent.ModelFinished) delivered.getFirst()).outcome();
  }

  @Test
  void textChunksMergeIntoOneBlockAndNarrateAsDeltas() {
    var turn = new RecordingTurnObserver();
    var outcome = run(
        List.of(new ModelEvent.TextChunk("Hel"), new ModelEvent.TextChunk("lo")),
        new VerbatimMemory(), turn);
    assertThat(outcome)
        .isEqualTo(new ModelOutcome.Responded(List.of(new TextBlock("Hello")), List.of()));
    assertThat(turn.events())
        .contains(new TurnEvent.TextDelta("Hel"), new TurnEvent.TextDelta("lo"));
  }

  @Test
  void thinkingIsSignedAndToolUseCarriesItsSignature() {
    var turn = new RecordingTurnObserver();
    var outcome = run(
        List.of(
            new ModelEvent.ThinkingChunk("hmm"),
            new ModelEvent.ThinkingSigned("anthropic-sig"),
            new ModelEvent.ToolUseEmitted(CALL, "gemini-sig")),
        new VerbatimMemory(), turn);
    var responded = (ModelOutcome.Responded) outcome;
    assertThat(responded.content())
        .containsExactly(
            new ThinkingBlock("hmm", "anthropic-sig"), new ToolUseBlock(CALL, "gemini-sig"));
    assertThat(responded.calls()).containsExactly(CALL);
    assertThat(turn.events()).contains(new TurnEvent.ToolCallRequested(CALL));
  }

  @Test
  void theRequestCarriesTheRecalledContext() {
    var memory = new VerbatimMemory();
    memory.remember(Message.user("earlier"));
    var turn = new RecordingTurnObserver();
    var pump = new PumpedExecutor();
    var provider =
        new ScriptedModelProvider(List.of(List.of(new ModelEvent.TextChunk("ok"))));
    var executor =
        new ProviderModelCallExecutor(
            provider, TestSettings.settings(), TestSettings.emptyRegistry(), memory, turn, pump);
    executor.callModel(event -> {});
    pump.pumpUntilQuiet();
    assertThat(provider.requests()).hasSize(1);
    assertThat(provider.requests().getFirst().context().messages())
        .containsExactly(Message.user("earlier"));
  }

  @Test
  void aProviderExplosionDeliversAFailedOutcomeInsteadOfEscaping() {
    var turn = new RecordingTurnObserver();
    var pump = new PumpedExecutor();
    var exploding = new org.jwcarman.nessy.spi.model.ModelProvider() {
      @Override
      public org.jwcarman.nessy.spi.model.ModelStream stream(
          org.jwcarman.nessy.spi.model.ModelRequest request) {
        throw new IllegalStateException("boom");
      }
    };
    var executor =
        new ProviderModelCallExecutor(
            exploding, TestSettings.settings(), TestSettings.emptyRegistry(),
            new VerbatimMemory(), turn, pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.callModel(delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    var outcome = ((AgentEvent.ModelFinished) delivered.getFirst()).outcome();
    assertThat(outcome).isInstanceOf(ModelOutcome.Failed.class);
  }
}
```

Create a small test helper `TestSettings` in the same test package: `static ModelSettings settings()` returning a minimal `ModelSettings` (check its record/builder shape in core and construct with systemPrompt "you are helpful", model "test-model", maxTokens 1024, empty capabilities), and `static ToolRegistry emptyRegistry()` returning a registry with no tools (use core's `DefaultToolRegistry` empty, or a lambda-backed `ToolRegistry` if it is a small interface — check `specs()` exists on it and return `List.of()`). Convert all FQNs to imports.

- [ ] **Step 3: fails to compile.**

- [ ] **Step 4: Implement**

```java
package org.jwcarman.nessy.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * The executor owns memory (§4.1): recall → request → stream → accumulate → exactly one
 * ModelFinished through the dispatch-time sink. Deltas narrate as they stream; failures fold to
 * ModelFinished(Failed) rather than escaping onto the executor thread.
 */
public final class ProviderModelCallExecutor implements ModelCallExecutor {

  private final ModelProvider provider;
  private final ModelSettings settings;
  private final ToolRegistry tools;
  private final Memory memory;
  private final TurnObserver turn;
  private final Executor executor;

  public ProviderModelCallExecutor(
      ModelProvider provider,
      ModelSettings settings,
      ToolRegistry tools,
      Memory memory,
      TurnObserver turn,
      Executor executor) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
  }

  @Override
  public void callModel(Sink sink) {
    executor.execute(() -> sink.deliver(new AgentEvent.ModelFinished(call())));
  }

  private ModelOutcome call() {
    try {
      ModelRequest request =
          new ModelRequest(
              memory.recall(),
              settings.systemPrompt(),
              settings.model(),
              settings.maxTokens(),
              tools.specs(),
              settings.capabilities(),
              null);
      return stream(request);
    } catch (RuntimeException e) {
      return new ModelOutcome.Failed(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private ModelOutcome stream(ModelRequest request) {
    List<ContentBlock> blocks = new ArrayList<>();
    List<ToolCall> calls = new ArrayList<>();
    try (ModelStream stream = provider.stream(request)) {
      for (ModelEvent event : stream) {
        switch (event) {
          case ModelEvent.TextChunk(String text) -> {
            turn.on(new TurnEvent.TextDelta(text));
            mergeText(blocks, text);
          }
          case ModelEvent.ThinkingChunk(String text) -> {
            turn.on(new TurnEvent.ThinkingDelta(text));
            mergeThinking(blocks, text);
          }
          case ModelEvent.ThinkingSigned(String signature) -> sign(blocks, signature);
          case ModelEvent.RedactedThinkingEmitted(String data) -> {
            turn.on(new TurnEvent.RedactedThinking(data));
            blocks.add(new RedactedThinkingBlock(data));
          }
          case ModelEvent.ToolUseEmitted(ToolCall call, String signature) -> {
            turn.on(new TurnEvent.ToolCallRequested(call));
            blocks.add(new ToolUseBlock(call, signature));
            calls.add(call);
          }
          case ModelEvent.TurnEnded ignored -> {
            // usage metrics ride the observability design, not this plan (decision 7)
          }
        }
      }
    }
    return new ModelOutcome.Responded(blocks, calls);
  }

  private static void mergeText(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof TextBlock(String prior)) {
      blocks.set(blocks.size() - 1, new TextBlock(prior + text));
    } else {
      blocks.add(new TextBlock(text));
    }
  }

  private static void mergeThinking(List<ContentBlock> blocks, String text) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock(String prior, String sig)) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(prior + text, sig));
    } else {
      blocks.add(new ThinkingBlock(text, ""));
    }
  }

  private static void sign(List<ContentBlock> blocks, String signature) {
    if (!blocks.isEmpty() && blocks.getLast() instanceof ThinkingBlock(String text, String ignored)) {
      blocks.set(blocks.size() - 1, new ThinkingBlock(text, signature));
    }
  }
}
```

NOTE: cross-check the old executor's merge semantics (does a `ThinkingSigned` close the thinking block so a following `ThinkingChunk` starts a new one?) and mirror them; the old file is the behavioral reference. If `ModelEvent` has variants beyond the six listed, handle them the way the old executor does.

- [ ] **Step 5: pass; Step 6: format and commit** — `git commit -m "feat: the model bridge — recall, stream, accumulate, deliver once"`

---

### Task 5: `RegistryToolCallExecutor` — the non-parking tool bridge

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/tool/RegistryToolCallExecutor.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/tool/RegistryToolCallExecutorTest.java`

**Interfaces:**
- Consumes: core `Tool<T>` (`name()`, `inputType()`, `execute(T, ToolContext) → Awaited<ToolResult>`), `ToolRegistry.find(String)`, `ToolContext(ConversationId, ToolCall, EventEmitter)`, `Awaited.Ready/Parked`. Agent: `spi.ToolCallExecutor`, `spi.Sink`, `AgentId`, grammar.
- Produces: `RegistryToolCallExecutor implements ToolCallExecutor`, constructor `(ToolRegistry registry, AgentId id, TurnObserver turn, Executor executor)`. Per call: find the tool (missing → `Failed("unknown tool: ...")`); bind `call.arguments()` to `inputType()` via a private `ObjectMapper.treeToValue`; execute with a bridged `ToolContext` (`new ConversationId(id.value())` — decision 3 — and an `EventEmitter` forwarding `String.valueOf(event)` as `TurnEvent.ToolCallProgressed`); `Awaited.Ready(result)` → `ToolOutcome.Returned(result)`; `Awaited.Parked` → `ToolOutcome.Failed("parking is unavailable in this wiring; the desk arrives with the autonomous host")` (§4.3, decision 2); any throw → `ToolOutcome.Failed(exception message)`. Narrates `ToolCallCompleted(call, result-or-error)` and delivers exactly one `ToolFinished` via the executor.

- [ ] **Step 1: Write the failing tests**

```java
package org.jwcarman.nessy.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RecordingTurnObserver;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;

class RegistryToolCallExecutorTest {

  record EchoInput(String value) {}

  static final class EchoTool implements Tool<EchoInput> {
    @Override public String name() { return "echo"; }
    @Override public String description() { return "echoes"; }
    @Override public Class<EchoInput> inputType() { return EchoInput.class; }
    @Override public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echo: " + input.value()));
    }
  }

  static final class ParkingTool implements Tool<EchoInput> {
    @Override public String name() { return "park_me"; }
    @Override public String description() { return "always parks"; }
    @Override public Class<EchoInput> inputType() { return EchoInput.class; }
    @Override public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.parked(new ParkToken("t-1"));
    }
  }

  private static ToolRegistry registryOf(Tool<?>... tools) {
    // use core's DefaultToolRegistry if its constructor accepts a collection; otherwise a
    // small inline ToolRegistry over a Map — check the interface's full member list first.
    return TestRegistries.of(tools);
  }

  private AgentEvent.ToolFinished run(ToolRegistry registry, ToolCall call,
      RecordingTurnObserver turn) {
    var pump = new PumpedExecutor();
    var executor =
        new RegistryToolCallExecutor(registry, AgentId.of("cli"), turn, pump);
    var delivered = new ArrayList<AgentEvent>();
    executor.executeTool(call, delivered::add);
    pump.pumpUntilQuiet();
    assertThat(delivered).hasSize(1);
    return (AgentEvent.ToolFinished) delivered.getFirst();
  }

  @Test
  void aKnownToolExecutesAndReturns() {
    var call = new ToolCall("c1", "echo",
        JsonNodeFactory.instance.objectNode().put("value", "hi"));
    var finished = run(registryOf(new EchoTool()), call, new RecordingTurnObserver());
    assertThat(finished.outcome())
        .isEqualTo(new ToolOutcome.Returned(ToolResult.ok("echo: hi")));
  }

  @Test
  void anUnknownToolFailsInBand() {
    var call = new ToolCall("c1", "nope", JsonNodeFactory.instance.objectNode());
    var finished = run(registryOf(new EchoTool()), call, new RecordingTurnObserver());
    assertThat(finished.outcome()).isInstanceOf(ToolOutcome.Failed.class);
  }

  @Test
  void aParkingToolFailsLoudlyInThisWiring() {
    var call = new ToolCall("c1", "park_me",
        JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(registryOf(new ParkingTool()), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("parking is unavailable");
  }

  @Test
  void aThrowingToolFailsInBandInsteadOfEscaping() {
    var boom = new Tool<EchoInput>() {
      @Override public String name() { return "boom"; }
      @Override public String description() { return "throws"; }
      @Override public Class<EchoInput> inputType() { return EchoInput.class; }
      @Override public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
        throw new IllegalStateException("kaboom");
      }
    };
    var call = new ToolCall("c1", "boom",
        JsonNodeFactory.instance.objectNode().put("value", "x"));
    var finished = run(registryOf(boom), call, new RecordingTurnObserver());
    var failed = (ToolOutcome.Failed) finished.outcome();
    assertThat(failed.error().message()).contains("kaboom");
  }
}
```

Create test helper `TestRegistries` in the same package (`static ToolRegistry of(Tool<?>...)`) — prefer constructing core's `DefaultToolRegistry` if its constructor takes tools; read that class first and implement whichever is smaller. Check `ParkToken`'s constructor shape (`new ParkToken("t-1")` vs a factory) and adjust.

- [ ] **Step 2: fails to compile.**

- [ ] **Step 3: Implement**

```java
package org.jwcarman.nessy.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.ToolError;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The non-parking tool executor (§4.3): find, bind, execute, deliver — and a park attempt fails
 * loudly in-band, because a parked turn wedges a conversation. The desk arrives with the
 * autonomous wiring (Plan 4). The ConversationId bridge is interim vocabulary (plan decision 3).
 */
public final class RegistryToolCallExecutor implements ToolCallExecutor {

  private final ToolRegistry registry;
  private final ConversationId bridgedId;
  private final TurnObserver turn;
  private final Executor executor;
  private final ObjectMapper mapper = new ObjectMapper();

  public RegistryToolCallExecutor(
      ToolRegistry registry, AgentId id, TurnObserver turn, Executor executor) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.bridgedId = new ConversationId(Objects.requireNonNull(id, "id must not be null").value());
    this.turn = Objects.requireNonNull(turn, "turn must not be null");
    this.executor = Objects.requireNonNull(executor, "executor must not be null");
  }

  @Override
  public void executeTool(ToolCall call, Sink sink) {
    executor.execute(
        () -> sink.deliver(new AgentEvent.ToolFinished(call, execute(call))));
  }

  private ToolOutcome execute(ToolCall call) {
    Optional<Tool<?>> found = registry.find(call.name());
    if (found.isEmpty()) {
      return failed(call, "unknown tool: " + call.name());
    }
    try {
      ToolResult result = invoke(found.get(), call);
      turn.on(new TurnEvent.ToolCallCompleted(call, result));
      return new ToolOutcome.Returned(result);
    } catch (RuntimeException e) {
      return failed(call, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private <T> ToolResult invoke(Tool<T> tool, ToolCall call) {
    T input;
    try {
      input = mapper.treeToValue(call.arguments(), tool.inputType());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("arguments do not bind to " + tool.inputType().getSimpleName(), e);
    }
    ToolContext context =
        new ToolContext(bridgedId, call,
            event -> turn.on(new TurnEvent.ToolCallProgressed(call, String.valueOf(event))));
    return switch (tool.execute(input, context)) {
      case Awaited.Ready<ToolResult>(ToolResult value) -> value;
      case Awaited.Parked<ToolResult> ignored ->
          throw new IllegalStateException(
              "parking is unavailable in this wiring; the desk arrives with the autonomous host");
    };
  }

  private ToolOutcome failed(ToolCall call, String message) {
    ToolResult error = ToolResult.error(message);
    turn.on(new TurnEvent.ToolCallCompleted(call, error));
    return new ToolOutcome.Failed(new ToolError(message));
  }
}
```

(Convert the `JsonProcessingException` FQN to an import. If `ToolContext`'s `EventEmitter` slot rejects lambdas — check the interface — write a tiny named class.)

- [ ] **Step 4: pass; Step 5: format and commit** — `git commit -m "feat: the tool bridge — find, bind, execute, and parking fails loudly here"`

---

### Task 6: `Nessy.cli()` — the first front door

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/host/Nessy.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/host/CliAgent.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/host/RelayTurnObserver.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/CliAgentTest.java`

**Interfaces:**
- Produces:
  - `RelayTurnObserver implements TurnObserver` — `set(TurnObserver)`, `clear()`, forwards `on` to the current delegate or drops when none.
  - `CliAgent` — `String converse(String line)` (blocking, default 2-minute timeout; overload with `Duration`).
  - `Nessy.cli()` → `CliBuilder` with `.provider(ModelProvider)`, `.settings(ModelSettings)`, `.tools(Tool<?>...)`, `.memory(Memory)` (default `VerbatimMemory`), `.id(String)` (default `"cli"`), `.executor(Executor)` (default `Executors.newVirtualThreadPerTaskExecutor()` — created once, owned by the CliAgent, closed on `close()`; make `CliAgent implements AutoCloseable`), `.build()` → `CliAgent`.

- [ ] **Step 1: Write the failing test** (fake provider, REAL virtual threads, real blocking converse — the one integration-style test, per Global Constraints)

```java
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.spi.model.ModelEvent;

class CliAgentTest {

  @Test
  void helloWorldEndToEnd() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(List.of(new ModelEvent.TextChunk("Hello "), new ModelEvent.TextChunk("back!"))));
    try (var agent =
        Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      assertThat(agent.converse("hello")).isEqualTo("Hello back!");
    }
  }

  @Test
  void twoTurnsShareOneMemory() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("one")),
                List.of(new ModelEvent.TextChunk("two"))));
    try (var agent =
        Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      agent.converse("first");
      agent.converse("second");
      // the second request's context carries the whole first exchange plus the new user turn
      assertThat(provider.requests()).hasSize(2);
      assertThat(provider.requests().get(1).context().messages()).hasSize(3);
    }
  }
}
```

(`TestSettings` — reuse Task 4's helper by moving it to `org.jwcarman.nessy.agent.support.TestSettings` when this task starts; update Task 4's test import. One shared helper, not two copies.)

- [ ] **Step 2: fails to compile.**

- [ ] **Step 3: Implement**

`RelayTurnObserver`:

```java
package org.jwcarman.nessy.agent.host;

import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;

/**
 * The CLI's outlet: the wiring's observer composition is fixed at construction (§3.5), so the
 * per-turn waiter attaches through this relay. App-side outlet management, not core machinery
 * (plan decision 5). Events with no delegate are dropped — nobody was listening.
 */
public final class RelayTurnObserver implements TurnObserver {

  private final AtomicReference<TurnObserver> delegate = new AtomicReference<>();

  public void set(TurnObserver observer) {
    delegate.set(observer);
  }

  public void clear() {
    delegate.set(null);
  }

  @Override
  public void on(TurnEvent event) {
    TurnObserver current = delegate.get();
    if (current != null) {
      current.on(event);
    }
  }
}
```

`CliAgent`:

```java
package org.jwcarman.nessy.agent.host;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.narrate.AwaitingReply;

/**
 * The interactive constant-id host (§1.1, §7.1): one scope for the process, one turn at a time,
 * the caller's thread parks on the reply.
 */
public final class CliAgent implements AutoCloseable {

  private final Agent<String> agent;
  private final RelayTurnObserver relay;
  private final ExecutorService executor;

  CliAgent(Agent<String> agent, RelayTurnObserver relay, ExecutorService executor) {
    this.agent = Objects.requireNonNull(agent);
    this.relay = Objects.requireNonNull(relay);
    this.executor = Objects.requireNonNull(executor);
  }

  public String converse(String line) {
    return converse(line, Duration.ofMinutes(2));
  }

  public String converse(String line, Duration timeout) {
    var waiter = new AwaitingReply();
    relay.set(waiter);
    try {
      agent.observe(line);
      return waiter.await(timeout);
    } finally {
      relay.clear();
    }
  }

  @Override
  public void close() {
    executor.close();
  }
}
```

`Nessy`:

```java
package org.jwcarman.nessy.agent.host;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.AgentWiring;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.model.ProviderModelCallExecutor;
import org.jwcarman.nessy.agent.narrate.TurnNarrationAdapter;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.tool.RegistryToolCallExecutor;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelSettings;

/** The front doors (§7.1). Builders wire existing seams; they never own machinery. */
public final class Nessy {

  private Nessy() {}

  public static CliBuilder cli() {
    return new CliBuilder();
  }

  public static final class CliBuilder {

    private ModelProvider provider;
    private ModelSettings settings;
    private Memory memory = new VerbatimMemory();
    private String id = "cli";
    private List<Tool<?>> tools = List.of();
    private ExecutorService executor;

    public CliBuilder provider(ModelProvider provider) {
      this.provider = provider;
      return this;
    }

    public CliBuilder settings(ModelSettings settings) {
      this.settings = settings;
      return this;
    }

    public CliBuilder memory(Memory memory) {
      this.memory = memory;
      return this;
    }

    public CliBuilder id(String id) {
      this.id = id;
      return this;
    }

    public CliBuilder tools(Tool<?>... tools) {
      this.tools = List.of(tools);
      return this;
    }

    public CliBuilder executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public CliAgent build() {
      Objects.requireNonNull(provider, "provider must not be null");
      Objects.requireNonNull(settings, "settings must not be null");
      ExecutorService exec =
          executor != null ? executor : Executors.newVirtualThreadPerTaskExecutor();
      var relay = new RelayTurnObserver();
      ToolRegistry registry = Registries.of(tools);
      var agentId = AgentId.of(id);
      var wiring =
          new AgentWiring<String>(
              memory,
              new InMemoryAgentStateStore(),
              inMemoryBacklog(),
              text -> List.of(new TextBlock(text)),
              new ProviderModelCallExecutor(provider, settings, registry, memory, relay, exec),
              new RegistryToolCallExecutor(registry, agentId, relay, exec),
              new TurnNarrationAdapter(relay),
              false,
              Duration.ofMinutes(5),
              Clock.systemUTC());
      return new CliAgent(new DefaultAgent<>(wiring), relay, exec);
    }

    private static Backlog<String> inMemoryBacklog() {
      Deque<String> queue = new ArrayDeque<>();
      return new Backlog<>() {
        @Override
        public synchronized void add(String observation) {
          queue.add(observation);
        }

        @Override
        public synchronized Optional<String> poll() {
          return Optional.ofNullable(queue.poll());
        }
      };
    }
  }
}
```

`Registries.of(List<Tool<?>>)` — a small package-private helper in `host` (or reuse core's `DefaultToolRegistry` if it accepts a collection; read it and pick the smaller). It must also produce `specs()` — if core's registry derives `ToolSpec`s from tools (schema generation via the jsonschema dependency), strongly prefer reusing core's implementation over reimplementing schema generation.

- [ ] **Step 4: run, pass; Step 5: full verify; Step 6: format and commit** — `git commit -m "feat: Nessy.cli() — the first front door opens and says hello"`

---

### Task 7: Live smoke test (excluded by default)

**Files:**
- Modify: `nessy-agent/pom.xml` (add test-scope dependency on `nessy-model-env`, `${project.version}`)
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/CliLiveSmokeTest.java`

**Interfaces:** consumes `nessy-model-env`'s environment-resolved provider — read `nessy-model-env`'s public API (a factory that builds a `ModelProvider` + `ModelSettings` from environment variables; find its exact entry point and mirror how other modules' live tests use it — grep `@Tag("live")` in the repo for the established pattern, including how they skip when the environment is absent).

- [ ] **Step 1: Add the dependency**; **Step 2: Write the test** following the repo's live-test pattern exactly (same tag, same env-guard idiom found via the grep), body:

```java
  @Test
  @Tag("live")
  void aRealProviderSaysHello() throws Exception {
    // construct provider + settings via nessy-model-env's entry point (see repo pattern)
    try (var agent = Nessy.cli().provider(provider).settings(settings).build()) {
      String reply = agent.converse("Reply with exactly the word: pong");
      assertThat(reply).isNotBlank();
    }
  }
```

- [ ] **Step 3: `./mvnw -q clean verify` must stay green WITHOUT the env** (test excluded/skipped); run the live test manually only if credentials are present (`./mvnw -pl nessy-agent test -Dnessy.excludedGroups= -Dtest=CliLiveSmokeTest`) and report which path you took. **Step 4: format and commit** — `git commit -m "test: the live smoke — a real model behind the cli door"`

---

## What Plans 4–5 pick up from here

**Plan 4 (the desk and the doors):** the durable park desk + expiry behind an autonomous `ToolCallExecutor`, the `AuthzContext` gate (§4.2 — requires the core adaptation), rendezvous approval for interactive, `Nessy.web()` (per-turn SSE, send/stream doors) and `Nessy.autonomous()` (drainOnIdle, durable backlog, sweep). **Plan 5 (distillation):** spec §9's deletion table, `TurnEnded` reshape + `ToolCallParked` removal, the `ConversationId`/`ToolContext`/`AuthzContext` vocabulary adaptation, the opaque-payload store SPI move, the dependency enforcer.
