# Distillation (Plan 5 of 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute spec §9 — shed the old loop from `nessy-core` and fell its dependents (scorched-earth ruling), reshape the surviving vocabulary (`TurnEvent`, `Awaited`, `ToolContext`, `AuthzContext`), kill `ParkToken` and `ConversationId`, and leave core as exactly what q8 promised: vocabulary + SPIs that never see the machine.

**Architecture:** Deletion-first, compiler-guided: fell dependent modules, then delete the old loop and let `./mvnw clean verify` name every survivor that still references it. Reshapes come after deletions so they touch only surviving files. `nessy-core` gains one dependency — `nessy-durable` (the base of the stack) — so `Awaited` can speak the primitive's vocabulary.

**Tech Stack:** Java 25, Maven. Mostly `git rm` and small reshapes; every task ends green.

**Spec:** `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` §9 (the deletion table) + §11 rulings; `docs/superpowers/specs/2026-08-20-durable-computation.md` preamble.

## Global Constraints

- `./mvnw -q clean verify` green after EVERY task — no task leaves the build broken.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage.
- No `@SuppressWarnings`; no star imports; S5778/S5841; camelCase prose test names; no mocking libraries.
- **This plan is licensed to change and delete `nessy-core`** — that is its purpose. The new gate: core keeps only `api/**` + `spi/model/**` (+ the new `nessy-durable` dependency); core must never reference `org.jwcarman.nessy.agent` (Task 6 makes that mechanical).
- Deletions are `git rm` — never stubbing, never commenting out.

## Plan-level design decisions (deliberate — do not "fix")

1. **Scorched earth** (user ruling): `nessy-console`, `nessy-autoconfigure`, `nessy-spring-boot-starter`, `nessy-tck`, `nessy-jdbc`, `nessy-examples` are deleted as modules. Spring/console stories return on the new API in later plans. Old-loop *tests* in surviving modules (`nessy-testing` ×3, `nessy-tool-mcp` ×1, `nessy-model-anthropic` ×2) are deleted, not migrated — the new-world equivalents already exist (`CliAgentTest`, `CliLiveSmokeTest`, `WiringDemo`, `DurableParkDemo`).
2. **`Awaited` becomes `Ready<T>(T) | Deferred<T>()` — a marker, carrying no id.** The wiring derives the deterministic slot identity (durable-spec ruling 4); a tool cannot mint one honestly because it cannot reach the backend and does not know the scope coordinate. `ToolExecution.Deferred(ComputationId)` remains the executor-side truth that *does* carry the reference. A future `ToolContext` may grow slot-creation for tools that own their references — not this plan.
3. **`ToolContext` loses identity entirely**: `ToolContext(ToolCall call, EventEmitter events)`; `ToolProgress(String toolCallId, String message)`. Progress is call-scoped; tools never needed the scope. This is what lets `ConversationId` die.
4. **`AuthzContext` sheds its dead members** (`conversationId()`, `state()`) per §4.2's adaptation note; the extensible key mechanism, `agentName()`, `call()`, and the grants survive untouched. The gate itself is Plan 6.
5. **`TurnEvent.TurnEnded` becomes `TurnEnded(String failureReason)`** with `failureReason == null` meaning completed (add `boolean failed()`); `ToolCallParked` and the `logging()` token line are deleted; `ConversationStatus` dies with them.
6. **The old `org.jwcarman.nessy.Nessy` dies in the sweep** — the two-Nessy collision resolves by subtraction.
7. **The old design doc gets a superseded banner, not edits**; `/Users/jcarman/CLAUDE.md` is NOT touched (user-global), but the repo `CLAUDE.md`'s design-of-record pointer flips to the 2026-08-18 spec + companion.

## Tasks

### Task 1: Fell the dependent modules
**Files:** `git rm -r` `nessy-console`, `nessy-autoconfigure`, `nessy-spring-boot-starter`, `nessy-tck`, `nessy-jdbc`, `nessy-examples`; remove their `<module>` entries from root `pom.xml` and any entries in `nessy-bom/pom.xml`. Delete old-loop tests: `nessy-testing/src/test/.../EndToEndTest.java`, `AgentFacadeTest.java`, `ReflectionEndToEndTest.java` (and any files those alone reference), `nessy-tool-mcp/src/test/.../McpToolboxEndToEndTest.java`, `nessy-model-anthropic/src/test/.../AnthropicLiveTest.java`, `RecoveryShapeTest.java`. If `nessy-testing`'s main sources reference the old loop, apply the same rule (delete the old-loop halves; if the module empties, delete the module and its pom/bom entries — report which).
**Steps:** rm → build → let the compiler find stragglers in the removed tests' support files → green → commit `"chore: the dependents fall — six modules and six old-loop test files"`.

### Task 2: Shed the old loop from core
**Files (git rm):** all 17 root classes under `nessy-core/src/main/java/org/jwcarman/nessy/` (`Agent`, `AgentAssembly`, `AgentConfig`, `AgentConfigurationException`, `AgentCustomizer`, `Conversation`, `Harness`, `HarnessConfig`, `HarnessCustomizer`, `IntentAssembly`, `ListenerDeclarations`, `Nessy`, `Subagent`, `SubagentAssembly`, `SubagentConfig`, `SubagentCustomizer`, `TypedSubagentDeclaration`); the whole of `internal/`; the whole of `spi/{conversation,execute,intent,memory,subagent,transcript,notebook,plan,reflection}`. KEEP: `api/**`, `spi/model/**`. Then delete every core test that tested the deleted (compiler-guided; expect a large `src/test` sweep — `ConversationTest`, `ConversationLoopTest`, `AgentDoorsTest`, `GatedToolCallExecutorTest`, `ProviderModelCallExecutorTest`, subagent/notebook/plan/reflection/transcript/store suites, and whatever else breaks). Where a surviving `api`/`spi.model` type's test imported a deleted helper, trim the test rather than the type. `api/conversation/{ConversationId,ConversationStatus}` SURVIVE this task (still referenced by `TurnEvent`/`ToolContext`/`AuthzContext`/`ToolProgress`) — they die in Tasks 3-4.
**Green gate matters here:** after this task the reactor must still build `nessy-core → nessy-durable? (not yet) → model modules → nessy-agent` — the model modules and `nessy-agent` import only `api/**` + `spi/model/**`, verified in earlier reviews. Commit `"chore: the old loop leaves core — api and spi.model remain"`.

