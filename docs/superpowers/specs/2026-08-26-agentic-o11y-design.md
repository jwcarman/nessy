# Agentic observability — OTel GenAI semconv over Micrometer Observation

*2026-08-26. Status: James ruled the seam and "use standard semconv"; the
segment ruling (§2) and the two in-executor instrumentation sites (§3) are
recorded for his review. Supersedes the 2026-08-14 single-counter shape.*

## 0. Thesis

Nessy has no numbers. `nessy-api` already depends on `micrometer-observation`
and nothing uses it; `ProviderModelCallExecutor` discards the model's
`Usage` with the comment "usage metrics ride the observability design."
This is that design.

Two rulings shape it:

- **One seam.** `HarnessConfig.observationRegistry(ObservationRegistry)`.
  Absent, the harness runs against `ObservationRegistry.NOOP` and nothing
  costs anything. `nessy-agent` depends on `micrometer-observation` only;
  exporters, the OTel tracing bridge and OTLP live in the application.
- **Standard names.** The OpenTelemetry GenAI semantic conventions —
  span names, `gen_ai.*` attributes, the two client metrics — wherever
  they have a word. `nessy.*` only where they do not: the two waits and
  three engine counters. GenAI semconv is still *development* status
  (`gen_ai.system` became `gen_ai.provider.name` in 2025); this spec pins
  the attribute names it uses in §1 and the CHANGELOG names the semconv
  version implemented against.

## 1. The roster

### 1.1 Spans (Observations)

| Observation (contextual name) | `gen_ai.operation.name` | opens | closes | attributes |
|---|---|---|---|---|
| `invoke_agent {agentType}` | `invoke_agent` | a segment starts (§2) | the segment ends | `gen_ai.agent.name`=agentType, `gen_ai.agent.id`=agentId, `gen_ai.conversation.id`=agentId; `nessy.turn.outcome` = complete / parked / failed |
| `chat {model}` | `chat` | `Model.stream` is called | the stream closes | `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.response.finish_reasons`=[stopReason], `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `nessy.usage.cached_input_tokens`; `error.type` on failure |
| `execute_tool {tool}` | `execute_tool` | `tool.execute` is called | it returns | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `gen_ai.tool.type`=function; `error.type` on failure; `nessy.tool.deferred`=true when it deferred |
| `nessy.approval.wait {tool}` | — | `ApprovalDeferred` applied | `ApprovalAnswered` applied | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `nessy.approval.answer`=approved / denied |
| `nessy.tool.wait {tool}` | — | `ToolDeferred` applied | `ToolFinished` applied | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `nessy.tool.outcome` |

High-cardinality values (`gen_ai.agent.id`, `gen_ai.conversation.id`,
`gen_ai.tool.call.id`) are **high-cardinality key-values** in Micrometer
terms: on the span, never on a meter.

### 1.2 Metrics

From semconv, produced by Micrometer's default observation handlers from
the spans above (Micrometer names the timer after the observation name;
the semconv metric names are set explicitly as the observation's
*low-cardinality* metric name so the exporter emits them as-is):

- `gen_ai.client.operation.duration` — histogram; tags
  `gen_ai.operation.name`, `gen_ai.provider.name`, `gen_ai.request.model`
  (chat) / `gen_ai.tool.name` (execute_tool), `error.type`.
- `gen_ai.client.token.usage` — histogram; tags `gen_ai.token.type` =
  input / output, `gen_ai.provider.name`, `gen_ai.request.model`.
  **Caveat:** an `ObservationRegistry` times observations; it cannot record
  a value histogram. The token counts ride the `chat` observation as
  key-values (`gen_ai.usage.input_tokens` / `output_tokens`), which the
  tracing bridge puts on the span; the `gen_ai.client.token.usage` metric
  is recorded by an `ObservationHandler` that lives with the application
  (ten lines: on stop, read the two key-values, record to its
  `MeterRegistry`). The example module ships that handler; `nessy-agent`
  never sees a `MeterRegistry`.

Ours, counters, tagged `gen_ai.agent.name` only:

- `nessy.delivery.dropped` — every `warnDropped`.
- `nessy.state.stale_retries` — every `StaleStateException` /
  `ConflictException` retry in `DefaultAgent.deliver` and
  `DeliveryWorker.fold`.
- `nessy.effects.refired` — every `AgentObserver.reFired`.

The wait spans yield `nessy.approval.wait` / `nessy.tool.wait` timers by
Micrometer's default; those are the dwell histograms. Tags: agentType,
tool, answer/outcome.

## 2. A segment, not a turn

An `invoke_agent` span does not straddle a park. A turn that waits on a
human for six hours would hold an open Observation across a crash, a
restart and a redeploy; an OTel span that survives none of those is a
lie. Ruling: **`invoke_agent` spans one segment** — from `Observed` (or a
delivery that resumes the scope) applied, to the next `Idle` or the next
park (`AwaitingApproval` / `AwaitingResult` entered with no other call
running). The wait spans carry the dwell between segments, and they are
the ones a dashboard reads for "how long do humans take."

In-flight spans die with the process. They are telemetry, not the ledger;
the phase in the store is the truth. Nothing reconstructs a span after a
restart.

## 3. One stream — the fold's output, at the harness

James, 2026-08-26: "we have a unified event model now. The observers and
the fold can observe the same stream … because we have all of the events
being folded now."

The `AgentEvent` grammar is the complete account of what happens to a
scope: `Observed`, `ModelFinished`, `ApprovalDeferred`, `ApprovalAnswered`,
`ToolDeferred`, `ToolFinished`. Nothing happens to an agent except by one
of those being folded — the approval lifecycle made the beginning and end
of every wait a folded fact. So there is one stream, and the fold is its
producer.

**The stream is the fold's output, not its input.** An event is not a
fact until the reducer accepts it: a dropped delivery arrived and changed
nothing. What flows is `(agentId, event, transition)` for an applied fold
and `(agentId, event)` for an ignored one — exactly what `AgentObserver`'s
`applied`/`ignored` already carry. The reform is where it goes: **both
fold sites publish through one harness-level door, and everything else
subscribes.**

```
DefaultAgent.deliver ─┐
                      ├─ fold ─► harness stream: (agentId, event, transition | ignored)
