# Observability

Three separate things, and it helps to keep them apart:

- **Narration** — what an agent is doing, for a person or a UI. Delivered as
  it happens.
- **Traces** — the span tree, for debugging one turn after the fact.
- **Metrics** — counts and timings, for a dashboard.

## Narration

Subscribe to an agent and you get `AgentEvent`s as they happen:

```java
try (AgentSubscription subscription = harness.subscribe(agentId, event -> {
        switch (event) {
            case AgentEvent.TextDelta delta -> System.out.print(delta.text());
            case AgentEvent.ToolCallRequested call -> log.info("calling {}", call.toolName());
            case AgentEvent.TurnEnded ended -> log.info("done: {}", ended.outcome());
            default -> { }
        }
    })) {
    harness.observe(agentId, "hello");
}
```

| Event | When |
|---|---|
| `TurnStarted` | a turn began |
| `TextDelta` | prose, as it streams |
| `ReasoningDelta` | reasoning, as it streams |
| `ToolCallRequested` | the model asked for a tool, with the action renderer's sentence |
| `ApprovalRequested` | a call was actually put to a person, with its deadline |
| `ApprovalDecided` | approved or denied |
| `ToolCallCompleted` | a call answered, with its result |
| `Answered` | the model's prose answer |
| `TurnEnded` | how it ended, with what it cost |

`ApprovalRequested` fires only when an approver **defers** — an ungated tool
is approved on the spot, and an event for that would be noise claiming a
person was asked.

**Close the subscription.** An unclosed one leaks a routing entry.

### Resuming

Every event carries a time-ordered id, so a listener that dropped off can
resume from the last one it saw:

```java
harness.subscribe(agentId, subscriber, lastEventIdItSaw);
```

Over SSE that costs one line, because a browser sends `Last-Event-ID` on
reconnect by itself:

```java
@GetMapping("/{id}/events")
public SseEmitter events(@PathVariable String id,
                         @RequestHeader(name = "Last-Event-ID", required = false) String cursor) {
    return streams.open(AgentId.of(id), cursor);
}
```

The cursor takes effect when the *subscription* is created. One subscription
serves every tab watching an agent, so a tab joining an existing audience
gets the live feed — what this fixes is the case that actually loses events:
the last listener leaves, the subscription goes, and something comes back to
find a gap.

### Narration has its own lifecycle

Narration for one agent is its own entity, and it decides for itself when to
go: when the last subscriber leaves it starts a short countdown, cancelled
the moment anyone subscribes again.

That is not an optimisation, it is a bug fix. Pekko's default unloads an
entity after two minutes without **messages**, and narration's whole state is
a set of live subscribers — unloading it does not free state to be read back
later, it destroys it, and every listener goes deaf with no error anywhere.
Measured breaking a real session: somebody read a long answer and typed a
reply, the turn that followed ran perfectly, finished, and published into an
empty set while the terminal waited out its patience. **An agent is allowed
to think for longer than its audience takes to type.**

## Traces

Supply an `ObservationRegistry` and the engine opens spans:

```java
new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(models)
        .traces(new Traces(observationRegistry)));
```

The Spring Boot starter wires this from your registry automatically.

Every message an agent handles is a span, parented to whatever the *sender*
carried — so one trace covers a whole turn, including the model calls,
approvals and tools it fanned out into. A verified run:

```
task watchmanRounds.round                    <- root
  send watchman BacklogUpdated
    agent receive WorkTaken
    agent call model
      chat qwen/qwen3.6-35b-a3b
    agent receive ModelAsked
    approval disk_usage
    tool disk_usage
      execute_tool disk_usage
    agent receive ToolCompleted
    agent receive ToolParked
    ...
```

23 spans, one trace, one root.

**Context travels in headers, not in thread-locals.** Work handed to the
blocking executor opens its span *there*, from headers carried into the
work, because a captured scope does not survive the hop and a header does.
This was measured rather than reasoned about: the actor tree nested
correctly and every model call still opened its own root trace, until the
scope was re-entered on the worker thread.

A message an agent sends *itself* on activation carries no headers, so
`agent receive Recovered` is a root of its own. That is honest — nothing
caused it.

## Metrics

The same registry produces timers per message type and per unit of work:

```
agent_receive_ModelAsked_milliseconds_count
agent_receive_ApprovalGiven_milliseconds_count
agent_call_model_milliseconds_sum
...
```

Anything a span measures is also a timer, so "how long do approvals take"
and "how often does the model get asked" are both already there.

Spans carry the OpenTelemetry GenAI attributes — `gen_ai.tool.name`,
`gen_ai.agent.name`, `gen_ai.tool.call.id` — pinned against the 2025
`gen_ai.*` attribute set.

**Low-cardinality tags must be known before the work starts.** A tag written
from inside a handler never reaches the span: measured, a span carrying
nothing but agent and turn ids while the high-cardinality details written
beside it arrived fine. They become metric tags, and a meter's tag set has
to be fixed when the meter is created.

## What a span says about itself

A span named `agent receive ToolCompleted` with nothing on it says only that
*something* finished — useless in the turn that called three tools, which is
the turn you opened a trace for. So the tool's name and whether the model
can act on the answer go on before the handler runs.

## Seeing it

`nessy-examples/watchman` exports to OTLP:

```bash
docker run -d --name lgtm -p 3000:3000 -p 4318:4318 grafana/otel-lgtm
export OTLP_TRACES_URL=http://localhost:4318/v1/traces
```

Then Grafana on `:3000` — Tempo for the trees, Prometheus for the timers.

## See also

- [The Harness](harness.md) — subscribing, and closing a subscription
- [Durable Computation](../concepts/durable-computation.md) — why answers cross process boundaries
- [Spring Boot](spring-boot.md) — wiring a registry
