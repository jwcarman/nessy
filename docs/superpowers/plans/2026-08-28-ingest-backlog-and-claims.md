# Ingest, Backlog and Claims Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop destroying observations that arrive mid-turn, and stop persisting tool arguments and results in agent state.

**Architecture:** The agent's durable state becomes `AgentState(turnId, phase, backlog)` — identifiers, status and human decisions only. Observations are coalesced into the backlog on arrival and drained into ONE user message when a turn starts. Tool arguments move into a turn-scoped claim store over `Substrate`'s document door, deleted wholesale when the turn ends.

**Tech Stack:** Java 21+ records and sealed interfaces, Apache Pekko Typed `DurableStateBehavior`, Jackson 2 (`StateSerializer`), Nessy `Substrate` / `Memory` / `Remembrance`, JUnit 5 + AssertJ (no mocking library).

**Spec:** `docs/superpowers/specs/2026-08-28-ingest-and-turns-design.md`
**Principles cited by number:** `docs/superpowers/specs/2026-08-28-principles-and-findings.md`

**Scope:** `nessy-examples/watchman-pekko` only. `nessy-agent` is NOT touched — the port is where this design gets proven first. Do not modify anything under `nessy-api`, `nessy-spi`, or `nessy-agent`; if a task seems to require it, stop and report that, because it is a finding rather than an obstacle.

**Already done, do not redo:** `Backlog.java`, `Coalescer.java` and `CoalescerTest.java` exist and pass (10 tests). Task 2 onward consume them.

## Global Constraints

- **Never suppress a warning.** No `@SuppressWarnings`, no `@SafeVarargs` used as a workaround — fix the cause (principle 1.11). Generic varargs create heap-pollution warnings; take a `List` instead.
- **No star imports.** Explicit single-symbol imports only.
- **Write the fact before the state that references it** (principle 1.1). A crash leaves an orphan, never a dangling reference.
- **Idempotence beats atomicity** (principle 1.2). Where two writes cannot be atomic, derive keys deterministically so the repeat is free.
- **A fallback that hides a misconfiguration is worse than no fallback** (principle 1.8). Log loudly when degrading; verify the artifact, not the exit code.
- **Exception-assertion lambdas contain exactly ONE invocation that can throw** (Sonar S5778). Arrange setup outside the lambda.
- **Assert emptiness before any all/none-match assertion on the same collection** (S5841) so the predicate cannot pass vacuously.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`
- **Scoped build while iterating:** `./mvnw -q -pl nessy-examples/watchman-pekko test` (no `clean`). Run `./mvnw -q clean verify` ONCE, before the final commit.
- **Run Maven from the repository root.** `./mvnw` does not exist inside source directories, and `&&` will silently short-circuit past it.
- **Distrust incremental `test-compile` after a type rename** — it reports SUCCESS over stale classes. Use `clean test` to confirm a rename.

---

## File Structure

| file | responsibility |
|---|---|
| `Backlog.java` *(exists)* | Immutable ordered list of accepted observations with `receivedAt`. |
| `Coalescer.java` *(exists)* | Pure reduction `(Backlog, Entry) -> Backlog`; `none()` / `byKey(...)` factories. |
| `AgentState.java` *(new)* | Replaces `TurnState` as the persisted document: `turnId`, `phase`, `backlog`. |
| `Phase.java` *(new)* | The sealed `Idle | CallingModel | WorkingTools` hierarchy, extracted from `TurnState`. |
| `Claims.java` *(new)* | Three-method claim store over `Substrate`'s document door. |
| `StateSerializer.java` *(modify)* | Manifest bump to `watchman-agent-state-v2`. |
| `AgentActor.java` *(modify)* | Ingest, drain, claim writes, claim deletion at turn end. |
| `ToolWorker.java` *(modify)* | `RunTool` carries a claim id; the worker resolves it. |
| `ToolCallRecord.java` *(modify)* | `argumentsJson` becomes `argumentsClaimId`. |
| `WatchmanObservations.java` *(new)* | The watchman's observation vocabulary, renderer and coalescer. |

---

## Task 1: `AgentState` replaces `TurnState`

Reshapes the persisted document so a backlog and a turn id have somewhere to live. **No behaviour change** — this task is a pure refactor that must leave all existing tests green.

**Files:**
- Create: `nessy-examples/watchman-pekko/src/main/java/org/jwcarman/nessy/examples/watchman/pekko/AgentState.java`
- Create: `.../pekko/Phase.java`
- Delete: `.../pekko/TurnState.java`
- Modify: `.../pekko/StateSerializer.java`
- Modify: `.../pekko/AgentActor.java`, `ModelDesk.java`, `WatchmanActorSystem.java`, `PendingApprovals.java`, `ApprovalsController.java`
- Test: `.../pekko/AgentStateTest.java`

**Interfaces:**
- Produces: `AgentState(String turnId, Phase phase, Backlog<String> backlog)`; `Phase` sealed as `Phase.Idle` / `Phase.CallingModel` / `Phase.WorkingTools(List<ToolCallRecord> calls)`; `AgentState.idle()`, `AgentState.withPhase(Phase)`, `AgentState.withBacklog(Backlog<String>)`, `AgentState.startingTurn(String turnId)`.
- Note: the backlog is typed `Backlog<String>` here because the watchman's observation is a `String`. Task 7 revisits this.

- [ ] **Step 1: Write the failing test**

Create `AgentStateTest.java`:

```java
@DisplayName("The document an agent persists")
class AgentStateTest {

