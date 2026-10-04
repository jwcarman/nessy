# Work in flight: an agent's status, and settling a call by its key

**Status: DESIGN, NOTHING BUILT. The direction was settled in conversation with James on
2026-10-04; this record writes it down and fills in what the conversation left open. New public
names are in §10 and the resolutions this record made on its own are in §11. Both await
sign-off.**

Date: 2026-10-04. The second of three records. It sits on
`2026-10-04-agent-story-design.md` ("the story record"), which must be built first: this one
uses its `parked_at` column, its stored deferral events, its `CallFailure` kinds, `decidedBy`
and its content read. Every path and name below was checked against `main` at `1fc8c8347`.

**This record changes nothing in the fold.** No command, no event, no guard. Everything here is
a read of current state, an index written beside the fold's own writes, or a change to how an
answer is reported after the fold has decided.

---

## 1. The problem

The story says what happened. Three questions are about now, and nothing public answers them.

- **Is this agent busy?** nessy-ap's evaluation cannot tell when a case is finished. Between
  `tell` and `TurnStarted` an agent with queued input looks idle. While a proposal waits on a
  person, the agent looks busy forever, and a case nobody answers never settles.
- **What is waiting, and on whom?** Every application with a person in the loop has built its
  own table for this: `watchman_pending_approval`, chat-web's `ApprovalDesk`, nessy-ap's
  `pending_decision`. Nessy already keeps the facts: a deferred approval or tool call is a
  parked row in `nessy_agent_effect`, with its deadline.
- **How does an answer get back?** Through `Replies`, with a `ReplyToken`. The application has
  to keep the token, which is a credential, and when an answer arrives too late it is told only
  `NotAwaiting`, which cannot say whether the call was already answered or had expired (nessy-ap
  F4). Worse, `DefaultReplies.settle` reports `Settled` whenever it found a row, even when the
  fold ignored the answer because the call had just expired (`DefaultReplies.java:206-215`).

## 2. The idea

**Nessy does not route, and it keeps the books.** Where a question goes (a page, an email, a
chat message) is the application's approver or tool, and stays so. What is waiting, since when
and until when, is bookkeeping Nessy already does. This record lets an application read it and
settle it.

- **Status** and **work in flight** are direct reads of current state. Neither replays a story.
- **An answer is addressed by the call's `IdempotencyKey`.** The token stays for anything handed
  to an outside system; an application's own code no longer needs to keep one.
- **Every answer comes back through Nessy**, and is told what became of it.

Applications may still keep a table of their own for their own workflow (who must decide, what
the ERP said). They no longer need one to know what is waiting or to answer it.

## 3. What is stored

Existing databases are recreated; there is no migration.

### 3a. The call index

A new insert-only table, one row for each call a model asks for:

```sql
CREATE TABLE IF NOT EXISTS nessy_agent_call
(
    idempotency_key UUID        PRIMARY KEY,
    agent_type      VARCHAR(64) NOT NULL,
    agent_id        UUID        NOT NULL,
    turn            BIGINT      NOT NULL,
    request_seq     BIGINT      NOT NULL,
    call_id         TEXT        NOT NULL,
    tool_name       TEXT        NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
```

It is written in the same transaction that appends the `ActionsRequested` event, from that
event's own calls, by the step that writes the fold's decision. The fold does not know it
exists. It is never updated.

It is what lets a bare key be enough. Given only a key, Nessy finds the agent, the turn and the
request, whether or not the call is still waiting. Without it, a key whose effect row is gone
could only be answered "unknown", and a late answerer could not be told why (§6c).

It holds identifiers and a tool's name, and no content: no arguments, no action line, no
question. Those stay behind the storage codec and are read through the story record's content
read.

### 3b. The effect row says what it is

`nessy_agent_effect` gains two insert-only columns, written once with the row and never
updated:

| Column | Holds |
|---|---|
| `kind` | `INFERENCE`, `APPROVAL` or `TOOL_CALL` |
| `idempotency_key` | the call's key; null for an inference |

and three indexes: on `idempotency_key`; on `(agent_type, created_at, effect_id)` for paging;
and a partial one on `(agent_type, parked_at)` where `parked_at` is not null.

**This amends a rule the table states about itself** (`JdbcEffects.java:322-324`): "No type
here, and no column for one ... a second copy of the same fact, free to disagree." The copy
cannot drift here, for a stated reason: both columns are written in the one `INSERT` that writes
the payload, from the same `AgentEffect`, and no statement updates either. A test decodes every
row's payload and checks the two columns against it.

