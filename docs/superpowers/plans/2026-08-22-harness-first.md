# Harness First Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute the harness-first spec — the harness absorbs the host's machinery, `bind` returns the agent, `Nessy.harness()` becomes the one door with the five-minute minimum, `AutonomousHost` and public `Binding` die.

**Architecture:** per `docs/superpowers/specs/2026-08-22-harness-first-design.md` (binding, commit 3451a22b). Two structural moves kept separately green: first the harness absorbs the machinery while the host thins to a delegating shim; then the door reshape deletes the shim and lands the builder minimum.

**Spec:** the harness-first spec + the amended §10.11 tier vocabulary + durable-deliveries (worker semantics unchanged, home changes).

## Global Constraints

- Build economics; house law (no mocking libraries/@SuppressWarnings/star imports; prose camelCase tests; S5778; S5841); license+spotless per commit; ONE full `./mvnw -q clean verify` per task; release profile when published javadoc changes; sequential foreground Mavens only.
- NO behavior changes to the delivery pipeline itself: the worker/reaper/desks move house but their semantics, tests, and golden pins stay byte-identical except the NEW type-filter law (spec §5), which gets its own tests.
- Design freeze: the spec's roster is exhaustive — `Nessy.harness()`/`harness(Class)`, `HarnessBuilder` (rename), `.systemPrompt`, `.tools` allow-sugar, `.type` default "agent", `harness.bind→Agent`, `harness.approvals()/completions()/shutdown()`; anything else stops the task.
- The full-weave and seam-kill items are OUT OF SCOPE (spec non-goals) — do not touch mapper threading or the memoryFactory/backend seams beyond mechanical relocation.

## Tasks

### Task 1: The harness absorbs its life-support
`Harness<O>` gains ownership of the DeliveryWorker, the ApprovalDesk/CompletionDesk, and the reaper wiring (constructed by the internal compiler, daemon-threaded exactly as today), plus `approvals()`, `completions()`, and the infrastructure-only `shutdown()` (javadoc states the container/test-teardown contract; NOT AutoCloseable). `bind(AgentId)` returns `Agent<O>` (constructs the DefaultAgent internally); `Binding` leaves the public surface (package-private or internal record; the worker's fold machinery keeps its package-visible stores seam). TYPE-FILTERED SWEEPS (spec §5, new law): worker drain and reaper sweep skip records whose routing/address agentType is not this harness's type, BEFORE decoding further; tests — two harnesses of different types over one substrate: each delivers only its own outbox records and reaps only its own computations (counting executors both sides); a foreign-type record is untouched (still present) after a sweep. `AutonomousHost` survives this task as a THIN delegating shim (post → bind+observe; approvals()/completions() → harness's) so the reactor stays green; its tests keep passing unmodified. Full gate + release profile. Commit: `feat: the harness absorbs its life-support — bind returns the agent`

### Task 2: One door — Nessy.harness() and the five-minute minimum
`AutonomousBuilder` renames `HarnessBuilder<O>`; `build()` returns `Harness<O>`; `Nessy.harness()` / `Nessy.harness(Class<O>)` are the doors (autonomous() deleted); `.systemPrompt(String)` sugar over ModelSettings; `.tools(Tool...)` allow-by-default beside `.grants(...)`; `.type` defaults to AgentType "agent" with the one-per-type-per-substrate contract in its javadoc; `.substrate` default unchanged. DELETE `AutonomousHost` and the shim; every test/demo/example migrates to the harness API (post→bind+observe; try-with-resources blocks around hosts DELETED — the harness is kept, not closed; test teardown uses shutdown()). `Harness.of` javadoc states the only-compiler law. CliBuilder untouched beyond compile ripples. Grep-clean: AutonomousHost zero java refs. Full gate + release profile. Commit: `feat: one door — ask Nessy for a harness and keep it`

### Task 3: Paper trail
getting-started rewritten to open with the spec §1 sentence and snippet; autonomous-agents guide becomes the harness guide (kept-not-closed, bind/observe, durability-is-the-substrate, one-type-per-harness contract, approvals/completions on the harness); the-four-tiers amends per §4 (harness = recipe + life-support; host retires to meaning the process); observability/mcp/index sweeps for AutonomousHost/post(); README five-minute example; CHANGELOG. mkdocs strict. Commit: `docs: ask Nessy for a harness — the site learns the one door`

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | **Opus** (machinery relocation + new sweep law) |
| 2 | Sonnet | **Opus** (the public API reshape) |
| 3 | docs-writer (Sonnet) | Haiku scoped |
| Final | — | **Opus** |
