# Reflection

A conversation that fails and is never looked at again fails the same way next
time. Reflection closes that loop: when a conversation settles, a critic reads
the transcript with a side model call and writes what it learned into the
subject's [notebook](notebook.md) — durable, and available to the very next
conversation over that subject.

## Two halves, one already built

Reflection has two halves, and only one is new. The **injection** half —
getting a lesson back into a future model call — is the notebook's existing
model-gated recall: a lesson is just a notebook entry, indexed and recalled
exactly like any note the model wrote itself. There is no separate reflection
store and no new injection machinery.

The **generation** half is `spi.reflection`: `Reflection.critic(...)` builds a
listener for `ConversationSettled` that reviews the settled transcript, calls
a model to distill it, and saves 0..n entries into the subject's `Notebook`
with `source = "reflection"`. The critic's own model call is deliberately not
a replay of the transcript's raw messages: it renders the whole thing to
role-labeled plain text first (a tool call and its result fold into one line;
thinking is omitted) and sends that as a single message, tools undeclared —
the critic wants prose to critique, not a resumable conversation, and a
tool-bearing history on a tools-less call is illegal history for at least one
major provider.

## The authorship invariant

Every notebook entry now carries a `source` — the identity that wrote it. A
tool granted through `NotebookTools` may only mutate entries whose `source`
matches its own identity: `remember`ing a name another source owns fails with
a `ToolResult.error` naming the conflict, and so does `forget`ting one.

This is what keeps reflection durable in practice. Without the guard, the
agent's own `remember` call could silently overwrite the lesson its critic
just wrote — the same LWW upsert that makes `remember` idempotent under
replay would just as happily erase a foreign entry. The guard covers writes,
not only deletes: a collision on `remember` is rejected the same way a
`forget` of a foreign entry is.

The rendered index reflects this too — a heading sourced from anywhere other
than the caller's own identity is annotated `(from reflection)`, so the model
can tell its own notes from a lesson it was handed.

## When the critic fires

- A `FAILED` settlement always triggers reflection — the highest lesson
  density, and the case worth the token spend by default.
- A `COMPLETE` settlement only triggers reflection if the critic was built
  with `reflectOnSuccess(true)`; the default is `false`.
- `subject(...)` is a `Function<ConversationId, SubjectId>` supplied at
  wiring time. If it returns `null` for a given conversation, that
  conversation is skipped entirely — no model call, no lessons.

`PARKED` conversations never publish a `ConversationSettled` in the first
place, so the critic never sees one.

Subagent conversations settle too, and each one reflects under the same
rules as a top-level conversation: a failed child's own critique is its own
token spend, distinct from — and in addition to — anything a failed parent
triggers.

!!! warning "Lesson names are deterministic, and last write wins"
    `ConversationSettled` is at-least-once. The critic derives each lesson's
    name from the conversation id (`lesson:<conversation-id>`, then `-2`,
    `-3`, ... for additional lessons from the same critique) rather than
    generating a fresh name per firing. A re-fired critic overwrites its own
    earlier write through the notebook's ordinary LWW upsert instead of
    duplicating it. This is deliberate, not a bug to route around — but it
    does mean a critic that changes its mind between two deliveries of the
    same settlement leaves whichever write lands last, exactly like any other
    notebook `save`.

    This LWW-by-name replacement is honest about the name it overwrites, not
    about the ones it doesn't: if an earlier delivery's critique produced
    three lessons (`lesson:<id>`, `-2`, `-3`) and a later delivery for the
    very same settlement produces only one, the later write replaces
    `lesson:<id>` but leaves `-2` and `-3` behind as orphans — nothing
    deletes a name the newer critique simply didn't mention. A critic whose
    output length varies between deliveries of the same settlement can leave
    stale extras in the notebook, not just a stale body.

## Runs on the settling drive

