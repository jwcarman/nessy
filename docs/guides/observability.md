# Observability

Three separate things, and it helps to keep them apart:

- **Narration**: what an agent is doing, for a person or a UI, delivered as
  it happens. That is [Events](events.md).
- **Traces**: the span tree, for debugging one turn after the fact.
- **Metrics**: counts and timings, for a dashboard.
- **The record**: what the model was actually shown, kept in the database.

## Traces and metrics

Supply a Micrometer `ObservationRegistry` and the engine and the starter
open observations around the work:

```java
new DefaultHarnessFactory(engine -> engine
        .observations(observationRegistry)
        ...);
```

The Boot starter wires this from your registry automatically.

**Observability by wrapping, not by listening.** A listener hears that a
turn started and that it ended, and can time the gap, but a turn that calls
tools makes several model calls inside that gap, and narration draws no
boundary around any of them. So the collaborators are wrapped, and the
engine does the wrapping: the harness observes every tool and approver it
is given (`ObservedTools`), whoever registered them, and the provider is
observed with `ObservedInference` by the Boot starter and by the
summarisers. Each call becomes an observation with a name and tags a
dashboard already understands, and two applications with the same tools
produce the same spans however they wired them.

**The names are the OpenTelemetry GenAI semantic conventions', not ours.**
A model call is `chat` with `gen_ai.operation.name`, `gen_ai.provider.name`,
`gen_ai.request.model`, `gen_ai.response.finish_reasons` and
`gen_ai.client.operation.duration`; a tool call is `execute_tool` with
`gen_ai.tool.name`, `gen_ai.tool.call.id` and the outcome; an approval is
`nessy.approval` with the answer. A dashboard that already groups by
provider, or an alert that already watches operation duration, works on a
Nessy application without being taught anything. What a call cost is on the
`chat` span as `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens`,
and in semconv's `gen_ai.client.token.usage` histogram split by
`gen_ai.token.type`, which the starter records whenever a meter registry is
present. Token counts are samples, never tags: a tag whose value is 606
makes a new time series per distinct count.

**Every span says whose it is, the same way.** The agent type is
`gen_ai.agent.name`, low cardinality, so a dashboard groups by it; the agent
id is `gen_ai.conversation.id`, high cardinality, so a trace search finds
everything one conversation did; and the turn, where the span knows it, is
`nessy.turn.id`. Turns, effects, model calls, tool calls, approvals and
background summaries all carry the first two. A model call learns them from
the span it opens under, since the provider is kept from knowing.

When a call fails, the failure's kind is a low-cardinality tag
(`error.type`) and its message a high-cardinality one (`error.message`), so
a `finish_reason=length` from a thinking model that ran out of budget is
readable on the span.

## Background work

A summary is triggered by a turn ending but written later, on a thread of its
own. It is still part of the turn's trace: the engine tells listeners on
context-propagating executors, so the observation current when the turn
ended, the effect that ended it, travels with the event, and a listener
marked `async()` runs on an engine thread that inherits it too. Each attempt
that found work becomes a `nessy.summary` observation beneath that effect
(contextual name `nessy.summary head` or `nessy.summary episode`) tagged with
whose it is, `nessy.summary.kind` and, once it is over,
`nessy.summary.outcome`: `written`, `nothing` when another process got there
first or the story had moved on, `lease-refused`, `fault` when the model
would not answer, or `empty` when it answered with nothing.

The model call inside is the same `chat` span as any other, with the same
GenAI tags, because both summarisers wrap the provider they are given with
`ObservedInference` from the engine, the same wrapper the starter uses.
Hand them a registry and the vendor's provider name:

```java
EpisodeSummarizer.create(c -> c
        ...
        .observations(observationRegistry, "openai"));
```

Hand in the plain provider, not one the starter has already observed, or
the call is counted twice.

## Traces cross the outbox

Work an agent owes is a row, performed later on another thread and possibly
in another process, so a span cannot be inherited. The W3C trace context is
captured when the effect row is written (`trace_context` on
`nessy_agent_effect`) and restored when it is performed, so a turn comes
back as one trace even when the process that started it is gone. A row
written before tracing was switched on simply starts a trace of its own.

Low-cardinality tags must be known before the work starts; they become
metric tags, and a meter's tag set is fixed when the meter is created. That
is why the tool's name and the model go on the observation before the
handler runs rather than inside it.

## The record

Traces are sampled and expire. What the model was shown is written to
`nessy_inference_context` on every call, whole, and stays until you prune
it. Read it back through `InferenceContexts` as `RecordedInference`: the
request as rendered, when it was made, how it came back and when, and what
it cost in tokens, so spend per agent, model or day is a query rather than a
sampled trace. That is the row to open when a trace says a call took thirty
seconds and answered with nothing. See [Storage](../concepts/storage.md#what-the-model-was-shown).

## Seeing it

`nessy-examples/watchman` exports to OTLP:

```bash
docker run -d --name lgtm -p 3000:3000 -p 4318:4318 grafana/otel-lgtm
export OTLP_TRACES_URL=http://localhost:4318/v1/traces
```

Then Grafana on `:3000`: Tempo for the trees, Prometheus for the timers.

## See also

- [Events](events.md), narration and streams
- [Durable Computation](../concepts/durable-computation.md), why work crosses process boundaries
- [Spring Boot](spring-boot.md), wiring a registry
