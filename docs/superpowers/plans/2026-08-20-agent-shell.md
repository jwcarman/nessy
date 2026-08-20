# Agent Shell (Plan 2 of 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `nessy-agent` shell — the `Agent<O>` API, the SPI seams (Sink, Memory, Backlog, ObservationRenderer, both executors, AgentObserver), and `DefaultAgent` with the full apply loop, the drain arm, and the recovery arm of `drive()`.

**Architecture:** The shell is load–handle–save–dispatch with a retry (spec §3.4); serialization is the store's version CAS and nothing else (§3.2); executors are asynchronous by contract, holding a `Sink` from construction (§4). Everything is testable deterministically with a pumped executor and hand-written doubles — no threads in tests, no mocks ever.

**Tech Stack:** Java 25, Maven, JUnit 5, AssertJ. Depends only on `nessy-core` vocabulary (`Message`, `ContentBlock`, `ToolCall`, `Context`) and Plan 1's machine.

**Spec:** `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` (§3, §4 contract, §6.1 govern this plan)

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no model-provider network access, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, then re-stage.
- No `@SuppressWarnings`; no star imports (single-symbol imports only, including statics).
- `assertThatThrownBy` lambdas contain exactly ONE invocation that can throw; setup outside (S5778).
- Assert emptiness before any all/none-match predicate on the same collection (S5841).
- Prose test names (lower-case prose sentences). No mocking libraries — hand-written doubles only.
- No thread-based waiting in tests: all asynchrony runs through the `PumpedExecutor` double.
- `nessy-core` must never reference any `org.jwcarman.nessy.agent` type.

## Plan-level design decisions (deliberate — do not "fix")

1. **`AgentObserver` is the shell's machine-level narration seam**, defined in this plan with three methods (`applied`, `ignored`, `renderFailed`). The spec's `TurnObserver` wiring (§8) arrives in Plan 3 as an *adapter over* `AgentObserver` — `applied(event, transition)` carries the next phase and commits, which is everything `AssistantSaid`/`TurnEnded` synthesis needs. Two vocabularies, two jobs, staged deliberately.
2. **`LatentSink` resolves the §4 construction chicken-and-egg**: executors hold their `Sink` from construction, but the sink's target (`DefaultAgent.deliver`) exists only after the agent is built. `LatentSink` is a one-shot settable `Sink` holder; the factory (tests now, Plan 3 builders later) constructs executors around it and completes it after the agent exists.
3. **`DefaultAgent.deliver` is package-private** — the continuation door stays off the public API (spec §3: "the method to deliver one is not expressible in [the application's] vocabulary"). Tests and Plan-3 factories live in `org.jwcarman.nessy.agent` and wire `sink.set(agent::deliver)`.
4. **`drainOnIdle` is a boolean on the wiring** — the §3.1 table's "who executes the drain" knob: `true` for autonomous, `false` for interactive.
5. **Staleness is wiring too**: `staleThreshold` (`Duration`) + `Clock`, compared against the store's new `lastSaved()` (§6.1: staleness is read from the store).
6. **The lost-race `Observed` test accepts a benign duplicate in memory**: commit-before-save means the loser's user message is remembered, then re-remembered when the observation re-drains (spec §3.4/§5.2's accepted class). The test asserts the re-add, not memory purity.
7. **`Transition` builder warts (final-review M3) stay** — `commit()` accessor/builder overload and `emit(List)` asymmetry are open question 1's pass, not this plan's.

## File Structure

```
nessy-agent/src/main/java/org/jwcarman/nessy/agent/
  Agent.java                 — public API: observe(O), drive()
  AgentWiring.java           — record of all collaborators + knobs
  DefaultAgent.java          — the shell
  Phase.java                 — MODIFIED: + outstandingEffects()
  store/AgentStateStore.java — MODIFIED: + lastSaved()
  store/InMemoryAgentStateStore.java — MODIFIED: Entry(State, Instant) + Clock
  spi/
    Sink.java                — @FunctionalInterface deliver(AgentEvent)
    LatentSink.java          — one-shot settable Sink
    Memory.java              — remember(Message) / recall(): Context
    Backlog.java             — add(O) / poll(): Optional<O>
    ObservationRenderer.java — render(O): List<ContentBlock>
    ModelCallExecutor.java   — callModel()
    ToolCallExecutor.java    — executeTool(ToolCall)
    AgentObserver.java       — applied / ignored / renderFailed + noop()
nessy-agent/src/test/java/org/jwcarman/nessy/agent/
  PhaseOutstandingEffectsTest.java
  spi/LatentSinkTest.java
  AgentWiringTest.java
  DefaultAgentApplyTest.java
  DefaultAgentDrainTest.java
  DefaultAgentRecoveryTest.java
  support/
    PumpedExecutor.java, TestClock.java, RecordingMemory.java,
    RecordingObserver.java, ScriptedModelExecutor.java,
    ScriptedToolExecutor.java, RaceOnceStore.java
  (MODIFIED: StateCodecTest.java, TransitionTest.java — Task 1;
   store/InMemoryAgentStateStoreTest.java — Task 3)
```

---

### Task 1: Plan-1 parked residuals

**Files:**
- Modify: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/StateCodecTest.java`
- Modify: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/TransitionTest.java`

**Interfaces:** none new — test-only.

- [ ] **Step 1: Add the absent-signature decode test**

The production behavior (StateCodec reads an absent `thinking.signature` as `""`) is correct but unpinned — no test decodes hand-authored JSON lacking the key, because the encoder always writes it. Append to `StateCodecTest`:

