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
| `Policy` (pre-1.0) | derived from `requiresApproval()` | path/allowlist rules | OPA, corporate policy |
| `EventHub` | `synchronous()` | async decorator | bridges (SSE, message bus) |
| Observations | `ObservationRegistry.NOOP` | conventions + starter wiring | any Micrometer handler |
| `ContextBuilder` (deferred) | identity (unnamed) | compacting | RAG, redaction |

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

Not yet built: a durable execution engine, the contextual `Policy` layer,
context compaction, the Spring Boot starter, and a TUI. See
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