### Task 3: `TurnEvent` reshape
**Files:** `nessy-core/.../api/turn/TurnEvent.java` (`TurnEnded(ConversationStatus, String)` → `TurnEnded(String failureReason)` + `public boolean failed() { return failureReason != null; }`; delete `ToolCallParked`), `TurnObserver.java` (delete the `token=` logging line and the parked handler; update `logging()`'s `TurnEnded` arm to use `failed()`), `TurnObserverConfig/Adapter` (drop parked plumbing), delete `api/conversation/ConversationStatus.java` when the compiler agrees. Update `nessy-agent`: `TurnNarrationAdapter` (emit `new TurnEvent.TurnEnded(null)` / `new TurnEvent.TurnEnded(reason)`), `AwaitingReply` (switch on `failed()`), their tests, `CliAgentTest` if touched. Commit `"refactor: TurnEnded says only what ended it — and parking was never narrated anyway"`.

### Task 4: The tool seam speaks the primitive
**Files:**
- Root `pom.xml`: move `<module>nessy-durable</module>` ABOVE `nessy-core`; `nessy-core/pom.xml`: add the `nessy-durable` dependency.
- `nessy-core/.../api/Awaited.java`: `Ready<T>(T value) | Deferred<T>()` (decision 2 — marker, javadoc explaining who derives identity and why); DELETE `api/ParkToken.java`.
- `nessy-core/.../api/tool/ToolContext.java` → `(ToolCall call, EventEmitter events)` with `progress(String)` emitting `ToolProgress(call.id(), message)`; `api/event/ToolProgress.java` → `(String toolCallId, String message)`.
- `nessy-core/.../api/tool/authorization/AuthzContext.java`: delete `conversationId()` and `state()` (and any now-dead imports); survivors untouched.
- DELETE `api/conversation/ConversationId.java` once unreferenced (the compiler confirms).
- `nessy-agent` updates: `spi/ParkedCallPolicy` → `ToolExecution onDeferred(ToolCall call)` (rename honest to the new arm; no token parameter); `RegistryToolCallExecutor` (drop `bridgedId`/`ConversationId`, match `Awaited.Deferred` → `parkedCallPolicy.onDeferred(call)`, `ToolContext` two-arg construction, default policy message unchanged); `DurableParkedCallPolicy.onDeferred`; all touched tests (`RegistryToolCallExecutorTest`'s `ParkingTool` returns `new Awaited.Deferred<>()`; `DurableParkedCallPolicyTest`; `DurableParkDemo`'s `RiskyTool`; `ScopeResumptionTest` unaffected).
Commit `"refactor: Awaited goes two-armed and anonymous — ParkToken and ConversationId leave the language"`.

### Task 5: Pointers and banners
**Files:** repo `CLAUDE.md` — design-of-record paragraph now names `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md` + the durable-computation companion (commit with `[skip ci]`? NO — this commit also isn't CLAUDE.md-only if batched; keep it CLAUDE.md-only and include `[skip ci]` per the global rule). Separate commit: prepend a superseded banner to `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md` (`> **Superseded** by 2026-08-18-agent-as-scope-design.md; §9 of that spec was executed by the distillation plan on 2026-08-20; retained as history`). Update agent-as-scope §9 table with a one-line "executed 2026-08-20" note and flip its **Status** line from "proposed" to "Design of record". Commits: `"docs: the pointer flips [skip ci]"` and `"docs: the old design of record retires with honors"`.

### Task 6: The enforcer
**Files:** new test `nessy-core/src/test/java/org/jwcarman/nessy/LayeringTest.java` — walks `src/main/java` under the module (locate via `Path.of("src/main/java")`), asserts no `.java` file contains `org.jwcarman.nessy.agent` (q8's "checked mechanically"). Companion in `nessy-durable`: no file contains `org.jwcarman.nessy.` EXCEPT `org.jwcarman.nessy.durable` (the base imports nothing above it). Both prose-named (`coreNeverSeesTheMachine`, `theBaseImportsNothingAbove`). Commit `"test: the layering is law — core never sees the machine, the base sees nothing"`.

### Task 7: Full-reactor proof
Run `./mvnw -q clean verify` and `./mvnw -pl nessy-agent test -Dtest=DurableParkDemo,WiringDemo -Dsurefire.failIfNoSpecifiedTests=false` (the demos still narrate). `grep -rn ParkToken . --include='*.java'` and `grep -rn ConversationId . --include='*.java'` must both return NOTHING. Report final module list and test counts. No commit unless stragglers surfaced.

## What Plan 6 ("the doors") picks up
`web()`/`autonomous()` builders; the `AuthzContext` gate + enricher story (§4.2) on the now-clean vocabulary; backlog backpressure (open question 0); desk expiry + deadline sweep; the transactional outbox; `CompletionPolicy` tool filtering; outlet guarding; completion-capability secrets (durable spec §9); batch model executor; the JDBC backends (durable slots + state store — remembering the `Outcome.Success(Object)` serialization seam changes the SPI); Spring/console rebirth planning.
