# Nessy Example: Newsroom

The subagent generation's own demo: a `writer` delegates research to a
`researcher` it defines right inside its own builder — a tool call whose work
is another agent's own conversation, nothing more exotic than that. When the
researcher's one gated tool, `ask_question`, needs a human answer, it parks;
because a subagent call is an ordinary tool call from the writer's own loop's
point of view, the writer's delegation call parks right alongside it. Both
parks are durable (a real Postgres database, via `nessy-jdbc`), so killing
this process mid-delegation and restarting it picks the conversation up
exactly where it left off.

## The two agents

- **`writer`** — the one console REPL. Delegates research to `researcher`,
  declared inline via `.subagent(sub -> ...)` (design of record 2026-08-16
  §1) — the delegation tool that grants is `UsagePolicy.allow()` by default,
  since the writer never itself asks a human anything. Also holds
  `update_plan` and the notebook's `remember`/`recall`/`forget` triad, and
  carries the one `Approver.parkAll()` both agents share (see below).
- **`researcher`** — never talks to the console directly. Two tools:
  `search_notes` (a canned, offline lookup over a few hardcoded topics — this
  demo's interest is the delegation mechanics, not real research, so there is
  no network call and no MCP dependency anywhere in this module) and
  `ask_question`, granted `UsagePolicy.requireApproval()` — every call to it
  parks, unconditionally, under the writer's own `Approver.parkAll()`,
  inherited by construction (design of record 2026-08-16 §3: the approver is
  not a subagent config knob, it cascades down from whichever agent declared
  it).

Both agents resolve to the same fixed `SubjectId` and read from the same
`Notebook` (spec §9's continuity ruling): a note the writer keeps is visible
in the researcher's own context on its very next turn, and vice versa,
because both agents' `AgentMemory` pipelines carry the same
`NotebookTools.transformer` over the same notebook and resolver.

**Fan-out here is sequential, not parallel** (spec §9): the writer waits on
one `researcher` call at a time before it can act on the answer or delegate
again. There is no scatter/gather across several outstanding delegations in
this demo.

## Building the agents

The whole story (design of record 2026-08-16 §1): the writer defines the
researcher right inside its own `.subagent(...)` call. No `AgentTools`, no
`CallbackRouter`, no manual `SubagentLinks` wiring, no listener registration —
building the writer builds the researcher, grants the delegation tool, wires
the links store from the harness's own store family, and registers the
completion wiring internally.

```java
Agent<String> writer =
    harness.agent(
        a ->
            a.name("writer")
                .systemPrompt(WRITER_SYSTEM_PROMPT)
                .approver(Approver.parkAll())
                .tools(/* update_plan, remember/recall/forget */)
                .subagent(
                    sub ->
                        sub.name("researcher")
                            .description("Delegates a research task to the researcher subagent...")
                            .systemPrompt(RESEARCHER_SYSTEM_PROMPT)
                            .tools(
                                ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
                                ToolGrant.grant(
                                    new AskQuestionTool(pendingAnswers),
                                    UsagePolicy.requireApproval()))));

Subagent researcher = writer.subagent("researcher");
```

The writer's `Approver.parkAll()` is declared once and cascades down to the
researcher by construction (design of record 2026-08-16 §3: the approver is
inherited, not a subagent config knob) — it's what makes `ask_question` park
unconditionally. The REPL reaches the researcher's own doors —
`approve`/`deny`/`snapshot` — through that `Subagent researcher` handle, never
through a held `Agent` reference.

## The park chain

1. The writer's model calls the `researcher` tool with a task. The
   internally-wired delegation tool drives one turn of a dedicated child
   conversation (keyed off the writer's own conversation id and tool-call id,
   so a redelivered call lands on the same child rather than spawning a
   sibling).
2. If the researcher's model calls `ask_question`, the gate consults the
   inherited `Approver.parkAll()` before the tool ever runs — the child
   conversation parks, unconditionally, on a fresh token.
3. Back in the writer's own tool call, the child came back `PARKED`, not
   settled — the delegation tool mints its *own* park token, saves the
   `child → parent token` link in the harness's own `SubagentLinks`, and
   parks the writer's turn too (the re-park fix, design of record 2026-08-16
   §4 — two waits, not a wedge). The console's `tell()` returns a
   `RunOutcome.Parked`.
