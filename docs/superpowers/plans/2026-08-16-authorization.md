# Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The tool's effect starts the decision — `Tool.effect(input)` seeds a
per-grant authorization pass (context + enrichers + typed policy), with the ladder
law guaranteeing rung-0 grants pay nothing and feel nothing.

**Architecture:** Four fronts, strictly ordered. (1) The effect: `describe`→`effect`
rename with `Object` return + the `EffectfulTool<I, E>` tier. (2) The heart:
non-generic `AuthorizationContext`, `UsagePolicy<E>` with variance + canonical
singletons + identity-skip, `Enricher<E>` ordered per grant, fail-closed staging in
the executor's chokepoint, approver parity. (3) The substrate feeders: agent-level
principal resolver + the `spi.intent` bolt-on. (4) The report + a living example +
docs. The sealed decision vocabulary and the reducer are untouched throughout.

**Tech Stack:** Java 21 (records, sealed types, pattern matching), JUnit 5 + AssertJ,
ScriptedModelProvider.

**Spec:** `docs/superpowers/specs/2026-08-16-authorization-design.md` (RATIFIED).

## Global Constraints

- `./mvnw -q clean verify` green offline, always; FOREGROUND builds.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Standing rule (lesson 8): API-touching tasks run `./mvnw clean install -DskipTests`
  then reactor-wide `./mvnw javadoc:javadoc -Dmaven.javadoc.failOnError=true`.
- No mocking; prose names; no suppressions; no star imports; S5778/S5976/S107.
- The reducer (`ConversationState`), the loop's fold/park semantics, and the sealed
  `PolicyDecision` vocabulary are UNTOUCHED.
- **The ladder law is a test**: rung-0 grants (`allow()`/static `deny`) render no
  effect, run no enrichers, assemble no context — spy tools prove it — and their
  behavior is byte-identical to today.
- Config surfaces follow the interface ruling (interface + package-private impl).
- Known shapes (verbatim from main): `UsagePolicy.evaluate(ToolCall, ConversationState)`
  today, canonical `Allow.INSTANCE` identity-compared in `AgentConfig`;
  `Tool<I>.describe(I)` returning String, invoked by `ToolInvoker.describe` (bind →
  describe) and consumed at the approval-prompt surface; `GatedToolCallExecutor` is
  the authority chokepoint; `Approver` seam exists; `IntentTools` does not.

---

### Task 1: The effect — rename and typed tier

**Files:** `nessy-core/.../api/tool/Tool.java` (describe→`Object effect(I)`),
new `EffectfulTool.java`; `ToolInvoker.java` (+ its describe path), every
`describe` override repo-wide (internal subagent tools, examples, tests — the
compiler finds them); approval-prompt rendering sites switch to
`String.valueOf(effect)`.

**Steps:**
- [ ] Rename with the compiler as the guide; every existing String-returning override
  compiles as a covariant override once the interface returns `Object` — verify by
  building BEFORE touching any override, then normalize signatures only where style
  demands.
- [ ] `EffectfulTool<I, E> extends Tool<I> { @Override E effect(I input); }` + tests
  (a record effect; toString rendering at the approval surface pinned).
- [ ] Standing javadoc rule; full verify; commit.

### Task 2: The heart — context, policy, enrichers, chokepoint (HIGH RISK)

**Files:** new `api/tool/authorization/` (or beside UsagePolicy — follow zone
conventions): `AuthorizationContext.java` (interface + package-private impl),
`Key.java`, `Enricher.java`; reshape `UsagePolicy.java` (generic `<E>`, two-arg
evaluate, canonical `UsagePolicy<Object>` singletons, `of(...)` rung-1 factory);
`ToolGrant.java` (ordered enrichers + typed overloads welding E);
`GatedToolCallExecutor.java` (the staged chokepoint: parse → effect → enrichers →
policy → decide; identity-skip for static policies; fail-closed Deny naming the
stage on any throw); `Approver` parity (receives final context + rendered effect —
smallest honest evolution of the current seam); migrate every in-repo policy usage.

**Interfaces (Produces):** exactly spec §3/§4/§5's signatures — including
`Enricher<? super E>` and `UsagePolicy<? super E>` acceptance everywhere, and
`AuthorizationContext.with(Key<T>, T)` functional extension.

**Steps:**
- [ ] Failing tests first, then the reshape: the ladder-law spy proof (rung 0);
  two-arg policy migration of allow/deny/requireApproval with identity preserved
  (`AgentConfig`'s approver-defaulting check still works); variance proofs
  (`allow()` terminates a typed grant; `Enricher<Object>` composes into one);
  enricher ordering + functional context extension (later enricher's deposit
  invisible to earlier one, visible to policy); fail-closed per stage with
  stage-named reasons; deny-reason compaction unchanged (existing tests);
  approver-parity test (approval prompt renders the effect's toString; approver
  sees deposited keys).
- [ ] Full verify; standing javadoc rule; commit.

### Task 3: The feeders — principal seam + spi.intent

**Files:** `AgentConfig.principal(Function<ConversationId, ?>)` (resolver seam,
impure-allowed, fail-closed → context slot); new `spi/intent/` module:
`IntentTools.declare(vocabulary)` (+ clear verb — one tool with clearing value or a
second tool, your call, reported), vocabulary-as-schema (enum/sealed → strict;
String.class → open), transcript-derived latest-wins feeding
`context.declaredIntent()`.

**Steps:**
- [ ] Principal: resolver wired at agent build; typed recovery tests (hit/miss/absent);
  resolver throw → the call's authorization fails closed (test).
- [ ] Intent: vocabulary schema pinned (enum case: model cannot declare off-vocabulary
  — parse rejects); lifetime tests (declare → decide sees it → redeclare → clear);
  replay reads the transcript (a rebuilt context sees the same intent); unwired →
  absent slot, zero ceremony.
- [ ] Full verify; standing javadoc rule; commit.

### Task 4: The report, the living example, the docs

**T4a (implementer):** the §8 report — a grant renders its authorization story
(effect type, enricher names in order, policy identity), the agent aggregates;
pinned against wiring. Upgrade order-desk: `RequestFulfillmentTool` becomes
`EffectfulTool` with a typed effect (amount/order shape) and a threshold policy
(approval above a limit, allow below) + one enricher — the living sample the docs
quote; its smoke tests keep passing.

**T4b (docs-writer):** rewrite `docs/concepts/tools-and-grants.md`'s authority half
around the ladder; new `docs/concepts/authorization.md` (the domain map, trust
gradient, the ladder, enrichers/policy symmetry, intent, principal, the report, the
named non-goals incl. §10's no-corners audit); README capabilities row; CHANGELOG
first-release entries; mkdocs nav; strict build.

**Steps:**
- [ ] T4a then T4b (docs quote the shipped example); full verify each; commit each.
