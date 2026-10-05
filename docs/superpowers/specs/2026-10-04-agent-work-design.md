# Work in flight: an agent's status, its waiting approvals, and answering a call by its key

**Status: APPROVED by James on 2026-10-05, after revision in conversation that day. NOTHING BUILT. The
rulings in §9 are his. He approved the proposals in §10 with the record.**

Date: 2026-10-04. The second of three records. It sits on
`2026-10-04-agent-story-design.md` ("the story record"), which is built: this one uses its
`parked_at` column, its stored deferral events, the facts on those events, `decidedBy` and its
content read. Every path and name below was checked against `main` at `cef1b56f3`.

**This record changes nothing in the fold.** No command, no event, no guard. **It adds no table,
no column and no index.** Everything here is a read of what is already stored, or a change to how
an answer is addressed and reported.

---

## 1. The problem

The story says what happened. Three questions are about now, and nothing public answers them.

- **Is this agent busy?** nessy-ap's evaluation cannot tell when a case is finished. Between
  `tell` and `TurnStarted` an agent with queued input looks idle. While a proposal waits on a
  person, the agent looks busy forever.
- **Which approvals are waiting on a person?** Every application with a person in the loop has
  built its own store for this: `watchman_pending_approval`, chat-web's `ApprovalDesk`,
  nessy-ap's `pending_decision`. Each is a second copy of what Nessy already holds, and each can
  drift from it. A deferred approval is a parked row in `nessy_agent_effect`, with its deadline,
  and everything a person is shown about it is in the agent's story.
- **How does an answer get back?** Through `Replies`, with a `ReplyToken`: an encrypted address
  that needs a configured secret, a minting step and a field on every request. The caller already
  holds the same address in the clear: the agent type, the agent id and the call's
  `IdempotencyKey`. And `DefaultReplies.settle` reports `Settled` whenever it found a row, even
  when the fold then ignored the answer, so two answers that race are both told they counted.

## 2. The idea

**Nessy does not route, and it keeps the books.** Where an approval request goes (a page, an
email, a chat message) is the application's approver, and stays so. What is waiting, since when
and until when, is bookkeeping Nessy already does. This record lets an application read it and
answer it.

- **Status** is a direct read of an agent's current state.
- **Waiting approvals** are read as `ApprovalRequest`s: the same object the approver was shown,
  rebuilt from what is stored.
- **An answer names the agent and the call's key.** That is the one way to answer. The reply
  token is removed.
- **An answer is applied or ignored,** and the caller is told which.

What Nessy records is for audit, not replay, and it stores only what cannot be rebuilt from
immutable stored data. So nothing new is stored for any of this.

Applications may still keep a table of their own for their own workflow (who must decide, what
the ERP said). They no longer need one to know what is waiting or to answer it.

## 3. The API

```java
public interface AgentWork {
  AgentStatus status(AgentType type, AgentId id);
  List<ApprovalRequest> waitingApprovals();                // every agent type served here
  List<ApprovalRequest> waitingApprovals(AgentType type);  // one agent type
}
```

It is read-only. A harness factory offers it beside `replies()`, and the Spring Boot starter
offers it as a bean.

## 4. Status

```java
public record AgentStatus(
    Activity activity,
    int queued,                               // inputs told and not yet started
    Optional<TurnId> turn,                    // the turn in progress, if one is
    List<ApprovalRequest> waitingApprovals,   // the approvals it waits on
    int waitingToolCalls) {                   // the deferred tool calls it waits on

  public enum Activity { IDLE, WORKING, WAITING, ENDED }
}
```

| Activity | Means |
|---|---|
| `IDLE` | no turn in progress and nothing queued. An agent nobody has told anything is idle |
| `WORKING` | something can make progress on its own: a turn is in progress and some of its work is not parked, or no turn is in progress and input is queued |
| `WAITING` | a turn is in progress and all of its outstanding work is parked. Nothing moves until someone outside answers. Input queued behind it does not change this, since it cannot start until the turn ends |
| `ENDED` | the agent was terminated: its story ends in `Terminated` |

"Is this case finished?" is `IDLE`. "Is it waiting on a person?" is `WAITING`, and
`waitingApprovals` says for what. A call that is running while another is parked is `WORKING`:
the agent is not held up yet. `queued`, `waitingApprovals` and `waitingToolCalls` are reported
beside the activity in every case.

