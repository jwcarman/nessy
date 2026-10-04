# Narration

An agent narrates what it is doing as `Narration`, and a `NarrationListener`
is whoever hears it. That is how a page streams a reply, how a console
prints a session, and how background work such as cutting history into chapters
knows a turn has ended.

```java
public interface NarrationListener {
  void on(Narrated narrated);
}
```

Every event arrives in a `Narrated` envelope: the agent type and id it
belongs to, so one listener can serve every agent, and the event itself.

A story event also carries its position: the `seq` it was stored at and the
time it was written, `narrated.position()`. The time is the engine's clock,
read once for the step that wrote the event, so an event heard as it commits
and the same event read back from the store later carry the same `seq` and
the same instant. A live signal was never stored and has no position, so
`narrated.position()` is empty for it.

## Where a listener attaches

At the engine, to hear every harness:

```java
DefaultDirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .listener(audit)
        ...);
```

At a harness, to hear its agents alone:

```java
factory.create(new AgentType("reporter"), config -> config.listener(reporter.listener()) ...);
```

The engine's listeners are told first, then the harness's own, in the order
they were attached. In a Boot application every `NarrationListener` bean is
attached to the engine once the context has started.

## After the commit, off the fold, in order

A listener hears an event only after the step that wrote it has committed.
The doors write an agent's events in short locked steps, which on a database
are transactions, and what a step narrates is held until the step returns.
So a listener is never told about something a rollback then undid, a step
that fails is never heard at all, and a listener that reads the history finds
what it was just told about: the turn whose end it hears is already stored.
The one exception is a queued harness's `tell` called from inside an
application's own transaction: the step joins that transaction, and is heard
when it returns, before the application commits or rolls back. The direct
door refuses to run inside one. The event heard then carries a position. If the
application rolls back, that position is not in the story, and its `seq` is used
again by the next event that is stored. A listener that keeps positions should
not treat one heard inside an application's transaction as stored until that
transaction commits.

Order holds per agent: events are heard in the order their steps committed,
and what one agent has not yet released never holds up another agent. What an
agent narrates outside a step, such as the deltas of a streaming reply,
is heard as it happens, but never ahead of a step that took the agent's lock
before it.

Narration never runs on the thread that folds a turn. Each harness tells
its listeners on one thread of its own, in order, and a listener that
throws is logged and skipped, so a broken listener costs the others
nothing.

A listener that does real work — a model call, a slow write — wraps itself:

```java
NarrationListener listener = NarrationListener.of(on -> on
        .agentType(TYPE)
        .onTurnEnding((narrated, ended) -> reporter.reportIfDue(narrated.agentId())))
    .async();
```

`async()` runs the listener on its own thread for every event. Order across
events is not preserved: two events may be handled at once, and the later
may finish first. Right for work that reads the story rather than the
event, wrong for a stream a person is reading. See
[Observability](observability.md#background-work) for what that thread
carries with it.

## The builder

`NarrationListener.of(...)` builds a listener from handlers, filtered by
agent type if you like, with one method per event kind or `on(Class,
handler)` for any of them. A handler is given the envelope and the event
already cast to its kind:

```java
NarrationListener console = NarrationListener.of(on -> on
        .onContentDelta((narrated, delta) -> out.print(delta.text()))
        .onThinkingDelta((narrated, delta) -> dim(delta.text()))
        .onActionsRequested((narrated, asked) -> asked.calls()
                .forEach(call -> note("calling " + call.toolName() + ": " + call.action())))
        .onApprovalDeferred((narrated, waiting) -> note("asked, until " + waiting.until()))
        .onTurnEnding((narrated, ended) -> out.println()));
```

## The kinds

`Narration` has two groups. A story event says something the agent's record
holds, and is told once the fold that wrote it has committed. A live signal
is heard only as it happens: nothing is stored, so a watcher that was not
listening never hears it.

### Story events (stored)

| Event | When |
|---|---|
| `TurnStarted(turn, label, arrivedAt)` | an input was taken up and a turn opened; `label` says what started it and `arrivedAt` is when its input reached the harness. How long the input waited is the event's time minus `arrivedAt` |
| `ActionsRequested(turn, calls, usage)` | the model asked for tools; each `Call(callId, idempotencyKey, toolName, action)` is what later call events join to, by `callId` or by `idempotencyKey` |
| `CallApproved(callId, idempotencyKey, decidedBy)`, `CallDenied(callId, idempotencyKey, reason, decidedBy)` | the decision, and who or what decided it (`decidedBy` is empty when nobody is named; Nessy never interprets it) |
| `CallFinished(callId, idempotencyKey)`, `CallFailed(callId, idempotencyKey, kind, message)` | a call's outcome; `kind` is a `CallFailure`: `FAILED` (the tool ran and failed, or could not be run), `PAST_DEADLINE` (the call did not finish before its deadline, and whether it ran is not known) or `NOT_AUTHORISED` (permission was never given: the approval's deadline passed, or the approver failed) |
| `Answered(turn, truncated, usage)` | the turn produced an answer; `truncated` is true when the model was cut off at its output limit and the answer stops short |
| `TurnRefused(turn, category, usage)` | the model declined to answer |
| `TurnFailed(turn, kind, reason, usage)` | a model call failed and ended the turn |
| `TurnStopped(turn, reason)` | a policy stopped the turn; no model call failed |
| `InferenceRetried(turn, kind, reason, usage)` | a model call failed and was tried again; the turn carries on |
| `ApprovalDeferred(callId, idempotencyKey, until)` | a call waiting on an approval was put aside; the question stands until `until` |
| `CallDeferred(callId, idempotencyKey, until)` | a running call was put aside; the tool will report back, and the call stands until `until` |
| `Terminated` | the agent will accept nothing further |

