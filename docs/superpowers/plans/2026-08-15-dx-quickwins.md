# The DX Quick Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pay the third DX audit's quick-wins list in one wave: guards, exceptions, the parking recipe, two tiny APIs, and the example/paper-cut sweep.

**Architecture:** Three batched tasks — kernel (APIs + exceptions + javadoc), examples (adoptions + renames + cleanups), paperwork (README Install + hygiene + CHANGELOG). Sequential; T2 consumes T1's APIs, T3 documents both.

**Spec:** `docs/superpowers/specs/2026-08-15-dx-quickwins-design.md` — binding. Evidence record: `.superpowers/dx-audit-2026-08-15.md` (git-ignored; cite by section, do not require it).

## Global Constraints

- TDD with RED/GREEN evidence for behavior changes; offline `./mvnw -q clean verify` green after EVERY task; container suites in tasks touching container-tested modules.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata. No suppressions, no star imports, no mocking libraries, prose snake_case test names, S5778/S5841. `./mvnw -q -pl nessy-core javadoc:javadoc` 0 errors after any javadoc-touching task.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Standing invariant: chat-web's smoke test assertions never change.

---

### Task 1: Kernel — guards, exceptions, recipes, two APIs

**Files:** `nessy-core`: `HarnessBuilder` (parks WARN gated on `storeSet`; `parks(...)` javadoc stops saying "callback door's own registry"), `AgentBuilder` (name path → `AgentConfigurationException` both branches with covenant; `requireNonNull` + javadoc on `approver`/`termination`/`systemPrompt`/`maxTokens`; `contextWindow` javadoc admits inertness), `api/WrongAgentException` (message gains token + fix sentence; keep both-sides), `api/UnknownParkTokenException` (drop "settled" from message+javadoc; print `token.value()`), `api/tool/Tool` (parking recipe on `execute`, `@see Agent#resume`), `api/Awaited` (javadoc on all four members), `api/turn/TurnEvent` (RunOutcome sentence → names `Agent#snapshot`/`Agent#peek`), `api/turn/TurnObserver` (+`logging(Logger, Supplier<String>)`), `spi/memory/Memory` (+`static Memory windowed(Memory delegate, int n)` via `Context.keepRecent`), `Agent` (`deny` outer double null-check dropped; `resume` javadoc restructured summary-first). `nessy-testing`: `ScriptedModelProvider` thread-safety (synchronize turn/request bookkeeping + javadoc line).

**Tests:** `AgentBuilderTest` both name branches → `AgentConfigurationException` with covenant (update existing two tests); parks-WARN quadrants (fires: store set + parks defaulted; silent: both defaulted / parks set) mirroring the memory-guard tests; null-rejection for the four setters; `MemoryWindowedTest` (clip at n, remember delegates, recall under n unclipped); `TurnObserverLoggingTest` supplier-overload cases (prefix resolved at narration time — a supplier returning different values narrates the current one); message-shape asserts for both exceptions. WrongAgentException test in `AgentDoorsTest` updates for the new message (keep one full-message assert).

- [ ] RED first for: exception types, WARN gating, windowed clip, supplier overload. GREEN; full offline verify + core javadoc 0 errors.
- [ ] Commit: `feat: the quick wins, kernel half — guards grow up and parking gets taught`

### Task 2: Examples — adoptions, renames, cleanups

**Files:** `night-watchman`: delete `WindowedMemory`, config uses `Memory.windowed(new TranscriptMemory(...), n)` one-liner (read the current wiring for the delegate shape); README's "about ten lines" corrected to the factory story. `order-desk/FulfillmentReplies`: collapse the hand-rolled resume observer onto `TurnObserver.logging(LOGGER, supplier)` (keep information parity — order id, ends, failed reason; the supplier resolves the id post-resume). `dispatcher`: inline `IncidentLog` (delete class, call `TurnObserver.logging` directly at both call sites with the bracketed-label prefix). `chat-web`: `NessyConfig` → `ChatWebConfig` (class + references; wiring-only). `hello`: `Hello.java` one comment on the testing-provider-in-main; new `hello/README.md` (what it demonstrates, run command, expected output line, no key/no network/no Docker). `chat-web/app.js`: `catch (err)` → `catch`. Remove stale local `nessy-examples/patient-researcher/` directory (untracked Eclipse leftovers; archived on branch `patient-researcher-archive`).

- [ ] Suites: night-watchman offline; order-desk, dispatcher, chat-web container (`-Dnessy.excludedGroups=live`); smoke assertions untouched. Full offline verify.
- [ ] Commit: `refactor: the quick wins, example half — one factory, fewer wrappers, honest fifteen lines`

### Task 3: Paperwork — Install, hygiene, CHANGELOG

Root README: **Install section** after the five-minute example (BOM import block with real coordinates from the reactor; the app-facing artifacts; the honest not-on-Central sentence); hero snippet's `.name("hello")` aligned with `Hello.java`; roster "mirroring" sentence replaced (facts vs narration, two vocabularies, one sentence on which to reach for); port map gains 4317; verify no other snippet drifted (compile-in-your-head pass over every code block touched this wave). CHANGELOG `[Unreleased]`: Added (Install docs, `Memory.windowed`, logging supplier overload, parking recipe, guard refinements) + Breaking (pre-1.0): name-path exceptions → `AgentConfigurationException`; parks WARN now conditional (behavioral doc note). Full offline + container sweep end to end.

- [ ] Commit: `docs: the quick wins sign off — install exists and the warnings mean it`

---

## Self-Review Notes (already applied)

- T1 lands both APIs before T2 consumes them; exception-type change lands with its README promise fixed in T3 (the promise text lives in the root README — T3's hygiene pass owns it; T1 owns making the code match the covenant).
- FulfillmentReplies parity named explicitly — its silent-resume regression history makes information parity the review lens again.
- The patient-researcher removal is local-untracked-only; the archive branch is the recovery path.