An agent told to terminate while a turn is in progress is `WORKING` or `WAITING` until that turn
ends and `Terminated` is written.

**Read, not replayed.** Three bounded reads:

1. the tail of the agent's story from its last `TurnStarted`
   (`AgentEvents.sinceLastTurnStarted`, which the engine already uses to rebuild an agent):
   whether a turn is in progress, and whether the agent ended;
2. the size of its backlog, through one new backend read (§7);
3. its live effect rows: which are parked now.

The first is one turn's events, however long the story is. It is what makes status right for a
direct-door agent, which has no effect rows: its turn in progress is in its story.

**"Parked now" has an exact meaning.** `parked_at` is never cleared, so a row is parked now when
`parked_at` is set, its status is `RUNNING`, and its deadline has not passed.

It is a moment's answer. A status read while a step commits may be a step old. Nothing is
remembered in process between calls.

## 5. Waiting approvals

`waitingApprovals` returns one `ApprovalRequest` for each approval that is parked now, oldest
first. It is the request the approver was shown, rebuilt:

| Field | Read from |
|---|---|
| `agentType`, `agentId` | the effect row |
| `turn`, `callId`, `idempotencyKey`, `toolName` | the row's stored effect |
| `action` | the story: the call's entry in the `ActionsRequested` event |
| `arguments` | the story: that event's stored request content |
| `facts` | the story: the call's `ApprovalDeferred` event, as the approver left them |
| `askedAt` | the row's `parked_at`: when the approver deferred |
| `deadline` | the effect row |

A waiting approval always belongs to its agent's turn in progress, so the story read for each
item is the same bounded read status makes: that agent's events from its last `TurnStarted`.

A page shows the request and, when a person decides, answers with three of its fields:

```java
for (ApprovalRequest request : work.waitingApprovals()) {
  // show request.toolName(), request.action(), request.facts(), request.deadline()
}

replies.approve(
    request.agentType(), request.agentId(), request.idempotencyKey(),
    ApprovalResult.approvedBy("buyer:j.smith"));
```

**How it is read.** The store returns rows that are parked now, filtered in SQL by agent type
when one is named, oldest first by `(created_at, effect_id)`. The engine decodes each row's
effect and keeps the approvals. The kind of work is inside the row's payload and has no column;
the effect table's rule against a type column stands. The table holds only live work.

A row whose payload cannot be decoded is skipped and logged at WARN. It never fails the read.

Because a finished call's row is deleted, the list never shows finished work, and nothing has to
be swept.

**The direct door has nothing here.** It performs its work inline and writes no effect rows, and
it cannot defer. Its agents have a `status`, through their story, and no waiting approvals.

**Only approvals Nessy is waiting on are here.** A request an application makes itself and hands
straight to an approver, as nessy-ap's rules do through `PolicyApprover`, is the application's
own.

## 6. Answering a call

### 6a. The API

```java
public interface Replies {
  ReplyOutcome approve(AgentType type, AgentId id, IdempotencyKey key, ApprovalResult result);
  ReplyOutcome complete(AgentType type, AgentId id, IdempotencyKey key, ToolResult result);
}

public sealed interface ReplyOutcome {
  record Applied() implements ReplyOutcome {}   // the fold took the answer; the agent's state changed
  record Ignored() implements ReplyOutcome {}   // nothing changed
}
```

These are the only two methods and the only two outcomes. Who decided travels in the
`ApprovalResult`, as `decidedBy`.

An approver or a tool that defers hands the three values to wherever the answer will come from.
`ApprovalRequest` and `ToolCallRequest` both carry them already.

### 6b. The reply token is removed

`ReplyToken`, the token methods on `Replies`, `replyToken()` on `ApprovalRequest` and
`ToolCallRequest`, `ReplyOutcome.Unreadable`, the engine's token minting with its encryption
keys, and the starter's `reply-token-encryption-keys` property are deleted.

**Nessy does not authenticate the caller of `Replies`.** Anyone who can reach an application's
answer endpoint with the three values can answer that call. Guarding that endpoint is the
application's job, as it was: a token proved only that Nessy issued a link, never who clicked it.

