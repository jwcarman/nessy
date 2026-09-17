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
is given (`ObservedTools`), whoever registered them. A provider is observed
with `provider.observed(registry)`. The Boot starter's provider
auto-configurations return their providers already observed when there is a
registry, so the engine and anything else the application hands one to
report its calls; without Boot, observe the provider where you build it.
The summarisers observe the provider they are given when handed a registry.
An observed provider is returned as it is, so observing twice never doubles a
span. The provider
reports its own vendor through `InferenceProvider.providerName()`; each
adapter returns semconv's value, and a provider written by hand is named
for the class that wrote it. Each call becomes an observation with a name and tags a
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
GenAI tags, because both summarisers observe the provider they are given.
Hand them a registry:

```java
EpisodeSummarizer.create(c -> c
        ...
        .observations(observationRegistry));
```

## Traces cross the outbox

Work an agent owes is a row, performed later on another thread and possibly
in another process, so a span cannot be inherited. The W3C trace context is
captured when the effect row is written (`trace_context` on
`nessy_agent_effect`) and restored when it is performed, so a turn comes
back as one trace even when the process that started it is gone. A row
written before tracing was switched on simply starts a trace of its own.

**A turn is one flat trace.** The context is captured once, inside the
`nessy.observe` span that admitted the observation, and every effect of the
turn is written with that same context. So every effect is a sibling
beneath `nessy.observe`, in the order it ran, and none is nested inside the
effect whose outcome caused it:

```
nessy.observe
  nessy.effect infer                   chat gpt-4.1
  nessy.effect approve disk_usage
  nessy.effect call_tool disk_usage    execute_tool disk_usage
  nessy.effect infer                   chat gpt-4.1
```

Each `nessy.effect` span is named for the kind of work it performed:
`infer`, or `approve` and `call_tool` followed by the tool's name, as
`execute_tool` is, since an approval often has no span beneath it to say
which call it was for. `nessy.observe` times admitting the
observation and nothing more; no span lasts as long as a turn, because a
turn can outlive the process that started it. A turn taken from the
backlog, as the one before it closes, starts a trace of its own rooted at
its first effect.

**Building a request is visible too.** Inside `nessy.effect infer`, before
`chat`, the request's assembly is `nessy.context`: the system prompt
(`nessy.context system_prompt`), the verbatim tail
(`nessy.context history`, with `nessy.context.turns`), each summary source
as semconv's `search_memory` (`gen_ai.operation.name=search_memory`, with
`gen_ai.memory.record.count`), and each ambient source as
`nessy.context ambient <kind>`, named for what it returned. Writing the
request to `nessy_inference_context` follows as `nessy.record`. Time spent
in any of these is time the model did not take.

**Embedding calls** are spans once an embedder is observed. What takes an
embedder observes it for you: hand `JdbcEpisodes` a registry beside the
embedder.

```java
JdbcEpisodes.create(c -> c
        ...
        .embedder(OpenAiEmbedder.create(c2 -> ...))
        .observations(observationRegistry));
```

Anywhere else, `embedder.observed(observationRegistry)` does the same; an
embedder that is already observed comes back as it is, so observing twice
never doubles the spans. Each call is `embeddings <model>` with `gen_ai.operation.name=embeddings`,
`gen_ai.provider.name` (which the embedder reports itself),
`gen_ai.request.model` and
`gen_ai.embeddings.dimension.count`, timed by the same
`gen_ai.client.operation.duration` histogram as model calls, and it carries
the agent tags of the span it opens under. Ranking episodes embeds the turn
being answered inside `search_memory`, and writing an episode's summary
embeds it inside `nessy.summary`, so an embedding endpoint that is slow
shows up where it costs.

**Writing the context without a span.** Micrometer's Observation API can
only have trace headers written by starting an observation, so on its own
the engine opens a momentary `nessy.effect.emit` span for each capture. A
`TraceCarrier` writes the current context directly; hand one to
`EngineConfig.traceCarrier(...)`. The Boot starter supplies one over
Micrometer Tracing's `Tracer` and `Propagator` whenever both are beans, so a
Boot application with tracing on has no emit spans.

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

`nessy-examples/watchman` and `nessy-examples/chat-web` export to OTLP at
`http://localhost:4318/v1/traces`. Each carries a `docker-compose.yml` that
runs its database and Grafana's all-in-one stack there:

```bash
docker compose -f nessy-examples/chat-web/docker-compose.yml up -d
```

Then Grafana on `:3000`: Tempo for the trees, Prometheus for the timers.

## See also

- [Events](events.md), narration and streams
- [Durable Computation](../concepts/durable-computation.md), why work crosses process boundaries
- [Spring Boot](spring-boot.md), wiring a registry
