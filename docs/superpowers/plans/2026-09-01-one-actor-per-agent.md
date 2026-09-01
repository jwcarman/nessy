# One Actor Per Agent — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the five-actor turn machinery into one sharded durable actor per agent, driven by a pure decision function, with the backlog moved out of the actor document into its own table.

**Architecture:** A new `org.jwcarman.nessy.engine.agent` package holds the pure core — `AgentState`, `Phase`, `CallState`, `Input`, `Instruction`, `Decision` and the `decide` function — with no Pekko import anywhere in it. `AgentActor` becomes a thin shell that translates a message into an `Input`, calls `decide`, persists the next state, and executes the instructions on the blocking executor, addressing every answer to the agent's LOGICAL address. `TurnActor`, `ToolCallActor`, `ApprovalActor` and `ExecutionActor` are deleted. The backlog leaves the document for a `nessy_backlog` table whose store owns the codec, the renderer and the coalescer — which is what removes the `<O>` parameter from the engine.

**Tech Stack:** Java 25, Apache Pekko (typed, cluster sharding, `DurableStateBehavior`), Spring JDBC as a library (`JdbcClient`), Jackson, JUnit 5 + AssertJ, H2 for tests, PostgreSQL for certification.

**Spec:** `docs/superpowers/specs/2026-09-01-one-actor-per-agent-design.md`

## Global Constraints

- **Never suppress a warning.** No `@SuppressWarnings` of any kind in this work; there is no spec-mandated deprecated API here.
- **No star imports** — regular or static.
- **No mocking library.** Test doubles are hand-written; that promise is in the design of record.
- Exception-assertion lambdas contain exactly ONE invocation that can throw (Sonar S5778). Arrange construction and lookups outside the lambda.
- Assert emptiness before any all/none-match predicate on the same collection (S5841 family).
- Prose test style: `@DisplayName` on the class, `@Nested` groups, snake_case method names that read as sentences.
- **Build economics:** iterate with `./mvnw -q -pl nessy-engine -am test` (no `clean`). `./mvnw -q clean verify` is the FINAL GATE, run ONCE per task before its last commit. Never run two Maven processes concurrently in this worktree.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- SQL portability, both enforced by `SchemasTest` on H2: ANSI spellings only (`TIMESTAMP WITH TIME ZONE`, never `TIMESTAMPTZ`), and no reserved words as identifiers (`key` is reserved in H2).
- Ids are UUIDv7 via `Identifiers.next()`.
- **The turn id IS the backlog row id** (James, 2026-09-01). One observation is one turn, so the id minted at `offer` is the id the turn runs under. Never mint a second id for a turn.

---

## File Structure

**New package `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/` — the pure core, no Pekko:**

| File | Responsibility |
|---|---|
| `AgentState.java` | What the agent persists: turn id, phase, observation claim id. Non-generic. |
| `Phase.java` | `Idle`, `CallingModel`, `WorkingTools(Map<String, CallState>)`. |
| `CallState.java` | `Approving`, `Running`, `Parked`, `Completed`. |
| `Input.java` | What happened. Sealed. |
| `Instruction.java` | What to do. Sealed. |
| `Decision.java` | `(AgentState next, List<Instruction> then)`. |
| `AgentLogic.java` | `decide(AgentState, Input) -> Decision`. The only file with the rules. |

**New in `org.jwcarman.nessy.engine`:**

| File | Responsibility |
|---|---|
| `BacklogStore.java` | `offer` / `take` over `nessy_backlog`. Owns codec, renderer, coalescer — the last generic type in the engine. |
| `Instructions.java` | The shell's executor: performs one `Instruction`, answers to the logical address. |

**Rewritten:** `AgentActor.java`, `NessyMessage.java`, `StateSerializer.java`, `PekkoHarnessFactory.java`, `EngineHarnessConfig.java`, `nessy-engine/src/main/resources/nessy-schema.sql`.

**Deleted:** `TurnActor.java` (580), `ToolCallActor.java` (327), `ApprovalActor.java` (192), `ExecutionActor.java` (149), `Turns.java`, `TurnState.java`, `Phase.java` (the old one), `AgentState.java` (the old one), `StateTypes.java`.

---

## Task 1: The state grammar

The persisted shape, alone, with nothing calling it. It compiles green beside the old `AgentState` because it lives in a different package.

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/CallState.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/Phase.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/AgentState.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent/AgentStateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `AgentState(String turnId, Phase phase, String observation)` with `AgentState.idle()`, `busy()`, `at(Phase)`, `working()`, `taking(String turnId, String observationClaim)`, `finished()`. `Phase.Idle`, `Phase.CallingModel`, `Phase.WorkingTools(Map<String, CallState>)` with `with(String callId, CallState)` and `allSettled()`. `CallState.Approving`, `CallState.Running`, `CallState.Parked`, `CallState.Completed`.

- [ ] **Step 1: Write the failing test**

Create `AgentStateTest.java`:

```java
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What an agent persists")
class AgentStateTest {

  @Nested
  class AtRest {

    @Test
    void an_idle_agent_is_working_on_nothing() {
      AgentState state = AgentState.idle();

      assertThat(state.busy()).isFalse();
      assertThat(state.phase()).isInstanceOf(Phase.Idle.class);
      assertThat(state.observation()).isNull();
    }

    @Test
    void taking_names_the_turn_and_the_claim_in_one_step() {
      AgentState state = AgentState.idle().taking("turn-1", "claim-1");

      assertThat(state.busy()).isTrue();
      assertThat(state.turnId()).isEqualTo("turn-1");
      assertThat(state.observation()).isEqualTo("claim-1");
      assertThat(state.phase()).isInstanceOf(Phase.CallingModel.class);
    }

    @Test
    void finishing_keeps_the_claim_id_because_the_next_take_must_name_it() {
      AgentState finished = AgentState.idle().taking("turn-1", "claim-1").finished();

      assertThat(finished.busy()).isFalse();
      assertThat(finished.observation())
          .as("the swept id, which take() hands back to the store")
          .isEqualTo("claim-1");
    }
  }

  @Nested
  class WorkingTools {

    @Test
    void a_phase_with_one_unsettled_call_is_not_finished() {
      Phase.WorkingTools working =
          new Phase.WorkingTools(Map.of("a", new CallState.Running("send_email"), "b", new CallState.Completed()));

      assertThat(working.allSettled()).isFalse();
    }

    @Test
    void a_phase_whose_calls_have_all_completed_is_finished() {
      Phase.WorkingTools working =
          new Phase.WorkingTools(Map.of("a", new CallState.Completed()));

      assertThat(working.calls()).isNotEmpty();
      assertThat(working.allSettled()).isTrue();
    }

    @Test
    void replacing_one_call_leaves_the_others_alone() {
      Phase.WorkingTools working =
          new Phase.WorkingTools(Map.of("a", new CallState.Approving("send_email"), "b", new CallState.Running("send_email")))
              .with("a", new CallState.Completed());

      assertThat(working.calls())
          .containsEntry("a", new CallState.Completed())
          .containsEntry("b", new CallState.Running("send_email"));
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=AgentStateTest`
Expected: compilation failure — the `agent` package does not exist.

