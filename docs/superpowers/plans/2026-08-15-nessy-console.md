# nessy-console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-console` — the terminal front door as a zero-dependency library (`ConsoleRepl`, the styled default renderer, `Ansi`, `ConsoleApprover` coming home), the three hand-rolled mains collapsed onto it, paperwork once.

**Architecture:** Three tasks on the EXISTING `scout` branch (fold ruling — scout's Task 1 landed there at 20bf5b3 and pauses; this plan continues the branch): the library with stream-seam unit tests; the collapse of three mains; combined paperwork. Sequential.

**Spec:** `docs/superpowers/specs/2026-08-15-nessy-console-design.md` — binding. Hard line: SGR-only, zero dependencies beyond nessy-core, no core/TurnEvent changes.

## Global Constraints

- Offline `./mvnw -q clean verify` green after every task (all console tests run headless via the injected-stream seam).
- **Standing invariant: `ScoutTest` passes UNTOUCHED through the collapse** (it tests the grant table via the seam, not the loop).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata, no suppressions, no star imports, no mocking libraries, prose snake_case test names, S5778/S5841, Awaitility not sleep (the spinner test especially), tolerant example switches.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: The library

**Files:** new module `nessy-console` (root aggregator + `nessy-bom` entries; pom: nessy-core only), `Ansi.java` (spec §2 — styles + `enabled()` with TTY/`NO_COLOR`/`TERM=dumb` detection, cached, package-private override seam), `ConsoleRenderer` (or equivalent — the default `TurnObserver` per spec §3: plain text deltas, dim-italic thinking, dim `⚙ tool:` lines with park token, red FAILED line, exposed for composition), the `\r` spinner (virtual thread, stops on first event, never runs when styling disabled), `ConsoleRepl` (spec §3 builder: of(Agent<String>), banner/prompt/exitOn/renderer, run(); package-private reader/writer constructor for tests; public path uses `java.lang.IO`), `ConsoleApprover` (spec §4 — moved in as PUBLIC library code, highlighted prompt via `describe(...)`, same stream seam), package-info.

**Tests (all headless/default-build):** Ansi on/off behavior (disabled = pass-through — pin exact equality); renderer cases per event type incl. FAILED (feed TurnEvents, capture the writer, assert styled/plain per the Ansi seam); spinner starts-then-erased-by-first-delta (Awaitility, capture stream, assert the `\r` erase; disabled-styling → zero spinner bytes); repl loop (banner, prompt, exit words, blank-line re-prompt, tell-per-line — a scripted agent via nessy-testing test-scope); ConsoleApprover y/n/garbage-input paths through injected streams.

- [ ] RED first; GREEN. Offline reactor green. Commit: `feat: nessy-console — the terminal front door becomes a library`

### Task 2: The collapse — and the env helper (amended: spec §4a/§5)

**Files:** NEW micro-module `nessy-model-env` first (aggregator + BOM; pom depends on BOTH provider modules non-optionally; one `fromEnv()` method per spec §4a with the env-map seam; offline tests: each-key-alone, both-keys+tiebreak+default-notice, neither-fails-noisy-naming-variables). Then the collapse: `chat-cli` consolidates to ONE main `Chat` (provider via `fromEnv()`, `events()` subscription survives, `AnthropicChat`+`OpenAiChat`+module-private `ConsoleApprover` DELETE, pom swaps provider deps for `nessy-model-env` + `nessy-console`); `scout`: `Scout` onto `ConsoleRepl` + `fromEnv()` (toolbox block + grants + construction seam verbatim; its `ConsoleApprover` copy DELETES).

- [ ] `ScoutTest` UNTOUCHED and green; chat-cli compiles + its (no-smoke) family posture unchanged; offline reactor green. Behavior-parity check in the report: every information channel the old hand-rolls printed (deltas, tool lines, endings, approval prompt) demonstrably present in the new default (unit-test evidence from Task 1 suffices — enumerate the mapping).
- [ ] Commit: `refactor: three mains, one library — the family learns its look`

### Task 3: Paperwork — once

`nessy-console/README.md` (spec §6: the one-liner, the look, SGR-only covenant, NO_COLOR, JLine v2 note); scout README refreshed post-collapse (grants lesson untouched); chat-cli README refreshed; root README (Install row for nessy-console; examples intro sentence re the shared console library); CHANGELOG `### Added` covering nessy-console AND scout together. Full offline + container sweeps end to end.

- [ ] Commit: `docs: the console papers — one look, written once`

---

## Self-Review Notes (already applied)

- The ScoutTest-untouched invariant is the collapse's tripwire — named in Task 2 and the Global Constraints both.
- The spinner is the one concurrency surface; its test is Awaitility-based and the disabled-mode zero-bytes case is explicit (a piped consumer must never see spinner frames).
- Paperwork deliberately waits for the collapse so scout's README is written against the shipped shape (the fold's whole point).
