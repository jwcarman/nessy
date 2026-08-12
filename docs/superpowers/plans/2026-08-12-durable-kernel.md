# Durable Kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the durable-execution kernel (`docs/superpowers/specs/2026-08-12-durable-execution-design.md`): fenced saves, the append-only lane, unified entries (every entry appends; one verb drives), `PARKED` + the parked lane, a real `resume`, `progress` signals + the `ToolCallProgressed` tee, and the `nessy-store-jdbc` reference store.

**Architecture:** Strangler again. Tasks 1–2 widen the state and store contracts additively (old loop keeps compiling via a bridge default). Task 3 rewrites the fold's entry semantics (told-accumulator, open/continue/ride rules). Task 4 rewrites the loop into the unified drive and cuts the facade over. Task 5 adds parking end-to-end plus the signals lane and the tee. Task 6 ships the JDBC store against the store TCK. Task 7 sweeps docs and runs conformance.

**Tech Stack:** Java 25, Maven reactor, JUnit 5 + AssertJ (no mocking libraries — hand-rolled fakes), Jackson, Micrometer Observation, Postgres + Testcontainers (Task 6 only, tag-excluded offline).

## Global Constraints

- **No warning suppressions** (sole exception per CLAUDE.md: spec-mandated `@SuppressWarnings("deprecation")` with a comment naming the contract — not expected here). **No star imports.**
- `./mvnw -q clean verify` at the reactor root must pass **offline** (no API key, no network, no Docker) after every task. Container tests carry `@Tag("container")` and the default exclusion (Task 6 widens `nessy.excludedGroups` to `live,container`).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply` (headers are added by the plugin — never hand-write them).
- Prose test names (`snake_case` sentences). S5778: exception-assertion lambdas contain exactly one throwing invocation. S5841: assert non-emptiness before all/none-match predicates. Core sealed switches: exhaustive, **no `default` arm**; temporary scaffold arms are NAMED arms throwing `IllegalStateException`, comment-marked for their deleting task.
- **The first law binds every task:** replaying facts through the fold reconstructs state; nothing off-record may alter what replay would produce. Loop discipline (fold → policy → remember → emit → fenced save → perform) is law, not preference.
- **Model policy (dispatch):** implementer = Sonnet; task review = Sonnet; Opus review for Tasks 3, 4 (fold semantics, loop rewrite) and 6 (persistence correctness); Haiku for scoped re-reviews of small fix diffs.
- Commit messages in the repo's evocative-but-precise voice, trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

**Package placement (locked):**

| Concept | Home |
|---|---|
| `LaneEntry` (sealed: `Told`, `Resolved`), `ParkedCall` | `org.jwcarman.nessy.api.conversation` |
| `ConversationStore.Loaded`, `StaleStateException` | `org.jwcarman.nessy.spi.conversation` |
| Store contract TCK (abstract test class) | `nessy-core` src/test (`spi.conversation`), exported via **test-jar** |
| `TurnEvent.ToolCallProgressed` | `org.jwcarman.nessy.api.turn` (existing sealed type) |
| `JdbcConversationStore`, `StateCodec`, `schema.sql` | new module `nessy-store-jdbc`, `org.jwcarman.nessy.store.jdbc` |

---

### Task 1: The lane grammar and the widened control block

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/LaneEntry.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ParkedCall.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationState.java` (three new components + withers; canonical-constructor call sites updated mechanically)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationStatus.java` (add `PARKED`)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/conversation/ConversationStateTest.java` (extend), new `LaneEntryTest.java`

**Interfaces:**
- Consumes: `ParkToken`, `ToolResolution`, `ToolCall`, `ContentBlock` (existing); `org.jwcarman.nessy.internal.Identifiers.next()` (the UUIDv7 minting `ParkToken.generate()` already uses — same api→internal precedent).
- Produces (later tasks rely on these exact shapes):
  - `LaneEntry` sealed: `record Told(String id, List<ContentBlock> content)` and `record Resolved(String id, ParkToken token, ToolResolution resolution)`, both `implements LaneEntry`, with `String id()` on the interface; factories `LaneEntry.told(List<ContentBlock>)` / `LaneEntry.resolved(ParkToken, ToolResolution)` minting `Identifiers.next()` ids. Null-checked; `content` defensively copied.
  - `record ParkedCall(ParkToken token, ToolCall call)` — null-checked.
  - `ConversationState` grows exactly three components, in this order, appended before `status`: `List<List<ContentBlock>> told` (the drained-tell accumulator), `List<ParkedCall> parkedCalls`, `long version` — plus `withTold(...)`, `withParkedCalls(...)`, `withVersion(long)`. `newConversation` defaults: empty, empty, `0L`.
  - `ConversationStatus.PARKED` with javadoc: "The open turn is waiting on the world — a parked call holds it; no driver, no lease, durable patience."