DeliveryWorker.fold  ─┘                │
                          ┌────────────┼──────────────┐
                    subscribers    engine health    this bridge
```

- `AgentObserver` stops being a per-scope object built by a factory; the
  harness holds the stream, `DefaultAgent` and `DeliveryWorker` publish to
  it, and the configured `AgentObserver` (default: the narrating one) is
  its first subscriber. `Harness.subscribe` gains an overload taking an
  `AgentObserver`, alongside the existing `TurnObserver` one — no new
  noun. Subscribers are isolated as turn subscribers are: a throw is
  logged, never propagated into the fold.
- The parked "delivered folds narrate nothing" item closes as a
  category: there is one door, not a second call to remember.
- **`TurnEvent` stays outside the stream, deliberately.** Deltas, thinking
  chunks and tool progress are an executor narrating *inside* an
  in-flight effect, before any fact exists. They remain the per-entry
  `TurnObserver`'s (segment-bound, as its javadoc says) and the harness's
  turn subscribers'. The stream gives the facts; the turn observer gives
  the typing.

### 3.1 Where the spans come from

The bridge is a subscriber on the stream (spec §1's segments and waits are
functions of `(event, transition)`), plus two spans that live inside
executors because their data exists nowhere else:

1. **`chat`** — inside `ProviderModelCallExecutor.stream`: `Usage` and
   `StopReason` arrive on `ModelEvent.TurnEnded` and are discarded there
   today. The executor gains the harness's `Observations` and the
   provider's `name()`.
2. **`execute_tool`** — inside `RegistryToolCallExecutor.run`, around
   `tool.execute`: for a deferring tool the execution ends at return, not
   at `ToolFinished`, and only the executor knows when the body returned.
   `nessy.tool.deferred` is set from `context.deferral()`.

Everything else — segments, both waits, the three counters — is the
package-private `Observations` object subscribed to the stream, holding the
registry and a per-`AgentId` map of the open segment and open waits.
`nessy.delivery.dropped` is the `ignored` arm of the stream;
`nessy.state.stale_retries` and `nessy.effects.refired` are engine-health
moments the two fold sites report to `Observations` directly (they are
not folds).

### 3.2 Parent–child

Each executor task runs on its own virtual thread; Micrometer's scope does
not follow `executor.execute`. The `Observations` object is the parent
registry: `chat` and `execute_tool` are opened with `parentObservation`
= the agent's open segment, looked up by `AgentId`. Wait spans are
children of the segment that parked them. The tracing bridge then renders
segment → chat → execute_tool → wait as one trace per segment, and the
`gen_ai.conversation.id` attribute stitches segments across parks in
Tempo.

## 4. Wiring

`HarnessConfig.observationRegistry(ObservationRegistry)` — default
`ObservationRegistry.NOOP`. The harness builds one `Observations`,
subscribes it to the stream, and hands it (with the provider name) to
`ProviderModelCallExecutor` and to `RegistryToolCallExecutor`.
`Harness.of(...)` loses the `agentObserverFactory` parameter and gains
the registry; the configured `AgentObserver` is subscribed at build.

The application side — for the home server — is an example module:
`micrometer-registry-otlp` for metrics, `micrometer-tracing-bridge-otel` +
`opentelemetry-exporter-otlp` for traces, the logback OTLP appender for
logs, all pointed at one collector endpoint. Not in `nessy-agent`, ever.

## 5. Retired

Nothing. `ObservationDependencyTest` is replaced by a test that proves
something.

## 6. Tests

- Against `TestObservationRegistry` (already a test dependency of
  `nessy-api`; add to `nessy-agent`):
  - a two-call turn yields exactly one `invoke_agent`, one `chat` with
    `gen_ai.usage.*` from the scripted model's `Usage`, two
    `execute_tool` with the right `gen_ai.tool.name`s, all stopped, chat
    and tools children of the segment;
  - a parked approval yields `nessy.approval.wait` open across the park
    and stopped with `nessy.approval.answer` when the desk answers —
    proven through the REAL `DeliveryWorker.fold`, which is what §3.1
    makes possible; the segment span closed at the park and a new one
    opened at the resume;
  - a deferred tool yields `execute_tool` stopped at return with
    `nessy.tool.deferred=true` and `nessy.tool.wait` stopped at delivery;
  - a failed model call yields `chat` with `error.type`;
  - `nessy.delivery.dropped` increments on a dropped delivery;
    `nessy.state.stale_retries` on a forced conflict.
- With the registry left at NOOP: the existing suite is unchanged (no new
  test; the whole reactor is the proof).
- No test asserts on exporter output; the bridge is the application's.

## 7. Docs

`docs/guides/observability.md` rewritten around this roster (it currently
describes turn events only), `docs/guides/harness.md` (the seam),
`CHANGELOG.md` (added: the seam, the roster, the semconv version; changed:
the worker narrates `AgentObserver`).

## 8. Open for James

1. §2 — segment-scoped `invoke_agent` rather than turn-scoped. The
   alternative is a span that can outlive the process; I do not think
   that is a real alternative.
2. §3 — settled with James 2026-08-26: one stream at the harness, both
   folds publish, `AgentObserver` subscribes via `Harness.subscribe`. The
   default narrating observer will log delivered folds it did not log
   before — intended.
3. `gen_ai.provider.name` sourced from `ModelProvider.name()`, whose
   default is the class's simple name (`AnthropicProvider`). Semconv wants
   `anthropic`. Either each provider overrides `name()` to the semconv
   value, or the executor lowercases and strips `Provider`. Proposed: the
   providers override — it is their name.

## 8a. Next, not now — settled in conversation 2026-08-26

- **Tool progress folds.** James: "the only other thing that could be
  folded would be tool progress updates." It is a fact about the world,
  unlike a text delta. Shape: `ToolProgressed(call, message)` as an
  `AgentEvent` whose transition is applied-but-unchanged — published on
  the stream, NOT saved when the phase is identical (a chatty tool must
  not pay a CAS write per message). Follow-on, not this branch.
- **`TurnEvent` becomes deltas plus a projection of the stream.** With
  progress on the stream, every `TurnEvent` except the model's deltas is
  derivable from a fold: `AssistantSaid` = `ModelFinished` applied,
  `ToolCallRequested` = its calls, `ToolCallDecided` = `ApprovalAnswered`,
  `ToolCallCompleted` = `ToolFinished`, `TurnEnded` = the transition into
  `Idle`/parked. One event model. Follow-on; this branch builds the stream
  it would project from.

## 9. Rejected

- A `nessy-observation` module — the seam is one method and the
  instrumentation lives where the data is; a module would be a home for
  nothing.
- Turn-scoped `invoke_agent` — §2.
- Token tags on meters by agentId / conversation — cardinality; span
  attributes only.
- A trajectory journal / eval export (the 2026-08-14 brainstorm's second
  half) — a separate generation; this one is the numbers the soak needs.