Nothing else about the table changes: its statuses, the claim, the fences, retire, reschedule.
`parked_at` is the story record's.

## 4. Status

```java
public interface AgentWork {
  AgentStatus status(AgentType type, AgentId id);
  List<InFlight> inFlight(InFlightQuery query);
}

public record AgentStatus(
    Activity activity,
    int queued,                      // inputs told and not yet started
    Optional<TurnId> turn,           // the turn in progress, if one is
    List<InFlight> waiting) {        // the parked calls, when it is waiting

  public enum Activity { IDLE, WORKING, WAITING, ENDED }
}
```

| Activity | Means |
|---|---|
| `IDLE` | no turn in progress and nothing queued. An agent nobody has told anything is idle |
| `WORKING` | a turn is in progress and some of its work is not parked, or input is queued |
| `WAITING` | a turn is in progress and all of its outstanding work is parked: it waits on someone outside. `waiting` says on what |
| `ENDED` | the agent was terminated |

"Is this case finished?" is `IDLE`. "Is it waiting on a person?" is `WAITING`, and the keys say
for what. A call that is running while another is parked is `WORKING`: the agent is not held up
yet.

**Read, not replayed.** Three bounded reads in one read-only transaction:

1. the agent's row and the tail of its story, from its last `TurnStarted`
   (`AgentEvents.sinceLastTurnStarted`, which the engine already uses to rebuild an agent):
   whether a turn is in progress, and whether the agent ended;
2. the size of its backlog;
3. its effect rows: which are parked.

The first is one turn's events, however long the story is. It is what makes status right for a
direct-door agent, which has no effect rows: its turn in progress is in its story.

It is a moment's answer. A status read while a step commits may be a step old.

## 5. Work in flight

```java
public record InFlight(
    Kind kind,
    AgentType agentType,
    AgentId agentId,
    Optional<IdempotencyKey> idempotencyKey,   // empty for a model call
    Optional<ToolName> toolName,
    Instant since,                             // when the work was written
    Optional<Instant> parkedAt,                // when it was handed to someone outside
    Instant deadline,
    int attempts,
    String cursor) {

  public enum Kind { INFERENCE, APPROVAL, TOOL_CALL }
}

public interface InFlightQuery {   // built with a Customizer, as the other configs are
  InFlightQuery agentType(AgentType type);
  InFlightQuery agent(AgentId id);
  InFlightQuery tool(ToolName tool);
  InFlightQuery kind(InFlight.Kind kind);
  InFlightQuery waitingOnly();
  InFlightQuery after(String cursor);
  InFlightQuery limit(int limit);
}
```

One row for each effect row, oldest first. It is always paged: `limit` has a default and a
ceiling, and `after` takes the `cursor` of the last item of the page before, a keyset on
`(created_at, effect_id)`. With no agent given it reads every agent of a type; with no type, the
factory's or the starter's own agent types.

"Every approval waiting on a person, oldest first" is `kind(APPROVAL).waitingOnly()`. Each item's
question, action line and arguments are read through the story record's content read, by the
item's key: `stories.of(type, id).content().question(key)`. The listing itself holds no content.

Because a finished call's row is deleted, the listing never shows finished work, and nothing has
to be swept.

**The direct door has nothing here.** It performs its work inline and writes no effect rows, and
it cannot defer. Its agents appear in `status`, through their story, and never in `inFlight`.

**Only calls Nessy is waiting on are here.** A request an application makes itself and hands
straight to an approver, as nessy-ap's rules do through `PolicyApprover`, is the application's
own. `PolicyApprover` stays usable on its own.

## 6. Settling by key

### 6a. The API

`Replies` gains the key beside the token, and its outcome learns to say why:

```java
public interface Replies {
  ReplyOutcome approve(IdempotencyKey key, ApprovalResult result);
  ReplyOutcome complete(IdempotencyKey key, ToolResult result);

  ReplyOutcome approve(ReplyToken token, ApprovalResult result);     // as today
  ReplyOutcome complete(ReplyToken token, ToolResult result);        // as today
}

public sealed interface ReplyOutcome {
  record Settled() implements ReplyOutcome {}          // this answer is the call's answer
  record AlreadySettled() implements ReplyOutcome {}   // something else answered first
  record Expired() implements ReplyOutcome {}          // its deadline passed first
  record Unknown() implements ReplyOutcome {}          // no such call, or none waiting for this kind of answer
  record Unreadable() implements ReplyOutcome {}       // a token this engine did not issue; tokens only
}
```

