# Subagents — an agent launches agents

**Date:** 2026-08-15
**Status:** RATIFIED in conversation (owner, 2026-08-15) — all open questions answered;
see §0 for the rulings and §9 for the second-round decisions.
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`; builds
directly on the agent-callback-doors design (park stamps, `WrongAgentException`, the
name-keyed `CallbackRouter` sanctioned in its §9).

## 0. Open questions — ANSWERED (owner rulings)

1. **Tool-per-child vs one generic tool.** This draft chooses one tool per child agent
   (tool name = the child's agent name, description written by the app). The alternative —
   one `delegate(agent, task)` tool — centralizes but blurs the grant principle and gives
   the model a weaker affordance. **RULED: tool per child.**
2. **The link store vs deterministic tokens.** This draft adds a tiny `SubagentLinks` store
   (child conversation id → parent park token). The alternative — deriving the parent's park
   token deterministically from the child id — needs no storage but makes tokens guessable,
   and tokens double as capability handles at the callback doors. **RULED: the store.**
3. **Depth guard.** This draft ships none (wiring makes accidental cycles hard; a
   parent-chain id prefix makes them visible). **RULED: no guard in v1.**
4. **Module placement.** Core (`spi.subagent`), zero dependencies, same argument as
   plan/notebook. **RULED: core.**

## 1. The idea

A subagent call is a **tool call whose work is another agent's conversation.** That single
sentence buys the whole feature, because everything nessy guarantees about tool calls —
at-least-once replay, parking, durable resumption, approval gates, observability — now
applies to delegation for free. No new kernel concept: the parent's model sees a tool; the
child is an ordinary `Agent` with its own identity, prompt, grants, and memory (12-factor
"small, focused agents" made literal).

```java
Agent<String> researcher = harness.agent().name("researcher")...build();
Agent<String> writer = harness.agent()
    .name("writer")
    .tools(ToolGrant.grant(
        AgentTools.subagent(researcher, "Delegate research questions to a focused researcher."),
        UsagePolicy.allow()))
    .build();