- [ ] **Step 1: Write the failing tests.** In `LaneEntryTest` (prose style): `told_entries_carry_content_and_a_time_ordered_id` (two `LaneEntry.told(...)` in a row: ids non-null, distinct, second lexicographically greater — UUIDv7 property, same assertion style as any existing `Identifiers` usage), `resolved_entries_carry_their_token_and_resolution`, `told_rejects_null_content`, `resolved_rejects_null_token` (S5778 discipline). In `ConversationStateTest` extend: `a_new_conversation_has_no_told_material_no_parks_and_version_zero`; `withers_replace_only_their_own_lane` (seed all three, change one, assert others untouched); `told_and_parked_lanes_are_unmodifiable` (mirror the existing unmodifiable assertions).
- [ ] **Step 2: Run to verify failure.** `./mvnw -q -pl nessy-core test -Dtest='LaneEntryTest,ConversationStateTest'` — expected: compilation errors (types/components missing).
- [ ] **Step 3: Implement.** The records as specified; `ConversationState` gains the components (defensive `List.copyOf` in the compact constructor for both lists; `version >= 0` guard), the three withers (full-copy style matching the existing ones), and updated javadoc placing the new lanes under the §9-of-the-essence jurisdictions: *told = words interjected; parkedCalls = homework waiting on the world; version = the fence's token*. Update every canonical-constructor call site (`grep -rn "new ConversationState(" --include="*.java" nessy-core nessy-testing`) mechanically — tests construct via `newConversation` + withers almost everywhere; fix the stragglers.
- [ ] **Step 4: Run to verify pass**, then whole-reactor `./mvnw -q clean verify` (offline).
- [ ] **Step 5: Commit.** `feat: the lane grammar — told, resolved, parked, and the fence's version`

---

### Task 2: The store contract v2 — fence, lane, parks — and the TCK

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/ConversationStore.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/StaleStateException.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/InMemoryConversationStore.java`
- Create: `nessy-core/src/test/java/org/jwcarman/nessy/spi/conversation/ConversationStoreContract.java` (abstract TCK)
- Modify: `nessy-core/src/test/java/org/jwcarman/nessy/spi/conversation/InMemoryConversationStoreTest.java` (extends the TCK)
- Modify: `nessy-core/pom.xml` (attach `test-jar` via `maven-jar-plugin` `<goal>test-jar</goal>` execution, so Task 6 can depend on the TCK)

**Interfaces:**
- Produces (exact contract Tasks 4–6 build on):

```java
public interface ConversationStore {

  record Loaded(ConversationState state, List<LaneEntry> lane) {}

  Optional<Loaded> load(ConversationId id);

  /**
   * The fenced save: persists {@code state} iff the stored version equals
   * {@code state.version()}, atomically bumping to {@code version()+1},
   * deleting the drained lane entries, and syncing the park index from
   * {@code state.parkedCalls()} — one atomic act. Returns the state with the
   * bumped version (the caller's new read-base).
   *
   * @throws StaleStateException when the stored version differs — the caller
   *     read a base that has since moved; reload and re-drive.
   */
  ConversationState save(ConversationState state, Collection<String> drainedLaneIds);

  /** Unconditional, atomic, never contended with saves. */
  void appendLane(ConversationId id, LaneEntry entry);

  /** The park index: token → the conversation and call it belongs to. */
  Optional<ParkedCall> findPark(ParkToken token);

  /** The conversation a token parks under, for driving after a resolution. */
  Optional<ConversationId> findParkConversation(ParkToken token);

  boolean consumeToken(ParkToken token);