4. The REPL notices the writer is parked, walks down to the researcher's own
   pending call — through `writer.subagent("researcher").snapshot(childId)`,
   deriving the child conversation id the same way step 1 derived it — prints
   the question, and reads an approve/deny decision.
5. Approving records the operator's free-text answer (`PendingAnswers`,
   since `Approver`'s own vocabulary is a bare yes/no, with no channel for a
   human's prose to ride back into the call it approved) and drives
   `researcher.approve(token)`, through that same `Subagent` handle. Denying
   drives `researcher.deny(token, reason)`.
6. Either way, driving the researcher's own conversation to settlement
   publishes a `ConversationSettled` fact. The harness's internally-wired
   completions listener — registered once, synchronously, at build time — is
   exactly the listener that fires for it: it looks up the parent token in
   `SubagentLinks`, reads the park's own `agentName` stamp (`"writer"`) off
   `Parks` to find which agent to route through, and resumes the writer's
   own park — all inside the same call that approved or denied the
   question. The REPL never resumes the writer directly.

## Running it

```bash
# 1. Start Postgres (start-only lifecycle, same as dispatcher's own compose file).
docker compose -f nessy-examples/newsroom/docker-compose.yml up -d
```

```bash
# 2. Run the REPL. Any provider EnvModelProviders recognizes works.
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/newsroom exec:java
```

```
newsroom — the writer delegates research to the researcher. Type exit or quit to leave.
writer> write two sentences about something you learned from the researcher about octopuses

⚙ tool: researcher requested
⚙ tool: researcher parked (7f3a1c9e-...)

researcher asks: Should I focus on their hearts, their blood, or something else about octopuses?
answer it? y/n> y
your answer> their hearts — that's the surprising one

Octopuses have three hearts: two pump blood to the gills, and the third
pumps it to the rest of the body. It's one of the odder adaptations in the
animal kingdom.
```

The `⚙ tool: researcher …` lines are `ConsoleRenderer`'s default rendering of
the writer's own turn — the delegation call being requested, then parking.
The researcher's own inner tool calls (`search_notes`, `ask_question`) run in
a separate child conversation and narrate only as `ToolProgress` pings back
through the writer's `ToolContext`; `ConsoleRenderer`'s default observer
doesn't render those, so they're silent here — the question text itself is
what the REPL prints once it walks down to the researcher's own pending
call.

## The restart scene

The reason `JdbcSubagentLinks` and `JdbcParks` both earn their keep here:
kill the process the moment it prints `researcher asks: …` — before
answering — and restart it. The writer's conversation lives at one fixed,
well-known id (not a fresh one per run), so the new process reattaches to the
exact same parked delegation:

```
$ ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/newsroom exec:java
newsroom — the writer delegates research to the researcher. Type exit or quit to leave.
resuming a delegation parked before this process last exited...

researcher asks: Should I focus on their hearts, their blood, or something else about octopuses?
answer it? y/n> y
your answer> their hearts — that's the surprising one

Octopuses have three hearts: ...
```

Nothing about the park, the delegation link, or the notebook was ever only in
the first process's memory — the second process never saw the original
`write ...` turn, and still finishes it correctly.

## What this example deliberately omits (spec §6)

No parallel fan-out — one outstanding delegation at a time, by design (see
above), not a scatter/gather scheduler. No deep subagent chains — the
researcher never itself delegates to a third agent, though nothing about the
mechanism stops it (the lexical nesting a further `.subagent(...)` inside the
researcher's own config would build). No cross-process delegation — writer
and researcher share one JVM, one harness, one internal name registry; a
subagent living in a different process or service is a later generation's
story. No retry policy beyond what the harness already gives every tool
call — a redelivered delegation call lands on the same child conversation
rather than spawning a sibling (the internally-wired delegation tool's own
idempotency), but this demo never exercises an actual at-least-once
transport, only the in-process loop.