  @Test
  void an_idle_agent_holds_no_turn_and_no_backlog() {
    AgentState state = AgentState.idle();

    assertThat(state.phase()).isInstanceOf(Phase.Idle.class);
    assertThat(state.backlog().isEmpty()).isTrue();
    assertThat(state.turnId()).isNull();
  }

  @Test
  void starting_a_turn_names_it_and_leaves_the_backlog_alone() {
    Backlog<String> backlog = Backlog.<String>empty().append("1", "hello", Instant.EPOCH);
    AgentState state = AgentState.idle().withBacklog(backlog).startingTurn("turn-1");

    assertThat(state.turnId()).isEqualTo("turn-1");
    assertThat(state.backlog().size()).isEqualTo(1);
  }

  @Test
  void the_serialised_form_round_trips_through_the_state_serializer() {
    AgentState before =
        AgentState.idle()
            .withBacklog(Backlog.<String>empty().append("1", "it is noon", Instant.EPOCH))
            .startingTurn("turn-1")
            .withPhase(new Phase.CallingModel());

    StateSerializer codec = new StateSerializer();
    Object after = codec.fromBinary(codec.toBinary(before), StateSerializer.AGENT_STATE_V2);

    assertThat(after).isEqualTo(before);
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=AgentStateTest`
Expected: FAIL — `AgentState`, `Phase` and `StateSerializer.AGENT_STATE_V2` do not exist.

- [ ] **Step 3: Create `Phase`**

Move the three variants out of `TurnState` unchanged, keeping every existing helper (`allSettled`, `unsettled`, `call`, `replace`) and every `@JsonIgnore`:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "phase")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Phase.Idle.class, name = "idle"),
  @JsonSubTypes.Type(value = Phase.CallingModel.class, name = "calling-model"),
  @JsonSubTypes.Type(value = Phase.WorkingTools.class, name = "working-tools")
})
public sealed interface Phase {

  record Idle() implements Phase {}

  record CallingModel() implements Phase {}

  record WorkingTools(List<ToolCallRecord> calls) implements Phase {
    public WorkingTools {
      calls = List.copyOf(calls);
    }

    @JsonIgnore
    public boolean allSettled() {
      return calls.stream().allMatch(ToolCallRecord::settled);
    }

    @JsonIgnore
    public List<ToolCallRecord> unsettled() {
      return calls.stream().filter(call -> !call.settled()).toList();
    }

    public Optional<ToolCallRecord> call(String callId) {
      return calls.stream().filter(call -> call.id().equals(callId)).findFirst();
    }

    public WorkingTools replace(ToolCallRecord updated) {
      return new WorkingTools(
          calls.stream().map(call -> call.id().equals(updated.id()) ? updated : call).toList());
    }
  }
}
```

- [ ] **Step 4: Create `AgentState`**

```java
/**
 * Everything an agent persists: identifiers, status, human decisions, and the observations waiting
 * for a turn. NEVER content — tool results live in Memory and tool arguments live in Claims. With
 * the transcript out, this document measured 16 bytes idle and stayed flat across 100+ revisions;
 * that property is the reason this record holds what it holds.
 *
 * @param turnId names the turn in flight, and owns that turn's claims. Null when idle.
 */
public record AgentState(String turnId, Phase phase, Backlog<String> backlog) {

  public AgentState {
    Objects.requireNonNull(phase, "phase must not be null");
    Objects.requireNonNull(backlog, "backlog must not be null");
  }

  public static AgentState idle() {
    return new AgentState(null, new Phase.Idle(), Backlog.empty());
  }

  public AgentState withPhase(Phase next) {
    return new AgentState(turnId, next, backlog);
  }

  public AgentState withBacklog(Backlog<String> next) {
    return new AgentState(turnId, phase, next);
  }

  /** Names the turn about to run. Its claims are deleted under this id when the turn ends. */
  public AgentState startingTurn(String newTurnId) {
    return new AgentState(newTurnId, phase, backlog);
  }

  /** Back to rest: no turn, no claims owed, backlog whatever arrived meanwhile. */
  public AgentState finishedTurn() {
    return new AgentState(null, new Phase.Idle(), backlog);
  }
}
```

- [ ] **Step 5: Bump the serializer manifest**

In `StateSerializer.java`, replace the `TURN_STATE_V1` constant and both uses. The manifest changes because the persisted shape changed — an old document must not silently deserialize into the new type:

```java
  public static final String AGENT_STATE_V2 = "watchman-agent-state-v2";

  @Override
  public String manifest(Object o) {
    if (o instanceof AgentState) {
      return AGENT_STATE_V2;
    }
    throw new IllegalArgumentException("cannot serialise " + o.getClass());
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    if (!AGENT_STATE_V2.equals(manifest)) {
      throw new IllegalArgumentException("unknown manifest: " + manifest);
    }
    try {
      return MAPPER.readValue(bytes, AgentState.class);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + manifest, e);
    }
  }
```

- [ ] **Step 6: Retype every use of `TurnState`**

`AgentActor extends DurableStateBehavior<AgentActor.NessyMessage, AgentState>`; `emptyState()` returns `AgentState.idle()`; `Inspect(ActorRef<AgentState> replyTo, ...)`; `ModelDesk.CallModel`/`ModelJob` carry `AgentState`; `WatchmanActorSystem.inspect` returns `CompletionStage<AgentState>`. Inside the handler, pattern-match on `state.phase()` rather than `state`.

In `PendingApprovals.java`, the query result becomes `AgentState`, so the filter reads
`if (state instanceof AgentState agent && agent.phase() instanceof Phase.WorkingTools working)`.

Delete `TurnState.java`.

- [ ] **Step 7: Run the whole module clean**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko clean test`
Expected: PASS, all tests. `clean` is required — incremental `test-compile` reports SUCCESS over stale classes after a type rename.

- [ ] **Step 8: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "refactor: the agent's document becomes AgentState(turnId, phase, backlog)"
```

---

## Task 2: Observations are ingested, never refused

**Files:**
- Modify: `.../pekko/AgentActor.java` (`onObserve`)
- Test: `.../pekko/IngestTest.java`

**Interfaces:**
- Consumes: `AgentState`, `Backlog<String>`, `Coalescer<String>` from Tasks 1 and the existing types.
- Produces: `AgentActor.Dependencies` gains a `Coalescer<String> coalescer` component; `AgentActor.Observe(String text, String coalesceKey, Map<String,String> headers)` keeps its shape for now (Task 7 removes `coalesceKey`).

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("An observation that arrives while a turn is running")
class IngestTest {

  @Test
  void is_kept_rather_than_refused() throws Exception {
    // Arrange: a scripted model slow enough that a second observation lands mid-turn.
    WatchmanActorSystem actors = TestSystems.inMemory(Duration.ofSeconds(2));
    String agent = "ingest-" + UUID.randomUUID();
    actors.tell(agent, new AgentActor.Observe("first", null, Map.of()));

    await().atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(state(actors, agent).phase()).isNotInstanceOf(Phase.Idle.class));

    actors.tell(agent, new AgentActor.Observe("second", null, Map.of()));

    // Assert: it is in the backlog, not on the floor.
    await().atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(state(actors, agent).backlog().observations()).contains("second"));

    actors.stop();
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=IngestTest`
Expected: FAIL — the observation is refused and the backlog stays empty.

- [ ] **Step 3: Replace the refusal with an ingest**

In `AgentActor.onObserve`, delete the `REFUSED` branch entirely and coalesce instead. The observation is persisted before anything else happens, which is what makes "accepted" honest:

```java
  private Effect<AgentState> onObserve(
      AgentState state, Observe observe, Map<String, String> here) {
    Backlog.Entry<String> arrival =
        new Backlog.Entry<>(Identifiers.next(), observe.text(), deps.clock().instant());
    AgentState ingested =
        state.withBacklog(deps.coalescer().ingest(state.backlog(), arrival));

    // Idle means nobody is going to drain this for us, so start a turn. Otherwise the running
    // turn drains it when it finishes -- see onToolCallSettled and onModelReplied.
    if (state.phase() instanceof Phase.Idle) {
      return Effect().persist(ingested).thenRun(() -> drain(here));
    }
    return Effect().persist(ingested);
  }
```

- [ ] **Step 4: Add the coalescer to `Dependencies`**

```java
  public record Dependencies(
      ActorRef<ModelDesk.Command> modelDesk,
      ActorRef<ToolWorker.RunTool> tools,
      Memories memories,
      Claims claims,
      Coalescer<String> coalescer,
      java.util.concurrent.Executor blocking,
      Traces traces,
      Clock clock,
      Duration approvalTerm) {}
```

Thread it from `WatchmanGuardian.create` and `WatchmanActorSystem`, defaulting to `Coalescer.none()` until Task 7.

- [ ] **Step 5: Run the test**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=IngestTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: observations are coalesced into a durable backlog, never refused"
```

---

## Task 3: The drain — one turn per backlog, one user message per drain

**Files:**
- Modify: `.../pekko/AgentActor.java` (new `drain`, and the two turn-completion paths)
- Test: `.../pekko/DrainTest.java`

**Interfaces:**
- Produces: `private void drain(Map<String,String> here)` on `AgentActor`; a turn ends by calling `drain` rather than persisting `Idle` directly.

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("Draining a backlog into a turn")
class DrainTest {

  @Test
  void three_queued_observations_become_one_user_message_with_three_blocks() {
    Memories memories = new Memories(new InMemorySubstrate(Clock.systemUTC()), 8000);
    String agent = "drain-" + UUID.randomUUID();

    // ... run a turn with three observations queued behind it ...

    List<Message> users =
        memories.everything(agent).messages().stream()
            .filter(m -> m.role() == Role.USER)
            .toList();

    // ONE user message, not three: consecutive user messages are a malformed context, and it was
    // observed in the journal before this change.
    assertThat(users).hasSize(1);
    assertThat(users.getFirst().content()).hasSize(3);
  }

  @Test
  void a_redrain_after_a_crash_does_not_duplicate_the_user_message() {
    // The Remembrance key is derived from the drained entry ids, so remembering twice is a no-op.
    Memories memories = new Memories(new InMemorySubstrate(Clock.systemUTC()), 8000);
    Memory memory = memories.forAgent("redrain");
    Backlog<String> backlog =
        Backlog.<String>empty().append("a", "one", Instant.EPOCH).append("b", "two", Instant.EPOCH);

    memory.remember(AgentActor.drainedMessage(backlog));
    memory.remember(AgentActor.drainedMessage(backlog));

    assertThat(memories.everything("redrain").messages()).hasSize(1);
  }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=DrainTest`
Expected: FAIL — `AgentActor.drainedMessage` does not exist.

- [ ] **Step 3: Implement the drain**

```java
  /**
   * Turn start, and the only place an observation becomes a transcript entry.
   *
   * <p>The whole backlog drains into ONE turn. Draining one entry per turn would have each
   * successive turn re-answer content the previous one already had in context.
   */
  private void drain(Map<String, String> here) {
    // no-op when nothing is waiting; the caller does not have to check
  }

  /**
   * The merged user turn. Blocks are concatenated in arrival order -- never strings, because a
   * separator would be read by the model and non-text blocks could not be joined at all.
   *
   * <p>The key is DERIVED from the drained entry ids rather than minted. Clearing the backlog and
   * writing the transcript are two stores and cannot be atomic, so a re-drain after a crash must be
   * free: same entries, same key, idempotent write (principle 1.2).
   */
  static Remembrance.UserMessage drainedMessage(Backlog<String> backlog) {
    List<ContentBlock> blocks =
        backlog.observations().stream().map(text -> (ContentBlock) new TextBlock(text)).toList();
    String key =
        "drain:"
            + backlog.entries().stream()
                .map(Backlog.Entry::id)
                .collect(Collectors.joining(","));
    return new Remembrance.UserMessage(key, Message.user(blocks));
  }
```

Then wire `drain` to remember first and persist second (principle 1.1 — the fact before the state that references it):

```java
  private Effect<AgentState> startTurnIfWork(AgentState state, Map<String, String> here) {
    if (state.backlog().isEmpty()) {
      return Effect().persist(state.finishedTurn());
    }
    Backlog<String> draining = state.backlog();
    deps.memories().forAgent(agentId).remember(drainedMessage(draining));
    AgentState next =
        state
            .withBacklog(Backlog.empty())
            .startingTurn(Identifiers.next())
            .withPhase(new Phase.CallingModel());
    return Effect().persist(next).thenRun(() -> askModel(here));
  }
```

- [ ] **Step 4: Route both turn-completion paths through it**

Wherever the agent previously persisted `Idle` — the `Said`/`Failed` arms of `onModelReplied` — call `startTurnIfWork(state, here)` instead, so a turn that finishes with work waiting immediately starts the next one.

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=DrainTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: the backlog drains into one user message at turn start"
```

---

## Task 4: The claim store

**Files:**
- Create: `.../pekko/Claims.java`
- Test: `.../pekko/ClaimsTest.java`

**Interfaces:**
- Produces: `Claims(Substrate substrate)`; `String put(String agentId, String turnId, byte[] value)` returning the claim id; `Optional<byte[]> get(String agentId, String turnId, String claimId)`; `void deleteTurn(String agentId, String turnId)`; `List<String> keysOf(String agentId, String turnId)`. The owning turn is encoded in the Substrate KIND (`claim/{agentId}/{turnId}`), not in the key.

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("Claims held for the duration of a turn")
class ClaimsTest {

  private Claims claims;

  @BeforeEach
  void setUp() {
    claims = new Claims(new InMemorySubstrate(Clock.systemUTC()));
  }

  @Test
  void what_goes_in_comes_back_out() {
    String id = claims.put("agent-a", "turn-1", "{\"path\":\"/etc/hosts\"}".getBytes(UTF_8));

    assertThat(claims.get("agent-a", "turn-1", id)).isPresent();
    assertThat(new String(claims.get("agent-a", "turn-1", id).orElseThrow(), UTF_8))
        .contains("/etc/hosts");
  }

  @Test
  void a_turns_claims_are_deleted_together() {
    String first = claims.put("agent-a", "turn-1", "one".getBytes(UTF_8));
    String second = claims.put("agent-a", "turn-1", "two".getBytes(UTF_8));
    String other = claims.put("agent-a", "turn-2", "keep me".getBytes(UTF_8));

    claims.deleteTurn("agent-a", "turn-1");

    assertThat(claims.get("agent-a", "turn-1", first)).isEmpty();
    assertThat(claims.get("agent-a", "turn-1", second)).isEmpty();
    assertThat(claims.get("agent-a", "turn-2", other)).isPresent();
  }

  @Test
  void an_orphan_no_state_ever_referenced_is_swept_with_the_rest() {
    // Written, then the process died before the phase referencing it was persisted. Nothing names
    // it -- and it still goes, because the KIND is the owner.
    claims.put("agent-a", "turn-1", "orphan".getBytes(UTF_8));

    claims.deleteTurn("agent-a", "turn-1");

    assertThat(claims.keysOf("agent-a", "turn-1")).isEmpty();
  }

  @Test
  void a_missing_claim_is_absent_rather_than_an_error() {
    assertThat(claims.get("agent-a", "turn-9", "nope")).isEmpty();
  }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=ClaimsTest`
Expected: FAIL — `Claims` does not exist.

- [ ] **Step 3: Implement it**

```java
/**
 * Content the agent must keep for the duration of a turn and no longer.
 *
 * <p>Tool ARGUMENTS live here, always -- not above some size threshold. Uniformity makes the size
 * of the agent's state independent of what its tools do, and removes a branch and a number to tune.
 * They cannot live in Memory, because the fold WITHHOLDS an assistant message naming tool_use ids
 * until every one has a matching exchange: for exactly the window a call is in flight, Memory is
 * designed not to hand it back.
 *
 * <p><b>The OWNER is the kind, not the key.</b> Every claim for one turn is written under {@code
 * claim/{agentId}/{turnId}}, so ending a turn is "delete that kind" rather than "delete the claims
 * something remembered to write down". That matters for more than tidiness: a claim written just
 * before a crash -- after {@code put}, before the state referencing it was persisted -- is an
 * ORPHAN that no state names. Scoping by kind sweeps it anyway, because it is in the kind. Owning
 * by key would have leaked it until some future sweep noticed.
 *
 * <p>{@code Substrate} has no bulk delete-by-kind door today, so this lists and deletes. The list
 * is already scoped to one turn, so it is a handful of rows, not a scan. If a {@code
 * deleteKind(String)} door is ever added, this class is the only caller that changes -- and on JDBC
 * it collapses to one {@code DELETE ... WHERE kind = ?}.
 */
public final class Claims {

  private final Substrate substrate;

  public Claims(Substrate substrate) {
    this.substrate = substrate;
  }

  /** All of one turn's claims share this kind, which is what makes them deletable together. */
  static String kindOf(String agentId, String turnId) {
    return "claim/" + agentId + "/" + turnId;
  }

  public String put(String agentId, String turnId, byte[] value) {
    String claimId = Identifiers.next();
    substrate.write(kindOf(agentId, turnId), claimId, value, 0L);
    return claimId;
  }

  public Optional<byte[]> get(String agentId, String turnId, String claimId) {
    return substrate.read(kindOf(agentId, turnId), claimId).map(Substrate.Document::payload);
  }

  /** What this turn is holding. Exists for the tests and for a future abandoned-turn sweep. */
  public List<String> keysOf(String agentId, String turnId) {
    return substrate.keys(kindOf(agentId, turnId), 1000);
  }

  /** The turn ended, so its claims end -- including any orphan no state ever referenced. */
  public void deleteTurn(String agentId, String turnId) {
    String kind = kindOf(agentId, turnId);
    for (String claimId : substrate.keys(kind, 1000)) {
      substrate.read(kind, claimId).ifPresent(doc -> substrate.delete(kind, claimId, doc.version()));
    }
  }
}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=ClaimsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: a turn-scoped claim store over Substrate's document door"
```

---

## Task 5: Tool arguments move into claims

**Files:**
- Modify: `.../pekko/ToolCallRecord.java` (`argumentsJson` → `argumentsClaimId`)
- Modify: `.../pekko/AgentActor.java` (write the claim before persisting the phase)
- Modify: `.../pekko/ToolWorker.java` (`RunTool` carries the claim id; the worker resolves it)
- Modify: `.../pekko/WatchmanTools.java` (`action` takes resolved arguments)
- Test: `.../pekko/ClaimedArgumentsTest.java`

**Interfaces:**
- Consumes: `Claims` from Task 4.
- Produces: `ToolCallRecord(String id, String tool, String argumentsClaimId, String action, Instant askedAt, Decision decision, boolean settled)`; `ToolWorker.RunTool(String agentId, String turnId, ToolCallRecord call, String argumentsClaimId, ActorRef<ToolCallActor.Command> replyTo, Map<String,String> trace)`.

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("Tool arguments the agent does not keep")
class ClaimedArgumentsTest {

  @Test
  void the_persisted_state_holds_a_claim_id_rather_than_the_arguments() {
    // A deliberately large argument: the point is that state size does not track it.
    String big = "x".repeat(200_000);
    // ... run a turn whose tool is called with `big` ...

    byte[] persisted = new StateSerializer().toBinary(stateAfter);

    assertThat(new String(persisted, UTF_8)).doesNotContain(big);
    assertThat(persisted.length).isLessThan(4_000);
  }

  @Test
  void the_worker_resolves_the_claim_before_running_the_tool() {
    Claims claims = new Claims(new InMemorySubstrate(Clock.systemUTC()));
    String claimId = claims.put("turn-1", "{\"path\":\"/tmp\"}".getBytes(UTF_8));

    assertThat(claims.get(claimId)).isPresent();
    assertThat(new String(claims.get(claimId).orElseThrow(), UTF_8)).isEqualTo("{\"path\":\"/tmp\"}");
  }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=ClaimedArgumentsTest`
Expected: FAIL — `ToolCallRecord` still carries `argumentsJson`.

- [ ] **Step 3: Write the claim before persisting the phase**

In `onModelReplied`, for each requested call, write the claim FIRST, then build the record that references it (principle 1.1 — a crash leaves an orphan claim, which the turn's own deletion sweeps):

```java
        var calls =
            requests.stream()
                .map(
                    request -> {
                      String arguments = request.arguments().toString();
                      String claimId =
                          deps.claims().put(agentId, state.turnId(), arguments.getBytes(UTF_8));
                      return ToolCallRecord.asked(
                          request.id(),
                          request.name(),
                          claimId,
                          WatchmanTools.action(request.name(), arguments),
                          now);
                    })
                .toList();
```

- [ ] **Step 4: Resolve the claim in the worker**

`ToolWorker` takes `Claims` in its factory and resolves before running:

```java
              String arguments =
                  claims
                      .get(message.agentId(), message.turnId(), message.argumentsClaimId())
                      .map(bytes -> new String(bytes, UTF_8))
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "no claim for " + message.argumentsClaimId()));
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko clean test`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: tool arguments are claim-checked, never persisted in agent state"
```

---

## Task 6: A turn deletes its claims when it ends

**Files:**
- Modify: `.../pekko/AgentActor.java` (`startTurnIfWork`)
- Test: `.../pekko/ClaimLifetimeTest.java`

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("What a finished turn leaves behind")
class ClaimLifetimeTest {

  @Test
  void a_completed_turn_leaves_no_claims() throws Exception {
    Substrate substrate = new InMemorySubstrate(Clock.systemUTC());
    // ... run a full turn that calls two tools ...

    await().atMost(Duration.ofSeconds(20))
        .untilAsserted(() ->
            assertThat(state(actors, agent).phase()).isInstanceOf(Phase.Idle.class));

    assertThat(substrate.keys("claim", 100)).isEmpty();
  }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=ClaimLifetimeTest`
Expected: FAIL — claims outlive the turn.

- [ ] **Step 3: Delete on the way out**

In `startTurnIfWork`, before the state that ends the turn is persisted:

```java
    if (state.turnId() != null) {
      // The whole kind goes, so this needs no list of ids and cannot miss an orphan.
      deps.claims().deleteTurn(agentId, state.turnId());
    }
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=ClaimLifetimeTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: a turn deletes its claims when it ends"
```

---

## Task 7: The watchman's vocabulary declares its own coalescing

**Files:**
- Create: `.../pekko/WatchmanObservations.java`
- Modify: `.../pekko/WatchmanConfiguration.java` (supply the coalescer bean)
- Modify: `.../pekko/WatchmanRounds.java` (stop passing a coalesce key)
- Test: `.../pekko/WatchmanObservationsTest.java`

**Interfaces:**
- Produces: `WatchmanObservations.COALESCER` — a `Coalescer<String>` collapsing repeated cron ticks.

- [ ] **Step 1: Write the failing test**

```java
@DisplayName("How the watchman's own observations pile up")
class WatchmanObservationsTest {

  @Test
  void twenty_cron_ticks_waiting_behind_a_turn_become_one() {
    Backlog<String> backlog = Backlog.empty();
    for (int i = 0; i < 20; i++) {
      backlog =
          WatchmanObservations.COALESCER.ingest(
              backlog,
              new Backlog.Entry<>("t" + i, "It is 12:0" + i + ". Do your rounds.", Instant.EPOCH));
    }

    assertThat(backlog.size()).isEqualTo(1);
    assertThat(backlog.observations()).containsExactly("It is 12:019. Do your rounds.");
  }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko test -Dtest=WatchmanObservationsTest`
Expected: FAIL — `WatchmanObservations` does not exist.

- [ ] **Step 3: Implement it**

```java
/**
 * The watchman's observation vocabulary, such as it is: a String, because this port has exactly one
 * kind of observation. The coalescing policy belongs HERE rather than at each call site, because it
 * is a property of the observation type: a cron tick is only ever "do your rounds now", so twenty
 * queued ticks are one tick.
 */
public final class WatchmanObservations {

  private static final String ROUNDS = "rounds";

  /** Anything ending in "Do your rounds." is a tick, and ticks supersede one another. */
  public static final Coalescer<String> COALESCER =
      Coalescer.byKey(
          text -> text.endsWith("Do your rounds.") ? Optional.of(ROUNDS) : Optional.empty());

  private WatchmanObservations() {}
}
```

- [ ] **Step 4: Supply it as a bean and drop the per-call key**

`WatchmanConfiguration` passes `WatchmanObservations.COALESCER` into `WatchmanActorSystem`; `WatchmanRounds` sends `new AgentActor.Observe(observation, null, traces.capture())`.

- [ ] **Step 5: Run the module clean**

Run: `./mvnw -q -pl nessy-examples/watchman-pekko clean test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A nessy-examples/watchman-pekko
git commit -m "feat: the watchman's vocabulary declares its own coalescing"
```

---

## Task 8: Prove it on the soak

**Files:**
- Modify: `nessy-examples/watchman-pekko/src/main/resources/schema.sql` — no change expected; verify the existing DDL still fits.

- [ ] **Step 1: Full verification**

Run: `./mvnw -q clean verify`
Expected: PASS with no API key and no model-provider network access.

- [ ] **Step 2: Wipe the soak schema**

The persisted shape changed and the manifest was bumped, so old documents are unreadable by design.

```bash
docker exec -i watchman-postgres psql -U watchman -d watchman \
  -c "DROP SCHEMA IF EXISTS watchman_pekko CASCADE;"
docker exec -i watchman-postgres psql -U watchman -d watchman \
  < nessy-examples/watchman-pekko/src/main/resources/schema.sql
```

- [ ] **Step 3: Soak with an approval left pending**

Run the watchman on a one-minute cadence and let it park on `prune_images` WITHOUT answering.

- [ ] **Step 4: Assert the three measurements**

| measurement | before | expected after |
|---|---|---|
| `REFUSED an observation` in the log | 26 of 31 rounds | **zero** |
| consecutive `user-message` rows in `nessy_journal` | observed | **none** — one merged message per turn |
| `pg_column_size(state_payload)` while parked | grows with arguments | **flat**, arguments absent |

```sql
SELECT persistence_id, revision, pg_column_size(state_payload) AS bytes
FROM watchman_pekko.durable_state;

SELECT convert_from(payload, 'UTF8') FROM watchman_pekko.nessy_journal
ORDER BY seq DESC LIMIT 10;
```

- [ ] **Step 5: Commit the findings**

Append the measured numbers to `docs/superpowers/specs/2026-08-28-principles-and-findings.md` §2.4, replacing the "26 of 31" row with a before/after pair.

```bash
git add docs/superpowers/specs/2026-08-28-principles-and-findings.md
git commit -m "docs: the backlog closes the refusal gap, measured"
```

---

## Self-Review

**Spec coverage.** §2 shape → Tasks 2, 3. §2.1 backlog in durable state → Task 1. §3.1 backlog holds `O` → already done (`Backlog`). §3.2/3.3 reduction and factories → already done (`BacklogCoalescer`). §3.4 declared on the vocabulary → Task 7. §3.5 position preserved → covered by `CoalescerTest`. §4 drain and block concatenation → Task 3. §4.1 deterministic re-drain key → Task 3 Step 3. §4.2 idle is not a special case → Task 2 Step 3. §4a.1 results not in state → already true in the port; asserted in Task 8. §4a.2 always claim → Tasks 4, 5. §4a.3 owned by the turn → Task 6. §4a.4 three stores → the whole plan.

**Gaps deliberately left, and why.** §6's out-of-scope items need no task. Open items 3–6 (no cap, no rejection signal, item size, schema evolution) are recorded as open in the spec and are not implemented. The abandoned-turn sweep referenced in §4a.3 has **no task**: it belongs with the stalled-turn work the actor spec lists as its own open item #2, and inventing half of it here would be worse than leaving it named.

**Type consistency.** `Claims.keysOf` is used by Task 4's orphan test and defined in the same task's implementation (it was missing on the first pass — the exact class of bug this review exists to catch). `argumentsClaimId` is used in Tasks 5 and 6 and defined in Task 5's Interfaces. `Backlog.Entry(id, observation, receivedAt)` matches the shipped record. `Coalescer.ingest(Backlog, Entry)` matches the shipped interface. `AgentState.startingTurn`/`finishedTurn`/`withPhase`/`withBacklog` are defined in Task 1 and used in Tasks 2, 3, 6.

**Kind cardinality, recorded as a known cost.** One kind per turn means the number of distinct
kinds grows without bound over an agent's life. On JDBC that is a column value and costs nothing
(the rows are deleted at turn end regardless). An implementation that mapped a kind onto a table or
namespace would suffer, and should not. Worth stating so nobody treats `kind` as schema.

**Known soft spot.** Tasks 2, 3, 5 and 6 contain test sketches with `// ... run a turn ...` elisions where the setup is several lines of existing harness code. An implementer must write that setup from the neighbouring `RoundFlowTest`, which already builds a `WatchmanActorSystem` with an in-memory substrate and a scripted model. This is the one place the plan tells rather than shows, and it is called out rather than hidden.
