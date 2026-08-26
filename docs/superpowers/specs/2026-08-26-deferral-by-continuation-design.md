# Deferral by continuation — and the call state machine

*2026-08-26. Replaces `ToolContext.defer()` / `ApprovalContext.defer()` with a
continuation returned by the deferring party; gives each call's state its own
transitions; makes expiry a first-class outcome. Amends the approval-lifecycle
spec (§1.3, §2, §3) and the tool-context-defer spec (which it supersedes).
Status: designed with James 2026-08-26; open items in §10.*

## 1. Thesis

Today a deferring party calls `defer()`, which creates the computation, folds
the wait, waits for the commit, and returns the id — a fold from inside an
effect. It is forced by two rulings that both stand: a mismatched delivery is
dropped permanently, and there is no harness-level notifier, so the id is
handed to the outside world during the call. If the fold happened after the
call returned, an answer arriving in that window would be lost.

It works. But it makes two wrong things writable, each needing guard code and
tests: returning a deferral without having called `defer()`, and returning a
result *after* deferring.

**Invert it.** The deferring party returns *what to do once the id exists*:

```java
return new Deferred(
    (id, deadline) -> tickets.open(work, id, deadline),
    Duration.ofDays(30));
```

The plumbing creates the computation, folds, commits, and only then runs the
continuation. The id cannot exist before the fold, so both errors become
unrepresentable and the fold stops being re-entrant.

## 2. The call state machine

### 2.1 States

`CallStatus` becomes **`CallState`** — a status is a scalar label; this carries
data *and* behaviour.

| state | meaning |
|---|---|
| `CallingApprover` | the approver is deciding |
| `DeferringApproval(id)` | the computation exists and is committed; the continuation has not run, so nobody outside knows the id |
| `AwaitingApproval(id, request, deadline)` | a human holds the question |
| `CallingTool` | the tool body is running |
| `DeferringCall(id)` | as above, tool side |
| `AwaitingCall(id, deadline)` | an external system holds the work |
| `Completed(result)` | the tool answered |
| `Denied(result)` | somebody said no |
| `Failed(result)` | the tool or the machinery broke |
| `Expired(result)` | **nobody answered before the term ran out** |

### 2.2 The pattern, twice

```
CallingApprover  →  [ DeferringApproval → AwaitingApproval ]  →  Denied | Expired | CallingTool
CallingTool      →  [ DeferringCall     → AwaitingCall     ]  →  Completed | Failed | Expired
```

The bracketed pair appears only if that side defers. On both sides:

| | `Deferring…` | `Awaiting…` |
|---|---|---|
| holds | the id | the id, the deadline |
| exits on | `Notified` (matching id) | the answer (matching id) |
| re-fire owes | **redo the originating step** | **nothing** |
| who holds the work | us — the continuation has not run | the world |

`Deferring` is always recoverable and `Awaiting` never is, for the same reason
on both sides: until the continuation runs nobody outside knows the id, so
redoing is safe; afterwards, redoing would double-ask the world.

### 2.3 The ten paths

Two early denials, plus four tool outcomes under each of two approval routes:

1. approved inline → tool returned → `Completed`
2. denied inline → `Denied`
3. approved inline → tool threw → `Failed`
4. approved inline → tool deferred → answered → `Completed`
5. approved inline → tool deferred → failed → `Failed`
6. deferred → approved → tool returned → `Completed`
7. deferred → approved → tool threw → `Failed`
8. deferred → approved → **tool deferred → answered → `Completed`** *(the long path: two waits, two handoffs, two crossings)*
9. deferred → approved → tool deferred → failed → `Failed`
10. deferred → denied → `Denied`

Plus `Expired` from either waiting state, and the crash loop from either
`Deferring` state back to its originating step — the only cycle in the machine.

Causes that ride existing edges and still need their own tests: the approver
throws (fail-closed to `Denied`), unknown tool (`Denied` before any approver
runs), argument binding fails (`Failed` before the body runs).

## 3. Events

`AgentEvent` gains a sealed sub-hierarchy so the phase routes without knowing
anything about calls:

```java
public sealed interface AgentEvent permits Observed, ModelFinished, CallEvent {}

public sealed interface CallEvent extends AgentEvent
        permits ApprovalDeferred, ApprovalAnswered, ApprovalExpired,
                ToolDeferred, ToolFinished, CallExpired, Notified {
    ToolCall call();
    default String callId() { return call().id(); }
}
```

| event | carries | admitted by |
|---|---|---|
| `ApprovalDeferred` | id, frozen request, deadline, **continuation** | `CallingApprover` |
| `Notified` | id | `DeferringApproval`, `DeferringCall` (matching id) |
| `ApprovalAnswered` | optional id, `Approval` | `CallingApprover` (id-less), `AwaitingApproval` (matching id) |
| `ApprovalExpired` | id | `AwaitingApproval` (matching id) |
| `ToolDeferred` | id, deadline, **continuation** | `CallingTool` |
| `ToolFinished` | optional id, `ToolOutcome` | `CallingTool` (id-less), `AwaitingCall` (matching id) |
| `CallExpired` | id | `AwaitingCall` (matching id) |

**A state accepts only the id it recorded.** The four id-holding states admit
their own id and nothing else; the two id-less events are legal only from the
two states that have not recorded one.

**Expiry becomes its own event.** The delivery worker stops mapping Continuum's
`Expired` into a denial or a failure — a judgement that was wrong on the
approval side, where nothing was decided. This *removes* logic from the worker
and lets `Expired` be a visible terminal rather than a reason string.

## 4. Effects

`CallModel`, `SeekApproval(call)`, `RunTool(call)` — unchanged — plus:

