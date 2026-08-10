<p align="center">
  <img src="docs/assets/nessy-hero.png" width="360" alt="Nessy — a friendly sea monster wearing a harness"/>
</p>

# Nessy

An AI agent harness framework for Java.

Nessy supplies the machinery that turns a model API into an agent — the effectful
loop, the tool plumbing, an approval gate the model cannot route around, sessions
that pause and resume across processes, streaming as a first-class citizen, and
observability built in rather than bolted on. Every one of those parts is a
deliberate seam: swap the piece, keep the framework.

## The five-minute example

This runs with no key, no network, and no real model: `ScriptedModelProvider`
(from `nessy-testing`) plays back a scripted conversation so the example compiles
and runs against exactly what ships today. Real providers are `nessy-model-*`
modules (`nessy-model-anthropic`, `nessy-model-openai`, both live-validated);
swap in one of those and nothing else about this shape changes — see
[the real variant](#the-same-example-for-real) below.

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public String name() { return "add"; }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }
    public boolean requiresApproval() { return false; }

    public Awaited<ToolResult> execute(Add input, ToolContext context) {
        return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
}

ObjectNode args = JsonNodeFactory.instance.objectNode();
args.put("left", 2);
args.put("right", 2);

ScriptedModelProvider provider = ScriptedModelProvider.builder()
        .toolUse("c1", "add", args)
        .endWithToolUse()
        .text("The answer is 4.")
        .endTurn()
        .build();

Agent agent = Nessy.agent().provider(provider).model("fake-model").tools(new AddTool()).build();
Reply reply = agent.converse().send("what is 2+2?");

reply.text(); // "The answer is 4."
```

`Nessy.agent()` is the only front door. `Agent` is a configured, reusable handle;
`converse()` opens a session and returns a `Conversation`, whose `send(String)`
returns a `Reply` wrapping the final state — `text()` is the assistant's prose,
extracted. Every builder default already works: in-memory session store,
in-process engine, an allow-all approver (replace it before you point real tools
at anything), a synchronous event hub, no-op observations. The smallest useful
agent is a provider and a model name.

### The same example, for real

Swap `ScriptedModelProvider` for `AnthropicModelProvider.builder().fromEnv()`
and nothing else about the shape above changes. Set `ANTHROPIC_API_KEY` first —
this one makes a real network call and spends real tokens (a couple of cents at
most for a prompt this size on a small model):

```java
AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();

Agent agent = Nessy.agent().provider(provider).model("claude-haiku-4-5-20251001").build();
Reply reply = agent.converse().send("what is 2+2?");

System.out.println(reply.text());
```

## The harness

A **harness** is the model-independent runtime an agent runs inside —
everything that stays the same when you swap the model or the prompt. An
**agent** is an identity — a model binding, a system prompt, granted tools,
declared authority — running inside a harness. Nessy's front door is a
two-builder story that says exactly that: a `Harness` holds the infrastructure
every agent shares, and `AgentBuilder` (reached via `Harness#agent()`, or
`Nessy.agent()` for the common single-agent case) layers one agent's identity
on top of it.

```java
Harness harness = Nessy.harness().provider(anthropic).build(); // once per app

Agent agent =
    harness
        .agent()
        .model("claude-sonnet-4-5")
        .tools(ToolGrant.grant(new AddTool()).with(UsagePolicy.allow()))
        .approver(Approver.denyAll("would fail if ever asked"))
        .build();
```

Two agents built from the same harness share its session store and event hub
by construction — one hub subscriber sees every agent's traffic, one store
holds every agent's sessions. `.tools(ToolGrant.grant(tool).with(policy))` is
the security statement: a grant declares which tool an agent may call and the
`UsagePolicy` the engine consults before it runs, together, per agent, per
tool — the same pairing `AgentFacadeTest`'s
`a_grant_line_declares_capability_and_authority_together` exercises end to
end. `tools(Tool...)` still works as sugar over the derived default policy;
reach for the grant form when an agent needs to loosen or tighten it.

A harness is best understood by the eight services it guarantees, each backed
by one or more seams:

