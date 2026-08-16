# Observability

Two independent surfaces exist today: a Micrometer `ObservationRegistry` for
metrics and traces, and event listeners for anything else that wants to
watch a conversation. Neither is required — both default to doing nothing.

## Traces and metrics: `ObservationRegistry`

`HarnessConfig#observations(ObservationRegistry)` sets where loop-level
metrics and traces go; the default is `ObservationRegistry.NOOP` — nothing
emitted, no cost paid.

```java
Harness harness =
    Nessy.harness(h -> h.provider(provider).observations(observationRegistry));
```

Three spans exist, named for stable metric identity, with contextual names
and attributes following the (pre-1.0) OTel GenAI semantic conventions:

| Observation | Name | Contextual name | Key attributes |
|---|---|---|---|
| A whole turn | `nessy.run` | `invoke_agent` | `gen_ai.conversation.id` |
| One model call | `nessy.model.call` | `chat {model}` | `gen_ai.request.model`, token usage on completion |
| One tool call | `nessy.tool.call` | `execute_tool {tool}` | `gen_ai.tool.name`, `gen_ai.tool.call.id`, `nessy.tool.outcome` |
| A human approval wait | `nessy.approval.wait` | — | `gen_ai.tool.name` |

`nessy.tool.outcome` is `success` or `error`, low-cardinality by
construction. Token usage lands on the model-call observation once the
response settles.

!!! warning "No per-stage or recall spans exist yet"
    A memory pipeline's hydration and its stages — including the summarizing
    hydrator's own model call (see [Summarizing Memory](summarizing-memory.md))
    — run inside `recall`, and none of that is observed today. The three
    spans above cover the loop's phases, not what a `Memory` implementation
    does internally to build a `Context`. A summarizing pipeline's fold call
    genuinely spends tokens against the configured model, and that spend is
    currently invisible to the observation registry — it's on the roadmap,
    not shipped.

## In a Spring Boot application

`nessy-autoconfigure`'s `Harness` bean takes whatever `ObservationRegistry`
bean is already in the context, if one is present
(`ObjectProvider<ObservationRegistry>.ifAvailable(h::observations)`) —
no application wiring required. `chat-web` dogfoods this: Boot's own
auto-configured registry means Nessy's model-call and tool-call
observations show up in the same trace as Boot's HTTP and JDBC spans — one
chat turn, one trace, from the `POST` that started it down through the
model call, the tool call, and the JDBC saves either side of it.

## Everything else: event listeners

`AgentConfig#listen(Class, Consumer)` and `#listenAsync(Class, Consumer,
Consumer<Throwable>)` (also on `HarnessConfig`, seeded into every agent the
harness builds) subscribe to the `ConversationEvent` grammar —
`AgentTold`, `ModelResponded`, and the rest of the four settled facts the
fold consumes. A synchronous listener that throws propagates and stops the
emitting operation; an asynchronous one runs on a fresh virtual thread and
reports failures to its own `onError` consumer instead.

`chat-cli`'s `DemoAgent` uses this to announce token usage — a fact turn
narration never shows — without duplicating what the console renderer
already prints live:

```java
.listen(ConversationEvent.ModelResponded.class,
    responded -> IO.println("tokens: " + responded.usage().inputTokens() + " in / "
        + responded.usage().outputTokens() + " out"))
```

Delivery is synchronous, in registration order: conversation-local
subscribers first (attached at runtime via `Conversation#events()`), then
this frozen, build-time chain.

## Watching one turn: `TurnObserver`

Distinct from both of the above: `TurnObserver` narrates one segment of one
turn — the caller of `tell`/`resume` hands one in, and it sees deltas,
tool-requested/completed/parked events, and the turn's ending, nothing after
a park. `TurnObserver.logging(Logger, prefix)` is the standard
settled-facts-only narrator every hand-rolled example logger used to
duplicate; `night-watchman`'s `Watchman` and `order-desk`'s `OrderDesk` both
call it directly now. See [Console Apps](console-apps.md) for the streaming
renderer built on the same interface.

## Where next

- [Console Apps](console-apps.md) — `TurnObserver`, its customizer, and the
  renderer chat-cli and scout both use.
- [Triggers](triggers.md) — `chat-web`'s trace, end to end, across an
  HTTP-triggered turn.
- [The Durable Loop](../concepts/durable-loop.md) — the fold and the events
  a listener actually subscribes to.
