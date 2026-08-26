# Tool Call State Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each tool call owns its own state machine — states that handle their own events and answer for their own recovery — and deferral happens by callback instead of by folding from inside an effect.

**Architecture:** `ToolCallEvent` becomes a sealed sub-hierarchy of `AgentEvent` so the phase routes by id and never names a state. `ToolCallState` (was `CallStatus`) becomes a sealed interface with default methods: each state handles the events it admits, reports its result, and says what a re-fire owes it. Deferral splits into request → effect → completion, so it matches the shape every other step already has.

**Tech Stack:** Java 25 sealed interfaces with default methods, Jackson polymorphic records, Micrometer Observation, JUnit 6, AssertJ, hand-written fakes.

**Spec:** `docs/superpowers/specs/2026-08-26-deferral-by-callback-design.md` (binding — read it in full, especially §2.1's naming rules, §6, §9 and §9a).

## Global Constraints

- **Naming rules are binding** (spec §2.1). Nouns qualified (`ToolCall`, `ToolCallState`, `ToolCallEvent`, `toolCallId`, `ToolCallAddress`); effects are imperative verb + noun; live states are gerund + noun; terminals are past participles. `Notify`-style verb-only effects are gone under this plan — `DeferApproval`/`DeferToolCall` replace them.
- **States are data; only events and effects carry behaviour.** No persisted type holds a callback. `@JsonIgnore` every derived method or Jackson invents properties for them.
- The wire format may break freely — James, 2026-08-26. No decode-side aliases; the soak database is disposable.
- No `@SuppressWarnings`; no star imports; sealed switches exhaustive with no `default` arm; S5778; S5841; no mocking library; license headers on new files.
- Containment is absolute: an observation, a scope close, or a handler must never break a fold, a turn, a tool or an approval.
- Build economics: warm scoped builds while iterating; `./mvnw -q clean verify` ONCE per task before its last commit; one Maven process at a time; `./mvnw license:format -Plicense && ./mvnw spotless:apply` before every commit.
- A live watchman JVM may be running from `nessy-examples/watchman/target/*.jar`. Rebuilding is fine; never kill a java process and never start the application.

---

### Task 0: A lost CAS race is not an error

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Observations.java` (the `nessy.fold` span)
- Modify: `nessy-examples/watchman/README.md` (the Grafana table)
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/FoldSpanTest.java`

**Interfaces:** Produces nothing new. Changes the status recorded on retried `nessy.fold` spans.

Independent of everything below, and wrong on a live dashboard right now: a fold that loses its compare-and-set is recorded with `observation.error(...)`, so Tempo renders it `STATUS_CODE_ERROR` — three of them in a single healthy round, because concurrent tool results contend by design. Any error-rate panel counts them.

- [ ] **Step 1: Write the failing test.** In `FoldSpanTest`, force a CAS conflict (the existing `StaleRetryCounterTest` shows the `SecondWriter` `Memory` technique) and assert the retried fold span's status is OK and it carries `nessy.fold.outcome = retried`, while a fold whose `Memory.remember` throws is `ERROR` with `error.type`.
- [ ] **Step 2: Run it and watch it fail** on the retried span's status.
- [ ] **Step 3: Classify in `Observations`.** `StaleStateException`/`ConflictException` → `nessy.fold.outcome = retried`, keep the version message as an attribute (`"expected version 551 but store holds 552"` is genuinely debuggable), status stays OK. Any other `RuntimeException` → `error()` as today.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Correct the runbook.** The README's Grafana table currently reads *"`nessy.state.stale_retries` should be near zero"*. It is not: a round with N parallel tool calls contends by design — five in one healthy round was observed. Rewrite to say what is actually pathological (a count climbing while nothing is running, or growing per round without more parallelism).
- [ ] **Step 6: `./mvnw -q clean verify`, format, commit** `fix: a lost CAS race is an outcome, not an error`.

---

### Task A: Each state owns its transitions

**Files:**
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/AgentEvent.java` (introduce `ToolCallEvent`)
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/CallStatus.java` (gains `handle`/`result`/`outstanding`)
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Phase.java` (loses the per-call switches)
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ToolCallTransition.java`
- Tests: `AwaitingToolsPhaseTest`, `PhaseOutstandingEffectsTest` — same cells, new call sites

**Interfaces:**
- Produces: `sealed interface ToolCallEvent extends AgentEvent { ToolCall call(); default String toolCallId(); }`; on `CallStatus` — `ToolCallTransition handle(ToolCallEvent)`, `Optional<ToolResultBlock> result()`, `List<Effect> outstanding()`.

**Pure refactor. No behaviour changes.** The existing matrix suite is the proof, and every assertion in it must survive unchanged in meaning. If a test needs a *new* assertion, the refactor changed behaviour and is wrong.

- [ ] **Step 1: Split the event hierarchy.** `AgentEvent permits Observed, ModelFinished, ToolCallEvent`; `ToolCallEvent` permits the five call events and declares `call()`. The five records already have a `call` component, so they satisfy it without change. Run the build: `Idle` and `AwaitingModel` should now fail to compile on their four-way ignore arms — replace each with `case ToolCallEvent _ -> Transition.ignore()`.
- [ ] **Step 2: Run the suite.** Expected: PASS, unchanged. Commit `refactor: call events are a sealed sub-hierarchy`.
- [ ] **Step 3: Add `result()` and `outstanding()` to `CallStatus`,** with `@JsonIgnore` on both. `Finished` returns its block; everyone else empty. `Pending → [SeekApproval]`, `Running → [RunTool]`, the rest empty. Then rewrite `AwaitingTools.outstandingEffects()` to delegate — deleting the switch that currently states the re-fire rule a second time.
- [ ] **Step 4: Run `PhaseOutstandingEffectsTest`.** Expected: PASS, unchanged.
- [ ] **Step 5: Move `handle` down.** Add the default-method dispatch to `CallStatus` per spec §6, with the drop-and-warn default. Move the admission logic out of `onApprovalAnswered`/`onToolFinished` into per-state overrides. `AwaitingTools.handle` reduces to three arms and a `route(ToolCallEvent)` that looks up `toolCallId()`, delegates, replaces the entry, and asks whether every call now has a result.
- [ ] **Step 6: Run the whole matrix suite.** Expected: PASS, unchanged. This is the task's proof.
- [ ] **Step 7: `./mvnw -q clean verify`, format, commit** `refactor: each call state owns its transitions`.

---

### Task B: The vocabulary

**Files:** every file naming a call state or a call id — `Phase`, `CallStatus`, `AgentEvent`, `Observations`, `ApprovalDesk`, `DeliveryWorker`, `StateCodec`, `CallAddress`, the Spring starter's `PendingApprovals`, and ~18 test files.

**Interfaces:**
- Produces: `ToolCallState` (was `CallStatus`) with variants `SeekingApproval`, `DeferringApproval`, `AwaitingApproval`, `RunningTool`, `DeferringResult`, `AwaitingResult`, `Completed`, `Denied`, `Failed`, `Expired`; `toolCallId` throughout; `ToolCallAddress` (was `CallAddress`).

Mechanical except the terminal split, which Task A has made cheap — `allMatch(Finished.class::isInstance)` is already `allMatch(s -> s.result().isPresent())`.

- [ ] **Step 1: Rename the type and its variants** per spec §2.1. `Pending → SeekingApproval`, `Running → RunningTool`, `AwaitingResult` keeps its name, `AwaitingApproval` keeps its name.
- [ ] **Step 2: Split `Finished`** into `Completed`, `Denied`, `Failed` — all three carrying a `ToolResultBlock`, all three returning it from `result()`. `Denied` and `Failed` carry error blocks as `Finished` did. Update `Observations.isInFlight` and the `@JsonSubTypes` list.
- [ ] **Step 3: Rename `callId` → `toolCallId` and `CallAddress` → `ToolCallAddress`** across main and test.
- [ ] **Step 4: Update the wire discriminators** in `@JsonSubTypes` to match the new names. No aliases. `StateCodecTest` round-trips must be updated to the new strings — that update *is* the wire-format break, and it is intended.
- [ ] **Step 5: `./mvnw -q clean verify`, format, commit** `refactor: one ubiquitous language for tool calls`.

---

### Task C: Deferral by callback

**Files:**
- Modify: `nessy-api` — `Awaited` (`Deferred` gains a callback and a term), `ToolContext` (back to a record; `defer()` and the `events` collaborator go), `Tool` and `ToolConfig` (`timeout()` deleted), `approval/ApprovalContext` (collapses to `request()`), `approval/ApprovalOutcome` (`Deferred` gains callback + term)
- Create: `nessy-api/.../ComputationCallback.java`
- Modify: `nessy-agent` — `AgentEvent` (`ApprovalDeferralRequested`, `ApprovalDeferred`, `ApprovalExpired`, `ToolCallDeferralRequested`, `ToolCallDeferred`, `ToolExpired`), `Effect` (`DeferApproval`, `DeferToolCall`), `ToolCallState` (the two `Deferring…` states), `Phase`, `DefaultAgent` (dispatch), `DeliveryWorker` (stop mapping expiry), `HarnessConfig` (`maxApprovalTimeout`, `maxCallTimeout`)
- Delete: `ComputationApprovalContext`, `ComputationToolContext`, the two in-band failure constants and their guards

**Interfaces:**
- Produces: `ComputationCallback { void accept(ComputationId id, Instant deadline); }`; `Deferred(ComputationCallback callback, Duration term)` on both `Awaited` and `ApprovalOutcome`; effects `DeferApproval(call, callback, term)` / `DeferToolCall(call, callback, term)`.

- [ ] **Step 1: The API shapes.** `ComputationCallback`, both `Deferred` records, `ApprovalContext` collapsed, `ToolContext` back to a record. Delete `Tool.timeout()` and its four call sites. Nothing compiles yet; that is expected.
- [ ] **Step 2: The two mandatory cells first** (spec §9a) — write these tests before the happy path:
  - `DeferringApproval` **admits a fresh `ApprovalDeferralRequested`** and replaces itself. Without this cell the re-ask can never land and the call is stuck forever. Assert the state's id/callback is replaced and no effect is lost.
  - A **throwing callback fails the call**: `fail(id, …)` on the computation, fold the failure completion, terminal `Failed` carrying the exception's detail. Assert the state is `Failed` and *not* `Awaiting…`.
- [ ] **Step 3: The request → effect → completion sequence.** The executor folds `…DeferralRequested` and never touches Continuum. `DeferApproval`/`DeferToolCall` create the computation with `min(term, ceiling)`, run the callback with `(id, deadline)`, then fold `…Deferred(call, id, deadline)`. `Deferring…`'s `outstanding()` returns the **originating** effect (`SeekApproval`/`RunTool`), not its own — document why at the override.
- [ ] **Step 4: Expiry as its own event.** `ApprovalExpired`/`ToolExpired`, both landing in `Expired`. Delete the delivery worker's expiry-to-denial/failure mapping — this removes logic rather than adding it.
- [ ] **Step 5: The ceilings.** Promote the private constants `APPROVAL_DEADLINE` (7 days) and `DEFAULT_TOOL_DEADLINE` (1 day) to `HarnessConfig.maxApprovalTimeout` / `maxCallTimeout`, and surface both on the Spring starter. Test that a term above the ceiling is clipped and that the callback receives the clipped `Instant`, taken from the harness's `InstantSource` and not `Instant.now()`.
- [ ] **Step 6: The long path, end to end.** Spec §2.3 path 8: approval deferred → notified → approved → tool deferred → notified → answered → `Completed`. Two waits, two handoffs, two crossings out of the process. This is the test that exercises everything at once.
- [ ] **Step 7: The observability follows.** `nessy.approval.seek` keeps its outcomes; the two `Deferring…` states appear in traces; `execute_tool`'s `nessy.tool.outcome` gains nothing new. Verify no span became a root.
- [ ] **Step 8: `./mvnw -q clean verify`, format, commit** `feat: deferral by callback`.

---

### Task D: Documentation

- [ ] **Step 1:** `docs/concepts/tools.md` (the deferring section — the callback replaces `defer()`), `docs/concepts/authorization.md` (the approver's deferred answer), `docs/guides/harness.md` (the two ceilings), `docs/guides/spring-boot.md` (the two properties), `CHANGELOG.md` (breaking: `defer()` gone, `Tool.timeout()` gone, wire format, the renames).
- [ ] **Step 2:** Amend `2026-08-25-approval-lifecycle-design.md` §1.3/§2/§3 and mark `2026-08-26-tool-context-defer-design.md` superseded, each with a two-paragraph pointer in the voice the earlier amendments use.
- [ ] **Step 3:** Add to the watchman README's failure table: *a WARN'd drop immediately following a handoff failure is the cleanup, not a fault* (spec §9a).
- [ ] **Step 4:** `python3 -m mkdocs build --strict`; `./mvnw -q clean verify`; format; commit `docs: deferral by callback`.
