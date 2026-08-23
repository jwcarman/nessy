# Front Ends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Land ask/tell, scoped subscriptions, and the Console per the front-ends spec.

**Architecture:** Rename lands first (pure sweep); the harness grows an internal per-id observer fanout surfaced as `Agent.subscribe` → `Subscription`; `ask` composes tell+subscribe+drive into a `TurnOutcome`; the Console owns the terminal and `Nessy.cli()` becomes preset sugar over it.

**Tech Stack:** Existing reactor; no new dependencies.

**Spec:** docs/superpowers/specs/2026-08-23-front-ends-design.md

## Global Constraints

- No new event types; `TurnObserver`'s existing vocabulary is the only stream.
- `Subscription` is the only AutoCloseable on the API; close idempotent, never throws.
- Fanout is worker-inclusive: a delivery folding later reaches that id's subscribers.
- No suppressions, no star imports, no mocking libraries; S5778/S5841; sealed switches without default arms; prose test names.
- Gates per task: warm scoped builds; final `./mvnw -q clean verify` once; javadoc gate via `./mvnw -q -B -P release -DskipTests -Dgpg.skip=true javadoc:jar` (never the full release verify locally); `python3 -m mkdocs build --strict` when site docs change; license:format + spotless:apply before commits.
- Commit trailers: Co-Authored-By: Claude Fable 5 <noreply@anthropic.com> / Claude-Session: https://claude.ai/code/session_011BDRXMHSwsWj2EjsRkwGTW

---

### Task 1: The tell rename

**Files:** `Agent.java` (rename observe→tell), every caller (main, tests, examples), site docs + README snippets, harness-first spec §1/§6 NOT touched (historical record; the front-ends spec §1 records the amendment).
**Interfaces:** Produces `Agent.tell(O)`; `drive()` unchanged. Consumers in later tasks use `tell`.
Steps: mechanical sweep (git grep observe( on Agent surfaces — do NOT touch the reducer/fold-internal "observation" vocabulary or ObservationRenderer); tests keep meaning; docs sweep; mkdocs gate; commit `feat: the agent learns to be told`.

### Task 2: Subscriptions and the fanout

**Files:** New `Subscription` (nessy-api, beside TurnObserver); `Agent.subscribe(TurnObserver)`; harness-internal per-id registry; wiring so BOTH the executor-side turn observer path and the DeliveryWorker fold path emit through the fanout for that id; `HarnessConfig` composes the configured global observer as one more subscriber, preserving existing behavior.
**Interfaces:** Produces `Subscription Agent.subscribe(TurnObserver)`; `interface Subscription extends AutoCloseable { void close(); }` (no throws). Existing `.turnObserver(...)` config keeps working unchanged.
Key risks (review lens): thread-safety of the registry under concurrent subscribe/close/emit (CopyOnWrite semantics recommended); no emission reordering; close-during-emit safe; no leak of worker threads through subscriber exceptions (subscriber throw = isolate, never break the fold — decide + document).
Tests: subscribe receives worker-driven turn events (durable path — park an approval, approve, subscriber sees the resumed turn); close is idempotent + stops delivery; two ids never cross; subscriber exception does not poison the fold; teardown discipline via HarnessTeardown.
Commit: `feat: turns become subscribable — scoped, closeable, worker-inclusive`.

### Task 3: ask and TurnOutcome

**Files:** `TurnOutcome` (nessy-api, sealed per spec §1); `Agent.ask(O)` implemented as the pattern: subscribe, tell, drive, resolve outcome from events, close subscription (try-with-resources).
**Interfaces:** Consumes Task 2's subscribe. Produces `TurnOutcome ask(O)`.
Resolution rules: AssistantSaid + TurnEnded(no failure) → Replied(text); ToolCallDecided→park / the §5a approval request surfacing → Parked(request) — thread the ApprovalRequest through the existing notifier/observer seam WITHOUT new event types (the notifier's request is captured at harness level for the asking id; design detail: implementer proposes mechanics in report, reviewer verifies no new event type snuck in); TurnEnded(failureReason) → Failed(reason).
Tests: scripted model replies → Replied with exact text; approval-required tool → Parked carrying the ticket whose id approves; failing model → Failed with reason; ask never leaks its subscription (registry empty after).
Commit: `feat: ask — a turn's outcome becomes a value`.

### Task 4: Console, cli preset, docs

**Files:** `Console` (nessy-agent host package or sibling; public); rework `CliAgent`/`Nessy.cli()` as preset sugar over harness+Console per spec §3; getting-started/harness guide additions (ask/tell + console story); CHANGELOG.
**Interfaces:** Consumes ask/tell/subscribe. Produces `Console` with `approver()` face + runner loop; `Nessy.cli()` unchanged signature, new internals.
Tests: console approver renders flattened request and answers by id (scripted IO — hand-rolled streams, no mocking); runner loop: Replied prints, Parked routes to approver then re-asks, Failed prints honestly; cli preset end-to-end with ScriptedModel.
Commit: `feat: the console — ask at the prompt, approve at the desk`.

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | Haiku scoped (mechanical sweep) |
| 2 | Sonnet | **Opus** (fanout concurrency) |
| 3 | Sonnet | Sonnet |
| 4 | Sonnet | Sonnet |
| Final | — | **Opus** whole-branch |
