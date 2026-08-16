# Subagents

A subagent call is a tool call whose work is another agent's conversation.
That single sentence buys the whole feature: everything nessy already
guarantees about tool calls — at-least-once replay, parking, durable
resumption, approval gates, observability — now applies to delegation for
free. The parent's model sees an ordinary tool; the child is an ordinary
`Agent<String>` with its own identity, prompt, grants, and memory.

## Wiring

`AgentTools.subagent` (`org.jwcarman.nessy.spi.subagent`) turns a child agent
into a tool the parent can grant:

```java
public static Tool<Delegation> subagent(Agent<String> child, String description)
public static Tool<Delegation> subagent(Agent<String> child, String description, SubagentLinks links)
```

- **Tool name** is the child's `name()` — the model calls the child by its
  durable identity, the same name a `ToolRegistry` already enforces is
  unique.
- **Input** is `record Delegation(String task)` — one plain-text assignment
  in v1.
- **`describe(input)`** shows the task text, which is what an approval
  prompt displays when an app gates delegation behind
  `UsagePolicy.requireApproval()`.

The two-argument overload works only for a child whose own tools never
park — the moment the child does park, `execute` throws
`IllegalStateException` naming the missing store, because there is nowhere
to remember the parent's own park token. The three-argument overload,
carrying a `SubagentLinks`, is the durable path and the one every real
wiring should use.

Waking the parent once the child settles is the other half, registered once
at harness build time:

```java
public static Consumer<ConversationSettled> completions(
    SubagentLinks links, Parks parks, CallbackRouter router)
```

```java
SubagentLinks links = JdbcSubagentLinks.create(dataSource);
CallbackRouter router = new CallbackRouter();

Harness harness =
    Nessy.harness(provider)
        .store(persistence.store())
        .parks(persistence.parks())
        .listen(ConversationSettled.class, AgentTools.completions(links, persistence.parks(), router))
        .build();

Agent<String> researcher = harness.agent().name("researcher")...build();
Agent<String> writer =
    harness
        .agent()
        .name("writer")
        .tools(
            ToolGrant.grant(
                AgentTools.subagent(researcher, "Delegate research questions to a focused researcher.", links),
                UsagePolicy.allow()))
        .build();

router.register(writer);
router.register(researcher);
```

`CallbackRouter` is a small name-keyed registry — `register(Agent)` and
`route(name)` — so the completions listener can find the live agent
instance a child's settlement should resume. Register a listener
synchronously (`listen`, never `listenAsync`): a subagent's settlement is
exactly the kind of fact an at-least-once transport must be able to retry,
and a swallowed failure here would leave the parent parked forever with
nobody left to nudge it.

## Deterministic child ids and true replay idempotency

The child's `ConversationId` is derived, not generated:
`<parent-conversation-id>/<tool-call-id>`. A redelivered parent turn replays
`execute` with the same call id and lands on the *same* child conversation
rather than spawning a sibling.

That alone would only buy the transcript's ordinary no-stutter fold. What
`AgentTools.subagent` adds is a true short-circuit: before telling the
child anything, `execute` inspects the child's own snapshot, and only a
genuinely fresh or idle child is told at all.

- A **completed** child answers from its last assistant message — no
  re-`tell`.
- A **failed** child answers with a generic already-failed error — no
  re-`tell`.
- A **parked** child answers with the parent token already on file in
  `SubagentLinks`, rather than minting a fresh one (minting again would
  orphan the earlier token's park entry and reopen the completions race
  window on every replay).

Only a conversation with no status yet — or one a redelivery should never
actually observe outside a genuine crash-replay — gets a fresh `tell`.

## The park chain

`execute` maps the child's outcome to one of three parent-side results:

- **Child completes** → `ToolResult.ok(child's final assistant text)`. One
  turn of the parent, however many turns of the child.
- **Child fails** → `ToolResult.error(reason)`, compacted into the parent's
  context so the model can decide what to do about a struggling subordinate.
- **Child parks** — its own HITL gate, its own webhook wait — the parent
  tool parks too: it mints a fresh parent `ParkToken`, saves
  `childId → parentToken` in `SubagentLinks`, and returns
  `Awaited.parked(token)`. Because a subagent call is an ordinary tool call
  from the parent loop's own point of view, a child park becomes a parent
  park automatically — no special-casing anywhere in the loop.

When the child eventually settles, it publishes a `ConversationSettled`
fact. The `completions` listener looks up the parent token in
`SubagentLinks`, reads the routing name off the parent's own park stamp
(`Parks.Park#agentName()` — never off `SubagentLinks`, so there is exactly
one place that name can go stale), and resumes the parent through
`router.route(name)`. The link is forgotten only after that resume returns
without throwing, so a resume that fails (an unknown token, a
`WrongAgentException`) leaves the link in place for whatever redelivery
follows.

## Fresh child per call, continuity via the Notebook

Every delegation opens a new child conversation — the derived id guarantees
it. There is no follow-up-the-same-researcher affordance in v1, on purpose.
Continuity across the parent and child instead comes from sharing a
`SubjectId`: give both agents the same subject and they share a
[Notebook](notebook.md), durable model-gated memory across the whole agent
family with zero new machinery.

## Fan-out is sequential

The loop executes a turn's tool calls in order, so N delegations in one
parent turn run N children sequentially, one at a time. There is no
scatter/gather across several outstanding delegations — parallel fan-out is
a loop-level feature with its own design questions and is not part of this
generation.

## What v1 deliberately omits

- **No child-delta streaming into the parent.** `AgentTools.subagent` relays
  one coarse `ToolContext.progress` ping per child turn boundary (so a long
  delegation never looks frozen), but the child's own text deltas never
  reach the parent's observer. Forwarding them is a later polish with real
  design weight — whose turn is it, exactly?
- **No fan-out coordinator.** The model can already fan out by calling
  several subagent tools (or the same one with different tasks) in one
  turn, sequentially; a "wait for all" combinator is application logic, not
  framework logic.
- **No cross-harness delegation.** Parent and child share one harness and
  one store family in v1. Delegation to an agent running in a different
  process or service is the A2A client's story, not this one.
- **No typed delegation inputs, no child-memory sharing beyond the shared
  Notebook, no depth cap.** A parent-chain id prefix makes accidental
  cycles visible rather than silently guarded against.

## Where next

- [Parks and Callbacks](parks-and-callbacks.md) — the park tokens, the
  agent-name stamp, and the doors a subagent's own park rides.
- [The Notebook](notebook.md) — the shared-subject continuity story a
  parent and its children use instead of a follow-up affordance.
- [Nessy and the 12-Factor Agents](twelve-factor-agents.md) — factor 10,
  small focused agents, made literal by delegation.
