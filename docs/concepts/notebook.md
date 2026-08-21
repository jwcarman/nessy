# The Notebook

> **Superseded.** This page describes the pre-agent-as-scope architecture (pre-2026-08-18)
> and is retained as historical reference. The design of record is the agent-as-scope,
> durable-computation, and action-and-tool-vocabulary specs (2026-08-18 and 2026-08-20). A
> rewritten docs site is pending.

"Remember this for me." The notebook gives the model a place to write durable notes about
whoever or whatever it's working with — a user's preferences, a project's stakeholders, a
fact worth not asking for twice — and a disciplined way to bring them back: a compact
**index** rides every recall, and the model reads a note's full body only when it judges
the note relevant, through a tool call of its own choosing.

That relevance judgment lives in the model, not in an embedding. There's no vector store
here and no new dependency — the notebook is core-shaped exactly like the plan facility,
and it's the second member of the same family: **tool-writable, recall-injected context**.
The model writes through a tool; the artifact persists durably; the memory pipeline
re-presents it at recall. `NotebookTools` (`org.jwcarman.nessy.spi.notebook`) is that one
invariant's two halves kept in one reviewable place — `remember`, `recall`, and `forget`
let the model write and read; `transformer` recalls the index. All four meet only at
`Notebook`.

## `SubjectId` — the first non-conversation key

Notes outlive conversations; that's their reason to exist. So `Notebook` is the first
nessy store **not** keyed by `ConversationId`. Its key is the **subject** — who or what
the notes are about — and nessy declines to invent an identity model for it:

```java
public record SubjectId(String value) { /* non-blank validation */ }
```

`SubjectId` lives in `org.jwcarman.nessy.api.conversation` beside `ConversationId` —
general identity vocabulary, opaque to nessy exactly the same way, the app owning the
meaning of the string it puts in.

The bridge from a conversation to its subject is an app-supplied resolver,
`Function<ConversationId, SubjectId>`, and every `NotebookTools` factory takes one, alongside the
caller's own author `identity` (see [Authorship](#authorship) below):

```java
public static Tool<RememberNote> remember(
    Notebook notebook, String identity, Function<ConversationId, SubjectId> resolver);
```

Every factory also has a resolver-less overload. Its convenience default maps subject to
conversation (`new SubjectId(id.value())`), which degenerates the notebook to
per-conversation notes — still useful, no identity model forced on an app that hasn't
decided what a "user" is yet. Supply a real resolver — looking up the authenticated user,
say — and the same notes follow that person across every conversation they start.

## The three verbs

`remember`, `recall`, and `forget` are the tools granted to the model. None of the three
ever parks, and none of the three lets a validation failure escape the loop as a thrown
exception — a null or blank name, hook, or body, or a `recall` of an unknown name, comes
back as a failed `ToolResult` the model can read and act on.

**`remember`** saves an entry — `name`, `hook`, `body`, all required and non-blank —
under `(subject, name)`. Saving an existing name **replaces** that note: `remember` is an
upsert, and the upsert key is the name, so a redelivered call — the same at-least-once
story every tool call lives with — rewrites the identical entry rather than duplicating
it. A null or blank `name`, `hook`, or `body` doesn't throw out of the loop; it comes back
as a failed `ToolResult` naming what was missing, so the model can retry with a
corrected call.

**`recall`** reads one note's full body back by name. An unknown name doesn't fail
silently either — the error names the notebook index itself (`no note named 'x' — check
the notebook index in your context`), pointing the model at the ground truth it should
have consulted.

**`forget`** deletes a note by name and is idempotent by construction: forgetting a name
that's already gone still confirms success, because from the model's point of view the
name is gone either way — there's nothing here for the model to get wrong by calling it
twice.

## Authorship

Every entry carries a fourth field, `source` — the identity that wrote it: an
agent name, or `"reflection"` for the [reflection](reflection.md) critic.
`NotebookTools` takes that identity at wiring time and enforces it on every
mutating call: `remember` and `forget` may only touch an entry whose stored
`source` already matches the caller's own identity. Remembering a name that
belongs to a different source doesn't overwrite it — it fails with a
`ToolResult.error` naming the conflict and the entry's true owner, and
`forget` fails the same way. The store itself stays dumb CRUD over whatever
`source` a trusted caller hands it; only this model-facing layer gates by
identity.

The rendered index carries authorship too: a heading whose `source` differs
from the caller's own identity is annotated `(from <source>)`, so the model
can tell its own notes from ones another author left it.

```
<notebook>
- user-taste — Prefers terse answers and metric units
- lesson:0198... — retry with backoff (from reflection)
</notebook>
```

One emergent win, for free: in the newsroom example, the writer and its
researcher subagent already share one notebook and resolver — now the
writer's notes are protected from any other identity's `remember` or
`forget`, with no code beyond the identity string each agent already passes.

## The injected index

`NotebookTools.transformer(notebook, identity, resolver)` is a `ContextTransformer`: it looks up the
subject's headings; none, and it returns the context unchanged — the same "if applicable"
rule the plan facility follows, so a conversation whose subject has never remembered
anything never sees the block. Otherwise it appends exactly one block via `Context.enrich`,
rendered byte-exact:

