# The agent's story: narration that can be replayed

**Status: APPROVED by James on 2026-10-04, NOTHING BUILT. The shape was settled in conversation
with him that day and this record writes it down. He approved it as a whole, the names in §12 and
the resolutions in §13 included, with two changes that are folded in: the request manifest is
stored but is not public (§8b), and `TurnStarted` carries when its input arrived (§6e).**

Date: 2026-10-04. First of three records:

1. **This record: the story.** Narration reshaped so that it can be replayed, with usage, the
   facts an approval was decided on and the model request on the record, and a public API to read it.
2. **Current state and the books** (next record): an agent's status, a paginated read of the work
   in flight, and settling a deferred call by its `IdempotencyKey`.
3. **Later:** an opt-in Actuator endpoint and fleet metrics.

It **amends** `2026-10-01-stratified-context-design.md` (the "no framework snapshot" ruling, §8
here), `2026-08-25-approval-lifecycle-design.md` (`reference` is replaced, §6c) and the narration
parts of `2026-08-22-harness-first-design.md`. Every path and name below was checked against
`main` at `1fc8c8347`.

---

## 1. The problem

An application cannot read what its agents did without reaching into the engine.

Verified in source and in the nessy-ap findings (F3, and its critique of 0.4.0):

- The only reader of an agent's history is `org.jwcarman.nessy.engine.store.TurnHistories`, an
  engine-internal type shaped for assembling context. nessy-ap imports it in four places: to
  check that a cited id appeared in a tool result, for its auditors' trail, to ask whether an
  agent is in a turn, and to count turns for a budget.
- `Narration` tells a listener what is happening, but only as it happens. Nothing can read it
  back. It carries no position and no time, and two copies of the mapping from stored events to
  narration exist (`DefaultDirectHarness.narrate`, `DefaultQueuedHarness`), free to drift.
- What an auditor asks for is thrown away or never stored: who approved a call
  (`Narration.CallApproved` drops `ToolApproved.reference`), that a model call was retried
  (`InferenceAttempted` is never narrated), what each call cost (no narration carries `Usage`),
  why a call failed (an expired approval, a tool past its deadline and a tool that threw are all
  `ToolFailed` with a sentence), that an answer was cut off (stored as a plain
  `InferenceAnswered`), the facts an approval was decided on (never stored), and what the model
  was shown (never stored).
- Each application with a person in the loop has built its own table of what is waiting. That is
  the second record's subject, but the vocabulary it needs is set here.

## 2. The idea

**Narration is the agent's story.** One vocabulary tells it, live and afterwards.

- A **story event** is stored. A listener hears it when its step commits, and a reader gets the
  same event back on replay, with its position and its time.
- A **live signal** is heard only as it happens: the streaming deltas, thinking, commentary, and
  the notice that an approver is being asked. It is never stored and never replayed.
- **Usage is first-class.** Every story event that records a model call carries what the call
  cost, a retried call included. Adding up the story gives the agent's cost.
- **The story carries no content.** No message text, no tool result, no approval facts. Content is a
  separate, explicit read, addressed by where it sits in the story (§9c).
- **A projection is a fold over the story.** `UsageReports` becomes one. Applications write their
  own.

The stored `AgentEvent` log stays internal. The story is its public projection, produced by one
internal adapter (§5), so the event shapes stay free to change behind it.

## 3. The vocabulary

`Narration` (in `nessy-api`) is reshaped in place. It stays one sealed type and gains two sealed
groups and one marker.

