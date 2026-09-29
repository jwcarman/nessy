# Observability

Three separate things, and it helps to keep them apart:

- **Narration**: what an agent is doing, for a person or a UI, delivered as
  it happens. That is [Narration](narration.md).
- **Events**: the durable record a turn leaves behind. That is
  [Events](../concepts/events.md).
- **Traces**: the span tree, for debugging one turn after the fact.
- **Metrics**: counts and timings, for a dashboard.

## Traces and metrics

Supply a Micrometer `ObservationRegistry` and the engine opens observations
around the work:

```java
DefaultDirectHarnessFactory.of(config -> config
        .observations(observationRegistry)
        ...);
```

The Boot starter wires this from your registry automatically.

**Observability by wrapping, not by listening.** A listener hears that a
turn started and that it ended, and can time the gap, but a turn that calls
tools makes several model calls inside that gap, and narration draws no
boundary around any of them. So the collaborators are wrapped, and the
engine does the wrapping: the harness observes every tool and approver it
is given. A provider is wrapped with
`ObservedInferenceProvider.wrap(provider, registry)`. The Boot starter's
provider auto-configurations return their providers already wrapped when
there is a registry, so the engine and anything else the application hands
one to report its calls; without Boot, wrap the provider where you build
it. The summarisers wrap the provider they are given when handed a
registry. An already-wrapped provider is returned as it is, so wrapping
twice never doubles a span. The provider reports its own vendor through
`InferenceProvider.vendor()`; each adapter returns semconv's value,
and a provider written by hand is named for the class that wrote it. Each
call becomes an observation with a name and tags a dashboard already
understands, and two applications with the same tools produce the same
spans however they wired them.

**The names are the OpenTelemetry GenAI semantic conventions', not ours.**
A model call is `chat <model>` with `gen_ai.operation.name`,
`gen_ai.provider.name`, `gen_ai.request.model`,
`gen_ai.response.finish_reasons` and `gen_ai.client.operation.duration`; a
tool call is `execute_tool <name>` with the outcome; an approval is
`nessy.approval` with `nessy.approval.answer`. A dashboard that already
groups by provider, or an alert that already watches operation duration,
works on a Nessy application without being taught anything. What a call
cost is on the `chat` span as `gen_ai.usage.input_tokens` and
`gen_ai.usage.output_tokens`, alongside `gen_ai.usage.cache_read_tokens`,
`gen_ai.usage.cache_write_tokens` and `gen_ai.usage.reasoning_tokens` when a
vendor reported them, and in semconv's `gen_ai.client.token.usage`
histogram split by token type, which the starter records whenever a meter
registry is present. Token counts are samples, never tags: a tag whose
value is 606 makes a new time series per distinct count. What actually
answered is `gen_ai.response.model` — not always what was asked for, since
a vendor may resolve an alias to a dated build and price against that.

**Every span says whose it is, the same way.** The agent type is
`gen_ai.agent.name`, low cardinality, so a dashboard groups by it; the agent
id is `gen_ai.conversation.id`, high cardinality, so a trace search finds
everything one conversation did; and the turn, where the span knows it, is
`nessy.turn.id`. These three are `Identity`'s own attribute names, read off
the observation a span opens under, since a provider is deliberately kept
from knowing which agent it is serving. Turns, effects, model calls, tool
calls, approvals and background listener work all carry the first two.

When a call fails, the failure's kind is a low-cardinality tag
(`error.type`) and its message a high-cardinality one (`error.message`), so
a `finish_reason=length` from a thinking model that ran out of budget is
readable on the span.

## Background work

A listener marked `async()` runs on a thread of its own, and that thread's
trace is deliberately **not** nested under the turn that caused it.

An episode summary, say, is a model call of its own, triggered by a turn
ending but not waited for by anything — it can begin after the turn's own
span has closed and outlive it. Nesting it anyway used to make a 1.45
second span appear inside a 1.12 second one: the longest bar in a trace is
the first thing anyone reads when asking why a request was slow, and that
bar was work nobody was waiting on. A child outliving its parent also
breaks self-time and critical-path arithmetic, which assume a child is
contained. So an async listener's span is a root of its own trace.

What survives the split is identity, not parentage: an async listener's
span still carries `gen_ai.agent.name` and `gen_ai.conversation.id`, so
"what else happened for this agent" is a query — and a better one than
parentage, since it finds that agent's work across every trace rather than
only the one you happened to open. As roots, these spans are also
measurable on their own, which is the only way to ask whether background
work is getting slower.

