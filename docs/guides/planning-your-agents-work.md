# Planning Your Agent's Work

Long-horizon work has a specific failure mode: an agent grinding on step 2
forgets step 4. The plan facility fixes it by giving the model a task list it
writes itself, through a tool, that then rides every subsequent recall as
ambient context. See [Planning](../concepts/planning.md) for the full
contract; this guide is the wiring, end to end, drawn from two examples that
ship it.

## The wiring

Three additions to an agent that already exists: grant the tool, add the
transformer to the memory pipeline, and (for a console app) hand the same
store to the REPL.

```java
PlanStore planStore = PlanStore.inMemory();
Transcript transcript = Transcript.inMemory();

Agent<String> agent =
    harness
        .agent()
        .name("scout")
        .tools(
            ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()))
        .memory(Memory.pipeline(transcript).transform(PlanTools.transformer(planStore)).build())
        .build();
```

`PlanTools.updatePlan(planStore)` is the write half — the model calls
`update_plan` with its whole task list every time anything changes.
`PlanTools.transformer(planStore)` is the read half — a `ContextTransformer`
that injects the current checklist as one user-role message, at the tail of
context, on every recall. They meet only at `PlanStore`; nothing about the
loop or `Agent` itself changes.

`nessy-examples/scout`'s `Scout#scout` is this exact wiring, plus the
DeepWiki grants from [MCP Clients](mcp-clients.md):

```java
Agent<String> agent =
    harness
        .agent()
        .name("scout")
        .model(model)
        .systemPrompt(SYSTEM_PROMPT)
        .tools(
            ToolGrant.grant(toolbox.tool("read_wiki_structure"), UsagePolicy.allow()),
            ToolGrant.grant(toolbox.tool("read_wiki_contents"), UsagePolicy.allow()),
            ToolGrant.grant(toolbox.tool("ask_question"), UsagePolicy.requireApproval()),
            ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()))
        .memory(Memory.pipeline(transcript).transform(PlanTools.transformer(planStore)).build())
        .approver(approver)
        .build();
```

## Wiring the console checklist too

A console app can print the checklist itself — not just let the model recall
it — by handing the same `PlanStore` to `ConsoleRepl.Builder#plan`:

```java
ConsoleRepl.of(agent)
    .banner("scout — ask about any public GitHub repo")
    .prompt("you> ")
    .plan(planStore)
    .run();
```

The store the model writes through `update_plan` is the exact store the REPL
reads back — the grant principle applied to the console's own opt-in.

## Prompt guidance

Tell the model, in its system prompt, that it has this tool and when to use
it. Scout's system prompt ends with one sentence: *"For multi-step research,
maintain a task list with `update_plan`."* That's the whole nudge needed —
the tool's own description carries the contract (send the complete list
every time, keep at most one task `IN_PROGRESS`, an empty list clears the
plan).

## What good plan behavior looks like

Research is genuinely multi-step: map the wiki, read a section, then ask a
targeted question. That's the exact long-horizon shape the plan facility
exists for, and it's why Scout adopts it as its showcase. One live turn,
Scout mapping a repository's wiki, reading a section, and asking DeepWiki a
question — the checklist renders **once**, at the very end, after
`conversation.tell` has returned:

```
you> what does jwcarman/nessy's reducer do, and why does it live in one method?

⚙ tool: read_wiki_structure requested

⚙ tool: read_wiki_structure completed

⚙ tool: read_wiki_contents requested

⚙ tool: read_wiki_contents completed

⚙ tool: ask_question requested

approve: ask_question {"repoName":"jwcarman/nessy","question":"why does the reducer live in one method?"}
y/n> y

⚙ tool: ask_question completed

The reducer lives in one method for locality — [...]

  [x] Read the wiki structure for jwcarman/nessy
  [x] Read the reducer's wiki section
  [x] Ask DeepWiki why the reducer lives in one method

you>
```

Everything above the checklist is ordinary turn narration — tool activity,
the approval gate, the settled answer. The checklist itself never
interleaves between tool calls; it prints exactly once, after the turn's own
output, right before the next prompt. A turn whose plan didn't change, or
that never wrote one, prints no checklist at all.

## When not to grant it

A single-turn tool-caller gains nothing from `update_plan` and still pays a
small, real cost: one more tool the model can call, one more transformer
stage evaluated on every recall, one more line of prompt surface to reason
about. Grant it when an agent's own work is long enough that step 4 might
otherwise get lost while it's grinding on step 2 — not by default.

## Where next

- [Planning](../concepts/planning.md) — the full contract: the tool, the
  store, the injected block, replay idempotency.
- [MCP Clients](mcp-clients.md) — Scout's other grants, imported from
  DeepWiki, wired beside `update_plan`.
- [Console Apps](console-apps.md) — `ConsoleRepl.Builder#plan`, the REPL's
  half of the same checklist.