| # | Service | Provided by |
|---|---|---|
| 1 | Turn-taking | `Reducer`, `ExecutionEngine`, `Context` |
| 2 | Context fit | `CompactionPolicy`, `Summarizer`, `ContextBuilder`, `TokenEstimator`, `Memory` |
| 3 | A memory of record | `SessionStore`, `TranscriptStore` |
| 4 | Safe hands | `ToolRegistry`, the invoker (Factor 9) |
| 5 | Guardrails | `ToolGrant`/`UsagePolicy`, `Approver` |
| 6 | A wallet guard | `TerminationPolicy` |
| 7 | Witnesses | Observations, `EventHub` |
| 8 | A vendor-neutral model line | `ModelProvider` |

`agent.contextFor(sessionId)` is the debugging affordance that comes with
service #2: it answers *what would a call made against this session see right
now*, truthfully and without spending a model call, by running the exact same
projection-and-recall choreography the engine runs on every send. See
[Context management](#context-management) below for what it does and doesn't
show.

## How it works

The core is an **effectful reducer**. `reduce(SessionState, Event)` is pure,
synchronous, and total — it returns the next state plus a list of `Effect`s
describing what should happen, and never performs I/O itself. An
`ExecutionEngine` performs those effects and feeds every result back in as an
`Event`, so streaming tokens are ordinary events rather than a retrofit, and
`SessionState` is a plain serializable record — pausing is "stop feeding events,"
resuming is "load the state and keep feeding," whether the gap is 200
milliseconds or two days. See
[`docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`](docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md)
for the full design.

## The zones

The codebase is organized by *who needs to read which package*, in the idiom Java
developers already know (`org.slf4j.spi`, JDBC drivers). The rule that sorts every
type: **if writing an agent requires it, it's API; if hosting agents requires it,
it's SPI.**

| Zone | Package | Audience |
|---|---|---|
| Front door | `org.jwcarman.nessy` | everyone's first five minutes — `Nessy`, `Agent`, `Conversation`, `Reply` |
| API | `org.jwcarman.nessy.api…` | application developers: `Tool`, `Approver`, the message/event grammar |
| SPI | `org.jwcarman.nessy.spi…` | infrastructure extenders: `ExecutionEngine`, `ModelProvider`, `SessionStore` |
| Internal | `org.jwcarman.nessy.internal` | nobody outside this repo — changes freely, never exported as a contract |

By this rule `Tool` is API — writing tools is everyday application development,
the way implementing a `Servlet` was — while `SessionStore` is SPI, the way
implementing a JDBC `Driver` is vendor work.

## The seams and their defaults

Every pluggable part ships a default that works out of the box, an upgrade path
Nessy itself will provide, and room for anyone else to extend it.

| Seam | In-core default | Upgrades Nessy provides | Extenders build |
|---|---|---|---|
| `ExecutionEngine` | `InProcessEngine` | `DurableEngine`; Temporal/Restate adapters | custom runtimes |
| `ModelProvider` | `ScriptedModelProvider` (testing) | `nessy-model-anthropic`, `nessy-model-openai` | any vendor |
| `SessionStore` | `SessionStore.inMemory()` | `nessy-store-jdbc` | Dynamo, Redis… |
| `Approver` | `allowAll()` / `denyAll()` | console; Slack/webhook | anything human-shaped |
| `TerminationPolicy` | error-ceiling + max-turns | cost budget (post-usage) | custom |
| `UsagePolicy` | derived from `requiresApproval()` via `ToolGrant#grant` | path/allowlist rules | OPA, corporate policy |
| `EventHub` | `synchronous()` | async decorator | bridges (SSE, message bus) |
| Observations | `ObservationRegistry.NOOP` | conventions + starter wiring | any Micrometer handler |
| `ContextBuilder` | `identity()` | `elidingToolResults(keepRecentMessages)` | RAG, redaction |
| `Memory` | `none()` | graph-backed recall | vector stores, custom retrieval |

Retries are a decorator, not a provider feature: wrap any `ModelProvider` with
`RetryingModelProvider.wrap(provider, RetryPolicy.defaults(),
AnthropicModelProvider.RETRYABLE)` (each provider module publishes its own
retryable-failure predicate) and nothing about calling it changes (the SDKs
also retry internally, so outer attempts multiply).

## Observability

Two channels, a clean division of labor, and no third. The **event hub** carries
narrative — UI rendering, audit, replay, progress, counters — as plain records
that anyone can emit and anyone can subscribe to by type. **Micrometer
Observation** carries structure — spans, traces, timers, context propagation —
adopted directly rather than reinvented, because it is a near-zero-dependency
artifact built for exactly this, it no-ops when unconfigured, and one
instrumentation point fans out to metrics and traces without us writing either
backend.

The engine's observation names are Nessy's stable metric identity; their
contextual (span) names follow the OpenTelemetry GenAI *agent* span conventions,
so metrics stay stable even as those still-evolving conventions do not:

| Observation (metric name) | Span (contextual name) | Key attributes |
|---|---|---|
| `nessy.run` | `invoke_agent` | `gen_ai.operation.name=invoke_agent`, `gen_ai.conversation.id` |
| `nessy.turn` | `nessy.turn` | ours — semconv has no turn concept |
| `nessy.model.call` | `chat {model}` | `gen_ai.operation.name=chat`, `gen_ai.request.model`, `gen_ai.usage.*` |
| `nessy.tool.call` | `execute_tool {tool}` | `gen_ai.operation.name=execute_tool`, `gen_ai.tool.name`, `gen_ai.tool.call.id` |
| `nessy.approval.wait` | `nessy.approval.wait` | `gen_ai.tool.name` — ours; semconv has no human-approval concept |

Wiring is `.observations(ObservationRegistry)` on the builder, default `NOOP`; the
seams themselves reference Micrometer nowhere. The planned Spring Boot starter
will wire the registry Boot's Actuator already auto-configures, so observability
lights up with no configuration at all in a Spring Boot app — that starter does
not exist yet (see Status).

For streaming a single reply without touching the raw hub, `Conversation.send`
has a tap overload — a natural fit for pushing tokens over SSE:

```java
Conversation conversation = agent.converse();
Reply reply = conversation.send("what is 2+2?", event -> System.out.println(event));
```

The tap sees only this conversation's events, in order, for the duration of that
one `send` call.

## Context management

Three words, three meanings. **The transcript** is a session's entire message
history, forever — append-only, held by the `TranscriptStore` journal if you
opt into one. **The working set** is `SessionState.messages()`: the ledger's
current, possibly-compacted view — `[summary, …tail]` once compaction has run.
**A `Context`** is what one model call actually sees: a validated, pairing-legal
message sequence minted per request, never smaller in scope than what
`ContextBuilder` and compaction agree to send.

### Compaction: a strategy, not a mechanism

Compaction is on by default. Every `Agent` runs a `CompactionStrategy` —
`requiresCompaction(SessionState)` decides when the working set needs
shrinking, `compact(List<Message>)` shrinks it — and unless you say otherwise
that strategy is `summarizing`: `CompactionPolicy.defaults()` triggers once
`SessionState.lastInputTokens()` (the measured input-token count the model
itself reported for the previous turn) reaches 100,000 tokens, and shrinks by
asking the model to summarize everything except the most recent 10 messages,
capping that summary reply at 2,048 tokens. The cut always lands on a
message-pair boundary — the pair-safe cut (`Context.pairSafeCut`, used by the
summarizing strategy) never splits a tool call from its result — so what
survives is always a valid working set. If no such boundary exists old
enough to compact — a tool-heavy transcript with no plain user-text turn far
enough back, for example — the strategy leaves the working set unchanged for
that turn rather than cutting somewhere unsafe.

Two ways to reach for `.compaction(...)`, and they mean different things:

```java
// Tune the default strategy's knobs — trigger, how much survives verbatim,
// the summary's own token cap, the instructions it summarizes with.
CompactionPolicy policy =
    new CompactionPolicy(
        CompactionTrigger.atTokens(50_000), // trigger at 50k measured input tokens
        20, // keep the last 20 messages verbatim
        1_024, // cap the summary reply at 1024 tokens
        "Summarize the conversation so far, focusing on open TODOs.");

Agent agent = Nessy.agent().provider(provider).model("fake-model").compaction(policy).build();
```

```java
// Replace the mechanism wholesale: your own CompactionStrategy, no model
// call required if you don't want one.
Agent agent =
    Nessy.agent().provider(provider).model("fake-model").compaction(myStrategy).build();
```

A `CompactionPolicy` only ever tunes the built-in summarizing strategy; a
`CompactionStrategy` replaces it outright and wins even if a policy was set
first. Turn compaction off entirely with the same policy overload:

```java
Agent agent =
    Nessy.agent()
        .provider(provider)
        .model("fake-model")
        .compaction(CompactionPolicy.disabled())
        .build();
```

Compaction is best-effort: if the strategy's own call fails, the turn proceeds
uncompacted rather than blocking the conversation, and the hub carries a
`CompactionFailed(sessionId, reason)` event so you can observe and alert on it
like any other hub event. Whatever the strategy spends producing a smaller
working set is a real cost, not a side channel: it is billed straight into
`SessionState.usage()` alongside every conversational turn's usage, so the
ledger's running total always matches what you were actually charged.

### Declaring a small model's window

The 100k-token default trigger is safe for 200k-class models and silently
wrong for a small local one — a 32k-window model would sail past its real
ceiling before the default ever fired. Declare the window on the model
binding instead, and the trigger derives itself:

```java
Agent agent =
    Nessy.agent()
        .provider(provider)
        .model("fake-model")
        .maxTokens(4_000)
        .contextWindow(32_000) // trigger derives to ~0.8 × (32_000 − 4_000)
        .build();
```

`contextWindow` only shapes the *derived* trigger — an explicit
`.compaction(CompactionPolicy)` or `.compaction(CompactionStrategy)` call
always wins over it, declared window or not.

### The journal: `TranscriptStore`

The transcript and the working set are not the same thing, and compaction
never touches the transcript. Wire up a `TranscriptStore` and the engine
appends every message the instant it is born — unconditionally, before
anything read-shaped (compaction, elision, windowing) gets an opinion — so the
full history survives in the journal even after compaction shrinks what the
ledger carries forward:

```java
InMemoryTranscriptStore journal = TranscriptStore.inMemory();
Agent agent = Nessy.agent().provider(provider).model("fake-model").transcript(journal).build();
```

The default is `TranscriptStore.none()`: retention is opt-in, so the
zero-config posture stays lean and compaction genuinely bounds memory. Once
you do opt in, the journal is audit-grade and strict — an append that throws
fails the run outright, the same way a failing model call would, because a
silent gap in the audit trail is worse than a failed turn. A durable
`TranscriptStore` persists opaque bytes through a `MessageCodec`
(`MessageCodec.json(mapper)` is the default); encryption at rest is a codec
*decorator* over that, not a separate store implementation, so the same
encrypting codec composes over whichever backing store you choose.

### Shaping what the model sees: `ContextBuilder`

`ContextBuilder` projects `SessionState` into the `Context` one model call
actually sees. The default, `ContextBuilder.identity()`, hands over the whole
working set unchanged. Where compaction rewrites `SessionState` itself,
`ContextBuilder` is a per-request lens — nothing it does touches what gets
stored or resumed.

`ContextBuilder.elidingToolResults(keepRecentMessages)` replaces the content of
tool results older than the last `keepRecentMessages` messages with a
placeholder, keeping the recent window verbatim:

```java
Agent agent =
    Nessy.agent()
        .provider(provider)
        .model("fake-model")
        .contextBuilder(ContextBuilder.elidingToolResults(2))
        .build();
```

Weigh the tradeoff before reaching for it: the sliding window rewrites one old
message per turn as it advances, and a rewritten message churns the
prompt-cache prefix from that point forward — you're trading cache hits for
context space. That's a fine trade when a tool result is enormous and the
context window is the scarcer resource, and a bad one when you're paying for
cache misses more than you're saving in tokens. It's why `identity()`, not
elision, is the default.

`ContextBuilder` is a per-request lens, and compaction's summarization call is
never projected through it: the summarizer always sees the un-elided prefix
the reducer chose to compact, even when `elidingToolResults` is shaping every
other request in the session. Combined with a large `keepRecentMessages`
window, that makes the compaction call the largest single request a session
sends.

### Recalling from outside the session: `Memory`

`ContextBuilder` is contractually pure — no I/O, same output for the same
state — so recalling facts from a graph or vector store outside the session's
own transcript is a sibling seam, not a projection. `Memory.recall(Context)`
runs beside `ContextBuilder`, engine-performed and I/O-sanctioned, and
whatever it returns is prepended ahead of the projected messages for that one
request:

```java
Memory memory = context -> vectorStore.recall(context);
Agent agent = Nessy.agent().provider(provider).model("fake-model").memory(memory).build();
```

The default is `Memory.none()`: recall is opt-in, scoped to one agent, never a
harness-level default. Recall is best-effort, matching the compaction pattern
— a downed memory store or a memory that hands back pairing-invariant-breaking
messages costs the request its *enrichment*, never the *turn*: the hub carries
a `RecallFailed(sessionId, reason)` event you can observe and alert on, and the
call proceeds with no recalled messages.

Weigh the same cache tradeoff `elidingToolResults` carries: recalled content
changes turn to turn, and front-of-prompt injection churns the prompt-cache
prefix every time it changes. A refresh-on-compaction strategy — recalling
only at the moment compaction already churns the prefix — aligns the two
churns instead of adding a second, independent one.

`agent.contextFor(sessionId)` runs the exact projection-and-recall
choreography above — the same `ContextBuilder` and `Memory` instance the
engine consults on every send — against a session's current stored state, and
hands back the resulting `Context`: *exactly what a call made right now would
see*, truthfully and without a model call. It still performs recall's I/O to
answer, so a configured `Memory` is genuinely consulted, not skipped for the
preview.

## Testing

**You will never need a mocking library to test a Nessy agent.** The reducer is
pure, so most of the loop is tested with plain unit tests and no doubles at all.
`ScriptedModelProvider` plays back a scripted conversation and records every
request the harness sent, so tool-calling, streaming, and approval flows are
assertable without a key or a network call. The framework's own suite holds
itself to this promise: its only test dependencies are JUnit and AssertJ.

## Building

Requires JDK 25 and Maven.

```bash
./mvnw verify
```

The default build needs no API key and makes no network calls. Tests that spend
real tokens are tagged `live` and excluded by default; to run them, clear the
exclusion:

```bash
./mvnw test -Dnessy.excludedGroups=
```

## Status

Early, and honest about it. `nessy-core` and `nessy-testing` are converged to the
v2 design: the effectful reducer, the full sealed grammar, the event hub,
`TerminationPolicy`, Micrometer Observation instrumentation, and the `Agent`
facade are all implemented and tested end to end against a scripted model.

Real model providers are **built and live-validated**: `nessy-model-anthropic`
and `nessy-model-openai` wrap each vendor's own Java SDK — native request
assembly, streaming translation, thinking/caching/usage, and a `StopReason`
mapping that fails loudly on anything the audit didn't enumerate rather than
guessing. OpenAI's live suite is fully green against a real key; Anthropic's is
live-validated too, including the empty-system fix (a real empty-system-block
bug the live run surfaced is fixed, with regression tests). `nessy-examples`
ships a runnable two-provider chat app — see [Try it](#try-it) below.

Context management landed too, and converged further: compaction unifies
behind the `CompactionStrategy` seam (default `summarizing`, tunable via
`CompactionPolicy` or replaceable wholesale), declared context windows derive
their own trigger for small models, the `ContextBuilder` seam
(`identity()`, `elidingToolResults(...)`) shapes what one call sees, and an
opt-in `TranscriptStore` journal — strict, audit-grade, with `MessageCodec`
for at-rest encoding — keeps the full transcript even after compaction trims
the working set. All of it is implemented and tested end to end.

Not yet built: `Harness` reification and typed agents, per-grant tool
authority, `Memory`, the context assembler, a durable execution engine, the
Spring Boot starter, and a TUI. See
[`docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`](docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md)
§14 for the sequencing.

## Try it

With a real key, run the example chat app against either provider:

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.AnthropicChat
```

```bash
OPENAI_API_KEY=… ./mvnw -q -pl nessy-examples -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.OpenAiChat
```

Both providers' `.fromEnv()` delegates to the underlying SDK's own environment
support, not a hand-rolled subset — `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL`,
auth tokens, and friends all work with no extra wiring.

That same delegation makes OpenAI-compatible endpoints a one-liner: point
`nessy-model-openai` at OpenRouter, Ollama, or anything else that speaks the
OpenAI wire format with `baseUrl(...)`:

```java
ModelProvider provider =
    OpenAiModelProvider.builder().fromEnv().baseUrl("https://openrouter.ai/api/v1").build();
```

## License

Nessy is licensed under the [Apache License 2.0](LICENSE).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to get
started, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the standards we hold
this project to. Please report security issues per [SECURITY.md](SECURITY.md)
rather than filing a public issue.
