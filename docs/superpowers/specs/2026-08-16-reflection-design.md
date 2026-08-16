# Reflection — the agent gets durably smarter

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner: "I like it!").
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`;
builds on the Notebook (`2026-08-15-notebook-design.md`) and `ConversationSettled`
(subagents generation).

## 1. The idea

Reflection has two halves, and only one needed building. The **injection half** —
feeding lessons back into future context — is the Notebook's existing model-gated
recall: lessons live in the same index as the agent's own notes, with hooks, recalled
by the existing tool. The **generation half** is new: a critic that fires when a
conversation settles, reviews the transcript with a side model call, and writes
distilled lessons into the subject's Notebook. Every conversation becomes training
data for the next one, across restarts, with zero new injection machinery.

## 2. Notebook authorship (the enabling change)

`Notebook.Entry` gains **`source`** — the author's identity (an agent name, or
`"reflection"` for the critic). The invariant, enforced in `NotebookTools` (the
model-facing layer; the store stays dumb CRUD, per the grant principle):

- **A tool may only mutate entries whose `source` matches its own identity** —
  create, update, and delete alike. `remember` colliding with a foreign-sourced name
  → `ToolResult.error` naming the conflict (the LWW upsert would otherwise let an
  agent silently overwrite its critic's lesson — the guard covers writes, not just
  `forget`).
- `NotebookTools` factories take the identity at wiring time (the agent's name).
- The index annotates foreign-sourced entries so the model can tell its own notes
  from imposed lessons at a glance.
- Emergent win, deliberate: in a shared-subject notebook (newsroom), agents can no
  longer delete or overwrite each other's notes — per-author protection falls out of
  the same field.
- Mechanics: `Entry(name, hook, body, source)`; five vendor schemas gain the column
  (additive, no migration — nothing released); TCK contract grows the authorship
  cases; app code holding the `Notebook` handle remains trusted (the critic writes
  through it).

## 3. The critic

A listener factory (new core package `spi.reflection`):

```java
harnessBuilder.listen(ConversationSettled.class,
    Reflection.critic(c -> c
        .transcript(transcript)
        .notebook(notebook)
        .subject(conversationId -> subjectFor(conversationId))
        .provider(provider)
        .model("claude-haiku-4-5-20251001")   // REQUIRED — no silent default spend
        .reflectOnSuccess(false)));            // FAILED always; COMPLETE opt-in
```

(Customizer-config idiom per the 2026-08-16 DSL rulings; exact wiring shape settles at
planning against the post-v2 codebase.)

- **Trigger:** FAILED settlements always (highest lesson density); COMPLETE only when
  `reflectOnSuccess(true)`. Reflection is a token spend — the model is a required,
  explicit choice, and a cheap critic reviewing an expensive worker is the intended
  shape.
- **The call:** reads the settled transcript, prompts for what worked / what failed /
  what this agent should do differently (default prompt overridable), and writes 0..n
  lessons: `source="reflection"`, hook = one-liner for the index, body = the lesson.
- **Replay discipline:** `ConversationSettled` is at-least-once — lesson names derive
  deterministically from the conversation id (e.g. `lesson:<conversation-id>[-n]`), so
  a re-fired critic overwrites its own earlier write via LWW instead of duplicating.
- **Subagent conversations settle too:** children reflect under the same rules; with a
  shared subject the family pools its lessons — noted, and `subject(...)` lets an app
  route or suppress child reflection (return-null-to-skip contract settles at
  planning).
- The critic never throws into the settling drive for reflection failures — a failed
  reflection logs at WARN and drops (a lesson lost is a shame; a conversation failed
  over its own homework is worse). This is the opposite of the completions listener's
  throw-for-retry, deliberately: wakes are load-bearing, lessons are best-effort.

## 4. Out of scope (v1)

- Embeddings-ranked lesson retrieval (the index + model gating is retrieval, v1;
  vector recall is the banked embeddings generation).
- Cross-conversation trajectory analytics (o11y generation; the `source` field is the
  hook it will use).
- A dedicated typed ReflectionStore — earns its place only if o11y demands structure
  prose can't carry.
- Model-initiated `reflect` tool — the Notebook's `remember` already permits
  voluntary lessons; the feature here is the automatic critic.

## 5. Testing

House rules. Notebook: authorship invariant (own create/update/delete pass; foreign
name collision on remember → error naming the conflict; foreign forget → error;
LWW-within-own-source preserved), index annotation, JDBC round-trip with source ×5
vendors, TCK cases public. Critic: scripted provider as the critic model — FAILED
settlement produces lessons with `source="reflection"` and deterministic names;
COMPLETE respects the toggle; duplicate settlement overwrites instead of duplicating;
critic failure logs and drops without disturbing the settling drive. End-to-end:
settle-fail → lesson written → NEXT conversation's index carries it → recall returns
the body.

## 6. Sequencing

Builds after subagents v2 lands (v2 owns the worktree and the settlement plumbing this
rides on). Notebook schema change ships inside this generation.