**`Notify(id, deadline, continuation)`** — runs the continuation after the fold
that recorded the wait has committed. It is the **only effect that cannot be
re-fired**, because it carries a closure and `outstandingEffects()` rebuilds
instructions from persisted state. That is precisely why `Deferring…` exists:
the closure is unreconstructible, but the *state* is, and recovery is to
re-fire the originating step rather than to resume.

## 5. Terms, ceilings, deadlines

Three words, three sides, none pretending to be the others:

- **`Duration term`** on `Deferred` — what the caller asks for. Required, not
  optional: the deferring party always knows, the harness never does.
- **`maxApprovalTimeout` / `maxCallTimeout`** — configuration; what the harness
  enforces. These exist today as the private constants `APPROVAL_DEADLINE`
  (7 days) and `DEFAULT_TOOL_DEADLINE` (1 day); this promotes them to where
  they belonged. Two ceilings because an approval waits on a person and a call
  waits on a machine.
- **`Instant deadline`** on the callback and in the state — what was agreed.
  The caller may ask for a year and get seven days; only the value it is
  *given* is ever visible to it, so it cannot promise a human something false.

The deadline rides the event into the state so the pending-approvals
projection — and therefore the page — can show it. Continuum has no read door;
if the fold does not carry it, nothing downstream can ever know it.

`Tool.timeout()` **is deleted.** It exists only to stamp a deadline at
deferral, so it declares a duration at registration for invocations that may
never defer. Referenced in `Tool`, `ToolConfig`, `RegistryToolCallExecutor`
and `HarnessConfig`; all four lose it.

## 6. The state's own transitions

`CallState` is a sealed **interface with default methods** — not an abstract
class, because the states are records and records cannot extend a class.

```java
public sealed interface CallState permits … {

    @JsonIgnore Optional<ToolResultBlock> result();
    @JsonIgnore List<Effect> outstanding();

    default CallTransition handle(CallEvent event) {
        return switch (event) {                       // the one exhaustive switch
            case ApprovalDeferred e -> onApprovalDeferred(e);
            …
        };
    }

    default CallTransition onApprovalAnswered(ApprovalAnswered e) { return dropped(e); }
    // … one per event; the default DROPS AND WARNS
}
```

**The default is drop-and-warn, not silent ignore.** Tolerable only because
`CallEvent` is a sub-hierarchy: a `CallState` can never receive `Observed` or
`ModelFinished`, so the structurally-impossible cases never arrive and every
unhandled event genuinely is unexpected. It also ends the silence around
in-process mismatches, which today vanish without a trace.

*To settle when built:* a dropped delivery would otherwise log twice, once from
the state and once from the worker. Emit one line, from the worker, carrying
what the state reported.

Adding an event breaks exactly one place — this dispatch — which is where
"what is the default for this?" is the right question.

### 6.1 What the phase keeps

`AwaitingTools` reduces to three arms:

```java
case Observed _      -> throw new IllegalStateException("observations absorb only at Idle");
case ModelFinished _ -> Transition.ignore();
case CallEvent e     -> route(e);
```

and `route` is: look up `e.callId()`, delegate, replace, then ask *do all calls
have a result?* — never naming a state, a call event, or an id. `Idle` and
`AwaitingModel` each collapse four ignore-arms into `case CallEvent _`.

The turn-level decision stays in the phase: when every call has a result, commit
the assistant turn plus the tool results in the phase's own insertion order and
advance to `AwaitingModel` with `CallModel`. No individual call can know that.

## 7. What this deletes

- `ApprovalContext.defer()` and `ToolContext.defer()`; `ApprovalContext`
  collapses to `request()`, `ToolContext` returns to a record.
- The two in-band failures `defer()` made writable, their guard code and tests.
- `Tool.timeout()` and its four call sites.
- The worker's expiry-to-denial/failure mapping.
- The duplicated re-fire rule, currently stated in two switches that must agree.

## 8. Serialization

States are persisted with explicit `@JsonSubTypes` discriminators; those strings
are the wire format. **James, 2026-08-26: the format may break freely — the
database can be deleted.** No decode-side aliases.

The rule that keeps it safe: **states are data; only events and effects carry
behaviour.** No state holds a continuation — `Deferring…` holds only the id,
because recovery is re-ask, not resume. `@JsonIgnore` the derived methods
(`result()`, `outstanding()`) or Jackson will invent properties for them.

## 9. Migration

**0 · Stale-retry error status** *(independent, do first)* — a lost CAS race is
recorded as `STATUS_CODE_ERROR` on the fold span today, so healthy contention
shows as a permanent error rate. It becomes `nessy.fold.outcome=retried`,
status `OK`; only a genuine throw is an error.

**A · States own their transitions** *(pure refactor)* — `CallEvent`
sub-hierarchy, the interface-with-defaults, `handle`/`result`/`outstanding`
moved out of `Phase`. Behaviour identical; the existing matrix suite is the
proof. Merge alone.

**B · Vocabulary** — the renames, the terminal split, `CallStatus → CallState`.
Mechanical except the split, which A has made cheap.

**C · The continuation** — `Deferring…` states, `Notified`, `Notify`, terms and
ceilings, expiry events, `defer()` and `Tool.timeout()` deleted. After A, a new
state is a new file.

## 10. Open

1. `Term` as a value type versus a plain `Duration term` field.
2. Whether `Tool.timeout()`'s removal wants a deprecation cycle (pre-1.0: probably not).
3. Where the separate executor simplification lands — `callModel` returning its
   outcome, memory recall moving into the loop, executors losing `Sink` and any
   knowledge of `AgentEvent`. Independent of A–C but touches the same files.
