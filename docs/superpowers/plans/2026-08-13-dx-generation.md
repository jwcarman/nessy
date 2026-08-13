# DX Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/superpowers/specs/2026-08-13-dx-generation-design.md`: the eight evidence-backed API improvements from the chat-web dogfood, with the chat-web rewrite as the in-plan acceptance test.

**Architecture:** Five core tasks land the API (each self-contained with tests), then the chat-web rewrite consumes all of them at once — its diff deleting the apology comments is the acceptance criterion — and a docs task closes the loop. Two deliberate pre-1.0 breaks ride along: `RunOutcome.Parked` slims to `(state)`, and `Agent.resume(id)` becomes `Agent.conversation(id)`.

**Tech Stack:** Java 25, existing reactor conventions (JUnit 5 + AssertJ only, no mocking libraries, sealed grammars without `default` arms in core).

## Global Constraints

- No warning suppressions of any kind; no star imports; prose snake_case test names; S5778 (one throwing invocation per exception-assertion lambda); S5841 (assert non-emptiness before all/none-match predicates).
- Core sealed switches stay exhaustive with NO `default` arms. New `TurnEvent` variant means every existing switch over `TurnEvent` must gain an arm (compiler finds them).
- `./mvnw -q clean verify` green offline (no Docker, no key) after every task; chat-web container smoke green with Docker for Task 6.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Javadoc voice: constraints and reasons, in the house style of the file being edited.
- **Model policy:** implementers Sonnet; task reviews Sonnet except Task 3 (loop/narration semantics → Opus); scoped re-reviews Haiku.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `ToolContext.progress` — the placeholder dies

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/ToolContext.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/GatedToolCallExecutor.java:252` (the one construction site)
- Modify: `nessy-core/src/test/java/org/jwcarman/nessy/api/tool/ToolRegistryTest.java:167` (the other constructor caller)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/tool/ToolContextTest.java` (new)

**Interfaces:**
- Produces: `ToolContext(ConversationId conversationId, ToolCall call, EventEmitter events)` with `public void progress(String message)` emitting `new ToolProgress(conversationId, call.id(), message)` on `events`. Task 6 relies on `context.progress("issuing…")`.

- [ ] **Step 1: failing test.** New `ToolContextTest`:

```java
class ToolContextTest {
  @Test
  void progress_emits_with_the_frameworks_own_ids() {
    List<Object> heard = new ArrayList<>();
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    ToolContext context =
        new ToolContext(new ConversationId("s1"), call, heard::add);
    context.progress("halfway");
    assertThat(heard)
        .containsExactly(new ToolProgress(new ConversationId("s1"), "c1", "halfway"));
  }
}
```

  (Check `EventEmitter`'s functional shape first — if `heard::add` doesn't satisfy it, use the smallest hand-built emitter the existing tests use; no mocking library.)
