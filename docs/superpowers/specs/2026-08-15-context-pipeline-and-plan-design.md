# The Context Pipeline and the Plan Facility

**Date:** 2026-08-15
**Status:** DRAFT — awaiting owner review
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`; where this
document is silent, the design of record speaks.

## 1. The idea and the pattern behind it

Give the model a plan. Not a plan we write for it — a task list the model itself creates and
maintains through a tool, that then appears in its context on every model call, unconditionally,
for as long as it has tasks. Every serious harness converged on this shape independently (Claude
Code's TodoWrite, Codex's plan tool) because it fixes the same failure mode: long-horizon drift,
where an agent forgets step 4 while grinding on step 2.

The plan is the first instance of a pattern we expect to recur: **tool-writable, recall-injected
context**. The model writes through a tool; the artifact persists durably; the memory subsystem
re-presents it at recall — unconditionally for a plan, relevance-gated for a future "remember this
for me" facility. This generation ships the plan concretely and cuts the seam the whole family
will use, without abstracting past the one consumer we actually have (ruling: plan-first,
seam-shaped).

Two placement rulings, made in conversation and binding here:

- **Planning is a core concept, not a satellite.** It is cognitive infrastructure in the same
  family as `Memory.windowed` and `SummarizingMemory` — and `SummarizingMemory` already set the
  precedent that core ships opinionated cognitive machinery, prompt text and all. Module
  boundaries in nessy isolate *dependencies* (`nessy-tool-mcp` exists because of the MCP SDK);
  the plan facility has none. Pluggability is preserved by the grant principle: an agent that
  never grants the tool and never adds the transformer pays zero.
- **Naming convention for persistence contracts:** new pure-persistence SPIs take the `Store`
  suffix (`ConversationStore`, `SummaryStore`, now `PlanStore`). `Transcript` and `Parks` keep
  their names: they are jurisdictions with semantics, not dumb persistence, and `Repository`
  buys nothing `Store` doesn't while carrying DDD baggage these narrow contracts don't honor.

## 2. The context pipeline — hydrate, then stages

Recall-side context production becomes a named pipeline with exactly two seams: a
**`ContextHydrator`** produces the initial context from durable history, and an ordered list
of **`ContextTransformer` stages** reshapes it — clamping, redacting, eliding, amending —
before it goes out the door. Both seams are open; both shipped implementations are
extractions of code that already exists. This section names the parts and gives the assembly
one composition surface.

### 2.1 Hydrate — the strategy owns how much history it reads

A hydration strategy produces the *initial* context. It references the `Transcript` (and any
companion stores it owns) — it does not necessarily read all of it. The seam, new in
`spi.memory`:

```java
/**
 * Bootstraps the context from durable history. The transcript arrives as a parameter — the
 * pipeline passes the same transcript it remembers into, so told-history and re-read-history
 * can never disagree — and a hydrator reads as much or as little of it as its strategy
 * requires, consulting whatever companion stores it holds. Duty at the border: apply the
 * open-tail trim ({@link TranscriptTrim#withoutOpenTail}) before {@code Context.of} — a
 * parked conversation's raw telling can legitimately end in an unanswered tool-use message,
 * and {@code Context}'s validating constructor rejects that shape.
 */
public interface ContextHydrator {
  Context hydrate(ConversationId id, Transcript transcript);
}
```

Two implementations ship, both extracted from code that already exists (the existing `Memory`
classes keep their public faces and delegate to the extracted hydrators, so the logic lives
exactly once):

- **`ContextHydrator.full()`** (the guts of `TranscriptMemory`): `transcript.all(id)`,
  open-tail-trimmed.
- **`ContextHydrator.summarizing(summaries, provider, model, prompt, tailThreshold)`** (the
  guts of `SummarizingMemory`): the folded prefix from `SummaryStore` rendered as one opening
  `Message.user(text)`, plus only `transcript.tail(id, watermark)` — the whole
  fold-on-threshold, watermark-bookkeeping mechanism is unchanged by this design.

The seam is **open**: a custom hydrator (bootstrap from a vector store, a checkpoint, an
external system of record) is a legitimate implementation, which is why `TranscriptTrim`
becomes public in `spi.memory` — the border duty must be dischargeable from outside the
package.

### 2.2 Stages — open, ordered, full mutation

Everything after hydration is a stage. A stage may mutate the context in any way — clamp it,
redact a credit-card number out of a message body, elide a stale tool exchange whole, or append
new messages. The general seam, new in `org.jwcarman.nessy.spi.memory`:

```java
/**
 * One stage of the context pipeline: takes the context as built so far, returns the context
 * as it should continue. May trim, redact, elide, reorder-within-law, or append.
 *
 * <p>Legality is enforced by the type, not by trust: a {@link Context} can only be built
 * through {@code Context.of}, which rejects illegal shapes (a split tool exchange, an
 * unanswered tool-use mid-history). A transformer therefore cannot hand the model a corrupted
 * dialogue — the worst it can do is throw, and §2.3 says what a throw means.
 * Eliding a tool exchange means removing the pair atomically; the border check makes a
 * half-elision fail loud.
 *
 * <p>Stage output is synthesized at recall and never remembered: not told to the transcript,
 * not folded into any summary. One fresh pass per model call, no accumulation, no drift.
 */