```
<notebook>
- user-taste — Prefers terse answers and metric units
- project-atlas — Stakeholders and deadline for Project Atlas
</notebook>
These are your saved notes, maintained by you through the remember and forget tools. Read a
note's full content with the recall tool when it is relevant. This is ambient state, not a
message from the user.
```

Only `name` and `hook` ride the index — the body is deliberately absent, which is the
whole point of gating it behind `recall`. The framing sentence matters as much as the
block itself: models are post-trained to read a framed block inside a user message as
environment, not dialogue, so it stays intact rather than paraphrased away.

## When to prefer the notebook over stuffing context

Some facts are worth keeping past the turn that produced them but too small, or too
occasional, to justify carrying in every prompt from here on. Stuffing them into the
system prompt or re-telling them every turn burns tokens on a subject the model may never
touch again this conversation; leaving them out means asking the user to repeat
themselves. The notebook's index costs one line per note on every recall regardless of
whether the model ever reads the body — cheap enough to keep unconditionally — while the
expensive part, the full note, loads only on the turn that actually needs it. Reach for it
when notes are per-subject rather than per-conversation, and when there could be more of
them over time than you'd want resident in every prompt at once.

## Wiring it

```java
Notebook notebook = Notebook.inMemory();
Function<ConversationId, SubjectId> subjectResolver = id -> new SubjectId("chat-cli-user");
Transcript transcript = Transcript.inMemory();

Agent<String> agent =
    harness.agent(
        a ->
            a.name("assistant")
                .model("claude-sonnet-4-5")
                .tools(
                    ToolGrant.grant(
                        NotebookTools.remember(notebook, "assistant", subjectResolver),
                        UsagePolicy.allow()),
                    ToolGrant.grant(
                        NotebookTools.recall(notebook, "assistant", subjectResolver),
                        UsagePolicy.allow()),
                    ToolGrant.grant(
                        NotebookTools.forget(notebook, "assistant", subjectResolver),
                        UsagePolicy.allow()))
                .memory(
                    Memory.pipeline(
                        transcript,
                        config ->
                            config.transform(
                                NotebookTools.transformer(
                                    notebook, "assistant", subjectResolver)))));
```

`"assistant"` here is this agent's own author identity — every entry it writes through `remember`
carries that `source`, and it may only update or delete entries already sourced from it (see
[Authorship](#authorship)).

The expected posture for all three tools is `allow()`: a self-bookkeeping tool, like the
plan's `update_plan`, earns no approval friction. If the pipeline also carries the plan
transformer, register the notebook transformer after it — `enrich` appends at the tail, so
whichever transformer runs last ends up closest to the model's next turn.

## Replay idempotency

`remember` upserts by name, so a redelivered call rewrites the same entry rather than
creating a second one; `forget` is a no-op on an already-absent name. Neither verb has a
partial-update path to get wrong under an at-least-once re-drive: every write lands
exactly where the previous attempt at it would have.

!!! warning "Last write wins — at entry granularity, under real concurrency"
    A `PlanStore` has exactly one writer per conversation, so last-write-wins there needs
    no more care than a plain map write. A `Notebook` carries no such guarantee — more than
    one conversation can share a subject and write concurrently, since the resolver is the
    app's own identity lookup, not nessy's. Last-write-wins here means each `save` stays
    atomic at entry granularity, not that races can't happen: two conversations remembering
    the same name at once leave whichever write lands last, and a durable backend has to
    make that upsert race-safe rather than merely correct in the single-writer case.

!!! note "Alphabetical is each backend's own collation"
    `headings` returns a stable, alphabetical-by-name order, but "alphabetical" is each
    implementation's own collation — an in-memory notebook orders by `String` code point, a
    JDBC one by its database's collation. The two only agree because `remember`'s expected
    names are kebab-case and lowercase, which every collation in play orders identically;
    the notebook doesn't otherwise promise cross-backend index order for arbitrary names.

!!! warning "JDBC backends cap `name` and `hook` at column width"
    `nessy_notebook` declares `name varchar(255)` and `hook varchar(1024)` — the spec parks
    size policy as an application concern, not the notebook's, so an oversized `name` or
    `hook` fails the `save` outright on a strict database, while `Notebook.inMemory()` takes
    the same call without complaint. Keep names and hooks within those widths if the same
    conversation might run against either backend.

Unlike the plan facility, there's no clear-the-whole-thing operation here, so the
empty-vs-absent ambiguity that `PlanStore.save(Plan.empty())` raises doesn't have an
analog to get wrong: `forget` always names one entry, and an empty index is just what a
subject with no notes yet looks like.

## Where next

- [Planning](planning.md) — the other tool-writable, recall-injected facility, sharing the
  same `ContextTransformer` shape.
- [Memory and the Pipeline](memory-and-the-pipeline.md) — the `ContextTransformer` seam
  `NotebookTools.transformer` is built on.
- [Storage](storage.md) — the durable-doors pattern `Notebook.inMemory()` follows, and its
  JDBC backing.
