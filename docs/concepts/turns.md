# Turns

A turn is one pass of input becoming an answer: whatever inference and
tool calls it took to get from something arriving to the turn being over.
An agent runs exactly one at a time, and everything about how Nessy
identifies, completes and narrates work follows from that one rule.

## One at a time, enforced twice

Only an idle agent can start a turn. `AgentState.Idle` is the one arm of
the state machine that accepts `AgentCommand.StartTurn`; a busy agent
accepts only the completion of work it is already waiting on and ignores
everything else. That is the state machine's half of the rule, and it
holds even if two callers both manage to ask at once — the second one's
command simply has nothing to land on.

The other half is what stops two callers from racing to be the one who
gets to ask in the first place. Both doors lock around `Locks.TURN` for
the agent before they read its state: the direct door's `ask` and
`terminate`, and the queued door's `tell`, `terminate` and its effect
completions. Whoever holds the lock reads idle-or-not honestly and, if
idle, starts the turn inside the same locked step; whoever arrives a
moment later reads busy and is turned away — `AskOutcome.Busy` on the direct
door, held in the backlog on the queued one. See
[Two Doors](two-doors.md) for what each door does with that answer.

The lock is held only for the short, local step — reading state, folding
a command, writing events — never across a model call or a tool call.
Nothing in Nessy holds a lock across slow work; see
[Durable Computation](durable-computation.md) for what happens instead.

## `TurnId`

A `TurnId` names a turn, and it does so without minting an identifier of
its own: it *is* the `Seq` of the input that opened the turn, wrapped in
its own type. `TurnId.openedAt()` gives back that `Seq` directly. The
first entry of a turn names it, and every entry after it in the story
points back to that same `TurnId`.

It is a distinct type from `Seq` on purpose, even though the two share a
number line: one is a position in the story, the other is a grouping of
positions, and a bare `long` could not tell a reader which was meant.
Crossing from one to the other has exactly one name, `Seq.opensTurn()`,
so the conversion is never silent.

## Why completion carries the turn

`AgentCommand.CompleteInference`, `CompleteApproval` and `CompleteToolCall`
each carry the `TurnId` they complete. `StartTurn` and `Terminate` do not,
because neither one answers anything — one opens a turn, the other terminates an
agent between turns.

That `TurnId` is not decoration. Delivery of a completion is
at-least-once, and a door releases its lock between steps, so an answer
for a turn that has already ended can still arrive while the agent is busy
on the next one — a slow tool call from turn 4 reporting back after turn 5
has already started. Without the turn on the command, the fold would have
no way to tell that apart from a legitimate answer to the turn actually in
flight; it would stamp the late answer onto whatever turn happens to be
running, and the caller driving turn 5 would be handed an answer to a
question it never asked.

With the turn on the command, `AgentState`'s busy arms check it before
doing anything else: `CompleteInference` is ignored when `!done.turn().equals(turn)`,
and the same guard sits on `CompleteApproval` and `CompleteToolCall`. A
late reply from an abandoned turn is discarded rather than folded in
anywhere. `CompleteApproval` and `CompleteToolCall` also carry the sequence
number of the request that asked for the call, and an answer for another
request of the same turn is ignored the same way, because a call id can
repeat across two requests of one turn. Nothing about this is exceptional — it is silent by design, the
same way a duplicate at-least-once delivery of anything else is silent.

## The lifecycle a watcher sees

`Narration` is what a listener attached to a harness actually receives —
not the durable events themselves, but an announcement of what just
happened or is about to. A turn's arms, in the order a watcher sees them:

- `Narration.TurnStarted(TurnId turn, String label, Instant arrivedAt)` — a turn opened on an input; the label says what started it and `arrivedAt` is when the input reached the harness.
- `Narration.Thinking()` — the model is about to be asked, narrated before
  the call so a watcher can show waiting.
- One of four ways the turn ends, each a `Narration.TurnEnding`:
  - `Narration.Answered(TurnId turn, boolean truncated, Usage usage)` — the
    turn produced an answer. `truncated` is true when the model was cut off
    at its output limit and the answer stops short. Not the answer itself; the direct door already returned it, and
    a queued watcher reads it from the story or has already seen it delta
    by delta.
  - `Narration.TurnFailed(TurnId turn, FailureKind kind, String reason,
    Usage usage)` — a model call failed and the turn ended without an
    answer. Carries the reason because, on the queued door, `tell` returns
    only a `TellOutcome`: a watcher hears it live, and `AgentStories.replay` reads it
    afterwards.
  - `Narration.TurnRefused(TurnId turn, String category, Usage usage)` —
    the model declined, and would decline again. Carries the provider's own
    word for why.
  - `Narration.TurnStopped(TurnId turn, String reason)` — a policy stopped
    the turn. No model call failed, so it carries no usage.

  A turn ends in exactly one of them. `onTurnEnding` hears all four, which
  is the one handler to write when what matters is that the story grew by
  a turn, not how.

A turn that calls tools narrates more along the way — `ActionsRequested`,
`CallApproved` or `CallDenied`, `CallFinished` or `CallFailed` — but
`TurnStarted` and one `TurnEnding` event are the bracket every turn has.

## What a turn leaves behind

The facts a turn produces are `AgentEvent`s, and every one but
`Terminated` carries the `TurnId` it belongs to: `TurnStarted`,
`InferenceAnswered`, `InferenceRefused`, `InferenceFailed`,
`ActionsRequested`, `ToolApproved`, `ToolDenied`, `ToolSucceeded` and
`ToolFailed`. `Terminated` carries none, because terminating an agent sits
between turns rather than inside one.

Replaying that stream in order, from `AgentState.idle`, rebuilds the exact
state a live agent would be in — which turn (if any) is in flight, what it
is still waiting on. That is reconstitution: a loop over events, not a
separate concept from the fold itself. See [Events](events.md) for the
store those events live in and what replaying them actually looks like.

## Where next

- [Two Doors](two-doors.md), the two ways a turn gets started
- [Events](events.md), the durable store a turn's facts are written to
- [Outcomes](outcomes.md), what `ask` hands back when a turn ends