public interface ContextTransformer {
  Context transform(ConversationId id, Context context);
}
```

`ContextTransformer` is the **only** stage concept — one interface, one builder verb. Stages
run in registration order, each seeing its predecessors' output. No helper factories are
needed, because **`Context` already carries the verb vocabulary** a stage body wants:

- `enrich(ContentBlock...)` / `enrich(List<ContentBlock>)` — appends exactly one user-role
  message, the documented carrier for non-human content. This is how appending stages amend:
  `(id, ctx) -> ctx.enrich(new TextBlock(rendered))`. Note `enrich` rejects an empty block
  list on purpose, so a nothing-to-add stage returns `ctx` unchanged rather than calling it.
- `map(UnaryOperator<Message>)` — rewrite every message; the redaction verb.
- `drop(Predicate<Message>)` — pair-atomic removal; matching either half of a tool exchange
  removes the whole exchange.
- `elideToolResults(n)` — blank old tool-result bodies, keep the recent window verbatim.
- `keepRecent(n)` / `limitTokens(budget, estimator)` — the pair-safe clamps.

The builder's `keepRecent(n)` verb simply registers `ctx -> ctx.keepRecent(n)` as a stage
(clamping after a summarizing hydration is legitimate belt-and-suspenders: the summary
absorbs old history, the window guarantees a ceiling).

Recommended order — clamp, then mutating stages, then appending ones — keeps amendments
unclippable and lets a relevance-judging appender see the same fitted context the model will
see; but order is the caller's, on purpose.

Why injection-as-user-message is legal and sufficient: the only rigid wire constraint is the
tool pair. Strict role alternation is not required — OpenAI accepts any sensible ordering and
Anthropic merges consecutive same-role messages into one turn (with `tool_result` blocks
required before text within a turn, which tail-append satisfies). `SummarizingMemory` has
injected a fabricated `Message.user(...)` since it shipped; this seam generalizes that move.

### 2.3 Stage failure — fail-closed by construction, optionality as a wrapper

To the pipeline, **every stage is required**. A stage that throws propagates out of `recall`:
the turn fails, the durable machinery retries it later, and the model never sees a context the
stage did not bless. A redactor that failed to strip a credit-card number must stop the
context from being built at all — and with this design that is not a default to configure but
the only behavior the pipeline has.

Optionality is not the pipeline's concept; a stage optionalizes itself via a decorator:

```java
/** On any exception: one WARN line (stage toString + conversation id), return the input
 *  context unchanged — this call behaves as if the stage were absent. A partial output from
 *  a failed stage is never used. */
static ContextTransformer optional(ContextTransformer delegate) { ... }
```

Wrap what is nice to have and safe to lose — a flaky relevance lookup, a best-effort
annotation, the plan transformer if an application decides a down `PlanStore` should not
stall turns:

```java
.transform(ContextTransformer.optional(PlanTools.transformer(planStore)))
```

One concept fewer in the builder, and the safety property gets stronger: fail-closed is
unconditional at the seam, and every fail-open decision is visible in the composition as an
explicit `optional(...)` wrapper.

### 2.4 The builder

A static factory on `Memory`, beside `windowed`:

```java
Memory memory = Memory.pipeline(transcript)                              // full hydration
    .summarizing(summaries, provider, model, prompt, tailThreshold)      // …or fold instead
    .keepRecent(50)                                                      // pair-safe clamp
    .transform(redactor)                                                 // a throw fails the recall
    .transform(ContextTransformer.optional(annotator))                   // self-optionalized: a throw skips it
    .transform(PlanTools.transformer(planStore))                         // appending stage
    .build();
