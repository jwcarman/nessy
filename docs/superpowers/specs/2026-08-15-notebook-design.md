# The Notebook — model-gated memory

**Date:** 2026-08-15
**Status:** DRAFT — awaiting owner review
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`; the context
pipeline spec (`2026-08-15-context-pipeline-and-plan-design.md`) defines the seams this rides.

## 1. The idea

"Remember this for me." The model writes named, durable notes through tools; every recall
injects a compact **index** of what it knows — one line per note — and the model reads a
note's full body only when it judges it relevant, via a tool call. Relevance-gating lives in
the model, not in an embedding: no vector store, no new dependency, core-shaped exactly like
the plan facility. (The system-gated variant — embed the context tail, search a vector store,
inject top-k — remains the designed satellite v2; this facility neither blocks nor needs it.)

This is the third consumer of `ContextTransformer` and the second member of the
tool-writable, recall-injected family. Placement rulings carry over from the plan facility:
core concept, zero dependencies, opt-in entirely at the composition line.

Ratified namings: the jurisdiction is the **`Notebook`** (a `Memory`-adjacent name was
rejected — that word is taken by the recall subsystem); the model-facing verbs are
**`remember` / `recall` / `forget`** (the protocol models are already trained on; the tool
named `recall` shares only a word with `Memory.recall`, never a call path — a spec note, not
a conflict).

## 2. SubjectId — the first non-conversation key

Notes outlive conversations; that is their reason to exist. The `Notebook` is therefore
nessy's first store **not** keyed by `ConversationId`. Its key is the **subject** — who or
what the notes are about — and nessy refuses to invent an identity model for it:

```java
/** An app-minted identity for whom or what a Notebook's entries concern — a user, a tenant,
 *  a project. Opaque to nessy, exactly like ConversationId: the app owns the vocabulary. */
public record SubjectId(String value) { /* non-blank validation */ }
```

`SubjectId` lives in `org.jwcarman.nessy.api.conversation` beside `ConversationId` — it is
general identity vocabulary (the future preferences facility shares it), not notebook-private.

The bridge from a conversation to its subject is an app-supplied resolver,
`Function<ConversationId, SubjectId>`. The zero-config convenience (overloads without a
resolver) maps subject = conversation (`new SubjectId(id.value())`), degenerating to a
per-conversation notebook — still useful, no identity model forced.

## 3. The Notebook SPI (`org.jwcarman.nessy.spi.notebook`)

```java
public interface Notebook {

  /** A note: the name the model files it under, the one-line hook the index shows, the body
   *  recall returns. All non-blank; name is the upsert key within a subject. */
  record Entry(String name, String hook, String body) { /* requireNonNull + non-blank */ }

  /** The index view: name + hook, bodies deliberately absent (the whole point). */
  record Heading(String name, String hook) {}

  /** Every heading for {@code subject}, stable order (alphabetical by name). */
  List<Heading> headings(SubjectId subject);

  /** The full entry, or empty if no note by that name. */
  Optional<Entry> find(SubjectId subject, String name);

  /** Upserts by (subject, name), last write wins — a replayed remember rewrites identically. */
  void save(SubjectId subject, Entry entry);

  /** Removes the note; absent is a no-op (idempotent under replay). */
  void forget(SubjectId subject, String name);