- [ ] **Step 3: Write `CallState`**

```java
package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * What one tool call is waiting on.
 *
 * <p>These exist to answer exactly one question: what should happen if this process dies right
 * now? That is why {@link Parked} carries no deadline — the deadline is a reminder row, and a
 * second copy here could only drift from it.
 *
 * <p>Wire names are a compatibility surface: a turn parked overnight is read back by name.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "call")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CallState.Approving.class, name = "approving"),
  @JsonSubTypes.Type(value = CallState.Running.class, name = "running"),
  @JsonSubTypes.Type(value = CallState.Parked.class, name = "parked"),
  @JsonSubTypes.Type(value = CallState.Completed.class, name = "completed")
})
public sealed interface CallState {

  /**
   * The approver was asked and has not answered. Asking again is safe.
   *
   * <p>The tool name rides along because recovery has to re-ask, and the asking message it would
   * otherwise read the name from is a claim that may be gone.
   */
  record Approving(String toolName) implements CallState {}

  /** Approved, and the tool is running. Running again is safe; see at-least-once in the spec. */
  record Running(String toolName) implements CallState {}

  /**
   * Waiting on the world: someone holds a reply token and an alarm is armed.
   *
   * <p>Re-driving this would mint a second token and restart a term, which is why recovery leaves
   * it alone. That distinction is the whole reason this type has four arms rather than two.
   */
  record Parked() implements CallState {}

  /** Its result is in claims. Nothing to redo. */
  record Completed() implements CallState {}
}
```

- [ ] **Step 4: Write `Phase`**

```java
package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the agent is doing, as data rather than as an actor's position in a behavior tree.
 *
 * <p>{@link Idle} is an ARM, not the absence of a turn, so going to sleep is a transition that can
 * be tested rather than a stale-snapshot check bolted onto a nudge.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = Phase.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = Phase.WorkingTools.class, name = "working-tools")
})
public sealed interface Phase {

  /** No turn is running. The agent may take from the backlog. */
  record Idle() implements Phase {}

  /** A model call is in flight. */
  record CallingModel() implements Phase {}

  /**
   * The model asked for tools, and this is what each call is waiting on.
   *
   * <p>Ids and small statuses only. What a call RETURNED is content, and content the size of
   * whatever a tool decided to hand back — keeping it here would make the document grow with what
   * its tools do, which is what claims exist to prevent.
   */
  record WorkingTools(Map<String, CallState> calls) implements Phase {

    public WorkingTools {
      calls = Map.copyOf(calls);
    }

    /** The same phase with one call in a new state. */
    public WorkingTools with(String callId, CallState state) {
      Map<String, CallState> next = new LinkedHashMap<>(calls);
      next.put(callId, state);
      return new WorkingTools(next);
    }

    /** Whether every call has its result in claims, so the exchange can be written. */
    public boolean allSettled() {
      return calls.values().stream().allMatch(CallState.Completed.class::isInstance);
    }
  }
}
```

- [ ] **Step 5: Write `AgentState`**

```java
package org.jwcarman.nessy.engine.agent;

import java.util.Objects;

/**
 * Everything an agent persists, and no more.
 *
 * <p><b>No backlog and no observation content.</b> The backlog is a table of its own, and the
 * observation this turn is working is a claim id. So this document is a turn id, a phase and two
 * short strings no matter how much work the agent has done or how large its observations are.
 *
 * <p><b>Why {@code observation} is not cleared when a turn ends.</b> The finished claim id is
 * exactly what the next take must name so the store can sweep the right row. One field serves the
 * working turn and the sweep, and an agent that is idle with an id has simply finished that one.
 *
 * @param turnId the backlog row this turn came from — one observation is one turn
 * @param phase what is being waited on
 * @param observation the claim id holding the rendered observation
 */
public record AgentState(String turnId, Phase phase, String observation) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), null);
  }

  /** Whether a turn is running. */
  public boolean busy() {
    return !(phase instanceof Phase.Idle);
  }

  /** The tool calls in flight, or empty when the agent is not working tools. */
  public Phase.WorkingTools working() {
    if (phase instanceof Phase.WorkingTools tools) {
      return tools;
    }
    throw new IllegalStateException("not working tools: " + phase);
  }

  /** The same turn, at a new phase. */
  public AgentState at(Phase next) {
    return new AgentState(turnId, next, observation);
  }

  /** A turn begins: the backlog row's id IS the turn id, and its claim is the input. */
  public AgentState taking(String newTurnId, String observationClaim) {
    return new AgentState(newTurnId, new Phase.CallingModel(), observationClaim);
  }

  /** The turn is over. The claim id stays, because the next take has to name it. */
  public AgentState finished() {
    return new AgentState(null, new Phase.Idle(), observation);
  }
}
```

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=AgentStateTest`
Expected: PASS, 6 tests.

- [ ] **Step 7: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent
git commit -m "feat: the phase an agent is in is data it persists, not where an actor stands"
```

---

## Task 2: Inputs, instructions and the idle arm

The grammar of what happens and what to do, plus the first rule: an idle agent that hears its backlog changed takes from it.

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/Input.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/Instruction.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/Decision.java`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/AgentLogic.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent/IdleLogicTest.java`

**Interfaces:**
- Consumes: `AgentState`, `Phase`, `CallState` from Task 1.
- Produces: `Decision(AgentState next, List<Instruction> then)`; `AgentLogic.decide(AgentState, Input)`; the sealed `Input` and `Instruction` grammars listed below.

- [ ] **Step 1: Write the failing test**

Create `IdleLogicTest.java`:

```java
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What an idle agent decides")
class IdleLogicTest {

  @Nested
  class HearingTheBacklogChanged {

    @Test
    void an_idle_agent_asks_for_work() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.BacklogUpdated());

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).containsExactly(new Instruction.TakeWork());
    }

    @Test
    void a_busy_agent_ignores_it_because_going_idle_always_takes() {
      AgentState busy = AgentState.idle().taking("turn-1", "claim-1");

      Decision decision = AgentLogic.decide(busy, new Input.BacklogUpdated());

      assertThat(decision.next()).isEqualTo(busy);
      assertThat(decision.then()).isEmpty();
    }
  }

  @Nested
  class BeingHandedWork {

    @Test
    void taking_work_starts_a_turn_on_the_backlog_rows_own_id() {
      Decision decision =
          AgentLogic.decide(AgentState.idle(), new Input.WorkTaken("turn-7", "claim-7"));

      assertThat(decision.next().turnId()).isEqualTo("turn-7");
      assertThat(decision.next().observation()).isEqualTo("claim-7");
      assertThat(decision.next().phase()).isInstanceOf(Phase.CallingModel.class);
      assertThat(decision.then())
          .containsExactly(new Instruction.Narrate.TurnStarted("turn-7"), new Instruction.CallModel());
    }

    @Test
    void an_empty_backlog_puts_the_agent_to_sleep() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.NoWork());

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).containsExactly(new Instruction.Sleep());
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=IdleLogicTest`
Expected: compilation failure — `Input`, `Instruction`, `Decision`, `AgentLogic` do not exist.