```java
  @Test
  void aThinkingBlockWithoutASignatureKeyDecodesAsUnsigned() {
    var json =
        """
        {"version":1,"phase":"AWAITING_TOOLS",
         "assistantTurn":{"role":"ASSISTANT","content":[
           {"type":"thinking","text":"hmm"},
           {"type":"tool_use","id":"a","name":"lookup","arguments":{}}]},
         "pending":["a"],"gathered":[]}
        """;
    var decoded = codec.decode(json);
    var turn = ((Phase.AwaitingTools) decoded.phase()).assistantTurn();
    assertThat(turn.content()).contains(new ThinkingBlock("hmm", ""));
  }
```

(`ThinkingBlock` is already imported by the every-block-type test.)

- [ ] **Step 2: Hoist the S5778 violation**

In `TransitionTest.anIgnoredTransitionRefusesToCommit`, `Message.user("x")` sits inside the lambda. Change to:

```java
  @Test
  void anIgnoredTransitionRefusesToCommit() {
    var ignored = Transition.ignore();
    var message = Message.user("x");
    assertThatThrownBy(() -> ignored.commit(message)).isInstanceOf(IllegalStateException.class);
  }
```

- [ ] **Step 3: Run, format, commit**

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS (57 tests).

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "test: the two parked residuals settle — legacy thinking blocks and a hoisted lambda"
```

---

### Task 2: `Phase.outstandingEffects()` — the §6.1 invariant becomes a method

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Phase.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/PhaseOutstandingEffectsTest.java`

