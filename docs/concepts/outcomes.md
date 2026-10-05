# Outcomes

Nessy prefers a value that names what happened over an exception or a
silent no-op. Two sealed interfaces carry that preference at the two places
a caller most needs it: `Outcome<T>`, what came of asking, and
`TerminationOutcome`, what came of asking an agent to end. Both are in
`nessy-api`, and both make a caller write an exhaustive `switch` rather than
a `catch` — the case has to be acknowledged, not discovered later in
production.

## `Outcome<T>`

```java
public sealed interface Outcome<T> {
  record Answered<T>(T value, TurnStats stats) implements Outcome<T> {}
  record Refused<T>(String category, TurnStats stats) implements Outcome<T> {}
  record Failed<T>(String reason, TurnStats stats) implements Outcome<T> {}
  record Busy<T>() implements Outcome<T> {}
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
also the only one worth simply retrying — the other three are answers, and
asking again just gets another one.

`Refused` has one case with no turn behind it, too. Asking an agent that
has been terminated returns `Refused` with the category `terminated`. No
turn runs, and its `TurnStats` is empty. A caller that shows "the model
declined" for every `Refused` will say that about a dead agent.

**`Answered`, `Refused` and `Failed` each carry a `TurnStats`** — what the
turn that produced them did, and what it cost. A caller who waited for the
answer is the one entitled to know what it spent; reading it off the
outcome means never reaching into a backend to find out. A failed turn's
tally matters as much as an answered one's: a turn that failed expensively
is a different problem from one that failed at once. `Busy` carries none —
**the one arm with no tally**, deliberately. There was no turn, and an
empty tally would read as a turn that ran and spent nothing rather than as
a turn that never was. (`Refused("terminated")` carries an empty tally,
because the arm's shape requires one.) See [Cost](cost.md) for what `TurnStats` holds and
how to read it.

## `TerminationOutcome`

```java
public sealed interface TerminationOutcome {
  record Ended() implements TerminationOutcome {}
  record AlreadyEnded() implements TerminationOutcome {}
  record Busy() implements TerminationOutcome {}
}
```

`DirectHarness.terminate` returns this. The principle it draws out: ending
an agent mid-turn is not allowed. A turn in flight is owed its outcome —
abandoning it would leave effects with nobody to deliver them to — so a
request to end a busy agent is refused rather than silently ignored while
the caller assumes it worked.

`Ended` means this call is the one that ended the agent. `AlreadyEnded`
means the agent was already over — told apart from `Ended` because a
caller reconciling its own records wants to know whether *this* request is
what did it, even though both mean the agent is finished. `Busy` means a
turn is in flight and nothing was written — the agent is exactly as it was
before the call.

`Busy` is also where the intent goes to die, and this is specific to the
direct door: there is nowhere on that door to record that somebody asked,
so a caller that means it must ask again. `QueuedHarness.terminate` doesn't
return an outcome at all — it returns `void`, because that door has
somewhere to put the intent instead of a caller's hand. It writes the
ending down and always accepts, taking effect at once if the agent is idle
or as soon as the turn already in flight finishes owing its outcome — the
same [backlog](backlog.md) that holds a `tell` arriving mid-turn holds an
ending, too, as the `Pill` a busy agent drains into next.

An operation that quietly does nothing is the failure mode this design
refuses. Returning `void` for `terminate` would have made a refused
ending indistinguishable from a successful one — the caller asked, nothing
happened, and nothing in the API said so. `TerminationOutcome` exists to
make that gap visible instead of silent.

## One idea, not two types

Both types exist because a `catch` block and a `void` return both let a
caller not deal with a case. A sealed interface doesn't: `switch` over
`Outcome<T>` or `TerminationOutcome` that omits an arm doesn't compile, so
the decision to handle `Busy` or not is made at the call site, in the open,
rather than inherited from whichever exception someone remembered to catch.

The same house rule shows up a level lower, on the wire between a turn and
its tools: `ToolOutcome` is a sealed `Succeeded` / `Failed` / `Denied`,
told apart for the same reason `Outcome.Refused` is told apart from
`Outcome.Failed` — a tool that was never run because an approver said no is
a different fact from a tool that ran and went wrong, and collapsing the
two would tell the model, and the caller, something that didn't happen.

## Where next

- [The Harness](../guides/harness.md), the door `Outcome` answers through
- [Backlog](backlog.md), where the queued door's `terminate` writes down
  what `Busy` cannot
- [Tools](tools.md), `ToolOutcome` and the rest of a turn's tool-calling
  shape
