# Agent as Scope

An agent is a **recipe** bound to an **id**.

The recipe is an `AgentType`: system prompt, tools, model, memory. You
compile it once into a `Harness` and keep it for the life of the process.
The id is an `AgentId` — a plain string naming one conversation, one tenant,
one ticket, whatever your domain calls a "who."

```java
harness.observe(AgentId.of("house-12"), "the porch light came on");
```

There is no handle in between. An earlier design handed you a transient
`Agent<O>` per id; it was deleted, because a handle is a thing that can go
stale and sharding already knows where an agent lives.

## Exactly one actor per id

Each `(AgentType, AgentId)` is one entity in a Pekko cluster shard. That is
the same shape as Orleans' `(grain type, grain key)`, and it comes with the
single-activation guarantee outright: **there is exactly one actor per id**,
cluster-wide, so two callers cannot corrupt one agent's state.

An agent works one turn at a time. Observations arriving during a turn wait
in the backlog, and what waiting *means* is your
[coalescer's](../guides/harness.md#coalescing-what-happens-to-what-is-already-waiting)
decision.

## The document is small on purpose

```java
record AgentState(String turnId, Phase phase, String observation, Usage usage) {}
```

A turn id, a phase, a claim id, and a token count. Around 260 bytes,
measured on an agent running real tools against PostgreSQL, and it does not
grow with what the agent does.

Everything that *could* grow lives elsewhere: the backlog is a table, tool
arguments and results are claimed, and the conversation is the transcript.
What is left is only what answers one question — **what should happen if
this process dies right now?**

## Phases are data, not positions

```java
sealed interface Phase {
  record Idle() {}
  record CallingModel() {}
  record WorkingTools(Map<String, CallState> calls) {}
}
```

`Idle` is an arm rather than the absence of a turn, so going to sleep is a
transition you can assert on instead of a stale-snapshot check bolted onto
something else.

The actor itself is a thin shell. It translates a message into an input,
calls a **pure function**, persists what comes back, and runs the
instructions:

```java
Decision decide(AgentState state, Input input);

record Decision(AgentState next, List<Instruction> then) {}
```

Every rule lives in the function, which has no way to *do* anything — no
clock, no store, no actor, no Pekko import. Every effect lives in the shell,
which decides nothing. That split is what lets a three-day parked approval
and a crash mid-model-call be ordinary unit tests rather than a cluster, a
race and a fifteen-second timeout.

## Recovery is the common path

There is no "should we re-drive?" branch anywhere. Pekko reads the document
before any command runs, and the agent then feeds itself a `Recovered` input
on **every** activation. The rare path is therefore exercised constantly
rather than only after a crash — which matters, because the last engine had
a real bug on that path that went unnoticed for exactly as long as nothing
ordinary ran it.

See [Durable Computation](durable-computation.md) for what each phase does
when it wakes up.

## Persistence ids

The agent type is the persistence id prefix. **Renaming an agent type
orphans its stored state** — that is not a bug to work around, it is what
renaming a type means.

## Where next

- [Durable Computation](durable-computation.md) — parked calls, deadlines as rows, and recovery
- [Storage](storage.md) — the tables, and why there is no abstraction over them
- [The Harness](../guides/harness.md) — observing, subscribing, and coalescing