```

- The type behind it is **`PipelineMemory`** — a public final `Memory` implementation in
  `spi.memory`, sibling to `TranscriptMemory` and `SummarizingMemory`, whose `recall` runs
  hydration then the stage list. Its builder is nested (`PipelineMemory.Builder`);
  `Memory.pipeline(Transcript)` is the shortcut that returns it. The transcript is the one
  required ingredient: `remember` always appends to it (idempotency stays the transcript's own
  no-stutter rule), whatever hydration chooses to re-read.
- `.hydrator(ContextHydrator)` sets the hydration strategy; the default is
  `ContextHydrator.full()`. `.summarizing(SummaryStore, ModelProvider, String model, String
  prompt, int tailThreshold)` is sugar for `.hydrator(ContextHydrator.summarizing(...))`;
  parameters mirror `SummarizingMemory`'s constructor exactly. Setting a hydrator twice (by
  either verb) is an `IllegalStateException` — one hydration strategy per pipeline.
- `.keepRecent(int n)` — registers the pair-safe trim as a required stage at its call
  position; same `n >= 1` floor as `Memory.windowed`.
- `.transform(ContextTransformer)` — zero or more; the one and only stage verb.
- All stages — clamps included — occupy **one ordered list** and run in registration order.
  Every stage is required (§2.3); optional behavior arrives pre-wrapped via
  `ContextTransformer.optional`.
- `.build()` returns the `PipelineMemory`: `remember` appends to the transcript, `recall`
  runs `hydrator.hydrate(id, transcript)` then folds the stage list. No hydration behavior is
  reimplemented — the shipped hydrators are extractions of the existing classes' logic.

`Memory.windowed(delegate, n)` stays for the simple wrap-anything case. `TranscriptMemory` and
`SummarizingMemory` stay public. The pipeline becomes the documented front door for composing
transcript-backed memory.

## 3. The plan facility

All of it lives in a new core package `org.jwcarman.nessy.spi.plan`, mirroring how
`spi.memory` holds the summary machinery.

### 3.1 The records

```java
/** A conversation's current plan: the model's own task list, in the model's own order. */
public record Plan(List<Task> tasks) {

  public Plan {
    tasks = List.copyOf(tasks);
    if (tasks.stream().anyMatch(t -> t.title().isBlank())) {
      throw new IllegalArgumentException("task titles must not be blank");
    }
  }

  public enum Status { PENDING, IN_PROGRESS, DONE }

  public record Task(String title, Status status) {
    public Task {
      Objects.requireNonNull(title, "title must not be null");
      Objects.requireNonNull(status, "status must not be null");
    }
  }

  public static Plan empty() { return new Plan(List.of()); }

  public boolean isEmpty() { return tasks.isEmpty(); }
}
```

Deliberately minimal: title and status, nothing else. No ids (wholesale replacement makes them
unnecessary), no notes, no nesting, no timestamps — YAGNI until a consumer demands otherwise.

### 3.2 The store

```java
/**
 * One conversation's current plan. Last-write-wins, no fencing, same justification as
 * {@link SummaryStore}: the writer is the update_plan tool, executing inside the loop, which
 * runs one turn at a time per conversation — and an at-least-once replay rewrites the
 * identical plan, so a clobbered write is re-done work, never a lost word.
 */
public interface PlanStore {

  /** The current plan for {@code id}, or empty if the model has never written one. */
  Optional<Plan> find(ConversationId id);

  /** Replaces whatever plan {@code id} had, wholesale. */
  void save(ConversationId id, Plan plan);