  static Notebook inMemory() { ... }
}
```

`InMemoryNotebook`: package-private, `ConcurrentHashMap<SubjectId, ConcurrentHashMap<String, Entry>>`
(or equivalent), null-checked.

**Concurrency note (differs from PlanStore):** conversations sharing a subject can write
concurrently — the single-writer-loop argument does NOT apply here. Semantics stay
last-write-wins at entry granularity, but the JDBC upsert must be race-safe (§5).

## 4. NotebookTools (`spi.notebook`)

Wire records nested in `NotebookTools` (schema-derived, SPI records stay clean):
`RememberNote(String name, String hook, String body)`, `RecallNote(String name)`,
`ForgetNote(String name)`.

- **`remember(notebook, resolver)`** → `Tool<RememberNote>`, name `remember`. Description
  (for the model): "Save a durable note under a short kebab-case name with a one-line hook;
  remembering an existing name replaces that note." Execute: validation failures
  (blank name/hook/body) → `ToolResult.error`, never a throw; success saves and confirms
  `Remembered 'user-taste'.` Never parks.
- **`recall(notebook, resolver)`** → `Tool<RecallNote>`, name `recall`. Returns the body as
  `ToolResult.ok`; unknown name → `ToolResult.error("no note named 'x' — check the notebook
  index in your context")` so the model self-corrects.
- **`forget(notebook, resolver)`** → `Tool<ForgetNote>`, name `forget`. Idempotent; confirms
  `Forgotten 'x'.` whether or not it existed.
- **`transformer(notebook, resolver)`** → `ContextTransformer`. No headings → context
  unchanged (same instance). Otherwise one `Context.enrich(new TextBlock(...))`:

```
<notebook>
- user-taste — Prefers terse answers and metric units
- project-atlas — Stakeholders and deadline for Project Atlas
</notebook>
These are your saved notes, maintained by you through the remember and forget tools. Read a
note's full content with the recall tool when it is relevant. This is ambient state, not a
message from the user.
```

Every factory has a resolver-less overload (subject = conversation, §2). Grants are the
app's, as always; expected posture `allow()` for all three.

## 5. Durability — `JdbcNotebook` (nessy-jdbc)

Table (per-dialect `notebook-schema.sql`, five dialects, each copying its directory's
conventions):

```sql
CREATE TABLE IF NOT EXISTS nessy_notebook (
  subject_id  <dialect id type>  NOT NULL,
  name        VARCHAR(255)       NOT NULL,
  hook        VARCHAR(1024)      NOT NULL,
  body        <dialect text/clob> NOT NULL,
  PRIMARY KEY (subject_id, name)
);
```

- `headings`: `SELECT name, hook ... WHERE subject_id = ? ORDER BY name` — bodies never leave
  the database for the index.
- `find`: constant select by PK.
- `save`: **the `JdbcSummaryStore` race-recovery pattern** (update → zero rows → insert via
  `WriteOnceInsert` → duplicate swallowed → retry update), because concurrent writers are
  real here (§3). All SQL complete constants in the class; dialect matters only to bootstrap.
- `forget`: constant delete, idempotent by nature.
- `JdbcPersistence` gains the component; autoconfigure gains the bean (mirroring the plan
  bean exactly; no fallback bean, matching every door).

**TCK:** `NotebookContract` joins the kit — **public `@Test` methods** (the nested-subscriber
discovery lesson): round-trip; headings carry no bodies and sort by name; upsert replaces;
forget removes; forget of absent is a no-op; subjects never see each other's notes; last
write wins. In-memory certification in the TCK test tree; a nest in every vendor suite that
nests the sibling contracts, plus a top-level postgres `JdbcNotebookTest` with any
JDBC-specific pins (bootstrap idempotence).

## 6. Demo

chat-cli: grant all three tools `allow()`, add the transformer to its existing pipeline
(after the plan transformer), fixed subject resolver (`id -> new SubjectId("chat-cli-user")`)
so notes survive across conversations *within a process run* — the README says plainly that
surviving restarts is one `JdbcNotebook` swap away. System prompt gains one sentence: "When
the user tells you something worth keeping, remember it."

## 7. Testing

House rules throughout (prose names, no mocks, S5778/S5841, public contract methods).
- Records: validation, non-blank rules.
- InMemoryNotebook: contract cases at unit level (subject isolation included).
- Tools: upsert-replace via replay (same input twice = same state), unknown-recall error
  path, forget idempotence, confirmation texts, resolver-less overloads key by conversation,
  resolver overloads key by subject (two conversations, one subject, shared notes).
- Transformer: empty → same instance; index renders exactly (block above, byte-pinned);
  bodies absent from the injected block; tail position.
- JDBC: mirror JdbcPlanStoreTest posture (container-tagged postgres, contract subscriber
  top-level) + the save-race recovery test in the JdbcSummaryStoreRaceRecoveryTest style.
- Full offline `./mvnw -q clean verify` green, reactor javadoc green, vendor matrix run by
  the controller before merge.

## 8. Out of scope, on purpose

- **Vector/system-gated relevance** — satellite v2, arrives with the embeddings SPI decision.
- **Preferences facility** — shares `SubjectId` and the resolver seam when it comes; not
  bundled here.
- **Body size limits / eviction** — LWW upsert and the model's own hygiene (forget) are the
  v1 policy; revisit on evidence.
- **Cross-agent sharing semantics** — the subject key already expresses either posture
  (shared subject vs composite key); the app chooses, nessy stays out of identity.
