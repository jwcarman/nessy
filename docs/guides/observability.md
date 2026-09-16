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
boundary around any of them. So the collaborators are wrapped: `Observed`
in `nessy-spring-boot-autoconfigure` wraps the provider, every tool and
every approver, and each call becomes an observation with a name and tags a
dashboard already understands.

**The names are the OpenTelemetry GenAI semantic conventions', not ours.**
A model call is `chat` with `gen_ai.operation.name`, `gen_ai.provider.name`,
`gen_ai.request.model`, `gen_ai.response.finish_reasons` and
`gen_ai.client.operation.duration`; a tool call is `execute_tool` with
`gen_ai.tool.name`, `gen_ai.tool.call.id` and the outcome; an approval is
`nessy.approval` with the answer. A dashboard that already groups by
provider, or an alert that already watches operation duration, works on a
Nessy application without being taught anything. Token counts are a
histogram, never tags: a tag whose value is 606 makes a new time series per
distinct count.

When a call fails, the failure's kind is a low-cardinality tag
(`error.type`) and its message a high-cardinality one (`error.message`), so
a `finish_reason=length` from a thinking model that ran out of budget is
readable on the span.

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
request as rendered, when it was made, how it came back and when. That is
the row to open when a trace says a call took thirty seconds and answered
with nothing. See [Storage](../concepts/storage.md#what-the-model-was-shown).

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
