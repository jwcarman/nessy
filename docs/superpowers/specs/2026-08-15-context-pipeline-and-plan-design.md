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
  never grants the tool and never adds the contributor pays zero.
- **Naming convention for persistence contracts:** new pure-persistence SPIs take the `Store`
  suffix (`ConversationStore`, `SummaryStore`, now `PlanStore`). `Transcript` and `Parks` keep
  their names: they are jurisdictions with semantics, not dumb persistence, and `Repository`
  buys nothing `Store` doesn't while carrying DDD baggage these narrow contracts don't honor.

## 2. The context pipeline — hydrate, clamp, amend

Recall-side context production becomes a named pipeline with three phases in fixed order. The
phases already exist in the code as separate classes; this section names them and gives them one
composition surface.

### 2.1 Hydrate — the strategy owns how much history it reads

A hydration strategy produces the *initial* context. It references the `Transcript` (and any
companion stores it owns) — it does not necessarily read all of it:

- **Full** (`TranscriptMemory` today): `transcript.all(id)`, open-tail-trimmed.
- **Summarizing** (`SummarizingMemory` today): the folded prefix from `SummaryStore` rendered as
  one opening `Message.user(text)`, plus only `transcript.tail(id, watermark)` — the whole
  fold-on-threshold, watermark-bookkeeping mechanism is unchanged by this design.

Hydration is a **closed phase**: strategies ship in core because they need transcript versions,
watermarks, and legality guarantees a rendered `Context` no longer carries. The existing classes
stay public; the builder composes them.

### 2.2 Clamp — optional, pair-safe

`keepRecent(n)` applies `Context.keepRecent(n)` after hydration — the existing pair-safe trim,
unchanged. Clamping after a summarizing hydration is legitimate belt-and-suspenders: the summary
absorbs old history and the window guarantees a ceiling even when the tail grows faster than
folding keeps up.

**Phase invariant (binding on every fit-side stage, present and future):** no cut splits a
tool-use message from the results message answering it.

### 2.3 Amend — the open seam

The one new SPI this design introduces, in `org.jwcarman.nessy.spi.memory`:

```java
/**
 * Amends the outgoing context: given the context as built so far, returns messages the
 * pipeline appends verbatim at the tail. Empty list means contribute nothing this call.
 *
 * <p>Append-only on purpose: the pipeline does the appending, tail position can never split
 * a tool exchange, and a contributor cannot reorder or drop history. Receiving the context
 * as input is what makes relevance possible — a future facts contributor can read the fitted
 * tail and return only what is germane. Contributed messages carry plain content (no tool-use
 * or tool-result blocks); the final {@code Context.of} still rejects illegal shapes, so a
 * misbehaving contributor fails loud at the border, never silently corrupts the dialogue.
 *
 * <p>Contributions are synthesized at recall and never remembered: not told to the transcript,
 * not folded into any summary. One fresh copy per model call, no accumulation, no drift.
 */
public interface ContextContributor {
  List<Message> contribute(ConversationId id, Context soFar);
}
```

Contributors run in registration order, each seeing the context including prior contributions.
They run **after** clamping, so an amendment can never be clipped by the window, and a
contributor judging relevance sees the same fitted context the model will see.

Why injection-as-user-message is legal and sufficient: the only rigid wire constraint is the
tool pair. Strict role alternation is not required — OpenAI accepts any sensible ordering and
Anthropic merges consecutive same-role messages into one turn (with `tool_result` blocks
required before text within a turn, which tail-append satisfies). `SummarizingMemory` has
injected a fabricated `Message.user(...)` since it shipped; this seam generalizes that move.

### 2.4 The builder

A static factory on `Memory`, beside `windowed`:

```java
Memory memory = Memory.pipeline(transcript)                              // full hydration
    .summarizing(summaries, provider, model, prompt, tailThreshold)      // …or fold instead
    .keepRecent(50)                                                      // optional clamp
    .contribute(PlanTools.contributor(planStore))                        // amend, repeatable
    .build();
```

- `Memory.pipeline(Transcript)` returns a `MemoryPipeline` builder. The transcript is the one
  required ingredient: `remember` always appends to it (idempotency stays the transcript's own
  no-stutter rule), whatever hydration chooses to re-read.