The synchronous listeners — the ones told inline, not `async()` — do stay
inside the turn's own trace: they run before the turn's span closes, on a
thread that carries it.

## Traces cross the outbox

Work a queued agent owes is a row, performed later on another thread and
possibly in another process, so a span cannot be inherited. The W3C trace
context is captured when the effect row is written (`trace_context` on
`nessy_agent_effect`) and restored when it is performed, so a turn comes
back as one trace even when the process that started it is gone. A row
written before tracing was switched on simply starts a trace of its own.

**A turn is one flat trace.** Every `nessy.effect` span is a sibling of the
turn's root — `invoke_agent <agentType>` for the direct door's `ask`,
`nessy.tell` for the input that opened a queued turn — in the order it ran,
never nested inside the effect whose outcome caused it:

```
invoke_agent watchman
  nessy.effect infer                   chat gpt-4.1
  nessy.effect approve disk_usage
  nessy.effect call_tool disk_usage    execute_tool disk_usage
  nessy.effect infer                   chat gpt-4.1
```

Each `nessy.effect` span is named by `EffectSpans`, the one class both
doors read the name from — `infer`, or `approve` and `call_tool` followed
by the tool's name, since an approval often has no span beneath it to say
which call it was for. That is what keeps a dashboard reading the same
words whichever door performed the work.

**Building a request is visible too.** Inside `nessy.effect infer`, before
`chat`, assembling the request is `nessy.context`: the verbatim tail
(`nessy.context history`, with `nessy.context.turns`), each summary source
as semconv's `search_memory` (`gen_ai.operation.name=search_memory`, with
`gen_ai.memory.record.count`), and each ambient source as
`nessy.context ambient <kind>`, named for what it returned. Time spent in
any of these is time the model did not take.

**Embedding calls** are spans once an embedder is wrapped:

```java
Embedder embedder = ObservedEmbedder.wrap(embedderFactory.create(c -> ...), observationRegistry);

JdbcEpisodes episodes = JdbcEpisodes.of(c -> c
        .dataSource(dataSource)
        .agentType(agentType)
        .embedder(embedder));
```

Wrap before handing the embedder to `JdbcEpisodes.Config.embedder(...)`; an
already-wrapped embedder is returned as it is, so wrapping twice never
doubles the spans. The Boot starter's `EmbedderFactory` beans wrap every
embedder they mint this way whenever a registry is present. Each call is
`embeddings <model>` with
`gen_ai.operation.name=embeddings`, `gen_ai.provider.name` (which the
embedder reports itself), `gen_ai.request.model` and
`gen_ai.embeddings.dimension.count`, timed by the same
`gen_ai.client.operation.duration` histogram as model calls, and it carries
the agent tags of the span it opens under. Ranking episodes by relevance
embeds the turn being answered inside `search_memory`, so an embedding
endpoint that is slow shows up where it costs.

**Writing trace headers without a span.** Micrometer's Observation API can
only have trace headers written by starting an observation, so on its own
the engine opens a momentary `nessy.effect.emit` span each time a trace is
captured for an outbox row. A `TraceCarrier` writes the current context
directly instead; hand one to `QueuedHarnessFactoryConfig.traceCarrier(...)`. The Boot
starter supplies one over Micrometer Tracing's `Tracer` and `Propagator`
whenever both are beans, so a Boot application with tracing on has no emit
spans.

Low-cardinality tags must be known before the work starts; they become
metric tags, and a meter's tag set is fixed when the meter is created. That
is why the tool's name and the model go on the observation before the
handler runs rather than inside it.

## Seeing it

`nessy-examples/watchman` and `nessy-examples/chat-web` export to OTLP at
`http://localhost:4318/v1/traces`. Each carries a `docker-compose.yml` that
runs its database and Grafana's all-in-one stack there:

```bash
docker compose -f nessy-examples/chat-web/docker-compose.yml up -d
```

Then Grafana on `:3000`: Tempo for the trees, Prometheus for the timers.

## Where next

- [Narration](narration.md), the announced-and-gone channel a listener hears
- [Events](../concepts/events.md), the durable record a turn leaves behind
- [Spring Boot](spring-boot.md), wiring a registry