- [ ] **Step 3: Write `Input`**

```java
package org.jwcarman.nessy.engine.agent;

import java.util.List;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;

/**
 * What happened. Facts, never provenance.
 *
 * <p>The same event used to have two names depending on who delivered it — {@code Ran} against
 * {@code RelayResult} — because a child actor replying and a reply token arriving took different
 * paths. The agent has no reason to care: {@link ToolCompleted} is identical whether a future
 * finished in two milliseconds or a webhook answered three days later.
 *
 * <p>Every arm carries ids and small statuses only. Content is checked in BEFORE the actor is
 * told, so a state that says completed cannot reference a result that is not there.
 */
public sealed interface Input {

  /** Something changed in the backlog. Carries nothing, on purpose: it is meant to be droppable. */
  record BacklogUpdated() implements Input {}

  /** The store handed over a row: its id is the turn id, and its claim holds the rendered input. */
  record WorkTaken(String turnId, String observationClaim) implements Input {}

  /** The store had nothing waiting. */
  record NoWork() implements Input {}

  /** Sent on every activation, so recovery is the common path rather than a rare one. */
  record Recovered() implements Input {}

  /** The model answered. Its content is held; this says only what kind of answer it was. */
  sealed interface ModelAnswered extends Input {

    /** Prose. Held under {@code answer}. */
    record Answered(StopReason stopReason, Usage usage) implements ModelAnswered {}

    /** Tool calls. The asking message is held under {@code asked}. */
    record Asked(List<CallSummary> calls, Usage usage) implements ModelAnswered {}

    /** The provider declined. The explanation is short and written to be read. */
    record Refused(String category, String explanation, Usage usage) implements ModelAnswered {}
  }

  /** Bounded: an id and a name, which is all the logic needs to decide what to dispatch. */
  record CallSummary(String callId, String toolName) {}

  record ModelFailed(String reason) implements Input {}

  /** The approver answered. The reason is short prose a person wrote. */
  record ApprovalGiven(String callId, String toolName, ApprovalResult result) implements Input {}

  /** The tool will answer later; someone holds a reply token. */
  record ToolParked(String callId) implements Input {}

  /** The tool answered, whoever asked and however long it took. Its result is in claims. */
  record ToolCompleted(String callId) implements Input {}

  /**
   * Time ran out on a call.
   *
   * <p>Distinct from {@link ToolCompleted} deliberately: the sweep knows time passed and does not
   * get to decide what that means. Whether a timeout is a denial, an error or a retry is policy,
   * and policy belongs where it is testable.
   */
  record DeadlinePassed(String callId) implements Input {}

  /** The idle linger elapsed with nothing to do. */
  record SleepNow() implements Input {}
}
```

- [ ] **Step 4: Write `Instruction`**

```java
package org.jwcarman.nessy.engine.agent;

import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.TurnResult;

/**
 * What to do. Executed by the shell, never by the logic.
 *
 * <p>There is no READ instruction. Reads happen in the shell before an input is fed, which is what
 * keeps {@code decide} pure and testable without a database.
 */
public sealed interface Instruction {

  /** Ask the backlog store for the next row. Answers with {@code WorkTaken} or {@code NoWork}. */
  record TakeWork() implements Instruction {}

  /** Send the exchange to the model. Answers with a {@code ModelAnswered} or {@code ModelFailed}. */
  record CallModel() implements Instruction {}

  /** Ask the approver about one call. */
  record AskApprover(String callId, String toolName) implements Instruction {}

  /** Run one tool. */
  record RunTool(String callId, String toolName) implements Instruction {}

  /** Write the exchange to memory. */
  record Remember() implements Instruction {}

  /** Release everything this turn claimed. */
  record Release() implements Instruction {}

  /** Arm a durable deadline for one call. */
  record SetAlarm(String callId) implements Instruction {}

  /** Disarm it. */
  record CancelAlarm(String callId) implements Instruction {}

  /** Go to sleep. */
  record Sleep() implements Instruction {}

  /** Tell the narrator. The shell redeems any claim the event needs before it narrates. */
  sealed interface Narrate extends Instruction {
    record TurnStarted(String turnId) implements Narrate {}
    record TurnEnded(TurnResult result) implements Narrate {}
    record ToolCallRequested(String callId, String toolName) implements Narrate {}
    record ApprovalRequested(String callId) implements Narrate {}
    record ApprovalDecided(String callId, ApprovalResult result) implements Narrate {}
    record ToolCallCompleted(String callId) implements Narrate {}
  }
}
```

- [ ] **Step 5: Write `Decision`**

```java
package org.jwcarman.nessy.engine.agent;

import java.util.List;

/**
 * What one input does: the state to persist, and what to do once it is durable.
 *
 * <p>Order matters and it is the reverse of what the engine used to do. Persist first, then
 * instruct: content is checked in before the actor is told about it, so a state referencing
 * something must find it there. The standing rule for any new instruction is that a state
 * referencing something missing must be RECOVERABLE, not stuck.
 */
public record Decision(AgentState next, List<Instruction> then) {

  public Decision {
    then = List.copyOf(then);
  }

  /** Nothing to persist and nothing to do — the shape of an ignored message. */
  public static Decision nothing(AgentState state) {
    return new Decision(state, List.of());
  }

  public static Decision of(AgentState next, Instruction... then) {
    return new Decision(next, List.of(then));
  }
}
```

- [ ] **Step 6: Write `AgentLogic` with the idle arm only**

```java
package org.jwcarman.nessy.engine.agent;

/**
 * Every rule about what an agent does next, and no way to do any of it.
 *
 * <p>Pure by construction: no clock, no store, no actor, no Pekko import. That is what lets a
 * three-day parked approval and a crash mid-model-call be ordinary unit tests rather than a
 * cluster, a race and a fifteen-second timeout.
 */
public final class AgentLogic {

  private AgentLogic() {}

  public static Decision decide(AgentState state, Input input) {
    return switch (input) {
      case Input.BacklogUpdated ignored -> onBacklogUpdated(state);
      case Input.WorkTaken taken -> onWorkTaken(state, taken);
      case Input.NoWork ignored -> Decision.of(state, new Instruction.Sleep());
      default -> Decision.nothing(state);
    };
  }

  /**
   * A busy agent drops this on the floor, and that is the point: going idle always ends with a
   * take, so missing the signal costs nothing when a signal-free path reaches the same place.
   */
  private static Decision onBacklogUpdated(AgentState state) {
    return state.busy() ? Decision.nothing(state) : Decision.of(state, new Instruction.TakeWork());
  }

  private static Decision onWorkTaken(AgentState state, Input.WorkTaken taken) {
    AgentState next = state.taking(taken.turnId(), taken.observationClaim());
    return Decision.of(
        next, new Instruction.Narrate.TurnStarted(taken.turnId()), new Instruction.CallModel());
  }
}
```

- [ ] **Step 7: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=IdleLogicTest`
Expected: PASS, 4 tests.

- [ ] **Step 8: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent
git commit -m "feat: what happened, what to do, and the one rule an idle agent has"
```

