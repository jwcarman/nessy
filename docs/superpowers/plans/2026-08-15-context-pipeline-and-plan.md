# Context Pipeline & Plan Facility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the context pipeline (ContextHydrator + ContextTransformer + PipelineMemory) and the plan facility (PlanStore + update_plan tool + plan transformer), with JDBC durability, TCK coverage, the module renames, and a chat-cli demo.

**Architecture:** Two open seams in `spi.memory` — `ContextHydrator` (bootstrap from durable history) and `ContextTransformer` (reshape before send) — composed by `PipelineMemory`. The plan facility is the first stage consumer: a wholesale-replace `update_plan` tool writes `PlanStore`, a transformer renders the checklist into the tail of every recall. Renames land first so all new code is born in final coordinates.

**Tech Stack:** Java 21+, Maven multi-module, JUnit 5 + AssertJ, Testcontainers (vendor-tagged), SLF4J/Logback.

**Spec:** `docs/superpowers/specs/2026-08-15-context-pipeline-and-plan-design.md` (the binding authority; its §-references are used throughout).

## Global Constraints

- No `@SuppressWarnings`, no `// NOSONAR`, no suppression of any kind. No star imports.
- No mocking libraries — hand-rolled doubles only. Prose snake_case test names.
- Exception-assertion lambdas contain exactly ONE throwing invocation (S5778); assert emptiness before all/none-match predicates (S5841); Awaitility over sleep.
- All SQL is complete constant strings — no fragment splicing, no dynamic IN-lists (S2077/S2695 lesson). Batch executes check `int[]` results for `Statement.EXECUTE_FAILED`.
- No `System.out`/`System.err` (S106) — SLF4J only (ConsoleIo's existing adapter is the sole grandfathered exception; do not add more).
- Full offline verification must pass at every task boundary: `./mvnw -q clean verify` with no API key, no Docker, no network.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never stage IDE metadata.
- Javadoc must survive the release profile: fully-qualify `{@link}` targets not imported in the file; every public member of a public type gets a comment (local reproducer: `./mvnw -q javadoc:javadoc`).
- Do NOT push to any remote. Merge target is local `main` only, performed by the controller at the end.

---

### Task 1: Module renames — nessy-jdbc and nessy-tck

**Files:**
- Rename dir: `nessy-store-jdbc/` → `nessy-jdbc/`; `nessy-store-tck/` → `nessy-tck/`
- Rename packages: `org.jwcarman.nessy.store.jdbc` → `org.jwcarman.nessy.jdbc` (main, test, and the resources path `src/main/resources/org/jwcarman/nessy/store/jdbc/**` → `.../nessy/jdbc/**`); `org.jwcarman.nessy.store.tck` → `org.jwcarman.nessy.tck`
- Modify: root `pom.xml` (module list), both renamed modules' `pom.xml` (artifactId, name), `nessy-bom/pom.xml` (if it lists the artifacts), `nessy-autoconfigure` (pom dep + imports in `JdbcPersistenceAutoConfiguration`, `NessyProperties`, tests), `nessy-spring-boot-starter/pom.xml` (dep list), `.github/workflows/maven.yml` (any literal module references), READMEs and docs referencing the old names
- Interfaces produced: all later tasks import `org.jwcarman.nessy.jdbc.*` and `org.jwcarman.nessy.tck.*` and artifacts `nessy-jdbc`/`nessy-tck`

**Rationale (spec §5):** the JDBC module implements five storage SPIs — technology-first naming matches reality; the TCK certifies all contracts, not "the store". Breaking pre-1.0, done now because it gets costlier every release.

- [ ] **Step 1: `git mv` the two module directories**; edit their poms' `<artifactId>` (and `<name>`/`<description>` if present) to `nessy-jdbc` / `nessy-tck`. Update root `pom.xml` `<module>` entries.
- [ ] **Step 2: Move source roots** with `git mv` so `store/jdbc` → `jdbc` and `store/tck` → `tck` under each of main/test java trees, and the schema resources dir (`JdbcSchemaBootstrap` loads schema files relative to class resources — keep resource path in lockstep with the package).
- [ ] **Step 3: Rewrite package/import statements repo-wide.** `grep -rl "nessy\.store\.jdbc\|nessy\.store\.tck" --include="*.java" --include="*.xml" --include="*.md" --include="*.yml" .` (exclude `target/`, exclude `docs/superpowers/` history — specs/plans keep their historical names) and fix every hit. `nessy-tck/pom.xml` keeps its Sonar reclassification properties (`<sonar.sources></sonar.sources>`, `<sonar.tests>src/main/java,src/test/java</sonar.tests>`) verbatim.
- [ ] **Step 4: Verify**: `./mvnw -q clean verify` green; `grep -r "store\.jdbc\|store\.tck\|nessy-store-jdbc\|nessy-store-tck" --include="*.java" --include="*.xml" --include="*.yml" . | grep -v target | grep -v docs/superpowers` returns nothing.
- [ ] **Step 5: Commit** `refactor: nessy-store-jdbc and nessy-store-tck shed the store they outgrew`.

### Task 2: Transcript moves to spi.transcript; TranscriptTrim goes public

**Files:**
- Move (git mv, update `package`): `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/{Transcript,InMemoryTranscript,TranscriptTrim}.java` → `.../spi/transcript/`
- Modify every referencing file (grep-driven; known: core `AgentBuilder`, `TranscriptMemory`, `SummarizingMemory`; core tests `AgentBuilderTest`, `ConversationLoopTest`, `ProviderModelCallExecutorTest`; `nessy-jdbc` `JdbcTranscript`, `JdbcPersistence`, vendor TCK tests, `JdbcTranscriptTest`; `nessy-tck` `TranscriptContract`; `nessy-examples/night-watchman` `WatchmanConfig`)
- Move+repackage: `nessy-tck/src/test/java/org/jwcarman/nessy/spi/memory/InMemoryTranscriptTest.java` → `.../spi/transcript/InMemoryTranscriptTest.java`
- Interfaces produced: `org.jwcarman.nessy.spi.transcript.Transcript` (unchanged API), `public final class TranscriptTrim` with its trim method public

**Rationale (spec §5):** the transcript is a front-door subsystem some memories reference, not memory plumbing; `TranscriptTrim` is the transcript's border law and Task 3's open hydrator seam needs it reachable.

- [ ] **Step 1: git mv the three files**, change `package` to `org.jwcarman.nessy.spi.transcript`.
- [ ] **Step 2: Make `TranscriptTrim` public** — `public final class TranscriptTrim`, public static method(s), keep the private constructor; add one class-javadoc sentence: it is public because custom `ContextHydrator`s must discharge the open-tail border duty from outside the package (spec §2.1).
- [ ] **Step 3: Rewrite all imports** (`grep -rl "spi\.memory\.Transcript\|spi\.memory\.InMemoryTranscript\|spi\.memory\.TranscriptTrim" --include="*.java" .` minus target). `SummaryStore` and the `Memory` implementations DO NOT move.
- [ ] **Step 4: Verify** `./mvnw -q clean verify`; grep proves no `spi.memory.Transcript` references remain.
- [ ] **Step 5: Commit** `refactor: the transcript moves out of memory's house into spi.transcript`.

### Task 3: ContextHydrator — extraction, not invention

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/ContextHydrator.java`
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/SummarizingHydrator.java` (package-private)
- Modify: `TranscriptMemory.java`, `SummarizingMemory.java` (both delegate; public faces unchanged)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/memory/ContextHydratorTest.java`

**Interfaces:**
- Produces: `public interface ContextHydrator { Context hydrate(ConversationId id, Transcript transcript); static ContextHydrator full(); static ContextHydrator summarizing(SummaryStore, ModelProvider, String model, String prompt, int tailThreshold); }`

- [ ] **Step 1: Write the interface** (javadoc from spec §2.1 — transcript-as-parameter coherence argument, open-tail border duty naming `TranscriptTrim`):

```java
public interface ContextHydrator {

  Context hydrate(ConversationId id, Transcript transcript);

  /** The floor: the whole telling, open-tail-trimmed — TranscriptMemory's recall, extracted. */
  static ContextHydrator full() {
    return (id, transcript) ->
        Context.of(
            TranscriptTrim.withoutOpenTail(
                transcript.all(id).stream().map(Transcript.Entry::message).toList()));
  }

  /** SummarizingMemory's recall, extracted: summary head plus tail-since-watermark. */
  static ContextHydrator summarizing(
      SummaryStore summaries, ModelProvider provider, String model, String prompt, int tailThreshold) {
    return new SummarizingHydrator(summaries, provider, model, prompt, tailThreshold);
  }
}
```

- [ ] **Step 2: Extract `SummarizingHydrator`** — move `SummarizingMemory`'s fields (minus `transcript`), `NOTHING_FOLDED`, `SUMMARY_MAX_TOKENS`, and its `recall`/`fold`/`summarize`/`render` bodies into package-private `SummarizingHydrator implements ContextHydrator` (transcript arrives per-call). Constructor validation moves with it. `SummarizingMemory` keeps its public constructor and javadoc, holds `transcript` + a `SummarizingHydrator`, `remember` unchanged, `recall(id)` → `hydrator.hydrate(id, transcript)`. `TranscriptMemory.recall` → `ContextHydrator.full().hydrate(id, transcript)` (hold the `full()` instance in a field). The fold logic must exist exactly once — in the hydrator.
- [ ] **Step 3: Write `ContextHydratorTest`** (prose names): `full_hydration_returns_the_whole_telling`, `full_hydration_trims_the_open_tail` (append user + assistant-tool-use with no results via `Transcript.inMemory()`, assert trailing tool-use absent), `summarizing_hydration_is_the_summarizing_memorys_recall` (drive both `SummarizingMemory` and a `summarizing(...)` hydrator over the same in-memory transcript/summary store with the existing test's fake `ModelProvider` pattern from `SummarizingMemoryTest` — copy its double — and assert identical rendered contexts), `summarizing_hydration_validates_its_arguments` (null checks / negative threshold; one throwing call per lambda).
- [ ] **Step 4: Run** `./mvnw -q -pl nessy-core test` — existing `TranscriptMemoryTest`/`SummarizingMemoryTest` suites must stay green UNTOUCHED (they are the extraction's proof, spec §8).
- [ ] **Step 5: Commit** `feat: hydration earns its interface — two strategies extracted, zero logic moved twice`.

### Task 4: ContextTransformer, optional(), PipelineMemory, Memory.pipeline

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/ContextTransformer.java`, `OptionalTransformer.java` (package-private), `PipelineMemory.java`
- Modify: `Memory.java` (add `static PipelineMemory.Builder pipeline(Transcript transcript)`)
- Test: `ContextTransformerTest.java`, `PipelineMemoryTest.java`

**Interfaces:**
- Produces: `public interface ContextTransformer { Context transform(ConversationId id, Context context); static ContextTransformer optional(ContextTransformer delegate); }`
- Produces: `public final class PipelineMemory implements Memory` with nested `public static final class Builder` — verbs `hydrator(ContextHydrator)`, `summarizing(SummaryStore, ModelProvider, String, String, int)`, `keepRecent(int)`, `transform(ContextTransformer)`, `build()`.

- [ ] **Step 1: `ContextTransformer`** — javadoc from spec §2.2 (legality by type; worst case is a throw; §2.3 fail-closed). `optional(delegate)` returns `new OptionalTransformer(delegate)`; `OptionalTransformer` is a package-private final class with an SLF4J logger, catching `RuntimeException` only: `LOGGER.warn("optional context stage {} failed for {}; continuing without it", delegate, id, e)` then returns the input context.
- [ ] **Step 2: `PipelineMemory`** (javadoc: spec §2 intro + §2.4 degenerate-floor + retention ruling):

```java
public final class PipelineMemory implements Memory {

  private final Transcript transcript;
  private final ContextHydrator hydrator;
  private final List<ContextTransformer> stages;

  private PipelineMemory(Transcript transcript, ContextHydrator hydrator, List<ContextTransformer> stages) {
    this.transcript = transcript;
    this.hydrator = hydrator;
    this.stages = List.copyOf(stages);
  }

  @Override
  public void remember(ConversationId id, Message message) {
    transcript.append(id, message); // idempotency is the transcript's own no-stutter rule
  }

  @Override
  public Context recall(ConversationId id) {
    Context context = hydrator.hydrate(id, transcript);
    for (ContextTransformer stage : stages) {
      context = stage.transform(id, context);
    }
    return context;
  }

  public static final class Builder {
    private final Transcript transcript;
    private ContextHydrator hydrator;
    private final List<ContextTransformer> stages = new ArrayList<>();

    Builder(Transcript transcript) {
      this.transcript = Objects.requireNonNull(transcript, "transcript must not be null");
    }

    public Builder hydrator(ContextHydrator hydrator) {
      Objects.requireNonNull(hydrator, "hydrator must not be null");
      if (this.hydrator != null) {
        throw new IllegalStateException("one hydration strategy per pipeline");
      }
      this.hydrator = hydrator;
      return this;
    }

    public Builder summarizing(SummaryStore summaries, ModelProvider provider, String model, String prompt, int tailThreshold) {
      return hydrator(ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold));
    }

    public Builder keepRecent(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("window must be at least 1");
      }
      stages.add((id, context) -> context.keepRecent(n));
      return this;
    }

    public Builder transform(ContextTransformer stage) {
      stages.add(Objects.requireNonNull(stage, "stage must not be null"));
      return this;
    }

    public PipelineMemory build() {
      return new PipelineMemory(transcript, hydrator != null ? hydrator : ContextHydrator.full(), stages);
    }
  }
}
```

- [ ] **Step 3: `Memory.pipeline`** — `static PipelineMemory.Builder pipeline(Transcript transcript) { return new PipelineMemory.Builder(transcript); }` with the degenerate-floor javadoc (spec §2.4).
- [ ] **Step 4: Tests.** `ContextTransformerTest`: `optional_swallows_the_failure_and_returns_the_input_context` + `optional_logs_exactly_one_warning` (Logback ListAppender on `OptionalTransformer`'s category, filter to WARN — copy the `warnings()` fixture pattern from `HarnessBuilderTest`), `optional_passes_success_through_untouched`, `optional_rejects_null_delegate`. `PipelineMemoryTest`: `the_degenerate_pipeline_is_transcript_memory_in_pipeline_clothing` (same appends into two, assert equal recall), `remember_appends_to_the_transcript`, `stages_run_in_registration_order_each_seeing_its_predecessors_output` (two enrich-appending lambdas + a `Context.map` mutator between them), `a_nothing_to_add_stage_leaves_the_context_untouched`, `an_appended_amendment_survives_a_clamp_registered_before_it`, `a_throwing_stage_fails_the_recall`, `a_custom_hydrator_receives_the_pipelines_own_transcript` (hand-rolled hydrator capturing the `Transcript` reference; assert same instance), `one_hydration_strategy_per_pipeline`, `keep_recent_rejects_a_window_below_one`. One throwing invocation per assertion lambda throughout.
- [ ] **Step 5: Run, commit** `feat: PipelineMemory — hydrate, then stages, fail-closed at every seam`.

### Task 5: The plan records and PlanStore (spi.plan)

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/plan/{Plan,PlanStore,InMemoryPlanStore,package-info}.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/plan/{PlanTest,InMemoryPlanStoreTest}.java`

**Interfaces:** exactly spec §3.1/§3.2: `Plan(List<Task> tasks)` (defensive copy; blank-title IAE; nested `Task(String title, Status status)` with requireNonNulls; nested `enum Status { PENDING, IN_PROGRESS, DONE }`; `static Plan empty()`; `boolean isEmpty()`), `PlanStore { Optional<Plan> find(ConversationId); void save(ConversationId, Plan); static PlanStore inMemory(); }` — javadoc carries the LWW/no-fencing justification (single-writer loop + idempotent replay). `InMemoryPlanStore`: package-private, `ConcurrentHashMap`, null-checked save.

- [ ] **Step 1: Write `PlanTest`** first: blank title rejected, null title/status rejected, tasks list defensively copied (mutate source after construction), `empty().isEmpty()`, non-empty `isEmpty()` false.
- [ ] **Step 2: Write `InMemoryPlanStoreTest`**: missing id → `Optional.empty()`, save/find round-trip, second save replaces wholesale (fewer tasks; departed task gone), `save(id, Plan.empty())` then find returns the empty plan, null args rejected.
- [ ] **Step 3: Implement all three types**; run `./mvnw -q -pl nessy-core test`.
- [ ] **Step 4: Commit** `feat: the plan facility's nouns — Plan, PlanStore, and the in-memory default`.

### Task 6: PlanTools — the tool and the transformer

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/plan/PlanTools.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/plan/PlanToolsTest.java`

**Interfaces (spec §3.3/§3.4):**
- `public final class PlanTools` (private ctor), nested wire records `public record UpdatePlan(List<PlannedTask> tasks)` (compact ctor normalizes null → `List.of()`) and `public record PlannedTask(String title, Plan.Status status)`.
- `public static Tool<UpdatePlan> updatePlan(PlanStore store)` — name `update_plan`; description (write for the model): "Maintain your task list for multi-step work. Send the COMPLETE list every time — this replaces the whole plan. Keep at most one task IN_PROGRESS; mark tasks DONE as you finish them. An empty list clears the plan."; `inputType()` `UpdatePlan.class`; `describe(input)` renders the checklist; `execute` maps `PlannedTask`s to `Plan.Task`s inside a try/catch of `IllegalArgumentException` → `Awaited.ready(ToolResult.error(e.getMessage()))`, else `store.save(context.conversationId(), plan)` and `Awaited.ready(ToolResult.ok(confirmation))` where confirmation is e.g. `Plan updated: 4 tasks (1 in progress, 1 done).` (counts computed; never parks).
- `public static ContextTransformer transformer(PlanStore store)` — absent/empty plan returns the context unchanged; otherwise `context.enrich(new TextBlock(render(plan)))`. Render format, byte-exact contract:

```
<current-plan>
- [ ] <PENDING title>
- [>] <IN_PROGRESS title>
- [x] <DONE title>
</current-plan>
This is your task list, maintained by you through the update_plan tool. It is ambient state, not a message from the user.
```

- [ ] **Step 1: Write `PlanToolsTest`** first. Tool: `update_plan_replaces_the_whole_plan_wholesale` (save 3 tasks, then 2 — store holds exactly 2), `replaying_the_same_update_stores_the_identical_plan` (execute twice, same input, assert store equals single-execution state), `an_empty_task_list_clears_the_plan`, `a_null_task_list_is_treated_as_empty`, `a_blank_title_returns_a_tool_error_not_a_throw` (assert `isError()` and that store is untouched), `the_confirmation_counts_the_statuses`. Transformer: `an_absent_plan_leaves_the_context_untouched` (same instance), `an_empty_plan_leaves_the_context_untouched`, `a_plan_renders_as_the_checklist_at_the_tail` (assert last message is user-role, text equals the exact block above), `all_three_markers_render`. Build `ToolContext` the way existing tool tests do (grep an existing `Tool` test for the fixture; hand-rolled `EventEmitter` double).
- [ ] **Step 2: Implement `PlanTools`**; run module tests.
- [ ] **Step 3: Commit** `feat: update_plan and its transformer — wholesale writes, checklist recalls`.

### Task 7: JdbcPlanStore + schemas + JdbcPersistence + autoconfigure bean

**Files:**
- Create: `nessy-jdbc/src/main/java/org/jwcarman/nessy/jdbc/JdbcPlanStore.java`
- Create: `nessy-jdbc/src/main/resources/org/jwcarman/nessy/jdbc/{postgres,mysql,mariadb,sqlserver,oracle}/plan-schema.sql`
- Modify: `JdbcPersistence.java` (fifth component `JdbcPlanStore planStore`, wired in both `create` overloads; requireNonNull in compact ctor), `JdbcPersistenceRecordTest`, `nessy-autoconfigure/.../JdbcPersistenceAutoConfiguration.java` (a `planStore` bean mirroring the `summaries` bean exactly — same conditional annotations) + its test
- Test: `nessy-jdbc/src/test/java/org/jwcarman/nessy/jdbc/JdbcPlanStoreTest.java`

**Interfaces:** `JdbcPlanStore implements PlanStore`, constructor/`create` shapes mirroring `JdbcSummaryStore` exactly (DataSource ctor, DataSource+dialect ctor, `create(DataSource)`, `create(DataSource, JdbcDialect)` bootstrapping `"plan-schema.sql"` via `JdbcSchemaBootstrap` with table hint `"plan"`).

**SQL — three constants, identical across dialects (no upsert, so no per-dialect variants needed; state this in the class javadoc):**

```java
private static final String DELETE_SQL = "DELETE FROM nessy_plan WHERE conversation_id = ?";
private static final String INSERT_SQL =
    "INSERT INTO nessy_plan (conversation_id, ordinal, title, status) VALUES (?, ?, ?, ?)";
private static final String FIND_SQL =
    "SELECT title, status FROM nessy_plan WHERE conversation_id = ? ORDER BY ordinal";
```

`save`: one connection, `setAutoCommit(false)`, DELETE then batched INSERT in ordinal order (0-based), `executeBatch()` checked for `Statement.EXECUTE_FAILED` (throw `SQLException` naming the index), commit; rollback + restore autocommit in finally; wrap `SQLException` the way `JdbcSummaryStore.save` wraps. Javadoc records why no fencing/retry: the writer is the loop's tool execution, single-turn-at-a-time per conversation, and a replay rewrites identically (spec §3.2, §4). `find`: zero rows → `Optional.empty()`; status via `Plan.Status.valueOf`.

**Schemas** — one file per dialect; copy the header comment style, `conversation_id` column type, and IF-NOT-EXISTS idiom from that dialect's `summary-schema.sql` byte-for-byte in spirit. Table:

```sql
CREATE TABLE IF NOT EXISTS nessy_plan (
  conversation_id  <same type as nessy_summary's>  NOT NULL,
  ordinal          INT           NOT NULL,
  title            VARCHAR(1024) NOT NULL,   -- VARCHAR2(1024) oracle, NVARCHAR(1024) sqlserver
  status           VARCHAR(16)   NOT NULL,
  PRIMARY KEY (conversation_id, ordinal)
);
```

(SQL Server uses the `IF NOT EXISTS (SELECT * FROM sys.tables ...)` guard like its siblings; Oracle follows whatever idiom its `summary-schema.sql` uses.)

- [ ] **Step 1: Write `JdbcPlanStoreTest`** mirroring `JdbcSummaryStoreTest`'s harness (same embedded/offline database rig, whatever it uses — read that test first and copy its fixture verbatim): round-trip, wholesale replacement removes departed rows, ordering preserved across save/find, empty save clears, missing conversation empty, create-bootstraps-schema.
- [ ] **Step 2: Implement store + five schema files.**
- [ ] **Step 3: `JdbcPersistence` gains `planStore`**; update `JdbcPersistenceRecordTest` expectations; autoconfigure bean + test additions mirror the summaries bean's test cases.
- [ ] **Step 4: `./mvnw -q clean verify`** (offline: container/vendor tags excluded by default).
- [ ] **Step 5: Commit** `feat: nessy_plan — one row per task, wholesale replacement, five dialects`.

### Task 8: PlanStoreContract in the TCK + vendor wiring

**Files:**
- Create: `nessy-tck/src/main/java/org/jwcarman/nessy/tck/PlanStoreContract.java` (mirror `SummaryStoreContract`'s abstract shape: abstract `PlanStore store()` + optional per-test reset hook, matching whatever its siblings do)
- Create: `nessy-tck/src/test/java/org/jwcarman/nessy/spi/plan/InMemoryPlanStoreCertificationTest.java` — the in-memory reference certification extending the contract (the kit certifies its own default, same as its siblings; note `nessy-core`'s own `InMemoryPlanStoreTest` from Task 5 stays — it is the unit test, this is the contract certification)
- Modify: every vendor test that nests `SummaryStoreContract` (grep `SummaryStoreContract` under `nessy-jdbc/src/test` — the four/five vendor `*StoreTckTest` classes and any postgres equivalent) — add a `@Nested` plan contract class wired to `JdbcPlanStore.create(dataSource)`, with the same table-truncate-between-tests discipline its siblings use (`DELETE FROM nessy_plan`)

**Contract cases (spec §4):** save/find round-trip; wholesale replacement removes departed tasks; ordering preserved (5 tasks, distinct titles, assert exact sequence); empty save clears; missing conversation → `Optional.empty()`; last write wins (two saves, second visible).

- [ ] **Step 1: Write the contract** (read `SummaryStoreContract` first; copy its structure, prose naming, and javadoc voice).
- [ ] **Step 2: In-memory certification** in the TCK test tree.
- [ ] **Step 3: Wire the vendor suites** — every class nesting `SummaryStoreContract` gains the plan nest; keep `@Tag("container")`/`@Tag("vendor")` class-level tags untouched.
- [ ] **Step 4: Offline verify** green (vendor suites compile but don't run). If Docker happens to be available, do NOT run the vendor matrix — that is the controller's decision, not this task's.
- [ ] **Step 5: Commit** `feat: PlanStoreContract joins the kit — six promises, certified in memory and wired to five vendors`.

### Task 9: chat-cli demo + docs

**Files:**
- Modify: `nessy-examples/chat-cli/src/main/java/org/jwcarman/nessy/examples/DemoAgent.java`, `Chat.java` (banner mention), chat-cli `README.md` if present, root `README.md` (How-it-works: one short paragraph on the context pipeline + plan facility)

**Changes (spec §7):** `DemoAgent.agentFor` creates `PlanStore planStore = PlanStore.inMemory();` and `Transcript transcript = Transcript.inMemory();`, adds `.memory(Memory.pipeline(transcript).transform(PlanTools.transformer(planStore)).build())`, adds grant `ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow())` (note: `updatePlan` returns `Tool<UpdatePlan>` — grant it like the others), extends `SYSTEM_PROMPT` with: `" For multi-step requests, maintain a task list with update_plan."`. Note the memory replaces the builder's default in-memory TranscriptMemory with the pipeline over an explicitly held transcript — same durability class, now with the plan riding recall. Javadoc on `agentFor` gains a sentence naming the pattern (tool-writable, recall-injected context, spec §1). Keep the existing fully-qualified-`{@link}` discipline (release-profile javadoc lesson).

- [ ] **Step 1: Wire the demo**; `./mvnw -q -pl nessy-examples/chat-cli -am verify` (offline).
- [ ] **Step 2: `./mvnw -q javadoc:javadoc`** at the reactor root — the release-profile reproducer must pass.
- [ ] **Step 3: Docs** — root README paragraph + chat-cli README section showing the three-line wiring and a sample `<current-plan>` block.
- [ ] **Step 4: Commit** `feat: chat-cli learns to plan — the pipeline's first public demonstration`.

---

## Self-review notes

- Type names cross-checked: `ContextHydrator.full()/summarizing(...)` (T3) are what `PipelineMemory.Builder` consumes (T4); `PlanTools.transformer` (T6) is what T9 wires; `JdbcPlanStore.create(DataSource)` (T7) is what T8's vendor nests call.
- Spec coverage: §2.1→T3, §2.2/2.3/2.4→T4, §3→T5+T6, §4→T7+T8, §5→T1+T2, §6→T7, §7→T9, §8 distributed per task.
- Deliberately not tasks: no README changelog exists (checked—skip); the vendor matrix run is a controller decision post-merge; pushing is forbidden.