`NotAwaiting` is removed; `AlreadySettled`, `Expired` and `Unknown` are what it could not tell
apart. Both forms report the same way. Who decided travels in the `ApprovalResult`, as
`decidedBy` (the story record).

An approver that defers hands the key to wherever the question goes. The button, the webhook or
the page that answers calls `replies.approve(key, ApprovalResult.approvedBy("buyer:j.smith"))`.
It keeps no token.

### 6b. One live request for a key, and the answer must fit it

A key names a call, and a call passes through two stages: awaiting approval, then running. At
most one effect row is live for a key at a time, the `APPROVAL` row or the `TOOL_CALL` row.

- `approve` settles a call that is awaiting approval. `complete` settles one that is running.
- An answer of the wrong kind for the stage settles nothing: `approve` for a call already
  running is `AlreadySettled`; `complete` for one not yet approved is `Unknown`.
- All attempts of a stage are the same operation, which is what the key says. A reply that was
  prompted by an earlier attempt settles the call. This is how a token behaves today, and it
  stays.
- A late answer is never applied. The fold already ignores an answer for a call that is not
  waiting for it, and that does not change.

### 6c. What the caller is told is what the fold decided

Settling is, as now: find the live row; deliver the answer to the fold in a locked step; delete
the row, fenced.

What changes is where the outcome comes from. The locked step reports whether the fold
**accepted** the answer or ignored it, and the outcome is built from that:

| What happened | Outcome |
|---|---|
| the fold accepted the answer | `Settled` |
| the fold ignored it, or no live row was found, and the story shows the call was answered at this stage (`CallApproved`, `CallDenied`, `CallFinished`, or `CallFailed` with `FAILED`) | `AlreadySettled` |
| the same, and the story shows `CallFailed` with `NOT_AUTHORISED` or `PAST_DEADLINE` | `Expired` |
| the key is not in the call index, or the call is not at a stage this answer fits | `Unknown` |

The reason is read from the story only on the path where the answer was not accepted, which is
the rare one: the call index gives the agent and the turn, and the story is read from that
turn's start.

The row's fence stays what it is: a failed delete is logged and ignored, because the call is
discharged either way. It no longer decides what the caller hears. This closes the defect in §1:
an answer the fold ignored is never reported as `Settled`.

Finding the row by key is one indexed lookup on `idempotency_key`. A token still names
`(agent type, agent, request, call)`; it is resolved to the same row, and gets the same reasons.

### 6d. It joins the caller's transaction

Settling writes on the calling thread: the locked fold step and the row's delete. `JdbcRowLocks`
uses `PROPAGATION_REQUIRED`, so both join a transaction the caller has open, as `tell` does. An
application can record its own decision and settle the call in one commit; if it rolls back, the
call is still waiting.

That is the behaviour today, and this record makes it a stated guarantee with a test. Settling
makes no model call, so the direct door's rule against running inside a transaction does not
apply. The dispatcher is nudged after the commit, as it is after `tell`.

### 6e. A tool can see who let it run

`ToolCallRequest` gains `Optional<String> decidedBy()`: what the approval of this call recorded.
`ToolCallHandler` already reads the call out of the story to run it; it reads the call's
`ToolApproved` event as well. The fold is not involved. The question the approval was decided on is
read through the content read, by `request.idempotencyKey()`.

This is nessy-ap's F1.

## 7. What the applications lose

| Application | Today | After |
|---|---|---|
| watchman | `watchman_pending_approval`, a stored reply token, its own "is it still waiting" checks | lists `inFlight(...waitingOnly())`, reads each question by key, answers with `replies.approve(key, ...)`. The table goes |
| chat-web | `ApprovalDesk` holding questions in memory | the same |
| nessy-ap | `pending_decision` with a `reply_token` column, a branch for "applied after the approval had expired", `TurnHistories` to ask whether an agent is busy | keeps `pending_decision` for its own workflow (role, buyer, ERP result), keyed by `IdempotencyKey`; drops the token column and the late-answer branch, and reads `Expired` instead; asks `status` |

The examples are changed as part of this work. They are the proof that an application needs no
desk of its own.

## 8. The fold, and the effect table

**Nothing in the fold changes.** `AgentState`, `AgentCommand`, `AgentEvent` and `Decision` are
not edited. The one touch on the path into the fold is that `AgentEffectCallback.deliverOutcome`
returns whether the fold advanced, which it computes today and discards.