  /** The zero-configuration default: plans live in this JVM and die with it. */
  static PlanStore inMemory() { return new InMemoryPlanStore(); }
}
```

`InMemoryPlanStore` is package-private, a `ConcurrentHashMap`, in the image of
`InMemorySummaryStore`.

### 3.3 The tool — wholesale replacement, idempotent by construction

`PlanTools` (final, private constructor, two static factories) provides the tool and the
transformer — the two halves of one invariant, kept in one reviewable place.

```java
public static Tool<UpdatePlan> updatePlan(PlanStore store) { ... }
```

- **Name:** `update_plan`.
- **Input:** `record UpdatePlan(List<PlannedTask> tasks)` with
  `record PlannedTask(String title, Plan.Status status)` — the wire twin of `Plan`, kept
  separate so the SPI record never grows schema annotations.
- **Semantics:** the model sends the **entire** task list every time it changes anything;
  `execute` maps it to a `Plan` and calls `store.save(context.conversationId(), plan)`. This is
  the TodoWrite shape, chosen over CRUD deliberately: durable re-drives execute at-least-once,
  and a replayed wholesale write stores the identical list — idempotent by construction, no
  task-id bookkeeping, no merge logic. An empty `tasks` list is legal and clears the plan.
- **Description (written for the model):** instructs it to use the tool for multi-step work,
  to send the full list on every update, to keep at most one task `IN_PROGRESS`, and to mark
  tasks `DONE` as they complete.
- **Result:** returns immediately (never parks) with a one-line confirmation the model reads
  in-band: `Plan updated: 4 tasks (1 in progress, 1 done).`
- **`describe(input)`** renders the checklist for approval prompts, though the expected grant
  is `allow()` — a self-bookkeeping tool earns no approval friction.
- Blank titles are rejected by `Plan`'s compact constructor; the tool surfaces that as a failed
  `ToolResult` (the standard tool-error path), so the model can correct itself.

### 3.4 The transformer

```java
public static ContextTransformer transformer(PlanStore store) { ... }
```

`transform` finds the plan; absent or empty returns the context unchanged — nothing is
injected, the "if applicable" rule. Otherwise it appends via `Context.enrich` — exactly one
user-role message carrying one `TextBlock`:

```
<current-plan>
- [ ] Fetch the order history
- [>] Summarize the disputes
- [x] Draft the refund email
</current-plan>
This is your task list, maintained by you through the update_plan tool. It is ambient state,
not a message from the user.
```

Markers: `[ ]` pending, `[>]` in progress, `[x]` done. The framing sentence is part of the
contract: models are post-trained to treat framed blocks inside user messages as environment,
not dialogue. Tail position (`enrich` appends at the tail; register the plan transformer
last) puts the plan at maximum recency — the same reason Claude Code injects todo reminders
last.

### 3.5 What the kernel does not learn

Nothing. No loop change, no `Harness` change, no `Agent` change. The tool is granted like any
tool; the transformer is composed like any memory decoration. The facility is opt-in at the
composition line, and its two halves meet only at `PlanStore`.

## 4. Durability — `JdbcPlanStore`

Lives beside `JdbcSummaryStore` in the JDBC backend module (renamed in §5). Storage is
**one row per task** — honest SQL, no JSON, no serialization dependency:

```sql
CREATE TABLE nessy_plan (
  conversation_id VARCHAR(64) NOT NULL,
  ordinal         INT         NOT NULL,
  title           VARCHAR(1024) NOT NULL,
  status          VARCHAR(16) NOT NULL,
  PRIMARY KEY (conversation_id, ordinal)
);
```

(Exact column types per dialect, one DDL fragment per dialect resource directory, all five:
postgres, mysql, mariadb, sqlserver, oracle — following the existing per-dialect schema layout
byte-for-byte in spirit.)

- `save` = one transaction: `DELETE FROM nessy_plan WHERE conversation_id = ?`, then a batched
  constant-SQL `INSERT` of the new rows in ordinal order. Wholesale replacement in the store
  mirrors wholesale replacement at the tool — and a replay redoes the same delete+insert,
  landing on the same rows. Batch results are checked for `EXECUTE_FAILED` (the
  `checkBatchResults` lesson).
- `find` = `SELECT ... ORDER BY ordinal`; zero rows means `Optional.empty()`.
- All SQL is complete per-dialect constants in `JdbcStatements` — no fragment splicing, no
  dynamic IN-lists (the S2077/S2695 lesson).
- `status` round-trips through `Plan.Status.name()`/`valueOf`.

**TCK:** `PlanStoreContract` joins the kit: save/find round-trip, wholesale replacement
actually removes departed tasks, ordering preserved, empty save clears, missing conversation
is `Optional.empty()`, last write wins. The in-memory certification lands in the TCK module's
test tree beside its siblings; the JDBC certification joins the five vendor `TckTests`
(`@Tag("container")` + `@Tag("vendor")`, exactly as today).

## 5. The renames

Two module renames, ratified in conversation, done in this generation because they get more
expensive with every release:

| From | To | Why |
|---|---|---|
| `nessy-store-jdbc` | `nessy-jdbc` | It implements five storage SPIs (ConversationStore, Transcript, Parks, SummaryStore, PlanStore); "store" names one tenant of five. Technology-first is what the module actually is: the JDBC backend for everything nessy persists. |
| `nessy-store-tck` | `nessy-tck` | Same defect, same fix: it certifies all the storage contracts, not "the store". |

Packages follow the modules: `org.jwcarman.nessy.store.jdbc` → `org.jwcarman.nessy.jdbc`,
`org.jwcarman.nessy.store.tck` → `org.jwcarman.nessy.tck`. Ripples to chase: parent POM module
list, inter-module dependencies, `nessy-autoconfigure` (imports and any Sonar/source
properties), the starter, CI workflow references, README and docs. This supersedes the old
deferred idea of splitting per-SPI JDBC modules (`nessy-store-jdbc`/`nessy-parks-jdbc`): the
split trigger never fired, and five SPIs sharing dialects, schema bootstrap, and
`WriteOnceInsert` is evidence they belong together.

These are breaking renames of artifactIds and packages, acceptable pre-1.0 and noted in the
README's changelog section if one exists.

## 6. Autoconfiguration

Minimal, additive: where the autoconfigure module wires `JdbcSummaryStore` today, it gains the
parallel `JdbcPlanStore` bean (DataSource present → bean present), and `PlanStore.inMemory()`
as the fallback default. Granting `update_plan` and adding the transformer remain app-code
decisions — the grant principle is not softened by autoconfiguration. The queued starter-tidy
generation (subpackages, MCP client properties) stays a separate generation; this design only
adds the one bean pair and follows whatever package layout exists when it lands.

## 7. Demo

`chat-cli` (the cheapest host) demonstrates the pattern end-to-end:

- grants `PlanTools.updatePlan(planStore)` with `allow()`,
- builds memory via the pipeline: `Memory.pipeline(transcript).transform(PlanTools.transformer(planStore)).build()`,
- keeps everything else as-is (env-based provider, `ConsoleRepl`).

Asking it for anything multi-step shows the plan appear, progress, and complete — visible in
the token-usage listener's turn boundaries.

## 8. Testing

House rules apply throughout: prose snake_case names, no mocking libraries, hand-rolled
doubles, S5778/S5841 discipline, Awaitility over sleep.

- **Hydrators:** the extraction refactor is proven by the existing `TranscriptMemory` and
  `SummarizingMemory` suites staying green untouched; a hand-rolled custom hydrator wired via
  `.hydrator(...)` shows the open seam works (and that the pipeline hands it the pipeline's
  own transcript).
- **Pipeline:** builder validation (double hydration, `n < 1`, null stage); pass-through of
  `remember`; stage ordering (two appending stages, the second seeing the first's message in
  its input; a mutating stage registered between them observed in sequence); a
  nothing-to-add stage leaves the context untouched; clamp-then-append order proven by an
  appending stage observing an already-clamped context; a genuinely mutating stage (redaction
  double: rewrites a message body via `Context.map`) applied and visible downstream;
  **failure:** a throwing bare stage propagates out of `recall`; the same stage wrapped in
  `optional(...)` is skipped — downstream stages receive its input unchanged, partial output
  discarded — and exactly one WARN line is logged (Logback `ListAppender`, filtered to WARN,
  per the established fixture pattern).
- **Plan records:** blank-title rejection, defensive copy, `empty()`/`isEmpty()`.
- **Tool:** wholesale replace (second call with fewer tasks shrinks the stored plan), replay
  idempotency (same input executed twice, same stored plan), empty-list clears, confirmation
  text, blank-title error path returns a tool error rather than throwing out of the loop.
- **Plan transformer:** absent plan → context unchanged; empty plan → context unchanged;
  rendering exact (all three markers); framing sentence present; appended as one user-role
  message at the tail.
- **JDBC:** `JdbcPlanStore` unit tests on the in-memory certification pattern where possible,
  plus the `PlanStoreContract` wired into all five vendor `TckTests`.
- **Demo:** compiles; existing chat-cli tests unaffected.
- Full `./mvnw -q clean verify` passes with no API key and no network, always.

## 9. Out of scope, on purpose

- **Relevance-gated fact memory** ("remember this for me"): the designed second consumer of
  `ContextTransformer`. It arrives with a real dependency decision (embeddings) and earns its
  own generation — likely as a satellite module, per the core-vs-satellite rule (§1).
- **A third shipped hydrator** (vector-store bootstrap, checkpointing): the seam is open for
  applications today; core ships more strategies only when a real consumer demands one.
- **Plan history/versioning:** the plan is current-state-only; the transcript already records
  every `update_plan` call and result, so history is reconstructible where it matters.
- **Cross-conversation (agent-scoped) plans:** ruled per-conversation; a standing backlog is a
  different beast with different keying.