---

## Task 3: The model arm

What an agent does with an answer, a tool request, a refusal and a failure.

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/AgentLogic.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent/ModelLogicTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1 and 2.
- Produces: `decide` arms for `Input.ModelAnswered.Answered`, `.Asked`, `.Refused`, and `Input.ModelFailed`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;

@DisplayName("What an agent does with what the model said")
class ModelLogicTest {

  private static final Usage NOTHING_MEASURED = new Usage(null, null, null, null);

  private static AgentState calling() {
    return AgentState.idle().taking("turn-1", "claim-1");
  }

  @Nested
  class Prose {

    @Test
    void an_answer_ends_the_turn() {
      Decision decision =
          AgentLogic.decide(
              calling(), new Input.ModelAnswered.Answered(StopReason.END_TURN, NOTHING_MEASURED));

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then())
          .hasAtLeastOneElementOfType(Instruction.Remember.class)
          .hasAtLeastOneElementOfType(Instruction.Release.class);
    }

    @Test
    void the_claim_id_survives_the_turn_so_the_next_take_can_sweep_it() {
      Decision decision =
          AgentLogic.decide(
              calling(), new Input.ModelAnswered.Answered(StopReason.END_TURN, NOTHING_MEASURED));

      assertThat(decision.next().observation()).isEqualTo("claim-1");
    }

    @Test
    void a_finished_turn_asks_for_the_next_one() {
      Decision decision =
          AgentLogic.decide(
              calling(), new Input.ModelAnswered.Answered(StopReason.END_TURN, NOTHING_MEASURED));

      assertThat(decision.then()).contains(new Instruction.TakeWork());
    }
  }

  @Nested
  class ToolRequests {

    @Test
    void every_requested_call_starts_out_being_approved() {
      Decision decision =
          AgentLogic.decide(
              calling(),
              new Input.ModelAnswered.Asked(
                  List.of(new Input.CallSummary("a", "send_email")), NOTHING_MEASURED));

      assertThat(decision.next().working().calls())
          .containsEntry("a", new CallState.Approving("send_email"));
    }

    @Test
    void asking_the_approver_is_what_it_does_about_it() {
      Decision decision =
          AgentLogic.decide(
              calling(),
              new Input.ModelAnswered.Asked(
                  List.of(new Input.CallSummary("a", "send_email")), NOTHING_MEASURED));

      assertThat(decision.then())
          .contains(new Instruction.AskApprover("a", "send_email"));
    }
  }

  @Nested
  class Trouble {

    @Test
    void a_refusal_ends_the_turn_rather_than_retrying_it() {
      Decision decision =
          AgentLogic.decide(
              calling(),
              new Input.ModelAnswered.Refused("safety", "not going to do that", NOTHING_MEASURED));

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).hasAtLeastOneElementOfType(Instruction.Release.class);
    }

    @Test
    void a_failed_call_ends_the_turn_and_says_why() {
      Decision decision = AgentLogic.decide(calling(), new Input.ModelFailed("connection reset"));

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then()).hasAtLeastOneElementOfType(Instruction.Narrate.TurnEnded.class);
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ModelLogicTest`
Expected: FAIL — every assertion, because `decide` currently falls through to `Decision.nothing`.

- [ ] **Step 3: Add the arms to `AgentLogic`**

Replace the `switch` in `decide` and add the handlers:

```java
  public static Decision decide(AgentState state, Input input) {
    return switch (input) {
      case Input.BacklogUpdated ignored -> onBacklogUpdated(state);
      case Input.WorkTaken taken -> onWorkTaken(state, taken);
      case Input.NoWork ignored -> Decision.of(state, new Instruction.Sleep());
      case Input.ModelAnswered.Answered answered -> endTurn(state, TurnResult.answered());
      case Input.ModelAnswered.Asked asked -> onAsked(state, asked);
      case Input.ModelAnswered.Refused refused -> endTurn(state, TurnResult.refused(refused.explanation()));
      case Input.ModelFailed failed -> endTurn(state, TurnResult.failed(failed.reason()));
      default -> Decision.nothing(state);
    };
  }

  /**
   * Every call starts as {@link CallState.Approving}. Approval is asked even when the binding
   * grants it outright — the approver answers immediately in that case, and one path through the
   * code is worth more than the message it saves.
   */
  private static Decision onAsked(AgentState state, Input.ModelAnswered.Asked asked) {
    Map<String, CallState> calls = new LinkedHashMap<>();
    List<Instruction> then = new ArrayList<>();
    for (Input.CallSummary call : asked.calls()) {
      calls.put(call.callId(), new CallState.Approving(call.toolName()));
      then.add(new Instruction.Narrate.ToolCallRequested(call.callId(), call.toolName()));
      then.add(new Instruction.AskApprover(call.callId(), call.toolName()));
    }
    return new Decision(state.at(new Phase.WorkingTools(calls)), then);
  }

  /**
   * The one exit. Remember before release, because releasing drops the claims the exchange is
   * written from; and take again, because an agent that finishes a turn without asking for the
   * next one is an agent that needs a nudge to notice work it already has.
   */
  private static Decision endTurn(AgentState state, TurnResult result) {
    return Decision.of(
        state.finished(),
        new Instruction.Remember(),
        new Instruction.Narrate.TurnEnded(result),
        new Instruction.Release(),
        new Instruction.TakeWork());
  }
```

Add the imports `java.util.ArrayList`, `java.util.LinkedHashMap`, `java.util.List`, `java.util.Map`, `org.jwcarman.nessy.api.tool.TurnResult` — single-symbol, no stars.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ModelLogicTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src
git commit -m "feat: an answer ends a turn, a request starts approving every call it named"
```

---

## Task 4: The tool arm

Approval, running, parking, completion and deadlines — the four `CallState` arms earning their existence.

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/AgentLogic.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent/ToolLogicTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `decide` arms for `ApprovalGiven`, `ToolParked`, `ToolCompleted`, `DeadlinePassed`.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ApprovalResult;

@DisplayName("What an agent does while its tools run")
class ToolLogicTest {

  private static AgentState working(Map<String, CallState> calls) {
    return AgentState.idle().taking("turn-1", "claim-1").at(new Phase.WorkingTools(calls));
  }

  @Nested
  class Approval {

