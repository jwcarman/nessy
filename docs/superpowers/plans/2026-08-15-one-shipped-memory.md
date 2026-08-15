# One Shipped Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire `TranscriptMemory`, `SummarizingMemory`, and `Memory.windowed` so `PipelineMemory` is the only shipped `Memory` implementation.

**Architecture:** Pure consolidation — no logic moves, only facades die. The hydrators already carry all behavior; every caller re-expresses itself through `Memory.pipeline(...)`.

**Tech Stack:** Java 21+, Maven multi-module, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-15-context-pipeline-and-plan-design.md` §10 (the amendment is the complete authority for this plan).

## Global Constraints

- No suppressions of any kind; no star imports; no mocking libraries; prose snake_case tests; S5778/S5841; no `System.out`.
- Behavior must be provably unchanged: no hydrator or pipeline main-code edits EXCEPT javadoc migration and `{@link}` retargeting. If a change to `SummarizingHydrator`/`PipelineMemory`/`ContextHydrator` logic seems needed, STOP — the plan is wrong.
- Test assertions that pin behavior may move files; they may not weaken. Fold, don't delete: any assertion in the three retiring suites not already equivalently present in `ContextHydratorTest`/`PipelineMemoryTest` moves there.
- Full offline `./mvnw -q clean verify` and reactor `./mvnw -q javadoc:javadoc` green before the commit.
- Before commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`. Trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never push.

---

### Task 1: The consolidation

**Files:**
- Delete: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/TranscriptMemory.java`, `SummarizingMemory.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/Memory.java` (remove `windowed`; javadoc points at `pipeline`), `SummarizingHydrator.java` (receives SummarizingMemory's class javadoc — watermark story, no-fencing, usage-jurisdiction note — adapted to hydrator voice), `ContextHydrator.java`/`TranscriptTrim.java`/`SummaryStore.java`/`PipelineMemory.java`/`InMemoryConversationStore.java`/`JdbcTranscript.java` (retarget `{@link}`/prose mentions of the deleted types), `AgentBuilder.java` (default → `Memory.pipeline(Transcript.inMemory()).build()`; WARN text updates its class name mention if it has one), `JdbcPersistence.java` (`memory()` → pipeline; javadoc), `JdbcPersistenceAutoConfiguration.java` (javadoc mentions), `WatchmanConfig.java` (`Memory.windowed(...)` → `Memory.pipeline(transcript).keepRecent(n)...` — read the current wiring first; it may compose over JdbcPersistence.memory(); re-express faithfully with the SAME window value and SAME transcript), `DemoAgent.java` (javadoc mention if any), `ChatWebSmokeTest.java`/`AgentFacadeTest.java`/`AgentBuilderTest.java`/`ConversationLoopTest.java`/`ProviderModelCallExecutorTest.java` (construction sites → pipeline equivalents, assertions untouched)
- Delete-after-folding: `TranscriptMemoryTest.java`, `SummarizingMemoryTest.java`, `MemoryWindowedTest.java` — first diff each against `ContextHydratorTest`/`PipelineMemoryTest` coverage; move any un-covered assertion (expected: SummarizingMemory's fold-behavior cases largely already re-covered via hydrator tests — verify case by case, list the disposition of EVERY test method in the report)
- Docs: root `README.md` (memory story → one implementation), `CHANGELOG.md` (Unreleased: Removed entries for the three names + Changed for the default), `nessy-examples/night-watchman/README.md` (window wording)

**Interfaces produced:** none new — `spi.memory` afterwards contains exactly: `Memory`, `PipelineMemory`, `ContextHydrator`, `ContextTransformer`, `OptionalTransformer` (pkg-private), `SummarizingHydrator` (pkg-private), `SummaryStore`, `InMemorySummaryStore` (pkg-private).

- [ ] **Step 1:** Grep-audit every reference (`TranscriptMemory|SummarizingMemory|Memory\.windowed|windowed\(`) and write the disposition list into your report BEFORE editing.
- [ ] **Step 2:** Migrate the javadoc (SummarizingMemory → SummarizingHydrator), then delete the two classes and `Memory.windowed`, then fix every caller per the file list.
- [ ] **Step 3:** Fold the three test suites; delete them only after their coverage disposition is written down.
- [ ] **Step 4:** `./mvnw -q clean verify` (offline, full reactor) green; `./mvnw -q javadoc:javadoc` green; grep proves zero remaining references outside `docs/superpowers/` and `CHANGELOG.md` history entries.
- [ ] **Step 5:** Formatters; single commit `refactor!: one shipped Memory — the facades retire, the pipeline remains`.

## Self-review notes
- Spec §10 covers every bullet above 1:1; no placeholder steps; single task because the change is one atomic consolidation no reviewer could partially accept.