```

## 2. AgentTools.subagent (spi.subagent)

```java
public static Tool<Delegation> subagent(Agent<String> child, String description)
public static Tool<Delegation> subagent(Agent<String> child, String description, SubagentLinks links)
```

- **Tool name:** the child's `name()` — the durable identity doubling as the model-facing
  verb ("call the researcher"). Uniqueness within a registry is already enforced by
  `ToolRegistry`.
- **Input:** `record Delegation(String task)` — one plain-text assignment, v1. Typed inputs
  ride the existing `InputRenderer` machinery in a later generation if wanted.
- **`describe(input)`** shows the task text — this is what an approval prompt displays when
  an app gates delegation with `requireApproval()` (deliberately supported: "may this agent
  spend tokens on that agent" is a legitimate human gate).

## 3. Execution — the child conversation

- **Child `ConversationId` is deterministic:** `<parent-conversation-id>/<tool-call-id>`.
  A re-driven parent turn replays `execute` with the same call id, lands on the SAME child
  conversation, and the child's fold absorbs the duplicate telling by its ordinary
  no-stutter rule. Replay-safe delegation with zero bookkeeping — the same trick the plan
  facility used, applied to identity.
- `execute` runs `child.converse(childId).tell(task)` synchronously on the tool-executor
  thread (virtual threads make the block cheap). Three outcomes:
  - **Child completes** → `ToolResult.ok(child's final assistant text)`. One turn of the
    parent, however many turns of the child.
  - **Child fails** → `ToolResult.error(reason)` — compact errors into the parent's context
    (factor 9); the parent's model decides what to do about a struggling subordinate.
  - **Child parks** (its own HITL gate, its own webhook wait) → the parent tool parks too:
    mint a parent `ParkToken`, record `childId → parentToken` in `SubagentLinks`, return
    `Awaited.parked(token)`. The delegation is now durable — both conversations sleep in
    their stores, survive restarts, and wake in order.

## 4. Completion routing — waking the parent

The wiring half, shipped as one factory:

```java
public static ListenerRegistration completions(SubagentLinks links, CallbackRouter router)
```

Registered at build time on the harness (or the child agent), it listens for the child's
terminal `ConversationEvent` (run completed/failed), looks up `links.find(childId)`, and if
a parent is waiting: `router.route(parentAgentName).resume(parentToken, childOutcomeText)`
(or `deny`-shaped failure text on child failure), then `links.forget(childId)`. The
`CallbackRouter` is the name-keyed registry the callback-doors spec §9 sanctioned and never
built — this generation builds it (~20 lines: `register(Agent)`, `route(name)`, throwing
with the park stamp's own vocabulary when no agent claims the name).

At-least-once discipline: the listener may fire more than once for one completion; `resume`
on an already-resolved token is the doors' existing no-op/`WrongAgentException` surface —
document, don't invent.

## 5. SubagentLinks (spi.subagent)

```java
public interface SubagentLinks {
  Optional<ParkToken> find(ConversationId child);
  void save(ConversationId child, ParkToken parentToken, String parentAgentName);
  void forget(ConversationId child);
  static SubagentLinks inMemory();
}
```

(Exact record shape settled at planning; the triple is child id → parent token + parent
agent name, since the router routes by name.) LWW, idempotent forget, same in-memory
default + `JdbcSubagentLinks` + TCK contract pattern as every store before it — one table,
`(child_conversation_id)` primary key. Concurrent writers are not real here (one child has
one parent), but replay rewrites identically, the familiar argument.

## 6. What this deliberately does not do (v1)

- **No streaming child progress into the parent** — the child's `ToolContext.progress`
  channel and turn observers exist; forwarding child deltas to the parent's observer is a
  later polish with real design weight (whose turn is it?).
- **No fan-out coordinator** — the model can already fan out by calling several subagent
  tools (or the same one with different tasks) in one turn; the loop's existing parallel
  tool execution and per-call parks handle it. A "wait for all" combinator is app logic.
- **No cross-harness delegation** — both agents share a harness in v1 (one store family).
  The A2A client generation covers the remote case; this spec is deliberately its local
  mirror so the two read as one family later.
- **No typed delegation inputs, no child-memory sharing, no depth cap.**

## 7. Demo + docs

- **Example:** `nessy-examples/newsroom` (working name, owner may rename): a `writer` agent
  delegating to a `researcher` (Scout-style DeepWiki grants) — terminal REPL via
  nessy-console, the plan checklist showing the writer's plan while the researcher works.
  The park-path is exhibited with `requireApproval()` on the researcher's `ask_question`:
  the CHILD parks for approval, therefore the PARENT parks too — restart the process
  mid-delegation and finish it after, in one README transcript.
- **Docs:** `docs/concepts/subagents.md` rides the generation (site stays truthful), plus a
  row in the 12-factor page when that lands (factor 10).

## 8. Testing

House rules throughout. Core: deterministic child-id derivation (replay executes into the
same child conversation — proven by telling twice and asserting one child fold);
complete/fail/park outcome mapping; links round-trip + idempotent forget; the completions
listener resuming through a real (in-memory) router with the park machinery, including the
duplicate-completion replay; `WrongAgentException` surfaces when the wrong agent claims a
parent name. JDBC: links table, five dialects, TCK contract, vendor nests, offline
race/rollback rigs per the established patterns. Example: ScriptedModelProvider on both
agents driving the full park-and-wake chain offline.

## 9. Second-round rulings (owner, 2026-08-15)

- **Fresh child per call, continuity via the Notebook.** Each delegation opens a new child
  conversation (`parent-id/call-id` guarantees it). There is no follow-up-the-same-researcher
  affordance in v1, on purpose: the continuity story is a shared `SubjectId` — give parent
  and child the same subject and they share a Notebook, durable model-gated memory across the
  agent family with zero new machinery. The newsroom demo wires exactly that.
- **Sequential fan-out, stated plainly.** The loop executes a turn's tool calls in order;
  N delegations in one turn run N children sequentially. Parallel fan-out is a loop-level
  feature deserving its own generation and is out of scope — the docs and the demo README say
  so rather than implying parallelism.
- **Coarse progress pings, v1.** The subagent tool relays one `ToolContext.progress` event
  per child turn boundary ("researcher: turn 3") so long delegations never look frozen in an
  observer or console. Delta-streaming of child output remains deferred (§6 unchanged).
- **Demo confirmed:** `nessy-examples/newsroom`, writer + researcher, with the researcher's
  `ask_question` approval gate exercising the child-parks-therefore-parent-parks chain,
  restartable mid-delegation.