A turn ends in exactly one of four events: `Answered`, `TurnRefused`,
`TurnFailed` or `TurnStopped`. They share the group `TurnEnding`, and
`onTurnEnding` hears all four. It is what the engine's chapter keeper
listens for.

### Live signals

| Event | When |
|---|---|
| `Thinking` | the model is being asked |
| `ThinkingDelta(text)`, `ContentDelta(text)` | reasoning and prose, as they stream |
| `Commentary(text)` | prose the model said beside a request for actions |
| `ApprovalSought(callId, action)` | somebody is being asked whether a call may run |

`Answered` carries no text: the direct door hands the answer back to the
caller who asked, and anything else watching reads it from the story. A
provider that streams has already said the words delta by delta.

`TurnFailed` carries `reason`, the provider adapter's own account of what
went wrong, and `kind`, a `FailureKind`: `TRANSIENT` (it might work next
time), `UNKNOWN` (nobody heard back), `PERMANENT` (the same request fails
the same way) or `REJECTED` (the provider named the input it refused). It
matters most on the queued door: `QueuedHarness.tell` returns nothing, so
a watcher learns why a turn failed here as it happens, and
`AgentStories.replay` reads it afterwards. The direct door
hands the same text back from `ask` as `Outcome.Failed`.

`TurnRefused` carries `category`, the provider's own word for why —
unchanged and uninterpreted. A refusal is not a failure: the call
succeeded and the model chose not to answer.

`TurnStopped` carries the policy's `reason` and no `usage`: nothing was
asked of the model. `Answered`, `TurnRefused`, `TurnFailed`,
`InferenceRetried` and `ActionsRequested` carry the `Usage` of the model
call they tell about, including the calls that were retried.

`ApprovalDeferred` and `CallDeferred` are story events: each is told from a
stored event, `AgentEvent.ApprovalDeferred` and `AgentEvent.ToolDeferred`.
They name the call and its key and not the action or the tool. A watcher that
wants those joins by `idempotencyKey` to the `ActionsRequested.Call` it heard
earlier.

On the wire each kind has a kebab-case name, `turn-stopped`, `content-delta`,
`approval-deferred`, carried as the JSON `type` field.

## Reading the story afterwards

A listener hears only what happens while it is attached. The story is also
stored, and `AgentStories` reads it back:

```java
AgentStory story = stories.of(new AgentType("reporter"), agentId);

List<Narrated> page = story.replay(Seq.NONE, 100);
Seq last = page.getLast().position().orElseThrow().seq();
List<Narrated> next = story.replay(last, 100);
```

`replay(after, limit)` returns up to `limit` story events after `after`,
oldest first. `Seq.NONE` reads from the start. A limit above 1,000 is treated
as 1,000, and a limit of zero or less is refused. An agent with no story has an
empty one.

