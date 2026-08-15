# Scout Plans Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** nessy-console renders the model's plan as a styled checklist (`ConsoleRepl.Builder.plan(store)`), and Scout adopts the plan facility as its showcase; chat-cli gains the display one-liner.

**Architecture:** Opt-in store handoff to the repl; change-detected end-of-turn rendering; SGR-only styling per the console covenant. No kernel change, no new dependencies.

**Tech Stack:** Java 21+, JUnit 5 + AssertJ, hand-rolled doubles.

**Spec:** `docs/superpowers/specs/2026-08-15-nessy-console-design.md` §9 and `docs/superpowers/specs/2026-08-15-scout-design.md` (final amendment). Read both before any task.

## Global Constraints

- No suppressions, no star imports, no mocking libraries, prose snake_case tests, S5778/S5841, no `System.out` outside ConsoleIo's existing adapter.
- SGR-only: no raw mode, no cursor addressing. Styling must pass through the existing `Ansi` enable/disable detection; disabled mode uses ASCII markers.
- Full offline `./mvnw -q clean verify` + reactor `./mvnw -q javadoc:javadoc` green per task.
- Before commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`. Trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never push.

---

### Task 1: The console checklist

**Files:**
- Modify: `nessy-console/src/main/java/org/jwcarman/nessy/console/Ansi.java` (strikethrough SGR 9 helper, same shape as bold/dim/italic), `ConsoleRenderer.java` (public checklist-rendering method taking a `org.jwcarman.nessy.spi.plan.Plan`, writing via its existing writer seam), `ConsoleRepl.java` (+nested Builder: `plan(PlanStore)` verb; end-of-turn change-detected render)
- Test: extend the existing `AnsiTest`/`ConsoleRendererTest`/`ConsoleReplTest` suites in their established style

**Interfaces produced (Task 2 consumes):** `ConsoleRepl`'s builder gains `plan(org.jwcarman.nessy.spi.plan.PlanStore store)` (returns the builder; NPE on null; IllegalStateException on second call).

**Method — read before writing:** read `ConsoleRepl`, `ConsoleRenderer`, `Ansi`, and their tests COMPLETELY first; follow their existing seams (the package-private reader/writer seam, the styling-disabled pass-through) rather than inventing new ones.

- [ ] **Step 1: Tests first** in each suite's own idiom: strikethrough emits `ESC[9m`…reset and passes through untouched when disabled; renderer checklist — DONE = dim+strikethrough with `☒`, IN_PROGRESS = bold `◐`, PENDING plain `☐`, two-space indent, one line per task, ASCII `[x]`/`[>]`/`[ ]` when styling disabled; repl — after a scripted turn, a changed plan prints once; an unchanged plan prints nothing on the next turn; absent store (no `.plan(...)`) prints nothing and reads no store; empty/absent plan prints nothing; the final all-DONE state prints (it differs from the previous render); builder validation (null, double-call).
- [ ] **Step 2: Implement** — `Ansi` strikethrough; `ConsoleRenderer` checklist method (javadoc names the spec §9 contract); `ConsoleRepl` holds the optional store + last-rendered `Plan`, and after each completed tell (after the turn's rendered output, before the next prompt) reads `store.find(conversationId)` for its own conversation and renders on change when present and non-empty.
- [ ] **Step 3:** `./mvnw -q -pl nessy-console -am verify` green; reactor javadoc green.
- [ ] **Step 4: Commit** `feat: the console shows the plan — a checklist in the flow, SGR and nothing more`.

### Task 2: Scout adopts the plan; chat-cli adds the line

**Files:**
- Modify: `nessy-examples/scout/src/main/java/**/Scout.java` (read it first — wire per the scout spec amendment: `PlanStore.inMemory()`, `Transcript.inMemory()`, grant `PlanTools.updatePlan(store)` with `UsagePolicy.allow()`, memory = `Memory.pipeline(transcript).transform(PlanTools.transformer(store)).build()`, one system-prompt sentence steering multi-step research through `update_plan`, `.plan(store)` on its `ConsoleRepl`), scout `README.md` (sample session with the checklist ticking), `nessy-examples/chat-cli/src/main/java/org/jwcarman/nessy/examples/Chat.java` or `DemoAgent.java` (wherever the repl is built and the store lives — add `.plan(planStore)`; the store may need to surface from `DemoAgent.agentFor` to `Chat`'s repl wiring; smallest honest refactor, e.g. `agentFor` returning a small record of agent+planStore, or the store constructed in `Chat` and passed in — read both files and pick the cleaner; document the choice in your report), chat-cli `README.md` (mention the display)
- Test: `ScoutTest` must stay green with assertions untouched (wiring change only — if its fixture builds the agent, adapt construction only); chat-cli has no tests to change.

**Interfaces:** consumes Task 1's `plan(PlanStore)` builder verb; consumes core's `PlanTools`/`PlanStore`/`Memory.pipeline` as shipped.

- [ ] **Step 1:** Wire scout per the spec amendment; `./mvnw -q -pl nessy-examples/scout -am verify` green.
- [ ] **Step 2:** chat-cli `.plan(...)` line + READMEs; `./mvnw -q -pl nessy-examples/chat-cli -am verify` green.
- [ ] **Step 3:** Reactor `./mvnw -q clean verify` + `./mvnw -q javadoc:javadoc` green.
- [ ] **Step 4: Commit** `feat: scout plans its research — the checklist ticks between DeepWiki calls`.

## Self-review notes
- T1's builder verb signature matches T2's consumption; marker glyphs and fallback pinned in both spec §9 and T1's tests; no placeholders; two tasks because the console feature and the example wiring are independently reviewable.