- `.summarizing(SummaryStore, ModelProvider, String model, String prompt, int tailThreshold)`
  switches hydration from full to summarizing; parameters mirror `SummarizingMemory`'s
  constructor exactly. Calling it twice is an `IllegalStateException` — one hydration strategy
  per pipeline.
- `.keepRecent(int n)` — at most once, same `n >= 1` floor as `Memory.windowed`.
- `.contribute(ContextContributor)` — zero or more, run in call order.
- `.build()` returns a `Memory`. Internally it delegates to `TranscriptMemory` or
  `SummarizingMemory` for hydration, applies the clamp, then folds contributors. No behavior is
  reimplemented; the builder is composition sugar with names.

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
contributor — the two halves of one invariant, kept in one reviewable place.

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

### 3.4 The contributor

```java
public static ContextContributor contributor(PlanStore store) { ... }
```

`contribute` finds the plan; absent or empty means an empty list — nothing is injected, the
"if applicable" rule. Otherwise it returns exactly one `Message.user(...)`:

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
not dialogue. Tail position (guaranteed by the amend phase) puts the plan at maximum recency —
the same reason Claude Code injects todo reminders last.

### 3.5 What the kernel does not learn

Nothing. No loop change, no `Harness` change, no `Agent` change. The tool is granted like any
tool; the contributor is composed like any memory decoration. The facility is opt-in at the
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
as the fallback default. Granting `update_plan` and adding the contributor remain app-code
decisions — the grant principle is not softened by autoconfiguration. The queued starter-tidy
generation (subpackages, MCP client properties) stays a separate generation; this design only
adds the one bean pair and follows whatever package layout exists when it lands.

## 7. Demo

`chat-cli` (the cheapest host) demonstrates the pattern end-to-end:

- grants `PlanTools.updatePlan(planStore)` with `allow()`,
- builds memory via the pipeline: `Memory.pipeline(transcript).contribute(PlanTools.contributor(planStore)).build()`,
- keeps everything else as-is (env-based provider, `ConsoleRepl`).

Asking it for anything multi-step shows the plan appear, progress, and complete — visible in
the token-usage listener's turn boundaries.

## 8. Testing

House rules apply throughout: prose snake_case names, no mocking libraries, hand-rolled
doubles, S5778/S5841 discipline, Awaitility over sleep.

- **Pipeline:** builder validation (double hydration, double clamp, `n < 1`, null contributor);
  pass-through of `remember`; contributor ordering (two contributors, second sees the first's
  message in `soFar`); empty-contribution injects nothing; clamp-then-contribute order proven
  by a contributor observing an already-clamped context; a contributor returning an illegal
  message shape fails loud at `Context.of`.
- **Plan records:** blank-title rejection, defensive copy, `empty()`/`isEmpty()`.
- **Tool:** wholesale replace (second call with fewer tasks shrinks the stored plan), replay
  idempotency (same input executed twice, same stored plan), empty-list clears, confirmation
  text, blank-title error path returns a tool error rather than throwing out of the loop.
- **Contributor:** absent plan → nothing; empty plan → nothing; rendering exact (all three
  markers); framing sentence present.
- **JDBC:** `JdbcPlanStore` unit tests on the in-memory certification pattern where possible,
  plus the `PlanStoreContract` wired into all five vendor `TckTests`.
- **Demo:** compiles; existing chat-cli tests unaffected.
- Full `./mvnw -q clean verify` passes with no API key and no network, always.

## 9. Out of scope, on purpose

- **Relevance-gated fact memory** ("remember this for me"): the designed second consumer of
  `ContextContributor`. It arrives with a real dependency decision (embeddings) and earns its
  own generation — likely as a satellite module, per the core-vs-satellite rule (§1).
- **Opening the hydration phase** to user strategies: closed until a second real hydrator
  exists outside core.
- **Plan history/versioning:** the plan is current-state-only; the transcript already records
  every `update_plan` call and result, so history is reconstructible where it matters.
- **Cross-conversation (agent-scoped) plans:** ruled per-conversation; a standing backlog is a
  different beast with different keying.