  static ConversationStore inMemory() { return new InMemoryConversationStore(); }
}
```

  - `StaleStateException extends RuntimeException` carrying `(ConversationId, long expected, long found)` with a message naming all three.
  - **Bridge for the old loop (deleted in Task 4):** a `default void save(ConversationState state)` that delegates to `save(state.withVersion(loadedVersionOf(state)), List.of())`… is not implementable generically — instead keep the old abstract `save(ConversationState)` REMOVED and give `ConversationLoop`'s two call sites a one-line mechanical update in THIS task: `store.save(state, List.of())` with a scaffold `state.withVersion(...)`? No — simpler and honest: update `ConversationLoop`'s `store.save(state)` calls to `state = store.save(state, List.of())` right here (two sites, `run`'s finally + `drive`) and let its tests stay green: with a freshly loaded state the version always matches in the in-memory store, so behavior is unchanged. The finally-path save wraps in try/catch-StaleStateException? No: in Task 2 the loop is still single-driver — staleness is impossible; add NOTHING speculative.
- TCK (`ConversationStoreContract`): abstract class with `protected abstract ConversationStore store();`, prose tests:
  - `load_of_an_unknown_conversation_is_empty`
  - `save_persists_and_bumps_the_version` (save v0 → returned state has v1 → load shows v1)
  - `a_stale_save_fails_loudly_naming_both_versions` (load twice, save one, save the other → `StaleStateException`, message contains both numbers; S5778: the second save is the lambda's only call)
  - `appends_are_unconditional_and_ordered` (append three `Told`s → load lane in order)
  - `an_append_never_disturbs_a_pending_save` (load → append → save with the loaded version SUCCEEDS — the fence ignores the lane)
  - `draining_removes_exactly_the_named_entries_atomically_with_the_save`
  - `the_park_index_follows_the_saved_state` (save state with a `ParkedCall` → `findPark`/`findParkConversation` resolve; save with it removed → both empty)
  - `a_token_consumes_exactly_once`
- `InMemoryConversationStore`: `ConcurrentHashMap<ConversationId, VersionedRow>` where `VersionedRow(ConversationState state)` — version lives IN state; `save` uses `compute` comparing `existing.state().version() == state.version()` (throw otherwise), storing `state.withVersion(v+1)` minus drained lane rows; lane as `ConcurrentHashMap<ConversationId, List<LaneEntry>>` with immutable-snapshot appends (the `ListMemory` discipline — copy-append-store, never mutate a published list); park index rebuilt per save (`parks.entrySet.removeIf(same conversation)` then re-put). Javadoc keeps the unbounded-growth honesty paragraph.

- [ ] **Step 1:** Write the TCK + `InMemoryConversationStoreTest extends ConversationStoreContract` (keep any existing in-memory-specific tests). Run: compilation failure.
- [ ] **Step 2:** Implement contract, exception, in-memory store; mechanically update `ConversationLoop`'s two save sites (`state = store.save(state, List.of())` and the finally-holder equivalently). Update anything else `grep -rn "store.save(" --include="*.java"` finds (loop + tests fakes: `ConversationLoopTest`'s recording store fake implements the new contract, journaling "save" exactly as before).
- [ ] **Step 3:** `./mvnw -q -pl nessy-core test` green; whole reactor verify offline green.
- [ ] **Step 4:** Add the `test-jar` execution to `nessy-core/pom.xml`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <executions>
    <execution>
      <goals><goal>test-jar</goal></goals>
    </execution>
  </executions>
</plugin>
```

- [ ] **Step 5:** Verify, format, commit. `feat: the store learns to referee — fence, lane, parks, and a TCK to hold them to it`

---

### Task 3: The fold learns the lane — told-notes, open, continue, ride