### 6c. Applied or ignored

A key names a call, and a call passes through two stages: awaiting approval, then running. At
most one effect row is live for a key at a time.

Answering is: look up the agent type; read the agent's running rows and find the one whose
effect carries the key and fits the kind of answer (`approve` fits a call awaiting approval,
`complete` fits one that is running); deliver the answer to the fold in a locked step; delete the
row, fenced, as today.

| What happened | Outcome |
|---|---|
| the fold took the answer and wrote it to the story | `Applied` |
| the fold ignored it; or no live row fits the key and the kind of answer; or the agent type is not served here | `Ignored` |

`Ignored` covers every reason: already decided, past its deadline, the wrong kind of answer for
the call's stage, a key or an agent this engine does not know. A caller does the same thing in
each case, and the agent's story holds the detail for anyone who needs it. The record cannot
always tell the reasons apart: an approval whose deadline passed and one whose approver failed
are both recorded as `NOT_AUTHORISED`.

`Applied` is a promise about the agent's state. To keep it,
`AgentEffectCallback.deliverOutcome` returns whether the fold wrote an event for the outcome.
Today the harness tracks only whether the step emitted new effects, which is false for an
accepted answer that asks for nothing more, such as a denial while other calls are outstanding.
The two are separate values inside the harness; the dispatcher's nudge is unchanged.

The row's fence stays what it is: a failed delete is logged and ignored, because the call is
discharged either way. It does not decide what the caller hears.

All attempts of a stage are the same operation, which is what the key says. An answer prompted
by an earlier attempt applies to the call.

### 6d. It joins the caller's transaction

Answering writes on the calling thread: the locked fold step and the row's delete.
`JdbcRowLocks` uses `PROPAGATION_REQUIRED`, so both join a transaction the caller has open, as
`tell` does. An application can record its own decision and answer the call in one commit; if it
rolls back, the call is still waiting.

That is the behaviour today, and this record makes it a stated guarantee with a test.

**Known limit, not changed here.** When a reply joins a caller's transaction, narration and the
dispatcher's nudge happen when the locked step returns, which is before the caller commits.
`tell` behaves the same way.

### 6e. A tool and its approval share the key

The `ApprovalRequest` and the `ToolCallRequest` of one call carry the same `IdempotencyKey`, so
a record an approver keyed by it is one the tool can read by it. Nothing is added to
`ToolCallRequest`.

## 7. What changes underneath

**Nothing in the fold changes.** `AgentState`, `AgentCommand`, `AgentEvent`, `Decision`,
`TurnTally` and `OutstandingAction` are not edited.

**The effect table does not change.** No column, no index. `INSERT`, the claim, `complete`,
`reschedule`, `park` and both fences keep their statements byte for byte. The backend SPI gains
reads only:

- one read of live effect rows with their parked time, deadline and status, for one agent
  (status) and for the rows parked now across agents (waiting approvals);
- `QueuedBackend.queued(AgentType, AgentId)`: the number of inputs told and not yet started. The
  backlog is typed on the application's input type and held privately by the harness, so nothing
  type-free can count it today.

Two places could disturb what exists, and both get the high-risk review on Opus:

- **`deliverOutcome` returning a value.** A test for every existing delivery path proves the
  fold's decision and the stored story are unchanged, that the value is true exactly when an
  event was written, and that the nudge happens exactly when it does today.
- **The effect store.** The existing claim, fence, reschedule and park tests pass unchanged.

## 8. What the applications lose

| Application | Today | After |
|---|---|---|
| watchman | `watchman_pending_approval`, a stored reply token, its own "is it still waiting" checks, an encryption key in its configuration | lists `work.waitingApprovals()`, shows each request, answers with the agent and the key. The table and the key go |
| chat-web | on the direct door: its approver holds the turn on an in-memory desk until a person answers | unchanged by this work. It cannot defer on the direct door, so it has no waiting approvals to list. Moving it to the queued door, where it defers and reads `AgentWork`, is follow-on work |
| nessy-ap | `pending_decision` with a `reply_token` column, `TurnHistories` to ask whether an agent is busy | keeps `pending_decision` for its own workflow (role, buyer, ERP result), keyed by `IdempotencyKey`; drops the token column and the key property; treats `Ignored` as it treated `NotAwaiting`; asks `status` |