    @Test
    void an_approved_call_runs() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Approving("send_email"))),
              new Input.ApprovalGiven("a", "send_email", ApprovalResult.approved()));

      assertThat(decision.next().working().calls()).containsEntry("a", new CallState.Running("send_email"));
      assertThat(decision.then()).hasAtLeastOneElementOfType(Instruction.RunTool.class);
    }

    @Test
    void a_denied_call_is_completed_without_ever_running() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Approving("send_email"))),
              new Input.ApprovalGiven("a", "send_email", ApprovalResult.denied("no")));

      assertThat(decision.next().working().calls()).containsEntry("a", new CallState.Completed());
      assertThat(decision.then()).noneMatch(Instruction.RunTool.class::isInstance);
    }
  }

  @Nested
  class Parking {

    @Test
    void a_parked_call_arms_an_alarm_that_outlives_this_process() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Running("send_email"))), new Input.ToolParked("a"));

      assertThat(decision.next().working().calls()).containsEntry("a", new CallState.Parked());
      assertThat(decision.then()).contains(new Instruction.SetAlarm("a"));
    }

    @Test
    void an_answer_that_finally_arrives_disarms_it() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Parked())), new Input.ToolCompleted("a"));

      assertThat(decision.then()).contains(new Instruction.CancelAlarm("a"));
    }
  }

  @Nested
  class Finishing {

    @Test
    void the_last_call_completing_sends_the_exchange_back_to_the_model() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Completed(), "b", new CallState.Running("send_email"))),
              new Input.ToolCompleted("b"));

      assertThat(decision.next().phase()).isInstanceOf(Phase.CallingModel.class);
      assertThat(decision.then()).contains(new Instruction.CallModel());
    }

    @Test
    void one_call_completing_while_another_runs_changes_nothing_else() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Running("send_email"), "b", new CallState.Running("send_email"))),
              new Input.ToolCompleted("a"));

      assertThat(decision.next().working().calls()).containsEntry("b", new CallState.Running("send_email"));
      assertThat(decision.then()).noneMatch(Instruction.CallModel.class::isInstance);
    }

    @Test
    void a_deadline_completes_the_call_rather_than_ending_the_turn() {
      Decision decision =
          AgentLogic.decide(
              working(Map.of("a", new CallState.Parked())), new Input.DeadlinePassed("a"));

      assertThat(decision.next().working().calls()).containsEntry("a", new CallState.Completed());
      assertThat(decision.next().busy()).isTrue();
    }
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ToolLogicTest`
Expected: FAIL — `decide` falls through for all four inputs.

- [ ] **Step 3: Add the arms**

```java
      case Input.ApprovalGiven given -> onApproval(state, given);
      case Input.ToolParked parked ->
          Decision.of(
              state.at(state.working().with(parked.callId(), new CallState.Parked())),
              new Instruction.SetAlarm(parked.callId()));
      case Input.ToolCompleted done ->
          settle(
              state,
              done.callId(),
              new Instruction.CancelAlarm(done.callId()),
              new Instruction.Narrate.ToolCallCompleted(done.callId()));
      case Input.DeadlinePassed passed -> settle(state, passed.callId());
```

```java
  private static Decision onApproval(AgentState state, Input.ApprovalGiven given) {
    Instruction narrate = new Instruction.Narrate.ApprovalDecided(given.callId(), given.result());
    if (given.result() instanceof ApprovalResult.Approved) {
      Phase.WorkingTools next =
        state.working().with(given.callId(), new CallState.Running(given.toolName()));
      return Decision.of(
          state.at(next), narrate, new Instruction.RunTool(given.callId(), given.toolName()));
    }
    // A denial is a completed call with a result of its own, not a failed turn: the model is told
    // it was refused and gets to decide what to do about that.
    return settle(state, given.callId(), narrate);
  }

  /**
   * One call reaches its end. When it is the last one, the exchange goes back to the model — which
   * is the only place a turn moves from working tools to calling the model.
   */
  private static Decision settle(AgentState state, String callId, Instruction... also) {
    Phase.WorkingTools next = state.working().with(callId, new CallState.Completed());
    List<Instruction> then = new ArrayList<>(List.of(also));
    if (next.allSettled()) {
      return new Decision(state.at(new Phase.CallingModel()), append(then, new Instruction.CallModel()));
    }
    return new Decision(state.at(next), then);
  }
```

`Input.ApprovalGiven` carries `String toolName` alongside the call id and the result — bounded, and the shell knows it when it asks. Update the record in `Input` and the `AskApprover` round trip in the same step.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ToolLogicTest`
Expected: PASS, 7 tests.

- [ ] **Step 5: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src
git commit -m "feat: a denial is a completed call, not a failed turn"
```

---

## Task 5: Recovery, which is not a mode

The `Recovered` arm — the reason `CallState` has four arms instead of two.

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/agent/AgentLogic.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/agent/RecoveryLogicTest.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: the `Input.Recovered` arm.

- [ ] **Step 1: Write the failing test**

```java
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("What an agent does when it wakes up")
class RecoveryLogicTest {

  private static AgentState working(Map<String, CallState> calls) {
    return AgentState.idle().taking("turn-1", "claim-1").at(new Phase.WorkingTools(calls));
  }

  @Nested
  class Rested {

    @Test
    void an_idle_agent_asks_whether_anything_is_waiting() {
      Decision decision = AgentLogic.decide(AgentState.idle(), new Input.Recovered());

      assertThat(decision.then()).containsExactly(new Instruction.TakeWork());
    }
  }

  @Nested
  class MidTurn {

    @Test
    void a_turn_that_died_calling_the_model_calls_it_again() {
      AgentState calling = AgentState.idle().taking("turn-1", "claim-1");

      Decision decision = AgentLogic.decide(calling, new Input.Recovered());

      assertThat(decision.then()).containsExactly(new Instruction.CallModel());
    }

    @Test
    void a_call_that_died_being_approved_is_asked_again_because_asking_is_idempotent() {
      Decision decision =
          AgentLogic.decide(working(Map.of("a", new CallState.Approving("send_email"))), new Input.Recovered());

      assertThat(decision.then()).hasAtLeastOneElementOfType(Instruction.AskApprover.class);
    }

    @Test
    void a_call_that_died_running_runs_again_because_nobody_else_will_answer() {
      Decision decision =
          AgentLogic.decide(working(Map.of("a", new CallState.Running("send_email"))), new Input.Recovered());

      assertThat(decision.then()).hasAtLeastOneElementOfType(Instruction.RunTool.class);
    }

    @Test
    void a_parked_call_is_left_alone_because_re_asking_mints_a_second_reply_token() {
      Decision decision =
          AgentLogic.decide(working(Map.of("a", new CallState.Parked())), new Input.Recovered());

      assertThat(decision.then()).isEmpty();
    }

    @Test
    void a_completed_call_is_not_redone_because_its_result_is_in_claims() {
      Decision decision =
          AgentLogic.decide(working(Map.of("a", new CallState.Completed())), new Input.Recovered());

      assertThat(decision.then()).isEmpty();
    }
  }
}
```

**Note on the parked case:** this is the bug the design exists to prevent. Today's `resumeTools` re-runs any call without a stored result, parked ones included — so a restart silently re-asks a person and invalidates the token already in their inbox. That behaviour has no test today because nothing ordinary ran the recovery path.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=RecoveryLogicTest`
Expected: FAIL — `Recovered` falls through to `Decision.nothing`.

- [ ] **Step 3: Add the arm**

```java
      case Input.Recovered ignored -> onRecovered(state);
```