```java
public sealed interface Narration {

  /** Stored: heard when its step commits, and again on replay. */
  sealed interface Story extends Narration {}

  /** Heard only as it happens; never stored, never replayed. */
  sealed interface Live extends Narration {}

  /** A story event that ends a turn. A turn ends in exactly one. */
  sealed interface TurnEnding extends Story {
    TurnId turn();
  }

  // ---- Story: the turn ------------------------------------------------------------------
  record TurnStarted(TurnId turn, String label, Instant arrivedAt) implements Story {}
  record TurnStopped(TurnId turn, String reason) implements TurnEnding {}   // a policy ended it
  record Terminated() implements Story {}

  // ---- Story: what the model did; each carries the call's Usage ------------------------------
  record ActionsRequested(TurnId turn, List<Call> calls, Usage usage) implements Story {
    record Call(CallId callId, IdempotencyKey idempotencyKey, ToolName toolName, String action) {}
  }
  record Answered(TurnId turn, boolean truncated, Usage usage) implements TurnEnding {}
  record TurnRefused(TurnId turn, String category, Usage usage) implements TurnEnding {}
  record TurnFailed(TurnId turn, FailureKind kind, String reason, Usage usage)
      implements TurnEnding {}                                              // the model call failed
  record InferenceRetried(TurnId turn, FailureKind kind, String reason, Usage usage)
      implements Story {}

  // ---- Story: what became of each call ------------------------------------------------------
  record ApprovalDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until)
      implements Story {}
  record CallApproved(CallId callId, IdempotencyKey idempotencyKey, Optional<String> decidedBy)
      implements Story {}
  record CallDenied(
      CallId callId, IdempotencyKey idempotencyKey, String reason, Optional<String> decidedBy)
      implements Story {}
  record CallDeferred(CallId callId, IdempotencyKey idempotencyKey, Instant until)
      implements Story {}
  record CallFinished(CallId callId, IdempotencyKey idempotencyKey) implements Story {}
  record CallFailed(
      CallId callId, IdempotencyKey idempotencyKey, CallFailure kind, String message)
      implements Story {}

  // ---- Live only ---------------------------------------------------------------------------
  record Thinking() implements Live {}
  record ThinkingDelta(String text) implements Live {}
  record ContentDelta(String text) implements Live {}
  record Commentary(String text) implements Live {}
  record ApprovalSought(CallId callId, String action) implements Live {}
}

public enum FailureKind { TRANSIENT, UNKNOWN, PERMANENT, REJECTED }     // Failure's four arms
public enum CallFailure { FAILED, PAST_DEADLINE, NOT_AUTHORISED }
```

What changes from today, and why:

| Change | Reason |
|---|---|
| `Story` / `Live` groups | a listener, and the compiler, can tell what will be there on replay |
| `TurnEnded` is removed; `TurnEnding` marks the four events that end a turn | `TurnEnded` was a second narration of the same stored event. With it gone, **each stored event is told as exactly one story event**, so a `Seq` names one story event |
| `TurnStopped` split from `TurnFailed` | a policy stopping a turn and a model call failing were one record; they are different facts and only one has a `Usage` |
| `InferenceRetried` | a retried model call is part of the story and of its cost |
| `Usage` on every model-call event | usage is first-class |
| `Answered.truncated` | a reply cut off at the output limit is delivered as an answer; the story now says so |
| `IdempotencyKey` on every call event | the key, not the call id, tells one call from another; approvals and outcomes interleave, so a projection groups by key |
| `decidedBy` on approvals and denials; `reference` is gone | §6c |
| `CallFailure` on `CallFailed` | "timed out waiting for a person" becomes a count, not a text match |
| `ApprovalDeferred`, `CallDeferred` move from live to story | the record says a call was handed off when it was handed off, not only once it ends (§7) |
| `TurnStarted.label` | says what started the turn without its content (§6e) |
| `TurnStarted.arrivedAt` | when the input arrived; with the event's own time it gives how long the input waited (§6e) |

`CallFailure` says which of three things happened: `FAILED`, the tool ran and failed, or could
not be run at all; `PAST_DEADLINE`, the call did not finish before its deadline, and whether it
ran is not known; `NOT_AUTHORISED`, permission was never given, because the approval's deadline
passed or the approver itself failed. Nobody said no: a refusal is `CallDenied`.

## 4. The envelope

A listener is handed an envelope, not a bare event:

```java
public record Narrated(
    AgentType agentType, AgentId agentId, Narration event, Optional<Position> position) {

  /** Where a story event sits in its agent's story, and when it was written. */
  public record Position(Seq seq, Instant at) {}
}

public interface NarrationListener {
  void on(Narrated narrated);
  // async(), none(), of(...) as today
}
```

`position` is present for every `Story` event and empty for every `Live` one. `seq` is the
stored event's own sequence number, so it is unique within an agent and increases; `at` is when
the event was written. Because each stored event is told as one story event, `(agent, seq)` names
one story event everywhere, live and on replay.