The semantic heart. `AgentTold` becomes a pure note; turn-opening becomes a
loop-invoked closure transition; a clean response against unread mail
continues; flushes carry riders. **Opus review.**

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationState.java` (fold arms + two new closure transitions)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/message/Context.java` (only if Step 1's legality test fails — see below)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/conversation/ConversationStateFoldTest.java` (heavy extension), `nessy-core/src/test/java/org/jwcarman/nessy/api/message/ContextTest.java` (one legality test)

**Interfaces:**
- Produces:
  - `fold(ConversationEvent.AgentTold)` → `Step.of(state.withTold(told + event.content()))` — **no remember, no effects, no status change** (the note fold). Misdelivery guard unchanged.
  - `public Step openTurn()` — precondition: status is quiescent (`IDLE`/`COMPLETE`/`FAILED`) and `told` non-empty; produces: one merged `Message.user(allToldBlocksInOrder)` remembered, `told` cleared, `consecutiveErrors` 0, `failureReason` null, `AWAITING_MODEL`, `Effect.callModel()`. Throws `IllegalStateException` naming the status otherwise (loop bug, fail loud).
  - `modelResponded` clean-path refinement: `END_TURN` + no homework + **`told` empty** → `COMPLETE` (as today). `END_TURN` + no homework + **`told` non-empty** → remember `[assistantMessage, Message.user(mergedTold)]` in that order, `told` cleared, `AWAITING_MODEL`, `Effect.callModel()` — the turn continues. Fatal and homework paths unchanged (homework leaves `told` in place for the flush).
  - `toolFinished` final-flush refinement: the flush message becomes `Message.toolResults(resultBlocks ++ mergedToldBlocks)` and `told` clears; with siblings outstanding, unchanged. Also: the fold now removes the finished call from `parkedCalls` as well as `pendingCalls` (by call id, first match) — a resumed call finishing must clear its park.
  - `halted(String)` refinement: the abandoned-results flush also carries `mergedToldBlocks` riders and clears `told` (a dying conversation still delivers the world's words to the record).
  - Private helper `List<ContentBlock> mergedTold()` — concatenation in arrival order, block boundaries preserved (no joining, no separators).

- [ ] **Step 1: The wire-legality gate.** Add to `ContextTest`: `a_results_message_may_carry_trailing_blocks_after_the_answers` — build `[assistant(toolUse c1), toolResults([result(c1), text("btw")])]`, assert `Context.of` accepts it. Run it FIRST. If it passes, `Context` is untouched. If it fails, loosen the validator minimally and deliberately: the answering message must *begin* with results answering exactly the open ids; blocks after the last `ToolResultBlock` are unconstrained; a `ToolResultBlock` appearing after a non-result block in the same message stays illegal. Update `Context`'s class javadoc sentence to say so, and keep every existing rejection test green.
- [ ] **Step 2: Write the failing fold tests** (prose, in the existing file's style — exact scenarios, each an executable spec sentence):
  - `a_told_fact_is_a_pure_note` (fold `AgentTold` → told grows; no remember, no effects, status unchanged, streak untouched)
  - `open_turn_merges_every_note_into_one_user_message_in_arrival_order` (three notes, blocks `a`,`b`,`c` → one `Message.user([a,b,c])` remembered, told empty, streak reset from a seeded 2, failureReason cleared from seeded, `AWAITING_MODEL`, exactly `CallModel`)
  - `open_turn_refuses_to_open_over_an_open_turn` (status `AWAITING_MODEL` → `IllegalStateException` naming it; S5778)
  - `a_clean_response_with_unread_notes_continues_instead_of_completing` (assistant msg + told note → remember `[assistant, mergedUser]` exactly in order, `AWAITING_MODEL`, `CallModel`, told cleared)
  - `a_clean_response_with_no_notes_still_completes`
  - `notes_ride_the_flush_beside_the_results` (homework turn, note arrives mid-debt (fold `AgentTold`), final `ToolFinished` → flush message content is `[toolResult..., noteBlocks...]` exactly; told cleared)
  - `a_halting_conversation_still_delivers_the_worlds_words` (pending call + note → `halted("x")` flush carries abandoned result + note blocks)
  - `a_resumed_call_finishing_clears_its_park` (state with `parkedCalls=[(t1,c1)]`, `pendingCalls=[]`… seed both lanes; fold `ToolFinished(c1)` → parkedCalls empty)
  - Update the two existing tests the refinements touch (`a_clean_response_completes_the_turn` gains the empty-lane clause; fatal-path `containsExactly` unchanged unless a note is seeded).
- [ ] **Step 3:** Run to verify the new tests fail for the right reasons (told component exists from Task 1, so failures are behavioral, not compile).
- [ ] **Step 4:** Implement the arms and transitions exactly as the Interfaces block states. The old `told(event)` private method's body (streak reset + immediate open) moves into `openTurn()`; the arm becomes the note.
- [ ] **Step 5:** Full module green, reactor verify offline green. **Expected casualty check:** `ConversationLoopTest`'s scenarios still pass because the loop (Task 2 shape) still folds `AgentTold` then… **it will break** — the old loop folded `AgentTold` and performed its `CallModel`; the note fold emits nothing, so `tell` returns without calling the model. **Scaffold for this task only** (deleted in Task 4): after folding the entry fact in `run`, if the folded step produced no effects and the state is quiescent-with-notes, apply `openTurn()` and continue — three lines in `ConversationLoop.run`, comment-marked `// Scaffolding until the unified drive (plan 2026-08-12, Task 4)`. This keeps every green bar green while the fold's semantics land reviewed and alone.
- [ ] **Step 6:** Commit. `feat: the fold learns the lane — notes, the open, the continue, the ride`

---

### Task 4: The unified drive — one verb, re-entrant, fenced

The loop rewrite. **Opus review.** Everything the essence loop did, now
re-entrant from any status, retry-on-stale, lane-draining, park-aware.

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java` (rewrite `run`; add `drive`; implement `resume`; delete the Task 3 scaffold and the `RESUMABLE` guard)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/Conversation.java` (tell = append + drive), `nessy-core/src/main/java/org/jwcarman/nessy/Harness.java` (+ `resume`, `progress` comes in Task 5), `AgentBuilder.java` (wire-through only)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationState.java` (one more closure transition: `parked`)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/internal/ConversationLoopTest.java` (major extension), `ConversationTest`, `HarnessTest`

**Interfaces:**
- Consumes: everything above; `GatedToolCallExecutor.resume(call, resolution, state, observer)` (shipped, until now unreachable).
- Produces:
  - `ConversationState.parked(ToolCall call, ParkToken token)` closure transition: moves the call `pendingCalls → parkedCalls(token, call)`; status becomes `PARKED` iff no `pendingCalls` remain un-parked, else stays `EXECUTING_TOOL`. (Fold-free, loop-applied, like `halted`.)
  - `ConversationLoop`:

```java
/** Appends nothing; drives the conversation from wherever its status points. */
public RunOutcome drive(ConversationId id, TurnObserver observer)

/** tell: the facade appends a Told entry, then drives. */
public RunOutcome run(ConversationId id, ConversationEvent.AgentTold input, TurnObserver observer)
   // becomes: store.appendLane(id, LaneEntry.told(input.content())); return drive(id, observer);
   // (the AgentTold fact is minted per lane entry during the drain, not here)

/** resume: consume-or-reread, append Resolved, drive. */
public RunOutcome resume(ConversationId id0IsUnusedRemoveParam…) — see facade below
```

  The **drive algorithm** (this is the implementation, transcribe faithfully):

```java
public RunOutcome drive(ConversationId id, TurnObserver observer) {
  Objects.requireNonNull(observer, "observer must not be null");
  Observation observation = EngineObservations.run(observations, id);
  try (var _ = observation.openScope()) {
    for (int attempt = 1; ; attempt++) {
      try {
        return driveOnce(id, observer);
      } catch (StaleStateException e) {
        if (attempt >= MAX_DRIVE_ATTEMPTS) {  // 5
          throw e; // somebody keeps winning; the caller retries or reads
        }
        // another driver moved the base — reload and re-enter
      }
    }
  } catch (RuntimeException e) {
    observation.error(e);
    throw e;
  } finally {
    observation.stop();
  }
}

private RunOutcome driveOnce(ConversationId id, TurnObserver observer) {
  Loaded loaded = store.load(id).orElseGet(() ->
      new Loaded(ConversationState.newConversation(id), List.of()));
  ConversationState state = loaded.state();
  List<String> drained = new ArrayList<>();

  // 1. Notes: fold every Told entry, in order (facts minted here, one per entry).
  for (LaneEntry entry : loaded.lane()) {
    if (entry instanceof LaneEntry.Told(String entryId, List<ContentBlock> content)) {
      state = foldAndDiscipline(state, new ConversationEvent.AgentTold(id, content), drained, observer).state();
      drained.add(entryId);
    }
  }

  // 2. Resolutions: while parked, route Resolved entries to the parked executor.
  for (LaneEntry entry : loaded.lane()) {
    if (state.status() == ConversationStatus.PARKED
        && entry instanceof LaneEntry.Resolved(String entryId, ParkToken token, ToolResolution resolution)) {
      Optional<ParkedCall> park = state.parkedCalls().stream()
          .filter(p -> p.token().equals(token)).findFirst();
      if (park.isEmpty()) { drained.add(entryId); continue; } // stale resolution: token's call already settled
      drained.add(entryId);
      ConversationEvent fact = performResume(park.get(), resolution, state, observer);
      state = fold(state, fact, drained, observer); // ToolFinished clears the park (Task 3)
      state = performAll(state, /*from*/ fact, drained, observer); // effects loop below
    }
  }

  // 3. The continuation pointer: do what status says until quiescent or parked.
  //    (openTurn for quiescent-with-notes; re-perform for in-flight; return for the rest.)
  ...
}
```

  Spelled out for step 3 of the algorithm (the plan's contract; the implementer writes it as one loop, not this prose):
  - quiescent (`IDLE`/`COMPLETE`/`FAILED`) and `told` non-empty → apply `state.openTurn()` (remember+emit+save discipline, drained ids included in the save), then perform its `CallModel`, folding each returned fact and performing each emitted effect exactly as the essence loop did — the inner fold/perform cycle is UNCHANGED law: fold → policy consult (halt discards + `halted()` closure) → remember → emit fact → `state = store.save(state, drainedSnapshotThenClear)` → perform next effect. Drained ids ride the FIRST save after their fold and only that one.
  - `AWAITING_MODEL` (a crashed or continued turn) → perform `CallModel` on the current state and continue the cycle.
  - `EXECUTING_TOOL` → re-perform `new Effect.ExecuteTool(call)` for each `pendingCalls` entry (at-least-once physics; parked calls are NOT re-performed), continue the cycle.
  - `PARKED` after step 2 → `return new RunOutcome.Parked(state, state.parkedCalls().getFirst().token())`.
  - quiescent with empty `told` → `return new RunOutcome.Completed(state)`.
  - A `Awaited.Parked(token)` from the tool executor during any perform → apply `state.parked(call, token)`, save (fenced), and continue performing remaining queued effects; when the queue empties and status is `PARKED`, return `Parked` as above. **The in-process park-refusal is DELETED** — parking is now supported everywhere; the in-memory store holds parks like any other.
  - The try/finally progress-holder + save-on-every-exit discipline survives verbatim (holder saves with current drained snapshot; a save that throws `StaleStateException` in the finally is caught and dropped there — the winning driver owns the base now).
  - `MAX_DRIVE_ATTEMPTS = 5`, named constant.
- Facade:
  - `Conversation.tell(I)` / `tell(I, TurnObserver)` → render (unchanged loud renderer contract) → `loop.run(id, agentTold, observer)` — signature and `RunOutcome` return UNCHANGED for callers.
  - `Harness.resume(ParkToken token, ToolResolution resolution)` and `(…, TurnObserver observer)`: `store.findParkConversation(token)` → empty ⇒ `IllegalArgumentException("unknown or settled park token")`; `store.consumeToken(token)` false ⇒ return `loop.drive(id, observer)` (idempotent re-delivery reads current truth); true ⇒ `store.appendLane(id, LaneEntry.resolved(token, resolution)); return loop.drive(id, observer);`.
  - `GatedToolCallExecutor`: on approver/tool park, RETURN the `Awaited.Parked` as it already does — but the token must reach the store's park index only via the loop's `state.parked(...)` + save (no executor-side store writes; the executor stays store-blind).

- [ ] **Step 1: Write the failing tests** — extend `ConversationLoopTest` with (prose names; reuse the journal/fakes; the store fake now implements the full v2 contract and can be SCRIPTED to throw `StaleStateException` on its Nth save):
  - `a_tell_is_an_append_and_a_drive` (journal order: append → fold(AgentTold) → openTurn remember → model)
  - `three_queued_tells_open_one_turn_with_three_voices` (append three before driving; one user message, blocks in order; THREE AgentTold emissions on the channel; ONE remember)
  - `a_mid_turn_tell_rides_the_flush` (scripted tool turn; append a Told between tool completion and drive continuation — via the store fake exposing lane injection; flush message carries the rider)
  - `a_clean_response_with_queued_mail_keeps_driving` (append during the model turn via the fake; assert second model call, then COMPLETE)
  - `a_parking_tool_parks_the_conversation_and_returns_the_token` (parking tool fake → `RunOutcome.Parked`, status `PARKED`, `parkedCalls` holds (token, call), saved)
  - `resume_consumes_the_token_routes_the_executor_and_finishes_the_turn` (full round-trip through `Harness.resume`; `ToolFinished` folds; park cleared; turn completes)
  - `a_second_resume_with_the_same_token_is_a_read_not_a_replay` (tool executor invoked exactly once across two resumes)
  - `a_resolution_for_a_settled_call_drains_quietly` (stale Resolved entry → drained, nothing performed)
  - `a_stale_save_makes_the_drive_reload_and_retry` (store fake throws once → drive succeeds on attempt 2; journal shows two loads)
  - `five_consecutive_stale_saves_surface_the_exception` (S5778)
  - `a_crashed_awaiting_model_conversation_is_re_driven_by_the_next_entry` (seed store with `AWAITING_MODEL` state → tell → model called, turn completes; the old refusal is GONE)
  - `a_crashed_executing_tool_conversation_re_performs_its_debt` (seed with pendingCalls → drive re-executes; at-least-once, documented)
  - Keep/adapt every existing ordering-law test (remember→emit→save→perform, consult-per-fold, halt discipline, durability finally) — same laws, new entry.
- [ ] **Step 2:** Run: failures for the right reasons.
- [ ] **Step 3:** Implement loop + transitions + facade; delete the Task 3 scaffold, the `RESUMABLE` set, and `resume`'s `UnsupportedOperationException`.
- [ ] **Step 4:** Module green; whole reactor offline green (facade signature unchanged, so nessy-testing/examples compile untouched — verify, don't assume: `./mvnw -q clean verify`).
- [ ] **Step 5:** Commit. `feat!: the unified drive — every entry appends, one verb drives, the fence referees`

---

### Task 5: Signals — progress from afar, the tee up close

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/turn/TurnEvent.java` (+`ToolCallProgressed`), `TurnObserver.java` (threading + throw-semantics javadoc), `TurnObserverAdapter.java` (+hook), `TurnObserverBuilder.java` (+method)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/GatedToolCallExecutor.java` (the tee)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/Harness.java` (+`progress`)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/tool/Tool.java` (at-least-once javadoc paragraph)
- Test: `TurnObserverAdapterTest`, `TurnObserverBuilderTest`, `GatedToolCallExecutorTest`, `HarnessTest` (extend each)

**Interfaces:**
- Produces:
  - `record ToolCallProgressed(ToolCall call, String message) implements TurnEvent` — null-checked; javadoc: "A running tool reported progress — the executor attaches the authoritative call; the tool's self-reported id is not trusted for narration."
  - `TurnObserverAdapter.onToolCallProgressed(TurnEvent.ToolCallProgressed event)` no-op hook + dispatch arm (the sealed switch forces it — that is the adapter working as designed).
  - `TurnObserverBuilder.onToolCallProgressed(Consumer<TurnEvent.ToolCallProgressed>)`, chaining like its siblings.
  - The tee in `GatedToolCallExecutor.invoke(...)`: the `ToolContext` handed to the invoker wraps the emitter —

```java
EventEmitter teed = event -> {
  emitter.emit(event);
  if (event instanceof ToolProgress(var _, var _, String message)) {
    try {
      observer.on(new TurnEvent.ToolCallProgressed(call, message));
    } catch (RuntimeException e) {
      LOGGER.warn("turn observer failed during tool-progress narration; narration dropped", e);
    }
  }
};
```

    Only `ToolProgress` is teed; everything else passes through untouched. The catch-and-log is the ruled exception to fail-loud (texture never alters the record) — the comment says so.
  - `TurnObserver` javadoc gains the two rulings verbatim: a throwing observer aborts the call it narrates on the model path (attributed to the caller's own `tell`); tool-progress narration is logged-and-dropped (propagation would misattribute a UI bug as tool failure); progress narration arrives on whatever thread the tool emits from — accumulating observers make themselves thread-safe or stay delta-only.
  - `Harness.progress(ParkToken token, String message)`: `store.findPark(token)` → empty ⇒ `false` (unknown/settled — signal dropped, return says so); present ⇒ emit `new ToolProgress(conversationId, park.call().id(), message)` on the registry, return `true`. Never consumes the token. (Conversation id via `store.findParkConversation(token)`.)
  - `Tool` javadoc, new paragraph: "Durable re-drives execute at-least-once: a tool that cannot be safely re-run makes itself idempotent, or parks and lets its remote side deduplicate by token."

- [ ] **Step 1: Failing tests.** Adapter: `every_variant_routes_to_its_own_hook` gains the seventh variant (compile force demonstrates the promise). Builder: progressed consumer heard; chaining. Executor: `a_tools_progress_is_teed_to_the_observer_with_the_authoritative_call` (tool emits `ToolProgress` with a WRONG call id; observer hears `ToolCallProgressed` carrying the true call; system channel hears the tool's original event untouched); `a_throwing_observer_never_becomes_a_tool_failure` (observer throws on progress; tool still completes; `ToolFinished` is a success; emission still reached the channel). Harness: `progress_peeks_the_park_and_emits_tool_progress` (park a call via a parking approver + drive, then `harness.progress(token, "halfway")` → registry hears `ToolProgress`, token still resumable afterward — full resume completes); `progress_for_an_unknown_token_reports_false_and_emits_nothing`.
- [ ] **Step 2:** Run failing; implement; the sealed-switch compile errors in adapter/examples' `default -> {}` arms are the design working (examples' extender `default` arms absorb the new variant silently — verify by building).
- [ ] **Step 3:** Reactor verify offline green; format; commit. `feat: progress finds its two lanes — the tee up close, the token from afar`

---

### Task 6: nessy-store-jdbc — the contract made real

**Opus review** (persistence correctness). New reactor module.

**Files:**
- Create: `nessy-store-jdbc/pom.xml`; Modify: root `pom.xml` (`<module>`, `nessy.excludedGroups` default → `live,container`, Testcontainers BOM in dependencyManagement), `nessy-bom/pom.xml` (new artifact entry)
- Create: `nessy-store-jdbc/src/main/resources/org/jwcarman/nessy/store/jdbc/schema.sql`
- Create: `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/JdbcConversationStore.java`, `StateCodec.java`
- Create: `nessy-store-jdbc/src/test/java/org/jwcarman/nessy/store/jdbc/JdbcConversationStoreTest.java` (extends the TCK, `@Tag("container")`), `StateCodecTest.java` (pure, untagged)

**Interfaces:**
- Consumes: `ConversationStore` contract + TCK (via `nessy-core` test-jar: `<classifier>tests</classifier>` dependency), Jackson (already a core dependency), Testcontainers `postgresql` + JUnit-Jupiter integration (test scope), Postgres JDBC driver.
- Produces: `JdbcConversationStore(DataSource dataSource, ObjectMapper mapper)` implementing the full contract with plain JDBC (no Spring, no JPA — house stance), `create(DataSource, ObjectMapper)` factory running `schema.sql` idempotently (`CREATE TABLE IF NOT EXISTS`).
- Schema (Postgres-first, UUIDv7 strings as `text` — index-friendly per the v2 identifier ruling):

```sql
CREATE TABLE IF NOT EXISTS nessy_conversation (
  id       text PRIMARY KEY,
  version  bigint NOT NULL,
  state    jsonb  NOT NULL
);
CREATE TABLE IF NOT EXISTS nessy_lane (
  entry_id        text PRIMARY KEY,
  conversation_id text NOT NULL,
  kind            text NOT NULL,          -- 'told' | 'resolved'
  payload         jsonb NOT NULL
);
CREATE INDEX IF NOT EXISTS nessy_lane_conversation ON nessy_lane (conversation_id, entry_id);
CREATE TABLE IF NOT EXISTS nessy_park (
  token           text PRIMARY KEY,
  conversation_id text NOT NULL,
  call            jsonb NOT NULL
);
CREATE TABLE IF NOT EXISTS nessy_token (
  token text PRIMARY KEY
);
```

  - `save`: one transaction — `UPDATE nessy_conversation SET version = ?, state = ?::jsonb WHERE id = ? AND version = ?` (insert on zero-rows-and-version-0 via `INSERT ... ON CONFLICT DO NOTHING` then re-check); affected-rows 0 ⇒ rollback + `StaleStateException(id, expected, foundByReread)`; `DELETE FROM nessy_lane WHERE entry_id = ANY(?)`; park sync = `DELETE FROM nessy_park WHERE conversation_id = ?` + batch insert from `state.parkedCalls()`. Return `state.withVersion(v+1)`.
  - `appendLane`: single `INSERT`, autocommit, never touches `nessy_conversation`.
  - `consumeToken`: `INSERT INTO nessy_token ... ON CONFLICT DO NOTHING`, affected-rows == 1.
  - `load`: state row + lane rows ordered by `entry_id` (UUIDv7 = arrival order).
  - `StateCodec`: Jackson serialization of `ConversationState` and `LaneEntry` — sealed hierarchies (`ContentBlock`, `ToolResolution`, `Decision`, `LaneEntry`) mapped via mixins in the codec (`@JsonTypeInfo(use = NAME)` + `@JsonSubTypes` registered programmatically with `mapper.copy()` — the harness's own mapper is never mutated). Round-trip tests are the spec: `every_content_block_variant_round_trips`, `a_full_state_with_debt_parks_and_told_round_trips`, `lane_entries_round_trip`, `unknown_json_fails_loudly_not_null`.
- Testcontainers test: `@Testcontainers @Tag("container")`, one static `PostgreSQLContainer<?>`, TCK subclass + two JDBC-specific tests: `two_connections_racing_a_save_see_exactly_one_winner` (real CAS under concurrency, `ExecutorService` + latch, loser collects `StaleStateException` — assert both outcomes present); `the_schema_bootstrap_is_idempotent` (create twice).
- Root pom: `<nessy.excludedGroups>live,container</nessy.excludedGroups>` — offline verify never needs Docker; the full run (`-Dnessy.excludedGroups=`) runs everything.

- [ ] **Step 1:** Module skeleton + poms; reactor builds with empty module.
- [ ] **Step 2:** `StateCodec` + tests (pure, offline) — failing → green.
- [ ] **Step 3:** `schema.sql` + `JdbcConversationStore` + TCK subclass + container tests. Verify locally WITH Docker: `./mvnw -q -pl nessy-store-jdbc test -Dnessy.excludedGroups=live`. Then confirm the offline bar: `./mvnw -q clean verify` (containers excluded) green with Docker STOPPED if available to prove it.
- [ ] **Step 4:** Format; commit. `feat: nessy-store-jdbc — the referee gets a courthouse`

---

### Task 7: Docs, CHANGELOG, and the conformance sweep

**Files:**
- Modify: `CHANGELOG.md`, `README.md`, `docs/superpowers/specs/2026-08-12-durable-execution-design.md` (status flip + two implementation notes), `docs/superpowers/specs/2026-08-11-conversation-essence-design.md` (amendment cross-reference note at top of §6/§10 pointing to the durable spec's ledger)

**Steps:**
- [ ] **Step 1: CHANGELOG** (match the file's final-vocabulary voice): the unified entry (every entry appends; one verb drives; four fold outcomes of a tell), merge-at-drain and the cancel-that rule, the fence + lane disciplines, `PARKED` + parked lane + real `resume`/`progress`, the tee and `ToolCallProgressed`, `nessy-store-jdbc`, the at-least-once tool contract, what was deliberately not built (broker obituary). README: the durable story gets a section (autonomous agents: any node, fenced, resumable; JDBC store snippet; `container` tag note), and the declared-listening/observer sections gain `onToolCallProgressed` where the rungs are listed.
- [ ] **Step 2: Spec truth-marks.** Durable spec → `Status: IMPLEMENTED (see plan 2026-08-12-durable-kernel)` + two notes where implementation refined the draft: the lane rides `load` as `Loaded(state, lane)` with the `told` accumulator on state (the "loaded view" made precise), and resolutions drain loop-side with fact-minting at the drain. Essence spec gets a one-line pointer to the durable amendment ledger.
- [ ] **Step 3: Conformance checklist** (fix drift found, cite evidence in the report): no new facts/effects (`ConversationEvent` still four, `Effect` still two — grep); every entry appends (no code path folds an `AgentTold` that has no lane row behind it except the loop's own drain); fence present on every save path; park-refusal gone (`grep -rn "cannot park" nessy-core/src/main` empty); tee only forwards `ToolProgress`; `progress` never consumes; `Tool` javadoc carries at-least-once; offline verify needs no Docker.
- [ ] **Step 4:** Reactor verify offline; format; commit. `docs: the durable kernel ships — the loch gets a bottom`

---

## Self-review notes (performed at plan time)

- **Spec coverage:** §3 unified entry/fold-outcomes → Tasks 3–4; §4 disciplines → Tasks 2, 4, 6; §5 park/resume/at-least-once → Tasks 1, 4, 5; §6 signals/tee → Task 5; §7 ledger → Tasks 4 (refusal retired, turn refined) + 7 (docs); §8 not-built → enforced by absence + Task 7 checklist; §9 opens → narration asymmetry ruled into Task 5 as specified, JDBC-in-plan honored (Task 6), drain-policy seam correctly absent; §10 testing posture → TCK (2, 6), loop laws (4), fence zombie test (2, 6).
- **Deliberate deviations from the draft spec, recorded for Task 7's truth-marks:** `Loaded(state, lane)` instead of lane-as-state-field; facts minted at drain rather than at append (the append IS the acceptance; the fold sees one `AgentTold` per entry, so accounting holds).
- **Type consistency:** `save(ConversationState, Collection<String>) → ConversationState` everywhere; `LaneEntry.id()` is `String` (UUIDv7); `ParkedCall(token, call)` order consistent in Tasks 1/2/4/5/6.
- **Known adaptation points** are instructions to read named files, never invitations to invent API.