`Reflection.critic(...)` is meant to be wired through `HarnessConfig#listen`
— a synchronous listener — so the critic's model call, and the notebook
writes it triggers, complete before the `tell` that settled `FAILED`
returns to its caller. Reflection's latency and token spend are on that
call's critical path by design: the drive that just failed is the one
that pays for its own postmortem.

An app that cannot afford that latency can wire the same critic through
`HarnessConfig#listenAsync` instead: the critique then runs on its own
virtual thread, off the drive that already returned. That escape hatch
has its own cost — a short-lived process (a CLI invocation, a Lambda
handler) can exit before that thread finishes, and the lesson never lands
at all, with nothing at the call site to say so. A synchronous critic
never has that failure mode; it trades it for the latency instead.

A transcript long enough to exceed the critic model's own context window
isn't trimmed or chunked here — the provider's own error surfaces exactly
like any other model-call failure the critic catches: logged at `WARN`,
no lesson written for that settlement.

## Best-effort, on purpose

The critic never throws into the settling drive it rides on. A resolver that
throws, a model call that errors, a response that fails to parse, a notebook
write that conflicts — every failure reflection can suffer on its own account
is caught, logged at `WARN` naming the conversation, and dropped.

This is the deliberate opposite of how a completions listener behaves: the
subagent wake path throws to force a retry, because a parent that never wakes
up is stuck forever — the wake is load-bearing. A lesson that never gets
written is a shame, not a stuck conversation. Reflection would rather lose a
lesson than fail a conversation over its own homework.

## What v1 leaves out

- **Embeddings-ranked lesson retrieval.** Recall here is the same
  index-plus-model-judgment the notebook already does; ranked vector recall
  over lessons is a later generation, not this one.
- **Cross-conversation trajectory analytics.** The `source` field is the hook
  a future observability pass will use, but nothing here aggregates across
  conversations.
- **A dedicated, typed reflection store.** Lessons are notebook entries —
  prose in `hook` and `body`. A structured store earns its place only if
  analytics need structure prose can't carry.
- **A model-initiated `reflect` tool.** The model can already save a lesson
  voluntarily through `remember`; what this feature adds is the automatic
  critic, not a new verb.

## Wiring it

This is `chat-cli`'s own wiring, verbatim in shape: the critic shares the
harness's own provider and model, and reads and writes through the same
transcript and notebook the agent's tools already use.

```java
Agent<String> agent =
    Nessy.harness(
            h ->
                h.provider(provider)
                    .listen(
                        ConversationSettled.class,
                        Reflection.critic(
                            c ->
                                c.transcript(transcript)
                                    .notebook(notebook)
                                    .subject(subjectResolver)
                                    .provider(provider)
                                    .model(model)
                                    .reflectOnSuccess(false))))
        .agent(
            a ->
                a.name("chat-cli")
                    .model(model)
                    .tools(
                        ToolGrant.grant(
                            NotebookTools.remember(notebook, "chat-cli", subjectResolver),
                            UsagePolicy.allow()),
                        ToolGrant.grant(
                            NotebookTools.recall(notebook, "chat-cli", subjectResolver),
                            UsagePolicy.allow()),
                        ToolGrant.grant(
                            NotebookTools.forget(notebook, "chat-cli", subjectResolver),
                            UsagePolicy.allow()))
                    .memory(
                        Memory.pipeline(
                            transcript,
                            config ->
                                config.transform(
                                    NotebookTools.transformer(
                                        notebook, "chat-cli", subjectResolver)))));
```

`model` is required with no silent default — reflection is a token spend, so
which model pays for it is always an explicit choice, typically a cheaper one
than the agent it's reviewing.

## Where next

- [The Notebook](notebook.md) — the index, recall, and the authorship
  invariant reflection depends on.
- [Subagents](subagents.md) — the settlement plumbing and the completions
  listener reflection's failure handling is deliberately unlike.
- [Memory and the Pipeline](memory-and-the-pipeline.md) — how a recalled
  lesson actually reaches a model call.
