# Two Doors

Nessy has two ways into an agent: `DirectHarness<I, O>` and
`QueuedHarness<I>`. They are peers. Neither wraps the other, and neither is
a convenience shortcut for the other — they make opposite bargains about
who is waiting, and that one question is what decides which door your
application needs.

## The bargain each makes

`DirectHarness.ask(agent, input)` runs a turn on the calling thread and
hands back an `Outcome<O>` before it returns. The caller is standing there
holding the answer. That gives `ask` two freedoms a queued call does not
have: it can refuse to start at all, and it can answer in a typed shape
`O` rather than always prose.

```java
Outcome<Reply> outcome = harness.ask(agentId, "what's the weather?");
```

`QueuedHarness.tell(agent, input)` returns nothing. Telling an agent
something cannot fail and cannot be refused — whatever arrives goes into
that agent's backlog, and the agent runs it at its own pace. By the time
the turn actually runs, whoever called `tell` has moved on; there is
nothing for the method to hand back.

```java
harness.tell(agentId, "the porch light came on");
```

Neither door is layered on the other. A queued harness does not call a
direct one internally, and a direct harness has no queue behind it — both
run the same fold (see below), but each drives it in its own shape.

## What each bargain buys and costs

**Who holds the thread.** `ask` occupies the caller's thread for the whole
turn — however long the model takes, and however many tool calls run
before it answers. `tell` occupies nothing: the caller is done as soon as
the input is written down, and a background dispatcher drives the turn
later.

**What happens if the process dies mid-work.** A process that dies while
`ask` is running loses the caller's chance to observe the answer, but not
the turn itself — the agent's state is exactly what the last committed step
left it, and a caller (or a new one) can ask again once the process comes
back. A process that dies while a queued agent's turn is in flight loses
nothing durable either, for the same reason: the story is one committed
step behind the crash, never mid-write.

**Whether backpressure is visible.** `ask` makes it visible immediately: a
second caller arriving while a turn is in flight is told `Outcome.Busy`
rather than being made to wait, because the caller is standing there and
would rather know than block. `tell` makes backpressure invisible by
design — there is no second caller to notice anything, because arrivals
while the agent is busy simply wait in its backlog until it is idle again.
That backlog is what a direct call never needs and a queued one cannot do
without: nothing on the direct door holds work for later, because nobody
who isn't waiting sent it.

**What each door can park and retry.** Only the queued door can defer a
call or retry work. On the direct door a tool or approver that returns
`Awaited.deferred()` produces a failed call, because a caller already
waiting has nowhere for a late answer to arrive, and no work is ever
attempted twice. See [Durable Computation](durable-computation.md).

**What the caller can learn about failure.** `ask` gets the reason
directly, as `Outcome.Failed(reason)` or `Outcome.Refused(category)`. A
`tell` caller gets nothing back at all — not even a promise to poll —
because it has already gone by the time the turn resolves. See
[Outcomes](outcomes.md) for the shapes and
[Narration](../guides/narration.md) for how a queued caller learns what
happened by watching instead of asking.

## What is shared, and what is not

Both doors fold the same commands into the same events. `StartTurn`,
`CompleteInference`, `CompleteApproval`, `CompleteToolCall` and `Terminate`
are the whole of `AgentCommand`, whichever door presents one, and the same
`AgentState` accepts them and produces the same `AgentEvent` stream either
way. A turn started through one door leaves exactly the same trail a turn
started through the other would.

They exclude each other the same way, too. Both doors lock around
`Locks.TURN` for the agent they are working on — the direct door's `ask`
and `terminate`, and the queued door's `tell`, `terminate` and its effect
completions, all take the same lock kind, so a turn taken through one door
genuinely excludes a turn started through the other over the same agent.
There is one exclusion mechanism, not two that happen to agree.

What is not shared is the backlog. Only the queued door has one — a
`Backlogs<I>` the `QueuedBackend` hands out, one per agent, holding
whatever arrived while the agent was busy until it falls idle. The direct
door has nothing to hold: its caller is standing there, so a second `ask`
arriving mid-turn is refused on the spot rather than queued, and there is
nothing left over to drain once the first caller's turn ends. A backlog
exists exactly where input can arrive while nobody is watching for the
chance to be told no.

## How failure reaches the caller

On the direct door, `ask` hands back the reason inline: `Outcome.Failed`
carries the provider adapter's account of what went wrong, and
`Outcome.Refused` carries the model's own category for declining. The
caller has it before its next line of code runs.

On the queued door, `tell` returns nothing, so there is no inline path at
all. The only place the reason is said is narration: `Narration.TurnFailed`
carries the same text the direct door would have returned, and
`Narration.TurnRefused` carries the same category — but only to whoever
is listening when it is announced. A queued agent that fails with nobody
watching still recorded the failure in its event stream, as an
`InferenceFailed` fact; the reason just was not narrated to anyone in
particular. See
[Narration](../guides/narration.md) for the listener side of this.

## The backends mirror the split

`DirectBackend` and `QueuedBackend`, in `nessy-backend-spi`, are the two
doors' needs written down as interfaces. They are deliberately not one
interface with the queued door as a superset: `DirectBackend` needs
`AgentEvents`, `Payloads` and `Locks` — enough to fold a turn and lock
around it — plus `Chapters` (an agent's closed chapters) and `Leases`
(background work that must run once). `QueuedBackend` needs those same five,
plus `Agents` (whether an agent has been told to end), `Effects` (the outbox
a queued turn's work is dispatched through) and `backlogs(TypeRef<I>)` (the
waiting-input store above). Nothing takes a `DirectBackend` hoping to be handed a queued one:
a backend that can do more than a direct door needs is not a direct
backend that happens to also work, it is a different kind of thing, and
the two interfaces say so by not extending each other.

## Choosing a door

The question is whether anybody is waiting. A request-response endpoint, a
CLI, anything with a caller who needs the answer before it can do its next
thing: `DirectHarness`. A webhook, a queue consumer, a scheduled sweep,
anything that fires an agent and moves on: `QueuedHarness`. An application
is free to expose both over the same agent type — a chat UI that also
reacts to background events — and the shared fold and shared lock are what
make that safe rather than a hazard to reason about by hand.

## Where next

- [Turns](turns.md), what runs behind either door
- [Outcomes](outcomes.md), the shapes `ask` hands back
- [The Harness](../guides/harness.md), building a harness from either door's factory
