# Durable Parcels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute the durable-parcels spec — identity over `ModelResponseId`, create-carries-continuation, presence-means-pending, the outbox parcel pipeline with fold-advance delivery, retry/deadline semantics, and the deletion ledger (§8).

**Architecture:** per `docs/superpowers/specs/2026-08-22-durable-parcels-design.md` (binding, commit b9e823fe). The reducer stays pure; ids are executor-generated and event-carried; both pipeline hand-offs are single substrate batches; the host worker gains delivery + reaping sweeps with in-process nudge.

**Parallel-branch note:** the json-repeal branch runs beside this one. This plan AVOIDS `RegistryToolCallExecutor`, `Schemas`, `SealedInputs`, and `CodecSupport` until Task 3, by which point the repeal should be merged; Task 3's dispatch must check and rebase/merge main first.

## Global Constraints

- Build economics; house law (no mocking libraries, @SuppressWarnings, star imports; prose tests; S5778; S5841); license+spotless per commit; full gate once per task; release profile when published javadoc changes.
- The reducer is a PURE fold — no id generation, no clock reads, no randomness inside `Phase.handle`.
- Wire-format law: phase/message/computation-document golden pins update ONLY where the spec changes shapes (AwaitingTools gains `responseId`; the computation document loses `status` and gains invocation/continuation/deadline; parcels are new) — every changed shape gets a NEW golden pin; nothing else drifts.
- All new wire records serialize via Jackson annotations — zero hand-built JSON (the repeal ruling binds here too).
- Deletions must be total per spec §8 — grep-clean at branch end for: await, AwaitResult, ComputationStatus, ALREADY_TERMINAL, ContinuationDispatcher, ContinuationHandler (docs/superpowers historical exempt).
- Design freeze: the ratified vocabulary is ModelResponseId, ToolInvocationId, RetrySemantics, PendingComputation, deadline/timeout config; anything else stops the task.

## Tasks

### Task 1: Identity — the fold learns whose answer it is waiting for
nessy-agent + nessy-durable only (parallel-branch safety). `ModelResponseId` record (nessy-agent, engine vocabulary; UUIDv7 via the existing id conventions); generated in the model-call executor when a response arrives (find the executor in the Harness guts — read how `ModelFinished` events are produced today); carried as a new component on `ModelOutcome.Responded`; stored as a new component of `Phase.AwaitingTools` (annotation-bound; compact-constructor null-check; golden pins for the phase wire shape UPDATED to include it — the assertions change deliberately, this is the one sanctioned wire change of the task). `ToolInvocationId(String responseId, String callId)` record in nessy-durable (pure strings, zero deps). Address derivation: wherever `CallAddress`/computation ids are derived (SlotDeferredToolCallPolicy, SlotApprover — read them), the derivation becomes `prefix:agentType:agentId:responseId:callId`, with the responseId read from the committed AwaitingTools state. Redrive derivations must recompute identically — find every derivation site and prove with a test that a re-derived address equals the original. Tests: purity (re-handling the same ModelFinished yields identical state including responseId); derivation stability across redrive; goldens. Commit: `feat: the fold knows whose answer it awaits — ModelResponseId lands`

### Task 2: The pivot — ownership transfer replaces waiting
The backend reshape and pipeline, one coherent unit (Opus-reviewed): `DurableComputationBackend` becomes `create(id, invocation, returnAddress, deadline) / complete(id, outcome) / find(id)` per spec §3 (single `Continuation`, `Optional<Instant>` deadline, `PendingComputation` record); `SubstrateComputations` rewritten presence-means-pending (create = document write with invocation/continuation/deadline, annotation-bound wire records; complete = batch [delete computation, create parcel]; absent-complete = benign already-done; find = read); the parcel recipe (`kind=outbox`, UUIDv7 key, `{destination, outcome}`); the delivery worker in the host (single heartbeat thread + in-process nudge; per parcel: resolve scope, pure re-handle, batch [journal appends, state CAS, parcel delete], dispatch effects post-commit; CAS-miss retry; Transition.ignore → bare delete); desks rewire (`CompletionDesk`/`ApprovalDesk` call the new complete then nudge); `SlotApprover`/`SlotDeferredToolCallPolicy` create-with-continuation at dispatch (continuation = the routing record, annotation-serialized); spec §8 deletions executed (await/AwaitResult/ComputationStatus/ALREADY_TERMINAL/ContinuationDispatcher/ContinuationHandler/ScopeResumption's live-fire path — ScopeRedrive SURVIVES for staleness); all suites migrated (the old await-shaped tests are re-expressed as pipeline tests, not weakened — the one-flip test becomes the concurrent-completion ownership test). New golden pin for the pending-computation document and the parcel document. Full gate + release profile. Commit: `feat: ownership transfer replaces waiting — the parcel pipeline lands`

### Task 3: Retry, deadlines, the reaper — and the tool learns its name
FIRST: merge main into the branch (the json-repeal should have landed; resolve trivially, rerun scoped tests). Then: `RetrySemantics` enum + optional timeout `Duration` on `ToolConfig`/registration (default NON_RETRYABLE, no timeout; javadoc teaches the assertion RETRYABLE makes); dispatch stamps `deadlineAt`; the reaper as the worker's second sweep (scan+decode pending computations; RETRYABLE overdue → CAS deadline bump + redispatch same ToolInvocationId through the existing dispatch path; NON_RETRYABLE overdue → complete(Failure(TIMEOUT_NON_RETRYABLE)); deadline-less skipped); `ToolContext` gains the `ToolInvocationId` (every invocation receives it — executor threading; the repeal's rewritten binding call sites updated accordingly). Tests: retry identity across redispatch; non-retryable timeout rides the pipeline into the fold; deadline-less never reaped; reaper race (two workers, one bump). Commit: `feat: the reaper and the retry contract — deadlines are durable state`

### Task 4: The failure-boundary suite (spec §9)
Tests only, over the shipped pipeline: injected-conflict atomicity at both hand-offs (RaceOnce-style substrate fixtures on batch); process-loss across three runtimes (create+dispatch / complete / deliver, each on a fresh host over one substrate); concurrent completion single-winner; duplicate parcel delivery absorbed with single consumption; plus any spec-§9 case not already covered by Tasks 2–3. No production changes (a found bug stops the task and reports). Commit: `test: the parcel pipeline proves its crash points`

### Task 5: Paper trail
docs/concepts/durable-computation.md rewritten to the parcel model (the CompletableFuture teaching frame retires; ownership-transfer diagram; retry/deadline section; truth discipline on what ships); storage.md §outbox updated from specified-to-built; tools.md gains RetrySemantics/timeout registration docs; glossary-level sweep for await/slot language in site docs; README/CHANGELOG; mkdocs strict. Commit: `docs: nothing waits — the site learns the parcel pipeline`

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | **Opus** (identity + wire + purity) |
| 2 | Sonnet | **Opus** (the pivot — pipeline atomicity) |
| 3 | Sonnet | Sonnet |
| 4 | Sonnet | Sonnet |
| 5 | docs-writer (Sonnet) | Haiku scoped |
| Final | — | **Opus** |
