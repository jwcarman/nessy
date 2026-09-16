# Events

An agent narrates what it is doing as `AgentEvent`s, and an
`AgentEventListener` is whoever hears them. That is how an answer reaches
you, how a page streams a reply, and how background work such as
summarising knows a turn has ended.

```java
public interface AgentEventListener {
  void on(AgentType agentType, AgentId agentId, AgentEvent event);
}
```

Every event arrives with the agent type and id it belongs to, so one
listener can serve every agent.

## Where a listener attaches

At the engine, to hear every harness:

```java
new DefaultHarnessFactory(engine -> engine.listener(audit) ...);
factory.listener(audit);          // or after construction; harnesses already made hear it too
```

At a harness, to hear its agents alone:

```java
factory.create(config -> config.listener(summarizer.listener()) ...);
```

The engine's listeners are told first, then the harness's, in the order
they were attached. In a Boot application every `AgentEventListener` bean
is attached to the engine once the context has started.

## Off the fold, in order

Narration never runs on the thread that folds a turn. Each harness tells
its listeners on one virtual thread of its own, in order, and a listener
that throws is logged and skipped, so a slow or broken listener costs
nothing but its own lateness.

A listener that does real work, a model call, a slow write, wraps itself:

```java
AgentEventListener listener = AgentEventListener.of(on -> on
        .agentType(TYPE)
        .onTurnEnded((type, id, ended) -> summarizer.summarizeIfDue(id)))
    .async();
```

`async()` tells it on a virtual thread per event. Order across events is
not kept, which is right for work that reads the story rather than the
event, and wrong for a stream a person is reading.

## The builder

`AgentEventListener.of(...)` builds a listener from handlers, filtered by
agent type if you like, with one method per event kind or `on(Class,
handler)` for any of them:

```java
AgentEventListener console = AgentEventListener.of(on -> on
        .onContentDelta((type, id, delta) -> out.print(delta.text()))
        .onThinkingDelta((type, id, delta) -> dim(delta.text()))
        .onActionsRequested((type, id, asked) -> note("calling " + asked.toolNames()))
        .onApprovalDeferred((type, id, waiting) -> note("asked, until " + waiting.until()))
        .onTurnEnded((type, id, ended) -> out.println()));
```

## The eighteen kinds

| Event | When |
|---|---|
| `TurnStarted(turn, observation)` | an observation opened a turn |
| `Thinking` | the model is being asked |
| `ThinkingDelta(text)`, `ContentDelta(text)` | reasoning and prose, as they stream |
| `Commentary(text)` | prose the model said beside a request for actions |
| `ActionsRequested(toolNames)` | the model asked for tools |
| `ApprovalSought(callId, action)` | somebody is being asked whether a call may run |
| `ApprovalDeferred(callId, action, until)` | nobody answered yet; the question stands until `until` |
| `CallApproved(callId)`, `CallDenied(callId, reason)` | the decision |
| `CallDeferred(callId, toolName, until)` | a tool started work and will report back |
| `CallFinished(callId)`, `CallFailed(callId, message)` | a call's outcome |
| `Answered(text)` | the model's prose answer |
| `TurnFailed`, `TurnRefused` | how a turn ended when it was not an answer |
| `TurnEnded(turn)` | the turn is over, however it ended; the one a summariser listens for |
| `Terminated` | the agent was ended |

`ApprovalDeferred` is the arm that pays for the channel. "Awaiting a
person" is the state an operator most wants to see, and the engine
deliberately does not store it: the fold cannot tell a tool that takes
three days from one that takes 200 milliseconds. So it is announced rather
than recorded, which is the one place it belongs.

On the wire each kind has a kebab-case name, `turn-ended`, `content-delta`,
`approval-deferred`, carried as the JSON `type` field.

## Streams for a browser

`nessy-narration-odyssey` is a listener that publishes every event to an
[Odyssey](https://github.com/jwcarman/odyssey) stream named
`nessy/<type>/<id>`, one per agent, typed as `OdysseyStream<AgentEvent>`.
A stream outlives the process and remembers its entries, so a browser that
reconnects picks up where it left off:

```java
@GetMapping("/{id}/events")
public SseEmitter events(@PathVariable String id,
                         @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
    return streams.resume(TYPE, agent(id), lastEventId);
}
```

The browser sends `Last-Event-ID` on reconnect by itself, so resuming is one
line. In a Boot application with an `Odyssey` bean present, `AgentStreams`
and the `OdysseyNarrator` are auto-configured, and the narrator is attached
as a listener like any other bean. Retention is `nessy.narration.odyssey.*`:
a day of inactivity and a day per entry by default.

`nessy-examples/chat-web` streams both the conversation and the approval
desk's cards this way, on two streams.

## Where next

- [Observability](observability.md), spans and metrics, which are a different thing
- [Memory](../concepts/memory.md), the head summariser, an event-driven listener
- [Spring Boot](spring-boot.md), the beans that attach listeners