The watchman is changed as part of this work. It is the proof that an application needs no
desk of its own.

## 9. Rulings

James's, in conversation on 2026-10-04 and 2026-10-05:

1. Nessy keeps the books and does not route. The books are read-only to applications, and every
   answer comes back through Nessy.
2. No call index table. An answer names the agent as well as the key.
3. Store only what cannot be rebuilt from immutable stored data. The record is for audit, not
   replay. No new columns or indexes on the effect row.
4. One way to answer. The reply token and everything that serves it is removed.
5. `ReplyOutcome` stays a sealed type, as the house style is, with two arms: an answer is
   `Applied` or `Ignored`.
6. The listing an application needs is its waiting approvals, and each is an `ApprovalRequest`.
   A general listing of work in flight is not built.
7. A backend read for queue depth.
8. An agent whose outstanding work is all parked is `WAITING`, with or without input queued
   behind it. An agent with input queued and no turn yet is `WORKING`; there is no "queued"
   activity.
9. It is an approval request, never a "question". Nessy does not store the approval request; it
   stores the facts, on the event.

## 10. Resolutions this record made

Each is a choice the conversation did not reach. James approved them with the record.

1. **`AgentStatus` reports waiting approvals as `ApprovalRequest`s and deferred tool calls as a
   count** (`waitingToolCalls`). A deferred tool call has no request to show a person, and no
   caller has asked to list them.
2. **`askedAt` of a rebuilt request is the row's `parked_at`.** The engine does not store the
   instant it first asked the approver; `parked_at` is written in the same step as the deferral,
   a moment later.
3. **The list is capped at 500, oldest first.** When more are waiting, the oldest 500 are
   returned and the rest appear as those are answered. There is no paging and no cursor.
4. **No form of `waitingApprovals` for one agent.** `status` already reports that agent's.
5. **`ENDED` is read from the story,** the same way on both doors.

## 11. Testing

- **Status**, for each activity, on both doors: nothing told; told and queued; in a model call;
  one call running and one parked; everything parked; everything parked with input queued behind
  it (`WAITING`, with the count); answered; terminated; told to terminate mid-turn. A parked row
  past its deadline does not make an agent `WAITING`. A direct-door agent mid-`ask` is `WORKING`.
- **Waiting approvals.** Each rebuilt request equals the one the approver was shown, field for
  field, except `askedAt`; the facts are the approver's; oldest first; one agent type only when
  one is named; an answered or expired approval is gone from the next read; a deferred tool call
  is not listed; a row that cannot be decoded is skipped; the cap.
- **Answering.** `Applied` for an approval, a denial and a deferred tool's result; a denial while
  other calls are outstanding is `Applied`; a second answer, two answers at once, an answer after
  the deadline, the wrong kind of answer, an unknown key, the right key for another agent and an
  agent type not served here are each `Ignored` and write nothing; a call id repeated in a later
  request of the same turn is told apart by its key.
- **The transaction.** An answer inside a caller's transaction that rolls back leaves the call
  waiting and the story unchanged; one that commits answers it with the caller's own writes.
- **The token is gone.** No reply token, key or property remains in main code, configuration or
  the documentation site.
- **The examples** run with no approvals store of their own.

## 12. New public names

- `AgentWork`; `AgentStatus`; `AgentStatus.Activity` with `IDLE`, `WORKING`, `WAITING`, `ENDED`
- `Replies.approve` and `complete` taking an agent type, an agent id and a key
- `ReplyOutcome.Applied` and `Ignored`, replacing `Settled` and `NotAwaiting`
- in the backend SPI: the live-row read on `Effects`, and `QueuedBackend.queued`

Removed: `ReplyToken`; the token methods on `Replies`; `replyToken()` on `ApprovalRequest` and
`ToolCallRequest`; `ReplyOutcome.Unreadable`; the `reply-token-encryption-keys` property.

## 13. What this record leaves

- A general listing of work in flight (model calls, running and deferred tool calls) for an
  operator, and an Actuator endpoint over it.
- Fleet metrics: approvals waiting, the longest wait, deadlines about to pass, agents in a turn.
- Retention and erasure (F13).
- Accepting requests no Nessy call is waiting on.
- A trace id on story events.
