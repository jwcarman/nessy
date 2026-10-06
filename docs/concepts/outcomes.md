# Outcomes

Nessy prefers a value that names what happened over an exception or a
silent no-op. Three sealed interfaces carry that preference at the places
a caller most needs it: `AskOutcome<T>`, what came of asking;
`TellOutcome`, what came of telling an agent something; and
`TerminationOutcome`, what came of asking an agent to terminate. All are in
`nessy-api`, and all make a caller write an exhaustive `switch` rather than
a `catch` — the case has to be acknowledged, not discovered later in
production.

## `AskOutcome<T>`

```java
public sealed interface AskOutcome<T> {
  record Answered<T>(T value, TurnStats stats) implements AskOutcome<T> {}
  record Refused<T>(String category, TurnStats stats) implements AskOutcome<T> {}
  record Failed<T>(String reason, TurnStats stats) implements AskOutcome<T> {}
  record Busy<T>() implements AskOutcome<T> {}
  record Terminated<T>() implements AskOutcome<T> {}
}
```

`DirectHarness.ask` returns one of these instead of throwing for anything
it understands.

**`Answered<T>`** carries the value itself — text when nothing was asked of
the answer's shape, the parsed shape when something was. Either way it's
the same arm, because a caller that asked for an invoice wants an invoice,
not one it has to parse out of a string.

A reply the model cut off at the output limit also arrives as `Answered`.
The engine delivers the partial text as the answer, logs a WARN, and
reports the finish reason `length` on the inference span. Nothing in the
outcome marks it. The limit is the `maxTokens` of the agent's inference
options, 4096 by default, so a long answer needs a larger one.

**`Refused<T>`** is not a failure. The turn ran, the model read it, and
chose not to answer — and would decline again if asked the same way. The
call succeeded at the thing a call is for: it got the model's actual
response, and that response was no. A caller that treats `Refused` as an
error is punishing the harness for telling the truth.

**`Failed<T>`** carries a reason rather than a message, because what
matters about a failed inference is whether trying again could work — the
opposite of a tool failure, whose message exists for the model to read, not
the caller. This is also where a well-formed answer that doesn't fit the
shape the caller asked for arrives: the model spoke, but what came back
wasn't the thing requested, which is a failure of the asking rather than a
refusal by the model. Handing back something that doesn't fit the caller's
type would be far less honest than saying the shape wasn't met.

A model response that repeats a call id is not a valid response either. The
engine refuses it as a failed inference, logs an error that names the repeated
id, and requests no call, so no approver is asked and no tool runs. The turn's
usage is on the record.

**`Busy<T>`** means no turn happened: somebody else is already running a
turn on this scope, so nothing was appended and nothing was spent. It's
also the only one worth simply retrying — the answers are answers, and
asking again just gets another one.

**`Terminated<T>`** means the agent has been terminated, so no turn ran:
nothing was appended and nothing was spent. Asking again gets the same
answer, because a terminated agent stays terminated. It is not a `Refused`,
which is the model's own no.

**`Answered`, `Refused` and `Failed` each carry a `TurnStats`** — what the
turn that produced them did, and what it cost. A caller who waited for the
answer is the one entitled to know what it spent; reading it off the
outcome means never reaching into a backend to find out. A failed turn's
tally matters as much as an answered one's: a turn that failed expensively
is a different problem from one that failed at once. `Busy` and
`Terminated` carry none — **the two arms with no tally**, deliberately.
There was no turn, and an empty tally would read as a turn that ran and
spent nothing rather than as a turn that never was. See [Cost](cost.md)
for what `TurnStats` holds and how to read it.

## `TellOutcome`

```java
public sealed interface TellOutcome {
  record Accepted() implements TellOutcome {}
  record Terminated() implements TellOutcome {}
}
```

`QueuedHarness.tell` returns one of these. It says whether the agent took the
input, and never how the turn went: by the time the turn runs, whoever spoke
has gone, and the answer reaches a caller through [narration](../guides/narration.md).

**`Accepted`** means the agent took the input. It was handed to the agent
type's [backlog policy](backlog.md), and a turn starts at once when the agent
is idle and the policy left something waiting. The policy decides what waits:
it may keep the input, merge it with what waits, replace what waits, drop older
inputs to hold a bound, or discard the arrival itself as a repeat. So
`Accepted` is not a promise that the input will run, and an input still waiting
when the agent is terminated is abandoned. A caller serving a request answers
it as received, such as `202`. Inside a caller's transaction, `Accepted` is only
as durable as the caller's commit.

**`Terminated`** means the agent has been terminated and takes no more input.
The input was dropped: nothing was stored, nothing was written to the story, and
no turn will run for it. An agent terminated while its last turn is still in
progress answers `Terminated` at once, though its status does not read as
terminated until that turn ends. A caller serving a request tells whoever is on
the other end, such as `409`.

## `TerminationOutcome`

```java
public sealed interface TerminationOutcome {
  record Terminated() implements TerminationOutcome {}
  record AlreadyTerminated() implements TerminationOutcome {}
  record Busy() implements TerminationOutcome {}
}
```

`DirectHarness.terminate` returns this. The principle it draws out: terminating
an agent mid-turn is not allowed. A turn in flight is owed its outcome —
abandoning it would leave effects with nobody to deliver them to — so a
request to terminate a busy agent is refused rather than silently ignored while
the caller assumes it worked.

`Terminated` means this call is the one that terminated the agent.
`AlreadyTerminated` means the agent had been terminated already — told apart
from `Terminated` because a
caller reconciling its own records wants to know whether *this* request is
what did it, even though both mean the agent is terminated. `Busy` means a
turn is in flight and nothing was written — the agent is exactly as it was
before the call.

`Busy` is also where the intent goes to die, and this is specific to the
direct door: there is nowhere on that door to record that somebody asked,
so a caller that means it must ask again. `QueuedHarness.terminate` doesn't
return an outcome at all — it returns `void`, because that door has
somewhere to put the intent instead of a caller's hand. It writes the
termination down and always accepts, taking effect at once if the agent is idle
or as soon as the turn already in flight finishes owing its outcome — the
same [backlog](backlog.md) that holds a `tell` arriving mid-turn holds a
termination, too, as the `Pill` a busy agent drains into next.

An operation that quietly does nothing is the failure mode this design
refuses. Returning `void` for `terminate` would have made a refused
termination indistinguishable from a successful one — the caller asked, nothing
happened, and nothing in the API said so. `TerminationOutcome` exists to
make that gap visible instead of silent.

## One idea, not three types

All three types exist because a `catch` block and a `void` return both let a
caller not deal with a case. A sealed interface doesn't: `switch` over
`AskOutcome<T>`, `TellOutcome` or `TerminationOutcome` that omits an arm doesn't compile, so
the decision to handle `Busy` or not is made at the call site, in the open,
rather than inherited from whichever exception someone remembered to catch.

The same house rule shows up a level lower, on the wire between a turn and
its tools: `ToolOutcome` is a sealed `Succeeded` / `Failed` / `Denied`,
told apart for the same reason `AskOutcome.Refused` is told apart from
`AskOutcome.Failed` — a tool that was never run because an approver said no is
a different fact from a tool that ran and went wrong, and collapsing the
two would tell the model, and the caller, something that didn't happen.

## Where next

- [The Harness](../guides/harness.md), the door `AskOutcome` answers through
- [Backlog](backlog.md), where the queued door's `terminate` writes down
  what `Busy` cannot
- [Tools](tools.md), `ToolOutcome` and the rest of a turn's tool-calling
  shape