The rules narration already keeps stand: a story event is heard only after the step that wrote
it commits, never if it rolls back, in order per agent; a listener that throws loses nothing,
because the event was stored first.

## 5. One adapter

A single engine-internal function turns one stored `AgentEvent` into its one story event:

| Stored event | Story event |
|---|---|
| `TurnStarted` | `TurnStarted` |
| `ActionsRequested` | `ActionsRequested` |
| `InferenceAnswered` | `Answered` |
| `InferenceRefused` | `TurnRefused` |
| `InferenceFailed` | `TurnFailed` |
| `InferenceAttempted` | `InferenceRetried` |
| `TurnStopped` (today's policy `TurnFailed`, renamed; §13.1) | `TurnStopped` |
| `ApprovalDeferred` (new) | `ApprovalDeferred` |
| `ToolApproved` | `CallApproved` |
| `ToolDenied` | `CallDenied` |
| `ToolDeferred` (new) | `CallDeferred` |
| `ToolSucceeded` | `CallFinished` |
| `ToolFailed` | `CallFailed` |
| `Terminated` | `Terminated` |

It is total, one-to-one, and reads nothing but the event: no lookups, no clock. Both doors hand
each committed event to it, replacing the two hand-written switches, and replay runs the same
function over stored events. A test fixes the equivalence (§11): for a scripted turn, the story
events a live listener heard equal the replay of what was stored, envelope included.

## 6. What is stored

All of this changes stored shapes. Existing databases are recreated; there is no migration.

### 6a. Event changes

| Stored event | Change |
|---|---|
| `TurnStarted` | gains `String label` and `Instant arrivedAt` |
| `InferenceAnswered` | gains `boolean truncated` and `RequestManifest request` |
| `InferenceRefused`, `InferenceFailed`, `InferenceAttempted`, `ActionsRequested` | gain `RequestManifest request` |
| `ToolApproved` | `reference` is replaced by `Optional<String> decidedBy`; gains `ObjectNode facts` |
| `ToolDenied` | the same two changes |
| `ToolFailed` | gains `CallFailure kind` and `ObjectNode facts` |
| `ToolApproved`, `ToolDenied`, `ToolSucceeded`, `ToolFailed` | each gains the call's `IdempotencyKey` |
| `ApprovalDeferred(seq, turn, callId, until, facts, idempotencyKey)` | new |
| `ToolDeferred(seq, turn, callId, until, idempotencyKey)` | new |
| `TurnFailed` (policy) | renamed `TurnStopped` (§13.1) |

`EffectOutcome`, `AgentCommand` and `FailedAttempt` gain the matching fields, so each value
travels from the handler that knows it to the event that records it.

### 6b. Time

`AgentEvents.append` takes the instant the events are written at, from the engine's clock, and
stores it as `written_at`; today the JDBC backend defaults the column to the database's `now()`.
The live envelope then carries the same instant the replay will read. Order is `seq`; `at` is
information. (§13.2.)

### 6c. `decidedBy` replaces `reference`

`ApprovalResult.Approved.reference` and `Denied.reference` are removed, with
`AgentEvent.ToolApproved.reference` and `ToolDenied.reference`. In their place, on all four, is
`Optional<String> decidedBy`: who or what decided, as the application chooses to say it
(`"buyer:j.smith"`, `"policy:ap-rules@v42"`). Nessy never interprets it. It is a string and not a
principal type for the reason facts are untyped: what an identity is belongs to the application.

`reference` was a join from the story to the application's own record of the decision. The
`IdempotencyKey` is that join now, and it is on every event of the call. An application's record
is keyed by it.

`ApprovalResult.approvedBy(String)` and `deniedBy(String reason, String)` keep their shape and
take `decidedBy`. There is no approval note: a note is the deciding application's own record.

### 6d. Why a call failed

`EffectTermsSource` and the handlers already know which case they are in and say it in prose.
They now say it as a `CallFailure` as well:

| Where the failure is made | Kind |
|---|---|
| the tool returned a failure, threw, was not bound, or its arguments could not be read | `FAILED` |
| the tool call's deadline passed (`whether it ran is not known`) | `PAST_DEADLINE` |
| the approval's deadline passed, or the approver threw | `NOT_AUTHORISED` |

### 6e. The input label

`TurnStarted.label` says what started a turn without its content. It is written once, when the
turn starts, and never worked out again, as a tool call's action line is.

```java
harnesses.create(type, CaseEvent.class, c -> c.inputLabel(event -> event.kind()) ...);
```

`inputLabel(Stringifier<I>)` sits beside `inputRenderer` on `DirectHarnessConfig` and
`QueuedHarnessConfig`, so both doors have it. The default is the input's simple class name, so a label is always present and the field is a plain `String`. An
agent whose input type is `String` reads `"String"` until it configures something better.

A turn takes exactly one input: the queued door's `backlog.take()` returns one item, and
coalescing happens earlier, between backlog items. So there is one label per turn, and it is
computed when the turn starts, from the item actually taken.

`TurnStarted.arrivedAt` is that item's `BacklogItem.arrivedAt`, which the backlog already keeps.
The time between it and the event's own `at` is how long the input waited for its turn. On the
direct door, which has no backlog, it is the instant `ask` was called.

### 6f. Deadlines that are shown are the deadlines that are kept

Today `ApprovalRequest.deadline` is `askedAt + approvalTimeout` and `CallDeferred.until` is
`now + timeout`, both computed when the handler runs. The effect row's deadline was fixed
earlier, when the work was written, so a person can be shown a deadline later than the one the
call is held to. The handlers are handed the row's deadline and use it for
`ApprovalRequest.deadline`, `ToolCallRequest.deadline` and both deferral events' `until`.

## 7. Deferral is recorded when it happens

Today, when an approver or a tool defers, the dispatcher leaves the effect row as it was claimed
and writes nothing. The story says nothing until the call ends.

It becomes one short locked step, **the park step**, run by the dispatcher when a handler returns
`Awaited.deferred()`:

1. Under the agent's lock, the fold is given a `DeferApproval` or `DeferToolCall` command (§10).
   If it accepts, `ApprovalDeferred` or `ToolDeferred` is appended. If it ignores the command,
   because the call was settled first, nothing is written and the step ends.
2. In the same transaction, the effect row is marked:
   ```sql
   UPDATE nessy_agent_effect
      SET parked_at = ?, actionable_at = deadline, updated_at = ?
    WHERE effect_id = ? AND status = 'RUNNING' AND attempts_made = ?
   ```
   Zero rows is not an error: the row moved on, and the event stands.

`parked_at` is a new nullable column, and it is the only change to the effect table in this
record. **There is no new status.** A parked row stays `RUNNING`; the claim, both fences, retire
and reschedule are unchanged. Setting `actionable_at` to the deadline makes "a parked row comes
due only to be given up on" true by construction. Today that holds only while the claiming
process's clock agrees with the one that wrote the row, and a row that wakes early is performed
again: the approver is asked twice.

**The park step runs outside the dispatcher's `try`.** That `catch` turns any exception into a
failed or retried call, and a deferral, which does nothing today, cannot fail. If the park step
throws, it is logged at WARN and the row is left exactly as today: correct, unmarked, and due at
its deadline. The call must never be failed, and the approver never asked again, because
bookkeeping could not be written.

The direct door cannot defer and is unchanged: a deferral there is a failed call.

## 8. What an approval was decided on, and what the model was shown

Both records are for **audit**, not replay. They keep what cannot be read back from what is
already stored and immutable, and nothing else.

### 8a. The facts behind every decision

Every approval is recorded with the facts it was decided on, whether it was decided at once,
deferred, or failed because the approver itself failed. A call approved by a policy in a
millisecond is the one no person looked at, and so the one an auditor most needs the evidence for.

**Only the facts are stored** (James ruled this on 2026-10-04, in place of storing the whole
`ApprovalRequest` as a document). Everything else in an approval request is already in the story
and never changes: the call's id, key, tool name and action line are on the request the model
made, its arguments are in what the model wrote, and a deferral's deadline is on its deferral
event. The facts are what enrichers and the approver itself add, and they are in no other place.

**They are stored on the event,** as a JSON object, in the same transaction as the decision.
Nothing is written to `Payloads` for an approval, so there is no reference that can dangle and
no case where the decision is recorded and its facts are not. A request with no facts records an
empty object. Every tool has an approver (the default one allows), so this is one rule for every
call. Facts are the application's own evidence and are not cut; they are read whenever the
agent's events are read, so an application keeps them small. They are not part of narration.

They are taken **after the approver returns**, because enrichers and the approver itself add
facts while deciding, and what stands when the approver returns is what was decided on.

| Outcome | Where the facts are recorded |
|---|---|
| approved or denied at once | `ToolApproved.facts` / `ToolDenied.facts` |
| deferred | `ApprovalDeferred.facts`; the later `ToolApproved` or `ToolDenied` carries none, and the deferral's facts are the decision's |
| the approver threw | `ToolFailed.facts`, the facts as they stood |
| the deferral expired | none on `ToolFailed`; the deferral's stand |

A deferral is recorded whether or not it has facts. When an ask is retried, the failure that is
finally recorded holds the facts of the last ask; a retried request that is not asked again
before its deadline records none. `askedAt`, and the deadline of a request answered at once, are
not recorded: the event's own time, who decided and the facts answer what an auditor asks.

`Payloads` gains a second kind of content beside message blocks, which the request manifest
(§8b) uses:

```java
PayloadRef putDocument(JsonNode document);
JsonNode getDocument(PayloadRef ref);
```

Same table, same storage codec, so a configured encryption covers it. A stored payload says which
kind it is in a `kind` column; asking for a document as blocks, or the reverse, fails by name. A
payload's reference is a hash of its content taken before the storage transform, so the same
content is one reference whatever the transform does.

### 8b. The request manifest

**This amends the 2026-10-01 ruling "no framework snapshot".** That ruling stands as far as
behaviour goes: sources return what is current and nothing is driven from a saved copy. What
changes is that what was sent is now **recorded**, by section.

Every stored model-call event carries a `RequestManifest`: what the request was made of, each
part named by reference and none copied. An event recorded with no request in hand (the handler
threw, the row could not be read, or the deadline passed first) carries none.

**The manifest is stored, and it is not public.** It sits beside `AgentEvent` in the backend SPI,
and nothing in `nessy-api` reads it. It shows how context is assembled (strata, chapters, content
hashes), and making it public would make that structure a contract while the context design is
still moving. Recording it from the start means that a later record can open it, and every call
made since will have one.

```java
public record RequestManifest(
    String engineVersion,
    PayloadRef instructions,                 // the system prompt
    PayloadRef tools,                        // the offers and the choice, a document
    Optional<PayloadRef> answerShape,        // the output schema, a document
    PayloadRef options,                      // model, max tokens, vendor properties: a document
    Optional<TurnId> summarizedThrough,      // the last turn the summaries shown cover
    Optional<TurnRange> tail,                // the completed turns shown verbatim
    List<Section> memory,
    List<Section> state,
    List<Section> ambient) {

  public record Section(String kind, PayloadRef content) {}
  public record TurnRange(TurnId from, TurnId through) {}
}
```

- A `PayloadRef` is a hash of the content. A section that did not change since the last call is
  the same reference and stores nothing new. The prompt, the tools and the answer shape are
  stored once per agent and change only with configuration; memory, state and ambient sections
  are stored when their sources say something new.
- History is never copied. The tail is a range of turns, and the active turn is "everything in
  this turn before this event". Both are already in the story, and a completed turn never
  changes.
- Summaries are named by one turn: the last turn they cover. A chapter's bounds never change
  once stored and its summary is written once, so the summaries shown are exactly those of the
  chapters up to that turn. (James ruled this on 2026-10-04, in place of a list of chapters with
  a reference each, which grew with every chapter on every event.)
- The prompt, the tools, the answer shape and the options can change between deployments, so
  each is named by a reference to what it was. Memory, state and ambient sections cannot be
  rebuilt afterwards, so each is kept as it was shown.
- The options document writes vendor properties with their keys sorted, so the same options are
  the same reference whatever order they were given in.
- There is no opt-out per source. What the model was shown is on the record, and nothing in
  the engine deletes it: a source must not return what an application may not keep. A source
  whose answer differs on every call adds one stored section for each model call.
- The manifest is built by the same assembly that builds the request, and returned beside the
  result, so it cannot describe a request other than the one sent. It is not worked out
  afterwards.
- It records the canonical, stratified request. Where a provider adapter puts each stratum on
  its wire is the adapter's own, and is a function of this and the engine version.

Not recorded: the requests of the engine's summariser. Those calls are not story events today,
and that does not change here.

**Cost.** A manifest is a handful of references on each model-call event, and one more for
each memory, state and ambient section. New content is stored only
when a section changes. Payloads are scoped to an agent, so the system prompt is stored once for
each agent, not once for all.

## 9. The read API

In `nessy-api`, in-process, read-only. Nessy adds no HTTP controller.

### 9a. Replay

```java
public interface AgentStories {
  AgentStory of(AgentType type, AgentId id);
}

public interface AgentStory {

  /** Up to {@code limit} story events after {@code after}, oldest first. */
  List<Narrated> replay(Seq after, int limit);

  /** The whole story, folded. */
  <T> T project(StoryProjection<T> projection);

  StoryContent content();
}
```

`replay` returns story events only, each with its position. An agent with no story is an empty
list. Both doors' agents are read the same way: they store the same events.

There is no "replay, then carry on live" here. A watcher that reconnects through Odyssey is
resumed by Odyssey, which assigns its own event ids and replays from them, and no application
that streams some other way has asked for it (§14).

The engine's implementation is built over a backend's stores, as `EventUsageReports` is, and
the Boot starter offers an `AgentStories` bean over both doors' stores, as it does for
`UsageReports`.

### 9b. Projections

```java
public interface StoryProjection<T> {
  T initial();
  T apply(T soFar, Narrated story);
}
```

`UsageReports` is reimplemented as one: it adds the `Usage` of every model-call story event, by
model. `ModelUsage.inferences` counts each of them, `InferenceRetried` included, so it stays
equal to the number of calls the provider billed.

A projection is given the story, not the content. One that needs content reads it (§9c).

### 9c. Content

Content is never in a story event. It is read on purpose, addressed by where it sits in the
story:

```java
public interface StoryContent {

  /** What was said in a turn: its input, and its answer if it has one. */
  TurnContent turn(TurnId turn);

  /** What one call returned, if it succeeded. */
  Optional<List<Block.ToolResultContent>> result(IdempotencyKey key);

  /** The results of the agent's successful calls after {@code after}, oldest first. */
  List<CallResult> results(Seq after, int limit);

  /** The facts the call's approver was shown, as they stood when it decided or deferred. */
  Optional<JsonNode> approvalFacts(IdempotencyKey key);

}

public record TurnContent(
    List<Block.InputContent> input,
    List<RequestContent> requests,                    // what the model wrote with each request
    Optional<List<Block.AnswerContent>> answer) {}
public record RequestContent(Seq seq, List<Block.ActionRequestContent> blocks) {}
public record CallResult(Seq seq, IdempotencyKey idempotencyKey, List<Block.ToolResultContent> blocks) {}
```

`results` is the bulk read grounding needs: "did any successful tool result of this agent hold
this id?" is one paginated read, not one read per call. Nothing here hands out a storage
reference: every read is addressed by a turn, a key or a position.

Everything read here went through the storage codec and comes back decoded. Nessy does not
decide who may read it: this is an in-process API, and the application's own code is the caller.

### 9d. What nessy-ap would change

| Today, through `TurnHistories` | After |
|---|---|
| grounding: walk turns, exchanges and outcomes | `content().results(after, limit)` |
| the auditors' trail | `replay`, and `content()` where the page shows words |
| turns spent, for a budget | a projection counting `TurnStarted` |
| "is this agent in a turn?" | a projection for now (`TurnStarted` without a `TurnEnding`); the second record's status answers it properly, queued input and waiting included |

## 10. The fold

**This is the part of the record that can break an agent.** Everything else here is a new
reader, a new field carried through, or a new write beside the fold. The changes to the fold
itself are listed in full, so that nothing reaches it that is not on this list.

### 10a. What changes

**1. Two new commands, and the events they write.** `DeferApproval(turn, requestSeq, callId,
until, facts)` and `DeferToolCall(turn, requestSeq, callId, until)`.

| State | `DeferApproval` | `DeferToolCall` |
|---|---|---|
| `Idle`, `Inferring`, `Terminal` | ignore | ignore |
| `AwaitingActions`, another turn or another `requestSeq` | ignore | ignore |
| `AwaitingActions`, the call is not outstanding | ignore | ignore |
| `AwaitingActions`, the call is `AWAITING_APPROVAL` | write `ApprovalDeferred`; **no effects** | ignore |
| `AwaitingActions`, the call is `RUNNING` | ignore | write `ToolDeferred`; **no effects** |

The guards are the ones `CompleteApproval` and `CompleteToolCall` already apply, in the same
order: turn, then request, then the call and its phase.

`AwaitingActions.accept` takes `ApprovalDeferred` and `ToolDeferred` and returns the same state
with the new `seq`: the same outstanding calls, each in the same phase with the same `since`,
the same `requestSeq`, the same `TurnStats`. Every other state rejects them as it rejects any
event that cannot happen there. Neither command consults the `TurnPolicy`, and neither event
changes a tally.

**2. Fields carried through.** The fold copies each to the event it writes, from the command it
was given or, for the call's key, from the state it already holds, and decides nothing on any of
them:

| Event | Fields | From |
|---|---|---|
| `TurnStarted` | `label`, `arrivedAt` | `StartTurn` |
| every model-call event | `request` | `CompleteInference`; for `InferenceAttempted`, the failed attempt |
| `InferenceAnswered` | `truncated` | `CompleteInference` |
| `ToolApproved`, `ToolDenied` | `decidedBy`, `facts` | `CompleteApproval` |
| `ToolFailed` | `kind`, `facts` | `CompleteToolCall` |
| `ToolApproved`, `ToolDenied`, `ToolSucceeded`, `ToolFailed` | `idempotencyKey` | the call's own `OutstandingAction`, which has held it since the request was recorded |

**3. A rename:** the stored policy event `TurnFailed` becomes `TurnStopped` (§13.1).

### 10b. What must not change

- Every existing guard: the turn, the `requestSeq`, the phase, "a failure may reach a call that
  never ran".
- What each existing command writes and which effects it emits.
- `OutstandingAction`, its two phases and what `since` means.
- When a turn ends, and the `TurnPolicy`'s say.
- That a state rebuilt by replaying stored events equals the state that wrote them.

### 10c. The scrutiny it gets

- **The fold changes are their own tasks**, apart from every reader and every refactor. The
  adapter's extraction (§5) lands first, alone, changing no behaviour, with the existing
  narration tests passing unchanged.
- **Red first.** Each cell of the table in §10a has a test that fails before the change. So does
  each "ignore": a deferral for another turn, for another request, for a discharged call, and
  for a call in the other phase.
- **The whole table is tested, not the new rows.** A test walks every state against both new
  commands, and every state against both new events.
- **Replay equals live.** For every scripted scenario, the state reconstituted from the stored
  events equals the state held when they were written, with deferrals in the stream.
- **Nothing else moved.** The existing fold tests (`AgentStateTest`,
  `AgentStateRepeatedCallIdTest`, the late-outcome tests) pass with no change beyond the new
  fields in their fixtures.
- **Every consumer of `AgentEvent` is named and updated on purpose:** `TurnTally`, `Transcript`,
  `EventStreamHistory`, `EventUsageReports`, and each state's `accept`. The two deferral events
  add nothing to a turn's exchanges, tally or usage.
- **Review.** Each fold task and the park step get the high-risk review on Opus, per the model
  policy. The final whole-branch review is told to read the fold diff line by line against
  §10a and report anything that is not on it.
- **The gate** is the container one, `clean verify -Dnessy.excludedGroups=live`, and Maven's own
  exit code is what is read.

## 11. Testing

Beyond §10c:

- **Story equivalence.** For a scripted turn with a retry, a gated call, a deferral and an
  answer, the `Story` events a live listener heard equal `replay` of what was stored: same
  events, same `seq`, same `at`.
- **The adapter is total and one-to-one**, by a test over every `AgentEvent` kind.
- **The park step.** A deferral writes the event and marks the row in one transaction. An answer
  that lands first leaves the park step writing nothing. A park step that throws leaves the row
  as it was, fails no call and asks no approver again. A parked row is not claimed before its
  deadline when the claimer's clock runs behind the writer's.
- **The facts.** On the event for a decision made at once, for a deferral (with or without
  facts), and for an approver that threw; facts the approver added while deciding are in them;
  nothing is written to `Payloads` for an approval.
- **The manifest.** Two calls with unchanged sections store no new payloads; a changed ambient
  section stores one. The manifest's references resolve to exactly what the request held. No
  type in `nessy-api` names it.
- **Content.** `results` pages in order and holds only successful calls. A document asked for as
  blocks fails by name.
- **Stored shapes**, written out by hand, for each changed and new event.
- **The examples and adapters** that consume narration: `OdysseyNarrator` (SSE names for the new
  kinds; Odyssey assigns and manages its own event ids), `ConsoleNarration`, chat-web's page, the watchman.

## 12. New public concepts

Approved by James in conversation on 2026-10-04:

- `Narration.Story`, `Narration.Live`
- `TurnStopped`, `InferenceRetried`
- `FailureKind`, `CallFailure` and its three kinds
- `IdempotencyKey` on every call event; `Usage` on every model-call event
- `decidedBy` in place of `reference`, as an opaque optional string
- `ApprovalDeferred` and `CallDeferred` as stored story events
- the `parked_at` column, and no new status
- the facts of an approval stored on its event (`facts`), read with `StoryContent.approvalFacts`;
  `Payloads.putDocument` / `getDocument` for the request manifest
- the input label, configured with a `Stringifier`, defaulting to the simple class name
- the request recorded by section, amending "no framework snapshot"; stored, not public

Proposed by this record, and approved with it:

- `Narration.TurnEnding`
- `Narrated` and `Narrated.Position`; `NarrationListener.on(Narrated)`
- `AgentStories`, `AgentStory`
- `StoryProjection`
- `StoryContent`, `TurnContent`, `RequestContent`, `CallResult`
- `inputLabel` on `DirectHarnessConfig` and `QueuedHarnessConfig`
- `Answered.truncated`, `TurnStarted.arrivedAt`

## 13. Resolutions this record made

Each is a decision the conversation did not reach. James approved them with the record.

1. **The stored policy event `TurnFailed` is renamed `TurnStopped`.** Otherwise the stored
   `TurnFailed` means "a policy stopped the turn" while the story's `TurnFailed` means "the model
   call failed", and the adapter maps one name onto a different one.
2. **`written_at` comes from the engine's clock, not the database's.** It is what lets the live
   envelope and the replay carry the same instant. The cost: instants written by two instances
   compare only as well as their clocks agree. Order is `seq` either way.
3. **Story events carry no content references.** The conversation spoke of an opaque handle on
   the event. This record addresses content by position instead (`TurnId`, `IdempotencyKey`,
   `Seq`), so narration sent to a browser carries no storage references, and the story stays
   free of anything that is not a fact about what happened.
4. **One label for each turn, written when the turn starts.** The conversation assumed a turn
   could take several inputs. It takes one (§6e).
5. **The effect row gains only `parked_at`.** The review of the table proposed a reference to
   the approval request on the row as well. With the deferral stored as an event, its facts are
   in the story, and the row does not need it.
6. **A park step that fails is silent to live listeners.** Today `ApprovalDeferred` is narrated
   whatever happens. As a story event it is heard only if it was stored. `ApprovalSought` is
   still heard first, live.
7. **A decision made after a deferral does not repeat the deferral's facts.** The read
   (`StoryContent.approvalFacts`) takes them from the deferral (§8a). This keeps the fold from
   having to remember them.

## 14. What this record leaves to the next

- An agent's **status**: idle, queued, working, waiting on someone (with the keys), ended. A
  direct read of current state, not a replay.
- The **work in flight**, paginated, for one agent and for all, with the insert-only columns
  (`idempotency_key`, `tool_name`, the effect's kind) that make it a lookup.
- **Settling by `IdempotencyKey`**, joining the caller's transaction, answering `Settled`,
  `AlreadySettled`, `Expired` or `Unknown` from the fence's result. This is where `Replies`
  reporting `Settled` for an answer that lost its fence is fixed.
- A tool reading its own call's decision (F1).
- Following a story: replaying from a position and then carrying on live with no gap. Dropped
  on 2026-10-04: Odyssey resumes its own streams, and nothing else needs it yet.
- Retention and erasure of stored history (F13). This record adds content to the story; what
  expires it is still unanswered.
- The Actuator endpoint and fleet metrics.