**Interfaces:**
- Produces: `List<Effect> outstandingEffects()` on `Phase` — `Idle` → empty; `AwaitingModel` → one `CallModel`; `AwaitingTools` → one `ExecuteTool` per pending id (sorted), each carrying the full `ToolCall` recovered from `assistantTurn`'s `ToolUseBlock`s. Task 6's recovery arm consumes this.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class PhaseOutstandingEffectsTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());
  private static final Message TURN =
      Message.assistant(
          List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null)));

  @Test
  void idleHasNothingOutstanding() {
    assertThat(new Phase.Idle().outstandingEffects()).isEmpty();
  }

  @Test
  void awaitingModelReDerivesItsBareModelCall() {
    assertThat(new Phase.AwaitingModel().outstandingEffects())
        .containsExactly(new Effect.CallModel());
  }

  @Test
  void awaitingToolsReDerivesOnlyThePendingCallsWithFullArguments() {
    var phase =
        new Phase.AwaitingTools(TURN, Set.of("b"), List.of(new ToolResultBlock("a", "42", false)));
    assertThat(phase.outstandingEffects()).containsExactly(new Effect.ExecuteTool(CALL_B));
  }

  @Test
  void awaitingToolsReDerivesInSortedIdOrder() {
    var phase = new Phase.AwaitingTools(TURN, Set.of("b", "a"), List.of());
    assertThat(phase.outstandingEffects())
        .containsExactly(new Effect.ExecuteTool(CALL_A), new Effect.ExecuteTool(CALL_B));
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test` — Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

In `Phase.java`, add to the interface (below `handle`):

```java
  /**
   * The effects still in flight for this phase, re-derivable on any node — the §6.1 recovery
   * invariant as a method. Every future phase must keep this total.
   */
  List<Effect> outstandingEffects();
```

In `Idle`: `@Override public List<Effect> outstandingEffects() { return List.of(); }`
In `AwaitingModel`: `@Override public List<Effect> outstandingEffects() { return List.of(new Effect.CallModel()); }`
In `AwaitingTools`:

```java
    @Override
    public List<Effect> outstandingEffects() {
      var byId = new java.util.HashMap<String, ToolCall>();
      for (var block : assistantTurn.content()) {
        if (block instanceof ToolUseBlock(ToolCall call, String ignoredSignature)) {
          byId.put(call.id(), call);
        }
      }
      return pending.stream().sorted().map(id -> (Effect) new Effect.ExecuteTool(byId.get(id)))
          .toList();
    }
```

Convert the inline `java.util.HashMap` to an import (`java.util.HashMap`) — no FQN inline. The constructor guard from Plan 1 guarantees every pending id resolves.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: every phase can name its outstanding effects — recovery's invariant compiles now"
```

---

### Task 3: The store learns time — `lastSaved()`

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/AgentStateStore.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/store/InMemoryAgentStateStore.java`
- Modify: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/store/InMemoryAgentStateStoreTest.java`

**Interfaces:**
- Produces: `Instant lastSaved()` on `AgentStateStore` — the instant of the most recent successful `save` (construction instant for a fresh scope). `InMemoryAgentStateStore` gains a `Clock` constructor (`InMemoryAgentStateStore(Clock clock)`); the no-arg constructor delegates with `Clock.systemUTC()`. Task 6's staleness check consumes this.

- [ ] **Step 1: Write the failing tests (append to the existing test class)**

```java
  @Test
  void aFreshScopeReportsItsBirthAsLastSaved() {
    var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-08-20T12:00:00Z"),
        java.time.ZoneOffset.UTC);
    var store = new InMemoryAgentStateStore(clock);
    assertThat(store.lastSaved()).isEqualTo(java.time.Instant.parse("2026-08-20T12:00:00Z"));
  }

  @Test
  void aSaveStampsTheClocksNow() {
    var birth = java.time.Instant.parse("2026-08-20T12:00:00Z");
    var later = java.time.Instant.parse("2026-08-20T12:05:00Z");
    var clock = new org.jwcarman.nessy.agent.support.TestClock(birth);
    var store = new InMemoryAgentStateStore(clock);
    clock.set(later);
    store.save(new State(new Phase.AwaitingModel(), 0L));
    assertThat(store.lastSaved()).isEqualTo(later);
  }
```

Convert FQNs to imports (`Clock`, `Instant`, `ZoneOffset`, `TestClock`). This task also creates the `TestClock` support double (Task 6's recovery tests reuse it):

`nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/TestClock.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A hand-set clock: tests move time; nothing else does. */
public final class TestClock extends Clock {

  private Instant now;

  public TestClock(Instant start) {
    this.now = start;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  public void advance(java.time.Duration by) {
    this.now = now.plus(by);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
```

(Convert the `java.time.Duration` FQN to an import.)

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test` — Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement**

`AgentStateStore.java` — add:

```java
  /**
   * The instant of the most recent successful {@link #save}; a fresh scope reports its
   * construction instant. Staleness — a dead effect versus a slow one — is read from here (§6.1).
   */
  java.time.Instant lastSaved();
```

(import `Instant`.)

`InMemoryAgentStateStore.java` — replace the `AtomicReference<State>` with an entry pair so state and stamp move atomically:

```java
public final class InMemoryAgentStateStore implements AgentStateStore {

  private record Entry(State state, Instant savedAt) {}

  private final Clock clock;
  private final AtomicReference<Entry> current;

  public InMemoryAgentStateStore() {
    this(Clock.systemUTC());
  }

  public InMemoryAgentStateStore(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.current = new AtomicReference<>(new Entry(State.initial(), clock.instant()));
  }

  @Override
  public State load() {
    return current.get().state();
  }

  @Override
  public Instant lastSaved() {
    return current.get().savedAt();
  }

  @Override
  public void save(State state) {
    Objects.requireNonNull(state, "state must not be null");
    State next = new State(state.phase(), state.version() + 1);
    while (true) {
      Entry stored = current.get();
      if (stored.state().version() != state.version()) {
        throw new StaleStateException(state.version(), stored.state().version());
      }
      if (current.compareAndSet(stored, new Entry(next, clock.instant()))) {
        return;
      }
    }
  }
}
```

(imports: `Clock`, `Instant`.) The CAS argument shifts from `State` to `Entry` — the identity-uniqueness argument from the final review holds unchanged, since every installed `Entry` is freshly allocated.

- [ ] **Step 4: Run to verify all store tests pass (old four included)**

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: the store learns when it last moved — staleness gets its clock"
```

---

### Task 4: The seams — `spi` package, `Agent`, `AgentWiring`

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/Sink.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/LatentSink.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/Memory.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/Backlog.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/ObservationRenderer.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/ModelCallExecutor.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/ToolCallExecutor.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/spi/AgentObserver.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Agent.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/AgentWiring.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/spi/LatentSinkTest.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/AgentWiringTest.java`

**Interfaces (Produces — Tasks 5–6 and Plan 3 build against these exact shapes):**

```java
// spi.Sink
@FunctionalInterface public interface Sink { void deliver(AgentEvent event); }
// spi.Memory — pre-scoped (§3.5); a no-op remember is legal
public interface Memory { void remember(Message message); Context recall(); }
// spi.Backlog — pre-scoped; poll may return something other than what was just added
public interface Backlog<O> { void add(O observation); Optional<O> poll(); }
// spi.ObservationRenderer — applied at poll time (§3.7)
@FunctionalInterface public interface ObservationRenderer<O> { List<ContentBlock> render(O observation); }
// spi.ModelCallExecutor / spi.ToolCallExecutor — async by contract; hold their Sink from construction (§4)
public interface ModelCallExecutor { void callModel(); }
public interface ToolCallExecutor { void executeTool(ToolCall call); }
// spi.AgentObserver — machine-level narration; Plan 3 adapts it to TurnObserver
public interface AgentObserver {
  void applied(AgentEvent event, Transition transition);
  void ignored(AgentEvent event);
  void renderFailed(Object observation, RuntimeException error);
  static AgentObserver noop() { ... }
}
// Agent — the whole public API (§3)
public interface Agent<O> { void observe(O observation); void drive(); }
// AgentWiring — every collaborator + both knobs; Plan 3's builders produce these
public record AgentWiring<O>(Memory memory, AgentStateStore store, Backlog<O> backlog,
    ObservationRenderer<O> renderer, ModelCallExecutor model, ToolCallExecutor tools,
    AgentObserver observer, boolean drainOnIdle, Duration staleThreshold, Clock clock) { }
```

- [ ] **Step 1: Write the failing tests**

`spi/LatentSinkTest.java`:

```java
package org.jwcarman.nessy.agent.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;

class LatentSinkTest {

  private static final AgentEvent EVENT =
      new AgentEvent.ModelFinished(new ModelOutcome.Failed("late"));

  @Test
  void deliveringBeforeBindingIsAProgrammingError() {
    var sink = new LatentSink();
    assertThatThrownBy(() -> sink.deliver(EVENT)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aBoundSinkForwardsEveryDelivery() {
    var sink = new LatentSink();
    List<AgentEvent> seen = new ArrayList<>();
    sink.bind(seen::add);
    sink.deliver(EVENT);
    assertThat(seen).containsExactly(EVENT);
  }

  @Test
  void bindingTwiceIsAProgrammingError() {
    var sink = new LatentSink();
    sink.bind(event -> {});
    Sink second = event -> {};
    assertThatThrownBy(() -> sink.bind(second)).isInstanceOf(IllegalStateException.class);
  }
}
```

`AgentWiringTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

class AgentWiringTest {

  private static final Memory MEMORY =
      new Memory() {
        @Override
        public void remember(Message message) {}

        @Override
        public Context recall() {
          return Context.empty();
        }
      };

  private static final Backlog<String> BACKLOG =
      new Backlog<>() {
        @Override
        public void add(String observation) {}

        @Override
        public Optional<String> poll() {
          return Optional.empty();
        }
      };

  @Test
  void everyCollaboratorIsRequired() {
    var store = new InMemoryAgentStateStore();
    ObservationRenderer<String> renderer = text -> List.of();
    ModelCallExecutor model = () -> {};
    ToolCallExecutor tools = call -> {};
    assertThatThrownBy(
            () ->
                new AgentWiring<>(
                    null, store, BACKLOG, renderer, model, tools, AgentObserver.noop(), false,
                    Duration.ofMinutes(5), Clock.systemUTC()))
        .isInstanceOf(NullPointerException.class);
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test` — Expected: COMPILE FAILURE.

- [ ] **Step 3: Implement the seams**

Each interface exactly as the Produces block above, one file each, with one-line javadoc citing its spec section. Two full listings:

`spi/LatentSink.java`:

```java
package org.jwcarman.nessy.agent.spi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.agent.AgentEvent;

/**
 * The construction-order answer (§4): executors hold their Sink from birth, but the sink's target
 * exists only after the agent is built. A factory constructs executors around a LatentSink and
 * binds it once, immediately after constructing the agent. Delivering before the bind is a wiring
 * bug and fails loudly.
 */
public final class LatentSink implements Sink {

  private final AtomicReference<Sink> target = new AtomicReference<>();

  public void bind(Sink sink) {
    Objects.requireNonNull(sink, "sink must not be null");
    if (!target.compareAndSet(null, sink)) {
      throw new IllegalStateException("this sink is already bound");
    }
  }

  @Override
  public void deliver(AgentEvent event) {
    Sink bound = target.get();
    if (bound == null) {
      throw new IllegalStateException("sink delivered to before being bound");
    }
    bound.deliver(event);
  }
}
```

`spi/AgentObserver.java`:

```java
package org.jwcarman.nessy.agent.spi;

import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Transition;

/**
 * Machine-level narration: exactly what the shell decided, including the next phase. Observers
 * narrate; they never influence (§8). TurnObserver adaptation is built on top of this seam.
 */
public interface AgentObserver {

  /** One event applied: the fact and the whole transition — next phase, commits, effects. */
  void applied(AgentEvent event, Transition transition);

  /** A stale or duplicate completion, discarded before anything was written (§3.4). */
  void ignored(AgentEvent event);

  /** A renderer threw; the observation is discarded and the scope stays idle (§3.7). */
  void renderFailed(Object observation, RuntimeException error);

  /** Accepts everything, tells no one. */
  static AgentObserver noop() {
    return new AgentObserver() {
      @Override
      public void applied(AgentEvent event, Transition transition) {}

      @Override
      public void ignored(AgentEvent event) {}

      @Override
      public void renderFailed(Object observation, RuntimeException error) {}
    };
  }
}
```

`AgentWiring.java`:

```java
package org.jwcarman.nessy.agent;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.jwcarman.nessy.agent.spi.AgentObserver;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;

/**
 * Everything a shell needs, pre-scoped (§3.5), plus the two host knobs: who executes the drain
 * (§3.1) and when a quiet phase counts as dead (§6.1). Plan 3's builders produce these.
 */
public record AgentWiring<O>(
    Memory memory,
    AgentStateStore store,
    Backlog<O> backlog,
    ObservationRenderer<O> renderer,
    ModelCallExecutor model,
    ToolCallExecutor tools,
    AgentObserver observer,
    boolean drainOnIdle,
    Duration staleThreshold,
    Clock clock) {

  public AgentWiring {
    Objects.requireNonNull(memory, "memory must not be null");
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(backlog, "backlog must not be null");
    Objects.requireNonNull(renderer, "renderer must not be null");
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(observer, "observer must not be null");
    Objects.requireNonNull(staleThreshold, "staleThreshold must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
  }
}
```

`Agent.java`:

```java
package org.jwcarman.nessy.agent;

/**
 * The whole public API (§3): observations in, progress on demand. The continuation door is not
 * here — executors hold a Sink from construction, and fabricating a completion is not expressible
 * in an application's vocabulary.
 */
public interface Agent<O> {

  /** Enqueue one ambient world fact; the backlog coalesces however it likes (§3.3). */
  void observe(O observation);

  /** Make this scope make progress: drain at Idle, re-fire when stale, else nothing (§6.1). */
  void drive();
}
```

`Memory`, `Backlog`, `ObservationRenderer`, `ModelCallExecutor`, `ToolCallExecutor`, `Sink`: exactly the Produces block, each with its one-line spec-citing javadoc.

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: the seams — every collaborator gets its door, and the sink is born latent"
```

---

### Task 5: `DefaultAgent` — deliver, apply, dispatch

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DefaultAgent.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/PumpedExecutor.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/RecordingMemory.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/RecordingObserver.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/ScriptedModelExecutor.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/ScriptedToolExecutor.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/support/RaceOnceStore.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DefaultAgentApplyTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–4.
- Produces: `DefaultAgent<O> implements Agent<O>` with constructor `DefaultAgent(AgentWiring<O> wiring)` and **package-private** `void deliver(AgentEvent event)` (the Sink target). Task 6 adds no surface — only tests.

- [ ] **Step 1: Write the support doubles (complete listings)**

`support/PumpedExecutor.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;

/** Tasks queue; the test pumps until quiet. Deterministic asynchrony, no threads (§3.2). */
public final class PumpedExecutor implements Executor {

  private final Deque<Runnable> queue = new ArrayDeque<>();

  @Override
  public void execute(Runnable task) {
    queue.add(task);
  }

  public void pumpUntilQuiet() {
    while (!queue.isEmpty()) {
      queue.poll().run();
    }
  }

  public boolean isQuiet() {
    return queue.isEmpty();
  }
}
```

`support/RecordingMemory.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/** Remembers in order; recall is an executor concern the shell never touches. */
public final class RecordingMemory implements Memory {

  private final List<Message> remembered = new ArrayList<>();

  @Override
  public void remember(Message message) {
    remembered.add(message);
  }

  @Override
  public Context recall() {
    return Context.of(List.copyOf(remembered));
  }

  public List<Message> remembered() {
    return List.copyOf(remembered);
  }
}
```

`support/RecordingObserver.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.Transition;
import org.jwcarman.nessy.agent.spi.AgentObserver;

/** Writes down everything the shell says. */
public final class RecordingObserver implements AgentObserver {

  public record Applied(AgentEvent event, Transition transition) {}

  private final List<Applied> applied = new ArrayList<>();
  private final List<AgentEvent> ignored = new ArrayList<>();
  private final List<Object> renderFailures = new ArrayList<>();

  @Override
  public void applied(AgentEvent event, Transition transition) {
    applied.add(new Applied(event, transition));
  }

  @Override
  public void ignored(AgentEvent event) {
    ignored.add(event);
  }

  @Override
  public void renderFailed(Object observation, RuntimeException error) {
    renderFailures.add(observation);
  }

  public List<Applied> applied() {
    return List.copyOf(applied);
  }

  public List<AgentEvent> ignored() {
    return List.copyOf(ignored);
  }

  public List<Object> renderFailures() {
    return List.copyOf(renderFailures);
  }
}
```

`support/ScriptedModelExecutor.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;
import org.jwcarman.nessy.agent.spi.Sink;

/**
 * Answers each callModel() with the next scripted outcome, delivered asynchronously through the
 * pump — honoring the §4 contract: the Sink never fires on the dispatching stack.
 */
public final class ScriptedModelExecutor implements org.jwcarman.nessy.agent.spi.ModelCallExecutor {

  private final Executor pump;
  private final Sink sink;
  private final Deque<ModelOutcome> script = new ArrayDeque<>();
  private final List<Integer> memorySizesAtCall = new ArrayList<>();
  private final RecordingMemory memory;

  public ScriptedModelExecutor(Executor pump, Sink sink, RecordingMemory memory) {
    this.pump = pump;
    this.sink = sink;
    this.memory = memory;
  }

  public void enqueue(ModelOutcome outcome) {
    script.add(outcome);
  }

  @Override
  public void callModel() {
    memorySizesAtCall.add(memory.remembered().size());
    ModelOutcome outcome = script.poll();
    if (outcome == null) {
      throw new IllegalStateException("callModel with an empty script");
    }
    pump.execute(() -> sink.deliver(new AgentEvent.ModelFinished(outcome)));
  }

  public int callCount() {
    return memorySizesAtCall.size();
  }

  /** What memory held at the instant of each call — the commit-before-dispatch witness (§3.4). */
  public List<Integer> memorySizesAtCall() {
    return List.copyOf(memorySizesAtCall);
  }
}
```

(Convert the FQN implements clause to an import of `ModelCallExecutor`.)

`support/ScriptedToolExecutor.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ToolOutcome;
import org.jwcarman.nessy.agent.spi.Sink;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.api.tool.ToolCall;

/** Answers each call by id with its scripted outcome, asynchronously through the pump. */
public final class ScriptedToolExecutor implements ToolCallExecutor {

  private final Executor pump;
  private final Sink sink;
  private final Map<String, ToolOutcome> outcomes = new HashMap<>();
  private final List<ToolCall> executed = new ArrayList<>();

  public ScriptedToolExecutor(Executor pump, Sink sink) {
    this.pump = pump;
    this.sink = sink;
  }

  public void answer(String callId, ToolOutcome outcome) {
    outcomes.put(callId, outcome);
  }

  @Override
  public void executeTool(ToolCall call) {
    executed.add(call);
    ToolOutcome outcome = outcomes.get(call.id());
    if (outcome == null) {
      throw new IllegalStateException("no scripted outcome for call " + call.id());
    }
    pump.execute(() -> sink.deliver(new AgentEvent.ToolFinished(call, outcome)));
  }

  public List<ToolCall> executed() {
    return List.copyOf(executed);
  }
}
```

`support/RaceOnceStore.java`:

```java
package org.jwcarman.nessy.agent.support;

import java.time.Instant;
import java.util.Objects;
import org.jwcarman.nessy.agent.State;
import org.jwcarman.nessy.agent.store.AgentStateStore;

/**
 * Simulates one lost race: the first save is preceded by a competitor's save (supplied by the
 * test), so the delegate throws a genuine StaleStateException; every later save goes straight
 * through. The competitor's state is computed by the test with the pure phase machine.
 */
public final class RaceOnceStore implements AgentStateStore {

  private final AgentStateStore delegate;
  private final State competitor;
  private boolean raced;

  public RaceOnceStore(AgentStateStore delegate, State competitor) {
    this.delegate = Objects.requireNonNull(delegate);
    this.competitor = Objects.requireNonNull(competitor);
  }

  @Override
  public State load() {
    return delegate.load();
  }

  @Override
  public Instant lastSaved() {
    return delegate.lastSaved();
  }

  @Override
  public void save(State state) {
    if (!raced) {
      raced = true;
      delegate.save(competitor); // someone else won first
    }
    delegate.save(state);
  }
}
```

- [ ] **Step 2: Write the failing tests**

`DefaultAgentApplyTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.LatentSink;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.PumpedExecutor;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.agent.support.RecordingMemory;
import org.jwcarman.nessy.agent.support.RecordingObserver;
import org.jwcarman.nessy.agent.support.ScriptedModelExecutor;
import org.jwcarman.nessy.agent.support.ScriptedToolExecutor;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class DefaultAgentApplyTest {

  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());
  private static final ToolCall CALL_B =
      new ToolCall("b", "restart", JsonNodeFactory.instance.objectNode());

  /** One fully-wired agent on a pump; the fixture is the test's vocabulary. */
  static final class Fixture {
    final PumpedExecutor pump = new PumpedExecutor();
    final LatentSink sink = new LatentSink();
    final RecordingMemory memory = new RecordingMemory();
    final RecordingObserver observer = new RecordingObserver();
    final ScriptedModelExecutor model = new ScriptedModelExecutor(pump, sink, memory);
    final ScriptedToolExecutor tools = new ScriptedToolExecutor(pump, sink);
    final Deque<String> backlogQueue = new ArrayDeque<>();
    final Backlog<String> backlog =
        new Backlog<>() {
          @Override
          public void add(String observation) {
            backlogQueue.add(observation);
          }

          @Override
          public Optional<String> poll() {
            return Optional.ofNullable(backlogQueue.poll());
          }
        };
    final AgentStateStore store;
    final DefaultAgent<String> agent;

    Fixture(AgentStateStore store, boolean drainOnIdle) {
      this.store = store;
      this.agent =
          new DefaultAgent<>(
              new AgentWiring<>(
                  memory,
                  store,
                  backlog,
                  text -> List.of(new TextBlock(text)),
                  model,
                  tools,
                  observer,
                  drainOnIdle,
                  Duration.ofMinutes(5),
                  Clock.systemUTC()));
      sink.bind(agent::deliver);
    }

    Fixture() {
      this(new InMemoryAgentStateStore(), false);
    }
  }

  @Test
  void aFullTurnRunsObserveToIdle() {
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("hello back")), List.of()));
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.memory.remembered())
        .containsExactly(
            Message.user(List.of(new TextBlock("hello"))),
            Message.assistant(List.of(new TextBlock("hello back"))));
    assertThat(f.observer.applied()).hasSize(2);
  }

  @Test
  void theUserMessageIsInMemoryBeforeTheModelIsCalled() {
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.model.memorySizesAtCall()).isNotEmpty();
    assertThat(f.model.memorySizesAtCall().getFirst()).isEqualTo(1);
  }

  @Test
  void aFanOutCommitsTheWholeUnitExactlyOnce() {
    var f = new Fixture();
    var turnBlocks =
        List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a"), new ToolUseBlock(CALL_B, null));
    f.model.enqueue(new ModelOutcome.Responded(turnBlocks, List.of(CALL_A, CALL_B)));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("both done")), List.of()));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.tools.answer("b", new ToolOutcome.Returned(ToolResult.ok("restarted")));
    f.agent.observe("do both");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.tools.executed()).containsExactly(CALL_A, CALL_B);
    assertThat(f.memory.remembered())
        .containsExactly(
            Message.user(List.of(new TextBlock("do both"))),
            Message.assistant(turnBlocks),
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false),
                    new ToolResultBlock("b", "restarted", false))),
            Message.assistant(List.of(new TextBlock("both done"))));
  }

  @Test
  void aDuplicateToolDeliveryIsIgnoredAndWritesNothing() {
    var f = new Fixture();
    var turnBlocks = List.<ContentBlock>of(new ToolUseBlock(CALL_A, null));
    f.model.enqueue(new ModelOutcome.Responded(turnBlocks, List.of(CALL_A)));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    f.tools.answer("a", new ToolOutcome.Returned(ToolResult.ok("42")));
    f.agent.observe("go");
    f.pump.pumpUntilQuiet();
    var rememberedBefore = f.memory.remembered();
    f.agent.deliver(
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Returned(ToolResult.ok("42-again"))));
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.ignored()).hasSize(1);
    assertThat(f.memory.remembered()).isEqualTo(rememberedBefore);
  }

  @Test
  void aModelFailureEndsTheTurnQuietlyInBand() {
    var f = new Fixture();
    f.model.enqueue(new ModelOutcome.Failed("overloaded"));
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
    assertThat(f.memory.remembered())
        .containsExactly(Message.user(List.of(new TextBlock("hello"))));
    assertThat(f.observer.applied()).hasSize(2);
  }

  @Test
  void aCompletionThatLosesTheRaceIsReHandledAgainstFreshState() {
    // Seed a store mid-fan-out: AwaitingTools{a,b}, and let a competitor apply a's result
    // out-of-band just before b's save — computed with the pure machine, no threads needed.
    var inner = new InMemoryAgentStateStore();
    var turn =
        Message.assistant(
            List.<ContentBlock>of(new ToolUseBlock(CALL_A, null), new ToolUseBlock(CALL_B, null)));
    var awaiting = new Phase.AwaitingTools(turn, java.util.Set.of("a", "b"), List.of());
    inner.save(new State(awaiting, 0L)); // now at v1
    var aFinished =
        new AgentEvent.ToolFinished(CALL_A, new ToolOutcome.Returned(ToolResult.ok("42")));
    var competitorState = new State(awaiting.handle(aFinished).next(), 1L);
    var f = new Fixture(new RaceOnceStore(inner, competitorState), false);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    f.agent.deliver(
        new AgentEvent.ToolFinished(CALL_B, new ToolOutcome.Returned(ToolResult.ok("ok"))));
    f.pump.pumpUntilQuiet();
    // b lost its first save, re-handled against the competitor's state, and correctly closed
    // the unit: exactly one commit of turn + results, exactly one model call.
    assertThat(f.memory.remembered())
        .containsExactly(
            turn,
            Message.toolResults(
                List.of(
                    new ToolResultBlock("a", "42", false), new ToolResultBlock("b", "ok", false))));
    assertThat(f.model.callCount()).isEqualTo(1);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }
}
```

(Convert the `java.util.Set` FQN to an import.)

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent test` — Expected: COMPILE FAILURE (`DefaultAgent` missing).

- [ ] **Step 4: Implement `DefaultAgent`**

```java
package org.jwcarman.nessy.agent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.agent.store.StaleStateException;
import org.jwcarman.nessy.api.message.ContentBlock;

/**
 * The shell: load–handle–save–dispatch with a retry (§3.4). No concurrency machinery — the
 * store's version CAS is the only lock (§3.2), and executors deliver on their own stacks (§4).
 */
public final class DefaultAgent<O> implements Agent<O> {

  private final AgentWiring<O> wiring;

  public DefaultAgent(AgentWiring<O> wiring) {
    this.wiring = Objects.requireNonNull(wiring, "wiring must not be null");
  }

  @Override
  public void observe(O observation) {
    wiring.backlog().add(observation);
    drive();
  }

  @Override
  public void drive() {
    State state = wiring.store().load();
    if (state.phase() instanceof Phase.Idle) {
      drain();
      return;
    }
    if (isStale()) {
      state.phase().outstandingEffects().forEach(this::dispatch); // §6.1 — the re-fire arm
    }
  }

  /**
   * The continuation door: executors hold this (via a bound Sink) from construction. Completions
   * that lose the version race re-handle against fresh state until applied or ignored (§3.4).
   */
  void deliver(AgentEvent event) {
    while (true) {
      try {
        applyOnce(event);
        return;
      } catch (StaleStateException e) {
        // another writer advanced the scope — re-handle against what it left behind
      }
    }
  }

  private void applyOnce(AgentEvent event) {
    State state = wiring.store().load();
    Transition t = state.phase().handle(event); // decide before committing
    if (t.isIgnored()) {
      wiring.observer().ignored(event);
      return;
    }
    t.commit().forEach(wiring.memory()::remember); // commit before dispatch
    wiring.store().save(new State(t.next(), state.version()));
    wiring.observer().applied(event, t);
    t.effects().forEach(this::dispatch);
    if (t.next() instanceof Phase.Idle && wiring.drainOnIdle()) {
      drive(); // §3.1 — the autonomous wiring's drain executor
    }
  }

  private void drain() {
    while (wiring.store().load().phase() instanceof Phase.Idle) {
      Optional<O> next = wiring.backlog().poll();
      if (next.isEmpty()) {
        return;
      }
      O observation = next.get();
      List<ContentBlock> content;
      try {
        content = wiring.renderer().render(observation);
      } catch (RuntimeException e) {
        wiring.observer().renderFailed(observation, e); // discard; stay idle; keep draining
        continue;
      }
      try {
        applyOnce(new AgentEvent.Observed(content));
      } catch (StaleStateException e) {
        wiring.backlog().add(observation); // lost race → back to the backlog (§3.3)
      }
    }
  }

  private boolean isStale() {
    Duration age = Duration.between(wiring.store().lastSaved(), wiring.clock().instant());
    return age.compareTo(wiring.staleThreshold()) >= 0;
  }

  private void dispatch(Effect effect) {
    switch (effect) {
      case Effect.CallModel ignored -> wiring.model().callModel();
      case Effect.ExecuteTool(var call) -> wiring.tools().executeTool(call);
    }
  }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 6: Full build, format, commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "feat: the shell — load, handle, save, dispatch, and nothing else"
```

---

### Task 6: Drain and recovery — the rest of the behavioral contract

**Files:**
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DefaultAgentDrainTest.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DefaultAgentRecoveryTest.java`

**Interfaces:** none new — tests over Task 5's shell. Reuse `DefaultAgentApplyTest.Fixture` by promoting it: move the `Fixture` class to its own file `nessy-agent/src/test/java/org/jwcarman/nessy/agent/AgentFixture.java` (same package, package-private class named `AgentFixture`, same fields/constructors — update `DefaultAgentApplyTest` references). Add one constructor `AgentFixture(AgentStateStore store, boolean drainOnIdle, Duration staleThreshold, TestClock clock)` that passes the clock and threshold into the wiring (import `org.jwcarman.nessy.agent.support.TestClock`).

- [ ] **Step 1: Write and pass the drain tests**

`DefaultAgentDrainTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.RaceOnceStore;
import org.jwcarman.nessy.api.message.TextBlock;

class DefaultAgentDrainTest {

  @Test
  void aFailingRendererDiscardsTheObservationAndKeepsDraining() {
    // A second agent over the SAME collaborators: instances are stateless views (§3.5), so the
    // fixture's sink delivering to f.agent while we drive `poisoned` is correct by design —
    // both apply against the shared store, and this test quietly proves interchangeability.
    var f = new AgentFixture();
    var poisoned =
        new DefaultAgent<String>(
            new AgentWiring<>(
                f.memory,
                f.store,
                f.backlog,
                text -> {
                  if (text.startsWith("bad")) {
                    throw new IllegalArgumentException("unrenderable");
                  }
                  return List.of(new TextBlock(text));
                },
                f.model,
                f.tools,
                f.observer,
                false,
                java.time.Duration.ofMinutes(5),
                java.time.Clock.systemUTC()));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.backlogQueue.add("bad-observation");
    f.backlogQueue.add("good-observation");
    poisoned.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.observer.renderFailures()).containsExactly("bad-observation");
    assertThat(f.memory.remembered())
        .contains(org.jwcarman.nessy.api.message.Message.user(List.of(new TextBlock("good-observation"))));
  }

  @Test
  void anObservationThatLosesTheRaceReturnsToTheBacklog() {
    // Competitor moves the scope off Idle just before our save; benign duplicate in memory is
    // the accepted §5.2 class — the assertion is the re-add, not memory purity.
    var inner = new InMemoryAgentStateStore();
    var competitorState = new State(new Phase.AwaitingModel(), 0L);
    var f = new AgentFixture(new RaceOnceStore(inner, competitorState), false);
    f.agent.observe("hello");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("hello");
    assertThat(f.store.load().phase()).isEqualTo(new Phase.AwaitingModel());
  }

  @Test
  void autonomousWiringDrainsTheNextObservationWhenTheTurnEnds() {
    var f = new AgentFixture(new InMemoryAgentStateStore(), true);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("one")), List.of()));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("two")), List.of()));
    f.agent.observe("first");
    f.agent.observe("second"); // arrives mid-turn; queues
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    assertThat(f.model.callCount()).isEqualTo(2);
  }

  @Test
  void interactiveWiringLeavesTheBacklogForTheNextDrive() {
    var f = new AgentFixture(new InMemoryAgentStateStore(), false);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("one")), List.of()));
    f.agent.observe("first");
    f.agent.observe("second");
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).containsExactly("second"); // waits for the client's next stream
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("two")), List.of()));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
  }
}
```

(Convert FQN inline uses — `java.time.Duration`, `java.time.Clock`, `Message` — to imports.)

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS (Fixture promotion done first; apply-test still green).

- [ ] **Step 2: Write and pass the recovery tests**

`DefaultAgentRecoveryTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.store.InMemoryAgentStateStore;
import org.jwcarman.nessy.agent.support.TestClock;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class DefaultAgentRecoveryTest {

  private static final Instant T0 = Instant.parse("2026-08-20T12:00:00Z");
  private static final Duration THRESHOLD = Duration.ofMinutes(5);
  private static final ToolCall CALL_A =
      new ToolCall("a", "lookup", JsonNodeFactory.instance.objectNode());

  private static AgentFixture stalled(Phase phase, TestClock clock) {
    var store = new InMemoryAgentStateStore(clock);
    store.save(new State(phase, 0L));
    return new AgentFixture(store, false, THRESHOLD, clock);
  }

  @Test
  void aFreshTurnIsLeftAlone() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    clock.advance(Duration.ofSeconds(30));
    f.agent.drive();
    assertThat(f.model.callCount()).isZero();
  }

  @Test
  void aStaleModelCallIsReFired() {
    var clock = new TestClock(T0);
    var f = stalled(new Phase.AwaitingModel(), clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("recovered")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.model.callCount()).isEqualTo(1);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void aStaleFanOutReFiresOnlyThePendingCallsWithTheirFullArguments() {
    var clock = new TestClock(T0);
    var turn = Message.assistant(List.<ContentBlock>of(new ToolUseBlock(CALL_A, "sig-a")));
    var f = stalled(new Phase.AwaitingTools(turn, Set.of("a"), List.of()), clock);
    f.tools.answer("a", new ToolOutcome.Returned(org.jwcarman.nessy.api.tool.ToolResult.ok("42")));
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("done")), List.of()));
    clock.advance(Duration.ofMinutes(6));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.tools.executed()).containsExactly(CALL_A);
    assertThat(f.store.load().phase()).isEqualTo(new Phase.Idle());
  }

  @Test
  void aStaleIdleScopeJustDrains() {
    var clock = new TestClock(T0);
    var store = new InMemoryAgentStateStore(clock);
    var f = new AgentFixture(store, false, THRESHOLD, clock);
    f.model.enqueue(new ModelOutcome.Responded(List.of(new TextBlock("ok")), List.of()));
    f.backlogQueue.add("waiting");
    clock.advance(Duration.ofHours(1));
    f.agent.drive();
    f.pump.pumpUntilQuiet();
    assertThat(f.backlogQueue).isEmpty();
    assertThat(f.model.callCount()).isEqualTo(1);
  }
}
```

(Convert the `ToolResult` FQN to an import.)

Run: `./mvnw -q -pl nessy-agent test` — Expected: PASS.

- [ ] **Step 3: Full build, format, commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent
git commit -m "test: the drain keeps its promises and recovery re-fires only what died"
```

---

## What Plan 3 picks up from here

`Memory` implementations (verbatim/summarising), the park desk with expiry behind a real `ToolCallExecutor` (gate + `AuthzContext` policy seam per §4.2's 2026-08-20 ruling), the `TurnObserver` adapter over `AgentObserver` (`TurnEnded` reshape, sync adapter), and the `cli()`/`web()`/`autonomous()` builders producing `AgentWiring`s. Plan 4: core distillation + the opaque-payload store SPI move + the dependency enforcer.