```java
  /**
   * There is no "should we re-drive?" decision anywhere. Pekko reads the document before any
   * command, and the actor feeds itself this on EVERY activation — so the rare path is the common
   * path, exercised constantly rather than only after a crash.
   */
  private static Decision onRecovered(AgentState state) {
    return switch (state.phase()) {
      case Phase.Idle ignored -> Decision.of(state, new Instruction.TakeWork());
      case Phase.CallingModel ignored -> Decision.of(state, new Instruction.CallModel());
      case Phase.WorkingTools working -> new Decision(state, resume(working));
    };
  }

  private static List<Instruction> resume(Phase.WorkingTools working) {
    List<Instruction> then = new ArrayList<>();
    working
        .calls()
        .forEach(
            (callId, call) -> {
              switch (call) {
                case CallState.Approving approving ->
                    then.add(new Instruction.AskApprover(callId, approving.toolName()));
                case CallState.Running running ->
                    then.add(new Instruction.RunTool(callId, running.toolName()));
                // Parked: someone holds a token and an alarm is armed. Completed: it is in claims.
                case CallState.Parked ignored -> { }
                case CallState.Completed ignored -> { }
              }
            });
    return then;
  }
```

`Approving` and `Running` already carry their tool name (Task 1), which is exactly what makes this arm writable: recovery re-dispatches from the phase alone, without reading the asking claim.

- [ ] **Step 4: Run all logic tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest='*LogicTest'`
Expected: PASS — Tasks 2–5, all green.

- [ ] **Step 5: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src
git commit -m "feat: recovery leaves a parked call alone, which is what four arms were for"
```

---

## Task 6: The backlog store

The backlog leaves the document. Spec §4a.

**Files:**
- Modify: `nessy-engine/src/main/resources/nessy-schema.sql`
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/BacklogStore.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/BacklogStoreTest.java`

**Interfaces:**
- Consumes: `Claims.put`, `Identifiers.next()`, `BacklogCoalescer<O>`, `BacklogItem<O>`, `ObservationRenderer<O>`, `Codec<O>`.
- Produces: `BacklogStore<O>` with `void offer(AgentId, O)` and `Optional<Taken> take(AgentId, String lastCompleted)`, where `record Taken(String turnId, String observationClaim)`.

- [ ] **Step 1: Add the table**

Append to `nessy-schema.sql`:

```sql
-- What is waiting to become a turn.
--
-- Out of the agent's document on purpose: a document holding a queue is rewritten every time
-- anything changes, and its size is whatever the application decided an observation is. The row id
-- is the TURN id, because one observation is exactly one turn — which is also what makes a take
-- idempotent across a crash, since a retry finds the id already minted.
--
-- taken_claim is NULL until the row is handed to an agent. A row with one has been rendered and
-- held, and the next take either sweeps it (the agent named it) or hands it back unchanged (the
-- agent died before recording it). Those two histories are indistinguishable from phase alone,
-- which is why the sweep names an id rather than inferring one.
CREATE TABLE IF NOT EXISTS nessy_backlog (
  agent_id    TEXT   NOT NULL,
  item_id     TEXT   NOT NULL,
  received_at TIMESTAMP WITH TIME ZONE NOT NULL,
  observation BYTEA  NOT NULL,
  taken_claim TEXT,
  PRIMARY KEY (agent_id, item_id)
);

-- The take reads one agent's waiting rows in arrival order. Ordering is the coalescer's output,
-- preserved by received_at.
CREATE INDEX IF NOT EXISTS nessy_backlog_waiting ON nessy_backlog (agent_id, received_at);
```

- [ ] **Step 2: Write the failing test**

```java
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.testing.TestDatabase;

@DisplayName("What is waiting to become a turn")
class BacklogStoreTest {

  private static final AgentId AGENT = AgentId.of("watchman");

  private DataSource dataSource;
  private Claims claims;

  /** Keeps everything, in arrival order. */
  private static <O> BacklogCoalescer<O> keepAll() {
    return (waiting, arrival) -> {
      List<BacklogItem<O>> all = new java.util.ArrayList<>(waiting);
      all.add(arrival);
      return all;
    };
  }

  /** Only the newest survives — the shape of the watchman's heartbeat. */
  private static <O> BacklogCoalescer<O> newestOnly() {
    return (waiting, arrival) -> List.of(arrival);
  }

