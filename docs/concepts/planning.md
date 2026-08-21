# Planning

> **Superseded.** This page describes the pre-agent-as-scope architecture (pre-2026-08-18)
> and is retained as historical reference. The design of record is the agent-as-scope,
> durable-computation, and action-and-tool-vocabulary specs (2026-08-18 and 2026-08-20). A
> rewritten docs site is pending.

Give the model a plan — not one written for it, but a task list the model itself creates
and maintains through a tool, that then appears in its context on every model call,
unconditionally, for as long as it has tasks. This fixes a specific failure mode:
long-horizon drift, where an agent forgets step 4 while grinding on step 2.

The plan is the first instance of a general pattern: **tool-writable, recall-injected
context**. The model writes through a tool; the artifact persists durably; the memory
pipeline re-presents it at recall. `PlanTools` (`org.jwcarman.nessy.spi.plan`) is the two
halves of that one invariant, kept in one reviewable place — `updatePlan` lets the model
write, `transformer` recalls it. They meet only at `PlanStore`.

## The tool: wholesale replacement, idempotent by construction

```java
public static Tool<UpdatePlan> updatePlan(PlanStore store) { ... }
```

The tool is named `update_plan`. Its input, `UpdatePlan(List<PlannedTask> tasks)`, is the
wire twin of `Plan` — kept separate so the SPI record never grows schema annotations.

**The model sends the entire task list every time it changes anything.** This is the
TodoWrite shape, chosen deliberately over CRUD: durable re-drives execute at-least-once,
and a replayed wholesale write stores the identical list — idempotent by construction, no
task-id bookkeeping, no merge logic. `execute` maps the input to a `Plan` and calls
`store.save(conversationId, plan)`.

The description the model reads states the whole contract:

> Maintain your task list for multi-step work. Send the COMPLETE list every time — this
> replaces the whole plan. Keep at most one task IN_PROGRESS; mark tasks DONE as you
> finish them. An empty list clears the plan.

- **One `IN_PROGRESS` task at a time** — a convention stated to the model, not enforced
  by the type.
- **An empty `tasks` list is legal and clears the plan** — see below.
- The tool never parks; it returns immediately with a one-line confirmation the model
  reads in-band: `Plan updated: 4 tasks (1 in progress, 1 done).`
- `effect(input)` renders the same checklist shown below, for approval prompts — though
  the expected grant is `allow()`: a self-bookkeeping tool earns no approval friction.
- A blank task title is rejected by `Plan`'s compact constructor; the tool surfaces that
  as a failed `ToolResult` rather than throwing out of the loop, so the model can correct
  itself.

## The store: last-write-wins, no fencing

```java
public interface PlanStore {
  Optional<Plan> find(ConversationId id);
  void save(ConversationId id, Plan plan);
  static PlanStore inMemory() { return new InMemoryPlanStore(); }
}
```

The same justification as `SummaryStore`: the writer is the `update_plan` tool, executing
inside the loop, which runs one turn at a time per conversation — and an at-least-once
replay rewrites the identical plan, so a clobbered write is re-done work, never a lost
word. No fencing, no version check.

!!! warning "Empty clears — cleared is absent"
    Saving `Plan.empty()` **clears** the plan: a subsequent `find` returns
    `Optional.empty()`, in every backend. "No plan" and "empty plan" are one state —
    nothing downstream distinguishes them (the transformer injects nothing either way),
    and a one-row-per-task storage layout can't tell them apart without a marker row it has
    no other use for.

## The injected block

`PlanTools.transformer(store)` is a `ContextTransformer`: it finds the plan; absent or
empty, it returns the context unchanged — the "if applicable" rule, so a conversation
that never asks for anything multi-step never sees the block. Otherwise it appends
exactly one user-role message via `Context.enrich`, rendered byte-exact by `PlanTools`'
own `renderChecklist`:

```
<current-plan>
- [ ] Fetch the order history
- [>] Summarize the disputes
- [x] Draft the refund email
</current-plan>
This is your task list, maintained by you through the update_plan tool. It is ambient
state, not a message from the user.
```

`[ ]` is pending, `[>]` is in progress, `[x]` is done. The framing sentence is part of the
contract, not decoration: models are post-trained to treat framed blocks inside user
messages as environment, not dialogue. Register the plan transformer last in the pipeline
— `enrich` appends at the tail, and tail position puts the plan at maximum recency.

## Wiring it

```java
PlanStore planStore = PlanStore.inMemory();
Transcript transcript = Transcript.inMemory();

Agent<String> agent =
    harness.agent(
        a ->
            a.name("assistant")
                .model("claude-sonnet-4-5")
                .tools(ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()))
                .memory(
                    Memory.pipeline(
                        transcript, config -> config.transform(PlanTools.transformer(planStore)))));
```

Three lines beyond whatever an agent already has: the grant, the transcript-backed
pipeline memory, and the transformer added to it. Nothing about the loop, `Harness`, or
`Agent` changes — the facility is opt-in entirely at the composition line, and its two
halves meet only at `PlanStore`.

## Replay idempotency

Because `update_plan` always replaces the whole list, a redelivered tool call — the same
at-least-once story every tool lives with — stores the same plan twice rather than
corrupting it. There is no partial-update path to get wrong: the second write lands on
the same rows the first one did.

## The console checklist

`nessy-console`'s `ReplConfig#plan(PlanStore)` hands the REPL the same
`PlanStore` an agent's memory pipeline reads, so the checklist prints in the terminal
itself — not just recalled into the model's own context — at most once per turn, after
that turn's own output, once `conversation.tell` has returned, right before the next
prompt:

```
you> add 2 and 3, then tell me the time

⚙ tool: add requested
⚙ tool: add completed
⚙ tool: clock requested

approve: read the current time
y/n> y

⚙ tool: clock completed

it's 2:00 PM.

  [x] add 2 and 3
  [x] tell the time

you>
```

`chat-cli` and `scout` (`nessy-examples`) both demonstrate the pattern end to end —
`Scout#scout` grants `PlanTools.updatePlan(store)` beside its DeepWiki tool grants, and
`Scout#main` hands the same store to `ReplConfig#plan`, since scout's own
research task is genuinely multi-step (map the wiki, read sections, ask a targeted
question) — the exact long-horizon shape the plan facility fixes.

## When not to grant it

The plan facility earns its keep on multi-step work with real intermediate state to lose
track of. A single-turn tool-caller, or an agent whose every task fits in one exchange,
gains nothing from `update_plan` and pays a small, real cost for it regardless: one more
granted tool the model can call, one more transformer stage evaluated on every recall
(a no-op when the plan is empty, but a stage nonetheless), and one more line of prompt
surface for the model to reason about. Grant it when an agent's own work is long enough
that step 4 might otherwise get lost while it's grinding on step 2 — not by default.

## Where next

- [Memory and the Pipeline](memory-and-the-pipeline.md) — the `ContextHydrator`/`ContextTransformer`
  seams `PlanTools.transformer` is built on.
- [Tools and Grants](tools-and-grants.md) — the grant principle `PlanTools.updatePlan` is
  itself an instance of.
- [Storage](storage.md) — `PlanStore`, one of the six durable doors, and its JDBC backing.