**The effect table's behaviour does not change.** Two insert-only columns and three indexes are
added (§3b). `markRunning`, `complete`, `reschedule` and both fences keep their statements. One
read is added, by key, and one paged read for the listing.

The scrutiny is the story record's (§10c there), applied here to the two places that could
disturb what exists:

- **`deliverOutcome` returning a value.** A test for every existing delivery path proves the
  fold's decision is unchanged, and that the value is true exactly when an event was written.
- **The effect table.** The existing claim, fence and reschedule tests (`JdbcEffectClaimTest`
  and its in-memory twin) pass with no change beyond fixtures. The columns' agreement with the
  payload is tested (§3b).

Both get the high-risk review on Opus.

## 9. Testing

- **Status**, for each activity, on both doors: nothing told; told and queued; in a model call;
  one call running and one parked; everything parked; answered; terminated. A direct-door agent
  mid-`ask` is `WORKING`.
- **In flight.** Pages are stable while rows are added and removed; filters by type, agent, tool,
  kind and waiting; a settled call is gone from the next read; no content in any item.
- **Settle by key**, for each row of §6c, including: an answer racing the deadline, where the
  loser is told `Expired` or `AlreadySettled` and never `Settled`; two answers at once; an
  answer prompted by an earlier attempt; `approve` for a running call; `complete` for an
  unapproved one; a key nobody issued.
- **The transaction.** An answer inside a caller's transaction that rolls back leaves the call
  waiting and the story unchanged; one that commits settles it with the caller's own writes.
- **The call index** holds one row for each requested call and agrees with its event.
- **`decidedBy()`** reaches the tool for an approval made at once and for one made after a
  deferral.
- **The examples** run with no approvals table of their own.

## 10. New public concepts

Settled in conversation with James on 2026-10-04:

- Nessy keeps the books and does not route; the books are read-only to applications, and every
  answer comes back through Nessy
- answers addressed by `IdempotencyKey`; one live request for a key; late answers ignored
- status and work in flight as direct reads, never replays; work in flight is paged
- the schema may change to make this effective
- only calls Nessy is waiting on are in the books

Proposed by this record, awaiting sign-off:

- `AgentWork`, `AgentStatus`, `AgentStatus.Activity` and its four values
- `InFlight`, `InFlight.Kind`, `InFlightQuery`
- `Replies.approve(IdempotencyKey, ...)` and `complete(IdempotencyKey, ...)`
- `ReplyOutcome.AlreadySettled`, `Expired`, `Unknown`, replacing `NotAwaiting`
- `ToolCallRequest.decidedBy()`
- the `nessy_agent_call` table; `kind` and `idempotency_key` on `nessy_agent_effect`

## 11. Resolutions this record made

Each is a decision the conversation did not reach. Each is asked, not assumed.

1. **A call index table.** The conversation spoke of indexed columns on the effect row. Those
   find a call only while it is waiting. To tell a late answerer why, a key must still resolve
   after the row is gone, and that needs a row that stays. The alternative is to make every
   answer name the agent as well as the key.
2. **`Replies` grows; no new interface.** Settling by key is two more methods on `Replies`, and
   the token forms report the same reasons.
3. **`WORKING` covers queued input.** An agent with input waiting and no turn yet is about to
   work. A separate "queued" activity would be a state an application has to handle and almost
   never sees; `queued` is a count beside the activity instead.
4. **A wrong-stage answer:** `approve` for a running call is `AlreadySettled`; `complete` for an
   unapproved call is `Unknown`.
5. **The reason is read from the story,** not kept on a row. It costs a read on the path where
   an answer was late, and nothing anywhere else.
6. **The listing carries no content.** The action line and the question may hold business data
   and stay behind the storage codec; an application reads them by key.
7. **`decidedBy()` on `ToolCallRequest`** is read by the handler from the story, so the fold
   does not have to carry it to the tool.
8. **The effect table's own rule against a type column is amended** (§3b).

## 12. What this record leaves

- The Actuator endpoint, which shows `status` and `inFlight` for an operator and is exposed only
  when an application asks for it.
- Fleet metrics: approvals waiting, the longest wait, deadlines about to pass, agents in a turn.
- Retention and erasure (F13). The call index is one more thing that grows with history.
- Accepting requests no Nessy call is waiting on.
- A trace id on story events.