- [ ] **Step 2:** Run — fails (no such constructor/method).
- [ ] **Step 3:** Add the `ToolCall call` component (with `requireNonNull`) and the `progress` method to `ToolContext`. Javadoc on `progress`: the framework supplies both ids — a tool cannot know its provider-assigned call id, and no longer needs to (spec §2: nothing untrusted arrives, so there is nothing to distrust). Update `GatedToolCallExecutor:252` to `new ToolContext(state.id(), call, teed(call, observer))` and `ToolRegistryTest:167` to pass its call.
- [ ] **Step 4:** `./mvnw -q -pl nessy-core clean verify` green; then full offline reactor (chat-web's `IssueCouponTool` still compiles — it uses `context.conversationId()` and `context.events()` which both survive; if the reactor breaks on the record change anywhere else, fix the call sites the compiler names).
- [ ] **Step 5:** Format; commit: `feat: ToolContext.progress — the framework's ids, nobody's placeholder`

---

### Task 2: `Context.empty`, `ConversationSnapshot`, `Agent.snapshot`

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/message/Context.java` (add `empty()`)
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationSnapshot.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/Agent.java` (add `snapshot`; `contextFor` UNCHANGED — it stays loud by spec §3)
- Test: extend `nessy-core/src/test/java/org/jwcarman/nessy/AgentTest.java`; extend `ContextTest`

**Interfaces:**
- Produces: `Context.empty()` (static, returns `Context.of(List.of())`); `record ConversationSnapshot(ConversationStatus status, List<ParkedCall> parkedCalls, Context context)` (components `requireNonNull`, lists defensively copied by the record if not already immutable); `Agent.snapshot(ConversationId id)` — total, one `store.load` + one `memory.recall`.

- [ ] **Step 1: failing tests.** In `ContextTest`: `empty_is_legal_and_has_no_messages` (`assertThat(Context.empty().messages()).isEmpty()`). In `AgentTest`, in the prose style of its neighbors:

```java
@Test
void snapshot_of_an_unknown_conversation_is_idle_and_empty() {
  ConversationSnapshot snap = agent.snapshot(new ConversationId("never-seen"));
  assertThat(snap.status()).isEqualTo(ConversationStatus.IDLE);
  assertThat(snap.parkedCalls()).isEmpty();
  assertThat(snap.context().messages()).isEmpty();
}

@Test
void snapshot_of_a_stored_conversation_carries_status_parks_and_recall() {
  // arrange with the test's existing scripted wiring: run one turn that parks
  // (copy the park arrangement from HarnessTest's resume tests), then:
  ConversationSnapshot snap = agent.snapshot(id);
  assertThat(snap.status()).isEqualTo(ConversationStatus.PARKED);
  assertThat(snap.parkedCalls()).hasSize(1);
  assertThat(snap.context().messages()).isNotEmpty();
}
```

- [ ] **Step 2:** Run — fails.
- [ ] **Step 3:** Implement. `Agent.snapshot` javadoc states the division of labor verbatim from spec §3: snapshot is total because a browser-minted fresh id is a normal page rebuild; `contextFor` throws because an unknown id under a debugger is a bug. Add the same sentence (inverted) to `contextFor`'s javadoc. Implementation:

```java
public ConversationSnapshot snapshot(ConversationId id) {
  Objects.requireNonNull(id, "id must not be null");
  return store
      .load(id)
      .map(loaded -> new ConversationSnapshot(
          loaded.state().status(), loaded.state().parkedCalls(), memory.recall(id)))
      .orElseGet(() -> new ConversationSnapshot(
          ConversationStatus.IDLE, List.of(), Context.empty()));
}
```

- [ ] **Step 4:** Module + offline reactor verify green.
- [ ] **Step 5:** Format; commit: `feat: Agent.snapshot — the page-rebuild read, total on purpose`

---

### Task 3: `TurnEvent.ToolCallParked` + `RunOutcome.Parked(state)` (HIGH-RISK REVIEW: Opus)

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/turn/TurnEvent.java` (8th variant + the two contracts in the type-level javadoc)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java` (`applyParked`, ~line 342 — the single choke point both the approver park and the tool park funnel through via `PerformOutcome.Parked`)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/RunOutcome.java:30` (`Parked(ConversationState state)`)
- Modify: every compiler-flagged switch over `TurnEvent` (expect: `TurnObserverBuilder`/`TurnObserverAdapter` dispatch, test helpers incl. the TextObserver duplicates) and every `RunOutcome.Parked` consumer (`HarnessTest:446-448,701`, `ConversationLoop`'s outcome construction, chat-web compiles in Task 6 — for THIS task make the minimal chat-web edit that keeps the reactor compiling: drop the `.token()` uses; the real rewrite is Task 6)
- Test: extend `nessy-core/src/test/java/org/jwcarman/nessy/internal/ConversationLoopTest.java`

**Interfaces:**
- Produces: `record ToolCallParked(ToolCall call, ParkToken token) implements TurnEvent`; `RunOutcome.Parked(ConversationState state)`. Task 6 relies on both.

- [ ] **Step 1: failing test.** In `ConversationLoopTest`, beside the existing park tests (copy their arrangement — a scripted tool executor whose `execute` returns `Awaited.parked(token)`):

```java
@Test
void a_park_is_narrated_to_the_observer_with_its_token() {
  List<TurnEvent> events = new ArrayList<>();
  // ...existing park arrangement...
  loop.run(ID, told, events::add);
  assertThat(events)
      .filteredOn(e -> e instanceof TurnEvent.ToolCallParked)
      .containsExactly(new TurnEvent.ToolCallParked(call, token));
}
```

- [ ] **Step 2:** Run — fails (no such type).
- [ ] **Step 3:** Add the variant. `TurnEvent`'s type-level javadoc gains the two named contracts, in spec §4's words:
  - **at-least-once narration**: retried segments may re-emit any event; observers materializing per-event UI dedupe by natural key (for `ToolCallParked`, the token);
  - **the entry-scoped-observer invariant**: capability-bearing events are legal only while observers are supplied by the caller of `tell`/`resume`, who already holds tokens via `RunOutcome`; any future standing observer must revisit `ToolCallParked` loudly.
  Emit in `applyParked` (both park paths cross it): `observer.on(new TurnEvent.ToolCallParked(call, token));` — placed so it narrates only when the park actually applies to state, not on a path that later fails to save (if `applyParked` is pure state-assembly, emit where its result commits; READ the method and its callers first and note the choice in your report).
- [ ] **Step 4:** Slim `RunOutcome.Parked` to `(ConversationState state)`; follow the compiler: the loop's construction site drops the token argument; `HarnessTest:446-448` re-anchors its token assertion on the narrated event or `state.parkedCalls()` (keep asserting the token VALUE — don't weaken the test); chat-web's `finish()` gets the minimal compile fix only.
- [ ] **Step 5:** Full offline reactor verify green (the compiler-found `TurnEvent` switch arms all added, no `default` anywhere).
- [ ] **Step 6:** Format; commit: `feat: parking joins the narration — ToolCallParked(call, token), Parked slims to state`

---

### Task 4: Resume ergonomics — typed exception, peek, approve/deny

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/UnknownParkTokenException.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/Harness.java` (throw site ~line 198; add `peek`, `approve` ×2, `deny` ×2)
- Test: extend `nessy-core/src/test/java/org/jwcarman/nessy/HarnessTest.java`

**Interfaces:**
- Produces: `UnknownParkTokenException extends RuntimeException` (constructor takes the `ParkToken`, message `"unknown or settled park token: " + token`); `Optional<ParkedCall> peek(ParkToken token)`; `approve(token[, observer])` → `resume(token, new ToolResolution.Decided(Decision.allow())[, observer])`; `deny(token, reason[, observer])` → `resume(token, new ToolResolution.Decided(new Decision.Deny(reason))[, observer])`. Task 6 relies on all three names.

- [ ] **Step 1: failing tests** (prose style of the file; S5778 — construction outside the lambdas):

```java
@Test
void an_unknown_token_is_a_typed_rejection() {
  ParkToken unknown = ParkToken.generate();
  assertThatThrownBy(() -> harness.resume(unknown, resolution))
      .isInstanceOf(UnknownParkTokenException.class)
      .hasMessageContaining(unknown.value());
}

@Test
void peek_reads_a_park_without_consuming_it() {
  // existing park arrangement; then:
  assertThat(harness.peek(token)).isPresent();
  assertThat(harness.peek(token)).isPresent(); // still there — peek never consumes
}

@Test
void approve_is_resume_with_an_allow_verdict() {
  // park, then:
  RunOutcome outcome = harness.approve(token);
  assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
}

@Test
void deny_carries_its_reason_into_the_tool_result() {
  // park, then harness.deny(token, "not today");
  // assert the conversation completed and the denial reason reached the record,
  // mirroring however the existing deny-path test in GatedToolCallExecutorTest asserts it.
}
```

- [ ] **Step 2:** Run — fails.
- [ ] **Step 3:** Implement. The exception's javadoc: thrown for unknown *or already-settled* tokens; `IllegalArgumentException` keeps meaning argument misuse, as everywhere else. `peek` delegates to `store.findPark` (the read `progress` already does — refactor `progress` to call `peek`). Sugar delegates to `resume`; no logic of its own.
- [ ] **Step 4:** Module + offline reactor green.
- [ ] **Step 5:** Format; commit: `feat: resume grows manners — typed rejection, a peek, and two-word verdicts`

---

### Task 5: The renames and the pair — `conversation(id)`, `parkAll`, `JdbcPersistence`

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/Agent.java:64` (rename `resume` → `conversation`; javadoc keeps its substance, notes the symmetry with `converse()`)
- Modify: callers: `nessy-core/src/test/java/org/jwcarman/nessy/AgentTest.java:92`, `nessy-examples/chat-web/.../ChatController.java:118` (mechanical rename only — the rewrite is Task 6)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/Approver.java` (add `parkAll()` beside `allowAll()`/`denyAll(String)`)
- Create: `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/JdbcPersistence.java`
- Test: extend `AgentTest` (compile-sweep covers the rename), extend an `ApproverTest` (create if none exists), extend `nessy-store-jdbc`'s container suite with one `JdbcPersistenceTest`

**Interfaces:**
- Produces: `Agent.conversation(ConversationId id)` (same body as old `resume`); `Approver.parkAll()` returning `request -> Awaited.parked(ParkToken.generate())`; `record JdbcPersistence(JdbcConversationStore store, JdbcMemory memory)` with `static JdbcPersistence create(DataSource dataSource, ObjectMapper mapper)` calling both existing `create` factories. Task 6 relies on all three names.

- [ ] **Step 1: failing tests.**

```java
// ApproverTest
@Test
void park_all_parks_every_request_with_a_fresh_token() {
  Approver approver = Approver.parkAll();
  Awaited<Decision> first = approver.approve(request);
  Awaited<Decision> second = approver.approve(request);
  assertThat(first).isInstanceOf(Awaited.Parked.class);
  assertThat(second).isInstanceOf(Awaited.Parked.class);
  assertThat(((Awaited.Parked<Decision>) first).token())
      .isNotEqualTo(((Awaited.Parked<Decision>) second).token());
}

// JdbcPersistenceTest (@Tag("container"), the suite's shared Postgres setup)
@Test
void create_bootstraps_both_schemas_and_returns_a_working_pair() {
  JdbcPersistence persistence = JdbcPersistence.create(dataSource, mapper);
  ConversationId id = ConversationId.generate();
  persistence.memory().remember(id, Message.user(List.of(new TextBlock("hi"))));
  assertThat(persistence.memory().recall(id).messages()).hasSize(1);
  assertThat(persistence.store().load(id)).isEmpty(); // store reachable too
}
```

- [ ] **Step 2:** Run — fail. Then implement all three; rename `Agent.resume` and fix the two callers.
- [ ] **Step 3:** Offline reactor verify green; run the store container suite with Docker (`./mvnw -pl nessy-store-jdbc test -Dnessy.excludedGroups=live`).
- [ ] **Step 4:** Format; commit: `feat: conversation(id), Approver.parkAll, and the JdbcPersistence pair`

---

### Task 6: The chat-web rewrite — the acceptance test

**Files:**
- Modify: `nessy-examples/chat-web/src/main/java/org/jwcarman/nessy/examples/chatweb/` — `IssueCouponTool.java`, `ChatController.java`, `ApprovalController.java`, `NessyConfig.java`, `SseEvents.java`
- Modify: `nessy-examples/chat-web/src/main/resources/static/app.js`
- Modify: `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/SseEventsTest.java`, `ChatWebSmokeTest.java`

**Interfaces:**
- Consumes: everything Tasks 1–5 produced, by exact name.

The expected diff IS the spec (§9) — verify each bullet lands:

- [ ] **Step 1: `IssueCouponTool`** — `context.progress("issuing…")`; the apology comment and the `ToolProgress`/`ConversationId` imports die.
- [ ] **Step 2: `SseEvents`** — the switch gains `case TurnEvent.ToolCallParked(var call, var token) -> new Event("approval-needed", Map.of("token", token.value(), "tool", call.name(), "args", …))` — args pretty-printed the way `approvalCard` did (move that helper here or inline it; `SseEvents` may now need the mapper — pass it to `observer(...)` or make `of` take it; pick the smallest change and note it). `SseEventsTest` gains the arm's test:

```java
@Test
void a_park_maps_to_an_approval_card() {
  ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
  ParkToken token = ParkToken.generate();
  assertThat(SseEvents.of(new TurnEvent.ToolCallParked(call, token)).name())
      .isEqualTo("approval-needed");
}
```

- [ ] **Step 3: `ChatController`** — constructor drops `ConversationStore`; `get` becomes `agent.snapshot(id)` + `TranscriptView.of(snap.context())` + cards from `snap.parkedCalls()`; `finish` loses the parked-stitching (the observer already narrated the cards) and emits only `done`; `postMessage` uses `agent.conversation(id)`.
- [ ] **Step 4: `ApprovalController`** — constructor drops `ConversationStore`; the pre-flight peek becomes `harness.peek(parkToken).orElseThrow(() -> new UnknownParkTokenException(parkToken))`; `runResume` uses `harness.approve(token, observer)` / `harness.deny(token, reason, observer)` per the request body; `@ExceptionHandler` narrows to `UnknownParkTokenException` (a genuine bad `decision` string now 400s via a second, narrow handler or a `ResponseStatusException` — choose and note).
- [ ] **Step 5: `NessyConfig`** — approver line becomes `Approver.parkAll()`; the two store beans become one `JdbcPersistence` bean plus `store()`/`memory()` accessor beans (or two beans built from one `JdbcPersistence.create` call — smallest readable form).
- [ ] **Step 6: `app.js`** — `approval-needed` handler dedupes by token (skip if a card with that `data-token` exists — the at-least-once contract, spec §4); this retires the duplicate-cards parked minor.
- [ ] **Step 7: `ChatWebSmokeTest`** — approval-card assertions now ride the SSE stream's `approval-needed` (already asserted) — verify the events arrive via the observer path and the GET still shows cards from `snapshot`; adjust whatever the compiler and a failing run demand, without weakening any assertion.
- [ ] **Step 8:** Offline reactor verify green; with Docker: `./mvnw -pl nessy-examples/chat-web test -Dnessy.excludedGroups=live` green. Confirm the acceptance greps: `grep -rn "n/a" IssueCouponTool.java` → nothing; `grep -n "ConversationStore" ChatController.java ApprovalController.java` → nothing.
- [ ] **Step 9:** Format; commit: `refactor: chat-web sheds its apologies — the acceptance diff`

---

### Task 7: Docs and the ruling

**Files:**
- Modify: `CHANGELOG.md` (Unreleased: the additions, and a loud **Breaking** subsection for `RunOutcome.Parked(state)` and `resume→conversation`), root `README.md` (mentions of `agent.resume(id)` if any; observability/durable sections gain nothing new — verify), `nessy-examples/chat-web/README.md` (wiring snippet: `parkAll`, `JdbcPersistence`), spec status flips: DX spec → `IMPLEMENTED (see plan 2026-08-13-dx-generation)`.
- The `Awaited<T>` S2326 SonarCloud won't-fix (spec §8) needs a UI click with the owner's login — the task records the exact justification text in the CHANGELOG entry and the final report flags it for the human.

- [ ] **Step 1:** Sweep and write; `grep -rn "agent.resume\|RunOutcome.Parked" README.md docs/ nessy-examples/*/README.md` to find every stale mention.
- [ ] **Step 2:** Offline verify; format; commit: `docs: the DX generation ships its own paperwork`

---

## Self-review notes (performed at plan time)

- **Spec coverage:** §2→T1, §3→T2, §4→T3, §5→T4, §6→T5 (rename), §7→T5 (parkAll, JdbcPersistence), §8→T7 (ruling, human-flagged), §9→T6 (rewrite + dedupe) and T7 (docs). No gaps.
- **Placeholder scan:** T3 step 3 deliberately delegates the emit-placement judgment to the implementer with a read-first instruction and report-back — that's a judgment clause, not a placeholder; T6 steps carry choose-and-note clauses where two smallest-change options exist.
- **Type consistency:** `ToolCallParked(call, token)` / `Parked(state)` / `conversation(id)` / `peek` / `approve`/`deny` / `parkAll` / `JdbcPersistence` names identical across producing and consuming tasks.
