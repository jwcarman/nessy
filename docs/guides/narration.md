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
door refuses to run inside one.

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
| `TurnStarted(turn)` | an input was taken up and a turn opened |
| `ActionsRequested(turn, calls, usage)` | the model asked for tools; each `Call(callId, idempotencyKey, toolName, action)` is what later call events join to by `callId` |
| `CallApproved(callId)`, `CallDenied(callId, reason)` | the decision |
| `CallFinished(callId)`, `CallFailed(callId, message)` | a call's outcome |
| `Answered(turn, usage)` | the turn produced an answer |
| `TurnRefused(turn, category, usage)` | the model declined to answer |
| `TurnFailed(turn, kind, reason, usage)` | a model call failed and ended the turn |
| `TurnStopped(turn, reason)` | a policy stopped the turn; no model call failed |
| `InferenceRetried(turn, kind, reason, usage)` | a model call failed and was tried again; the turn carries on |
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
| `ApprovalDeferred(callId, action, until)` | nobody answered yet; the question stands until `until` |
| `CallDeferred(callId, toolName, until)` | a tool started work and will report back |

`Answered` carries no text: the direct door hands the answer back to the
caller who asked, and anything else watching reads it from the story. A
provider that streams has already said the words delta by delta.

`TurnFailed` carries `reason`, the provider adapter's own account of what
went wrong, and `kind`, a `FailureKind`: `TRANSIENT` (it might work next
time), `UNKNOWN` (nobody heard back), `PERMANENT` (the same request fails
the same way) or `REJECTED` (the provider named the input it refused). It
matters most on the queued door: `QueuedHarness.tell` returns nothing, so
this is the only place a watcher learns why a turn failed. The direct door
hands the same text back from `ask` as `Outcome.Failed`.

`TurnRefused` carries `category`, the provider's own word for why —
unchanged and uninterpreted. A refusal is not a failure: the call
succeeded and the model chose not to answer.

`TurnStopped` carries the policy's `reason` and no `usage`: nothing was
asked of the model. `Answered`, `TurnRefused`, `TurnFailed`,
`InferenceRetried` and `ActionsRequested` carry the `Usage` of the model
call they tell about, including the calls that were retried.

`ApprovalDeferred` is the arm that pays for this whole channel. "Awaiting a
person" is the state an operator most wants to see, and the engine
deliberately does not store it — the fold cannot tell a tool that takes
three days from one that takes 200 milliseconds, and should not learn. So
it is announced rather than recorded, which is the one place it belongs.

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
