# Parks and Callbacks

A conversation is a plain serializable record and a durable inbox, so it can run on
**any node**, be driven by whatever process gets to it next, and pick up a wait that
started days ago and a process ago. When a tool (or an approver) must outlive the
process, its `execute` returns `Awaited.parked(token)` instead of a ready result — the
loop persists the session and moves on. The fact arrives whenever a callback door
delivers it, from any process, however much later.

## Park tokens and the agent stamp

`ParkToken` is an opaque correlation id — external systems hold it, embed it in URLs and
AMQP headers. Identity is not encoded into the token string itself; instead, the park
record it names carries a stamp:

```java
record Park(ConversationId conversationId, ParkToken token, ToolCall call, String agentName) { ... }
```

`agentName` is the name of the agent whose loop parked the call — the same
`.name(...)` every `AgentBuilder` requires at `build()`. Every callback door verifies
this stamp against the agent it's called on, **before** appending or driving anything.
A mismatch throws `WrongAgentException`, naming both agents:

```
park <token> was minted by agent 'order-desk'; this agent is 'orderdesk' —
an agent's name is a durable wire contract; redeploy under 'order-desk' to
drain its parks
```

!!! warning "An agent's name is a durable wire contract"
    Renaming an agent with parks in flight orphans them — recovery is redeploying under
    the old name. It deserves the same care as a queue name or a callback URL, not a
    cosmetic label. Two agents that happen to declare the same name share one identity
    and can serve each other's parks — that's an application contract violation, not
    something a stateless harness can detect.

`Parks` is the registry this all lives in — a durable, keep-forever record of every wait
a process has ever registered, so a callback arriving days later can still translate its
token into a conversation and a call. Registry entries **survive resolution by design**:
they are the durable record that a token once named a particular wait, not a single-use
claim, so a settled wait's token still reads back present — useful for an ops surface
describing a parked (or once-parked) conversation before or after anyone acts on it.

## The doors

Every one of these lives on `Agent<I>`, not on `Harness` — the token is the whole
correlation contract, and transport home (a webhook, a queue, a cron poll) is the tool
author's business.

| Door | What it does |
|---|---|
| `agent.resume(token, resolution[, observer])` | Answers a parked call by token: appends the resolution to the conversation's inbox and drives, exactly the way `tell` does. Appending always succeeds — a resolution is never refused for arriving mid-turn or while parked. |
| `agent.approve(token[, observer])` | Sugar over `resume` for the common HITL verdict: an unconditional allow. No logic of its own. |
| `agent.deny(token, reason[, observer])` | Sugar over `resume` for the HITL refusal, carrying `reason` back to the model. |
| `agent.progress(token, message)` | `resume`'s non-terminal sibling: never consumes the token, only narrates a still-running tool's progress as a `ToolProgress` event to whoever is listening. Returns `false`, quietly, for an unknown token or one whose call has already settled. |
| `agent.peek(token)` | Reads a park without consuming it — an `Optional<ParkedCall>`, empty only for a token this registry never minted. Useful for an ops surface deciding how to resolve a wait before acting on it. |

`resume`, `approve`, and `deny` each come in a plain form and one that takes a
`TurnObserver` to watch the drive that follows; unwatched calls default to
`TurnObserver.noop()`. All five doors throw `WrongAgentException` on a stamp mismatch;
`resume`/`approve`/`deny` also throw `UnknownParkTokenException` for a token the registry
has never seen at all — `peek` and `progress` treat an unknown token as "nothing to
report" instead, since neither is asserting a resolution happened.

```java
RunOutcome outcome = agent.resume(token, new ToolResolution.Completed(result));
```

!!! warning "Redelivery is quiet-drain, not exactly-once"
    Every real transport redelivers at least once. A redelivered `resume` translates the
    token again and appends another resolution entry, and the fold's own
    is-this-call-still-outstanding check drains it quietly rather than replaying the tool —
    the drive simply reads whatever the first delivery already produced. That protection is
    **serial, not concurrent**: it's the fold picking a winner among entries already
    appended. Two deliveries of the same token driven concurrently can both observe the
    call as still outstanding and both invoke the tool before the fence settles on which
    fold wins — the same at-least-once exposure `Tool#execute` documents. A tool that
    cannot be safely re-run makes itself idempotent, or parks and lets its remote side
    deduplicate by token.

## Two write disciplines

A version-fenced control block (one writer wins; a stale writer reloads and re-drives,
never overwrites) carries the conversation's status, while the inbox — which the fence
doesn't gate — absorbs every `tell` and every resolution concurrently, so a chatty world
can never fence-fail a working driver. `ConversationStatus.PARKED` joins the other
statuses: a parked conversation self-describes to any ops surface — no driver, no lease,
durable patience.

## Where next

- [The Durable Loop](durable-loop.md) — the fold, the effects, and why at-least-once
  execution shapes every door here.
- [Tools and Grants](tools-and-grants.md) — how a tool mints the token these doors answer.
- [Storage](storage.md) — `Parks`, the SPI backing this registry, and what `nessy-jdbc`
  adds for restart survival.