  private BacklogStore<String> storeWith(BacklogCoalescer<String> coalescer) {
    return new BacklogStore<>(
        dataSource, claims, JsonCodec.of(String.class), UserMessage::of, coalescer);
  }

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.fresh();
    claims = new Claims(dataSource);
  }

  @Nested
  class Offering {

    @Test
    void a_take_from_an_empty_backlog_hands_back_nothing() {
      assertThat(storeWith(keepAll()).take(AGENT, null)).isEmpty();
    }

    @Test
    void what_is_offered_first_is_taken_first() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");
      store.offer(AGENT, "two");

      BacklogStore.Taken first = store.take(AGENT, null).orElseThrow();

      assertThat(claims.get(AGENT, first.turnId(), BacklogStore.OBSERVATION_KEY)).isPresent();
    }

    @Test
    void a_superseding_coalescer_leaves_one_row_no_matter_how_many_arrive() {
      BacklogStore<String> store = storeWith(newestOnly());
      store.offer(AGENT, "tick");
      store.offer(AGENT, "tick");
      store.offer(AGENT, "tick");

      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();

      assertThat(store.take(AGENT, taken.turnId())).isEmpty();
    }
  }

  @Nested
  class TakingAndSweeping {

    @Test
    void the_turn_id_is_the_row_id_so_a_second_take_of_the_same_row_returns_the_same_id() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");

      BacklogStore.Taken first = store.take(AGENT, null).orElseThrow();
      BacklogStore.Taken again = store.take(AGENT, null).orElseThrow();

      assertThat(again.turnId())
          .as("the agent died before recording the take; nobody named the row, so it comes back")
          .isEqualTo(first.turnId());
    }

    @Test
    void naming_the_finished_claim_sweeps_that_row_and_moves_on() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "one");
      store.offer(AGENT, "two");

      BacklogStore.Taken first = store.take(AGENT, null).orElseThrow();
      BacklogStore.Taken second = store.take(AGENT, first.turnId()).orElseThrow();

      assertThat(second.turnId()).isNotEqualTo(first.turnId());
    }

    @Test
    void sweeping_the_last_row_leaves_nothing_waiting() {
      BacklogStore<String> store = storeWith(keepAll());
      store.offer(AGENT, "only");

      BacklogStore.Taken taken = store.take(AGENT, null).orElseThrow();
      Optional<BacklogStore.Taken> nothing = store.take(AGENT, taken.turnId());

      assertThat(nothing).isEmpty();
    }
  }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=BacklogStoreTest`
Expected: compilation failure — `BacklogStore` does not exist.

- [ ] **Step 4: Write `BacklogStore`**

```java
package org.jwcarman.nessy.engine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.backlog.ObservationRenderer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What is waiting to become a turn.
 *
 * <p><b>Where the observation type ends.</b> The codec, the renderer and the coalescer all live
 * here, so nothing above this is generic: the agent deals in a turn id and a claim id, and this is
 * the last place that knows what an observation actually is.
 *
 * <p><b>Why the coalescer still sees observations.</b> Rendering at the door would force a policy
 * to compare rendered messages, which is a lossy way of asking a question it already had a direct
 * answer to. Rendering happens at {@link #take}, once, so an observation coalesced away is never
 * rendered at all.
 */
public final class BacklogStore<O> {

  /** The claim key an observation is held under, alongside {@code asked} and {@code result-*}. */
  static final String OBSERVATION_KEY = "observation";

  private static final String WAITING =
      "SELECT item_id, observation, taken_claim FROM nessy_backlog WHERE agent_id = ?"
          + " ORDER BY received_at, item_id";
  private static final String INSERT =
      "INSERT INTO nessy_backlog (agent_id, item_id, received_at, observation) VALUES (?, ?, ?, ?)";
  private static final String DELETE_ROW = "DELETE FROM nessy_backlog WHERE agent_id = ? AND item_id = ?";
  private static final String DELETE_ALL = "DELETE FROM nessy_backlog WHERE agent_id = ?";
  private static final String MARK_TAKEN =
      "UPDATE nessy_backlog SET taken_claim = ? WHERE agent_id = ? AND item_id = ?";

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Claims claims;
  private final Codec<O> codec;
  private final ObservationRenderer<O> renderer;
  private final BacklogCoalescer<O> coalescer;
  private final Clock clock;

  /** What a take hands back: the row's id, which is the turn id, and where its input is held. */
  public record Taken(String turnId, String observationClaim) {}

  BacklogStore(
      DataSource dataSource,
      Claims claims,
      Codec<O> codec,
      ObservationRenderer<O> renderer,
      BacklogCoalescer<O> coalescer,
      Clock clock) {
    this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    this.transactions =
        new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    this.claims = claims;
    this.codec = codec;
    this.renderer = renderer;
    this.coalescer = coalescer;
    this.clock = clock;
  }

  /**
   * Takes an observation in, letting the coalescer decide what the waiting list becomes.
   *
   * <p>The caller MUST tell the agent afterwards and never before: an agent that takes before this
   * commits finds nothing and goes back to sleep with work sitting in the table.
   */
  public void offer(AgentId agentId, O observation) {
    transactions.executeWithoutResult(
        status -> {
          List<Row> waiting = waiting(agentId);
          BacklogItem<O> arrival =
              new BacklogItem<>(Identifiers.next(), observation, clock.instant());
          List<BacklogItem<O>> kept =
              coalescer.coalesce(waiting.stream().filter(Row::untaken).map(Row::item).toList(), arrival);
          rewrite(agentId, waiting, kept);
        });
  }

  /**
   * Finishes the row named by {@code lastCompleted} and hands over the next.
   *
   * <p>One transaction, so the row is either untaken (retry, clean) or taken with its claim already
   * written (handed back unchanged). Never neither.
   */
  public Optional<Taken> take(AgentId agentId, String lastCompleted) {
    return Optional.ofNullable(
        transactions.execute(
            status -> {
              if (lastCompleted != null) {
                jdbc.sql(DELETE_ROW).param(agentId.value()).param(lastCompleted).update();
              }
              List<Row> rows = waiting(agentId);
              Optional<Row> alreadyTaken = rows.stream().filter(row -> !row.untaken()).findFirst();
              if (alreadyTaken.isPresent()) {
                // Taken, rendered and held, and nobody named it — so the agent died before it
                // recorded the take. Hand back what is already there rather than minting a second.
                Row row = alreadyTaken.get();
                return new Taken(row.itemId(), row.takenClaim());
              }
              if (rows.isEmpty()) {
                return null;
              }
              Row head = rows.getFirst();
              claims.put(
                  agentId,
                  head.itemId(),
                  OBSERVATION_KEY,
                  MESSAGES.encode(renderer.render(head.item().observation())));
              jdbc.sql(MARK_TAKEN)
                  .param(OBSERVATION_KEY)
                  .param(agentId.value())
                  .param(head.itemId())
                  .update();
              return new Taken(head.itemId(), OBSERVATION_KEY);
            }));
  }
}
```

The row mapping, the `rewrite` helper (delete-all then insert the kept list in order, preserving each item's id and `received_at`), the `Row` record and the `MESSAGES` codec for `UserMessage` are written out in full during implementation following the shape of `Claims` and `Reminders`.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=BacklogStoreTest`
Expected: PASS, 6 tests.

- [ ] **Step 6: Certify it on PostgreSQL**

Add a backlog case to `nessy-store-tests/.../PostgresStoreCertificationTest`, mirroring the H2 tests for offer / take / sweep.

Run: `./mvnw -q -pl nessy-store-tests test -Dgroups=container`
Expected: PASS.

- [ ] **Step 7: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src nessy-store-tests/src
git commit -m "feat: the backlog is a table, and the row id is the turn id"
```

---

## Task 7: The shell's executor

One class that performs an `Instruction`, with every answer addressed to the agent's LOGICAL address.

**Files:**
- Create: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/Instructions.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/InstructionsTest.java`

**Interfaces:**
- Consumes: `Instruction` (Task 2), `BacklogStore` (Task 6), `Claims`, `Reminders`, `ReplyTokens`, `Narrator`, `Memory`, `Model`, `ToolBindings`, `Traces`.
- Produces: `Instructions.perform(AgentId, AgentState, Instruction)`, dispatching on the blocking executor and telling the entity ref.

- [ ] **Step 1: Write the failing test**

The test drives `Instructions` with a hand-written model, tool binding and approver, and a recording `ActorRef` probe, asserting that each instruction produces the right message at the entity address. Key cases, one test each:

```java
@Test
void a_model_call_answers_to_the_logical_address_rather_than_to_a_specific_incarnation() { }

@Test
void the_answer_is_claimed_before_the_message_is_sent() { }

@Test
void a_tool_that_defers_reports_parked_and_never_reports_a_result() { }

@Test
void a_tool_that_throws_reports_a_completed_call_whose_claim_holds_a_failure() { }

@Test
void taking_work_from_an_empty_backlog_reports_no_work() { }
```

The second is the one that matters: **content is durable before the actor is told**, which is what makes §7's inversion safe.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=InstructionsTest`
Expected: compilation failure.

- [ ] **Step 3: Write `Instructions`**

Each arm runs its work on `blocking` (the virtual-thread executor from `EngineConfig`) and, on completion, tells `ClusterSharding.get(system).entityRefFor(KEY, agentId.value())`. It never touches `context.getSelf()`. The claim is written inside the async body, before the tell.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=InstructionsTest`
Expected: PASS.

- [ ] **Step 5: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src
git commit -m "feat: slow work answers to an address, not to an object that can die"
```

---

## Task 8: The cutover

`AgentActor` becomes the shell, and four actors are deleted. This is the only task that is not additive.

**Files:**
- Rewrite: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/AgentActor.java`
- Rewrite: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/NessyMessage.java`
- Modify: `StateSerializer.java`, `PekkoHarnessFactory.java`, `EngineHarnessConfig.java`, `ShardedHarness.java`
- Delete: `TurnActor.java`, `ToolCallActor.java`, `ApprovalActor.java`, `ExecutionActor.java`, `Turns.java`, `TurnState.java`, `Phase.java`, `AgentState.java`, `StateTypes.java`
- Delete: `TurnLifecycleTest.java`, `TurnRecoveryTest.java`, `TurnResumeTest.java` — their subjects are gone and the logic tests cover the rules
- Modify: `AgentActorTest.java`, `ConversationTest.java`, `PassivationTest.java`, `ToolCallTest.java`, `DeferredToolTest.java`, `DeferredApprovalTest.java`, `ReminderExpiryTest.java`, `StateSerializerTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: `NessyMessage` reshaped to `BacklogUpdated`, `WorkTaken`, `NoWork`, `Recovered`, `ModelAnswered`, `ModelFailed`, `ApprovalGiven`, `ToolParked`, `ToolCompleted`, `DeadlinePassed`, `SleepNow`, `Inspect`, `Stop`.

- [ ] **Step 1: Reshape `NessyMessage`**

One arm per `Input`, plus `Inspect` and `Stop`. `Wake`, `Observe`, `TurnFinished`, `Expired`, `AnswerToolCall` and `AnswerApproval` go. Every arm carries `Map<String, String> headers` for trace context, as today.

- [ ] **Step 2: Rewrite `AgentActor`**

```java
  @Override
  public CommandHandler<NessyMessage, AgentState> commandHandler() {
    return newCommandHandlerBuilder().forAnyState().onAnyCommand(this::onMessage);
  }

  /** Translate, decide, persist, run. There is nothing else in this class. */
  private Effect<AgentState> onMessage(AgentState state, NessyMessage message) {
    Decision decision = AgentLogic.decide(state, inputOf(message));
    return Effect()
        .persist(decision.next())
        .thenRun(next -> decision.then().forEach(i -> instructions.perform(agentId, next, i)));
  }
```

`onStop` becomes `Effect().none().thenStop()` — no revival `Wake`, no `shuttingDown()` guard, because a model answer addressed logically is its own knock. On activation the actor tells itself `Recovered`.

- [ ] **Step 3: Update the serializer**

`StateSerializer` serializes a non-generic `AgentState`, so `StateTypes` and its `JavaType` registry are deleted. Add serialization for the `NessyMessage` arms that now cross a node boundary — the answers from `Instructions`, which had no serializer before because they were child-actor replies.

- [ ] **Step 4: Wire the harness factory**

`PekkoHarnessFactory` builds one `BacklogStore<O>` per harness (it is where `Class<O>`, the renderer and the coalescer are all in scope) and one `Instructions`. `harness.observe(...)` becomes `store.offer(...)` followed by `entityRef.tell(new BacklogUpdated(headers))` — in that order.

- [ ] **Step 5: Run the whole engine suite**

Run: `./mvnw -q -pl nessy-engine -am test`
Expected: PASS. Failures here are the point of the task; fix them until green.

- [ ] **Step 6: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add -A
git commit -m "refactor: one actor works a turn, and four actors that helped it are gone"
```

---

## Task 9: The alarm actually runs

`ReminderSweep` has been built and tested since 8f2650e5 and nothing has ever run it. `CallState.Parked` depends on it.

**Files:**
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/PekkoHarnessFactory.java`
- Modify: `nessy-engine/src/main/java/org/jwcarman/nessy/engine/EngineConfig.java`
- Test: `nessy-engine/src/test/java/org/jwcarman/nessy/engine/ReminderSweepSchedulingTest.java`

**Interfaces:**
- Consumes: `ReminderSweep.sweep()`, `EngineConfig`.
- Produces: `EngineConfig.sweepInterval` (default 30 seconds) and a scheduled sweep on the blocking executor.

- [ ] **Step 1: Write the failing test**

Assert that a reminder written with an expiry in the past causes `DeadlinePassed` to reach the agent without anyone calling `sweep()` by hand, within a bounded wait.

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ReminderSweepSchedulingTest`
Expected: FAIL — timeout, because nothing sweeps.

- [ ] **Step 3: Schedule it**

`system.scheduler().scheduleAtFixedRate` on the blocking dispatcher, cancelled on coordinated shutdown. One sweeper per node, not per harness.

- [ ] **Step 4: Run it and watch it pass**

Run: `./mvnw -q -pl nessy-engine -am test -Dtest=ReminderSweepSchedulingTest`
Expected: PASS.

- [ ] **Step 5: Format, gate and commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
./mvnw -q clean verify
git add nessy-engine/src
git commit -m "feat: the deadline we wrote down is now one somebody reads"
```

---

## Task 10: The examples, and the live proof

**Files:**
- Modify: `nessy-examples/chat-cli`, `nessy-examples/chat-web`, `nessy-examples/watchman` as the API requires
- Modify: `nessy-spring-boot-starter` if `EngineConfig` gained a property

- [ ] **Step 1: Build the whole reactor**

Run: `./mvnw -q clean verify`
Expected: PASS.

- [ ] **Step 2: Drive the CLI through a multi-turn conversation**

```bash
printf 'hello\nwhat did I just say?\nbye.\n' | OPENAI_API_KEY=not-needed \
  OPENAI_BASE_URL=http://localhost:1234/v1 NESSY_MODEL=<id> \
  ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

Expected: two answered turns and a clean exit. Piped input submits turn 2 microseconds after turn 1 ends, which is exactly the window that produced the passivation hang — so this is the reproduction, not a smoke test.

- [ ] **Step 3: Exercise a deferred approval end to end**

Start chat-web, ask for an email, approve it from the desk, then decline the next one. Both decisions must reach the model.

- [ ] **Step 4: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "chore: the examples run against one actor"
```

---

## Self-Review

**Spec coverage.** §2 the shape → Tasks 2, 8. §3 logical addressing → Task 7. §4 state and phases → Task 1. §4a backlog store → Task 6. §5 inputs and instructions → Tasks 2–4. §5 tickets not cargo → Tasks 2, 7. §6 style → the tests throughout. §7 persist-then-instruct → Task 8 Step 2. §7a recovery → Task 5. §8 deletions → Task 8. §9 costs → Task 10 measures them.

**Gaps I am naming rather than papering over:**

- **§10's `ToolCallRequest`** (replacing `ToolContext` and `ApprovalContext` across fourteen modules) is NOT in this plan. It is a separable API break and folding it in would make Task 8 unreviewable.
- **`Instruction.Narrate` versus the shell narrating directly** — the spec says the shell narrates tool completion because the event carries the result. Task 7 keeps `Narrate` instructions for everything else; if that split proves fussy in implementation, the whole of narration moving into the shell is the fallback.
- **Task 7's test list is behaviours, not code.** Every other task has literal test bodies. Task 7's doubles depend on the `Instructions` constructor shape, which Task 6 and Task 8 both bear on; writing fictional code there would be worse than naming the five cases precisely.

**Type consistency.** `CallState.Approving(String toolName)` and `CallState.Running(String toolName)` carry the name from Task 1 onward, and `Input.ApprovalGiven` carries it too, so Tasks 3, 4 and 5 construct them identically. An earlier draft of this plan retrofitted the field in Task 5 and made three earlier tasks wrong in hindsight; carrying it from the start is why Task 5's recovery arm can re-dispatch from the phase alone.