Each element is a `Narrated` with its position, and it equals the one a
listener heard as the event was stored: the same event, at the same `seq`,
written at the same instant. Only story events are replayed; a live signal
was never stored. In a Boot application an `AgentStories` bean is configured
over the stored events of both doors.

## Reading content

The story never carries content: no message text and no tool results. An event
holds identifiers, status and a reference. What the references point to is read
separately, with `AgentStory.content()`, and every read is addressed by a turn,
a key or a position.

```java
StoryContent content = stories.of(new AgentType("reporter"), agentId).content();

TurnContent turn = content.turn(turnId);
List<Block.InputContent> input = turn.input();
Optional<List<Block.AnswerContent>> answer = turn.answer();

Optional<List<Block.ToolResultContent>> result = content.result(idempotencyKey);

List<CallResult> results = content.results(Seq.NONE, 100);
```

- `turn(turnId)` returns the turn's input, what the model wrote each time it
  asked for tool calls (a `RequestContent` with the position of its event), and
  its answer. The answer is empty when the turn did not end in one. A turn that
  is not in the agent's story is refused.
- `result(key)` returns what the call with that `IdempotencyKey` returned. It is
  empty when the key is not in this agent's story, or when the call did not
  succeed.
- `results(after, limit)` returns up to `limit` successful results after a
  position, oldest first. Each `CallResult` has the position of the event that
  recorded the success and the call's key. The limit rule is the one `replay`
  uses.
- `allResults(after)` streams every successful result after a position, oldest
  first. It reads a page at a time as the stream is consumed, holds nothing
  open, and need not be closed. An operation that stops early stops the
  reading:

```java
boolean grounded =
    content.allResults(Seq.NONE).anyMatch(result -> result.idempotencyKey().equals(key));
```

Content can be removed on a different schedule from the story. A reference
with nothing behind it is a fault, and is thrown as an `IllegalStateException`
that names the reference.

## Projections

To fold the whole story into one value, give `AgentStory.project` a
`StoryProjection`. It starts from `initial()` and calls `apply` once for each
event, oldest first:

```java
StoryProjection<Integer> turns = new StoryProjection<>() {
    public Integer initial() {
        return 0;
    }

    public Integer apply(Integer soFar, Narrated story) {
        return story.event() instanceof Narration.TurnStarted ? soFar + 1 : soFar;
    }
};

int started = stories.of(new AgentType("reporter"), agentId).project(turns);
```

For a projection that fits in a lambda, `StoryProjection.of` is the short form:

```java
int started =
    stories
        .of(new AgentType("reporter"), agentId)
        .project(
            StoryProjection.of(
                0, (n, story) -> story.event() instanceof Narration.TurnStarted ? n + 1 : n));
```

`project` reads the agent's whole story each time it is called, so its cost
grows with the story. Do not call it on a hot path, such as a poll or every
request.

The story is read a page at a time, so a long story is never held whole. An
exception the projection throws reaches the caller unchanged. A projection is
given the story, not the content of what was said. `UsageReports` is a
projection: it adds the `Usage` of every event that records a model call.

## Streams for a browser

`nessy-narration-odyssey` is a listener that publishes every event to an
[Odyssey](https://github.com/jwcarman/odyssey) stream named
`nessy/<type>/<id>`, one per agent, typed as `OdysseyStream<Narration>`. A
stream outlives the process and remembers its entries, so a browser that
reconnects picks up where it left off:

```java
@GetMapping("/{id}/events")
public SseEmitter events(@PathVariable String id,
                         @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return streams.resume(TYPE, agent(id), lastEventId);
}
```

The browser sends `Last-Event-ID` on reconnect by itself, so resuming is one
line. The stream holds what listeners were told, so it holds only steps that
committed, in the order they committed. In a Boot application with an `Odyssey` bean present, `AgentStreams`
and the `OdysseyNarrator` are auto-configured, and the narrator is attached
as a listener like any other bean. Retention is `nessy.narration.odyssey.*`:
a day of inactivity and a day per entry by default, an hour once a stream
is completed.

`nessy-examples/chat-web` streams both the conversation and the approval
desk's cards this way, on two streams.

## Where next

- [Events](../concepts/events.md), the durable record narration is announced alongside
- [Observability](observability.md), spans and metrics, which are a different thing
- [Spring Boot](spring-boot.md), the beans that attach listeners
