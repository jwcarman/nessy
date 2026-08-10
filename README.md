<p align="center">
  <img src="docs/assets/nessy-hero.png" width="360" alt="Nessy — a friendly sea monster wearing a harness"/>
</p>

# Nessy

An AI agent harness framework for Java.

Nessy supplies the machinery that turns a model API into an agent — the effectful
loop, the tool plumbing, an approval gate the model cannot route around, conversations
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

Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .model("fake-model")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .build();
Reply reply = agent.converse().tell("what is 2+2?");

reply.text(); // "The answer is 4."
```

`Nessy.harness(provider)` is the only front door — the provider is the
harness's one required thing, enforced by signature rather than discovered
later at `build()`. `Agent<I>` is a configured, reusable handle over its input
vocabulary `I`; `converse()` opens a conversation and returns a `Conversation<I>`,
whose `tell(I)` returns a `Reply` wrapping the final state — `text()` is the
assistant's prose, extracted. `.agent()` gives you `Agent<String>`, the
degenerate case where `I` is plain text — see [Typed agents](#typed-agents)
below for an application's own input vocabulary. Every builder default already
works: in-memory conversation store, an allow-all approver (replace it before you
point real tools at anything), no-op observations. The smallest useful agent
is a provider and a model name.

### The same example, for real

Swap `ScriptedModelProvider` for `AnthropicModelProvider.builder().fromEnv()`
and nothing else about the shape above changes. Set `ANTHROPIC_API_KEY` first —
this one makes a real network call and spends real tokens (a couple of cents at
most for a prompt this size on a small model):

```java
AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();

Agent<String> agent =
    Nessy.harness(provider).build().agent().model("claude-haiku-4-5-20251001").build();
Reply reply = agent.converse().tell("what is 2+2?");

System.out.println(reply.text());
```

## The harness

A **harness** is the model-independent runtime an agent runs inside —
everything that stays the same when you swap the model or the prompt. An
**agent** is an identity — a model binding, a system prompt, granted tools,
declared authority — running inside a harness. Nessy's front door is a
two-builder story that says exactly that: a `Harness` holds the infrastructure
every agent shares, and `AgentBuilder` (reached via `Harness#agent()`) layers
one agent's identity on top of it.

The two builders are **disjoint by design** — the razor: if a proposed harness
feature could not be expressed as "pre-configuration of an agent builder," it
does not belong on the harness.

| | Owned by the harness | Seeded (agent may extend) | Granted (agent-only) |
|---|---|---|---|
| What | provider, conversation store, observations, object mapper | `defaultModel`, declared listeners | tools |
| Override on `AgentBuilder`? | never — a second harness, not an override | `.model(...)` wins; `.listen`/`.listenAsync` append after the harness's own | no harness toolkit API at all — `tools(...)` accepts a userland constant handed to whichever agents need it |

```java
Harness harness = Nessy.harness(anthropic).build(); // once per app — provider is required, by signature

Agent<String> agent =
    harness
        .agent()
        .model("claude-sonnet-4-5")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .approver(Approver.denyAll("would fail if ever asked"))
        .build();
```

Two agents built from the same harness share its conversation store by
construction — one store holds every agent's conversations. Cross-agent
observability is a harness-declared listener (see
[Declared listening](#declared-listening) below): declare it once on the
harness and it is seeded into every agent's own frozen chain, so the same
listener instance fires for every agent's traffic without a shared, mutable
hub object anywhere. `.tools(ToolGrant.grant(tool, policy))` is the
security statement, structurally: a grant declares which tool an agent may
call and the `UsagePolicy` the engine consults before it runs, together, per
agent, per tool — the same pairing `AgentFacadeTest`'s
`a_grant_line_declares_capability_and_authority_together` exercises end to
end. It is also the *only* way to attach a tool — `tools(Tool...)` does not
exist, because no derivable policy exists: a tool carries zero authority
content, so every attachment states its policy or does not compile.

The odd-one-out agent — a different provider, a different store — is a
**second harness**, one per infrastructure profile, never an override on
`AgentBuilder`; harnesses are free to share store instances where that is
what an application wants.

**Model resolution**: agent `.model(...)` wins; else the harness's
`.defaultModel(...)`; neither declared is an `AgentConfigurationException` at
`build()`, naming the missing model — the same exception every other
agent-configuration failure at build time now raises.

A harness is best understood by the eight services it guarantees, each backed
by one or more seams:

| # | Service | Provided by |
|---|---|---|
| 1 | Turn-taking | `Reducer`, `ExecutionEngine`, `Context` |
| 2 | Context fit | `Compactor`, `Summarizer`, `ContextPipeline`, `Projection`, `TokenEstimator`, `ContextEnricher` |
| 3 | A memory of record | `ConversationStore`, a declared `MessageAppended` listener |
| 4 | Safe hands | `ToolRegistry`, the invoker (Factor 9) |
| 5 | Guardrails | `ToolGrant`/`UsagePolicy`, `Approver` |
| 6 | A wallet guard | `TerminationPolicy` |
| 7 | Witnesses | declared listeners (`listen`/`listenAsync`), `Conversation#events()` |
| 8 | A vendor-neutral model line | `ModelProvider` |

### Declared listening

Listening is declared, scoped, and frozen (design §17) — there is no
general-purpose, runtime-subscribable hub any more. Both builders expose the
same two verbs:

```java
Harness harness =
    Nessy.harness(anthropic)
        .listen(ConversationEvent.class, auditLog::record) // seeds into every agent
        .build();

Agent<String> agent =
    harness
        .agent()
        .model("claude-sonnet-4-5")
        .listen(MessageAppended.class, journal::append)          // sync: veto-by-throw
        .listenAsync(ToolProgress.class, ui::renderProgress)     // async: never vetoes
        .build();
```

Delivery order per emitted event: this conversation's dynamic subscribers
first (see below), then the frozen chain — the harness's declarations, then
the agent's own, in declaration order. A throw from a sync listener, in
either tier, propagates straight out and stops delivery to everything after
it, aborting whatever operation emitted — the veto is the throw, unchanged
from before. An async declaration never gets that power: its listener already
runs on its own virtual thread by the time delivery reaches it, and whatever
it throws reaches its own error handler instead.

The one dynamic level left is per conversation:

```java
Conversation<String> chat = agent.converse();
Subscription live = chat.events().subscribe(ConversationEvent.class, sse::push);
// ... later, when the UI disconnects:
live.close();
```

`Conversation#events()` is in-memory, per-handle, non-durable — the
UI/SSE-attachment case — and already scoped: nothing subscribed through it
ever sees another conversation's traffic, so no manual id filtering is ever
needed. `Conversation#tell(input, tap)` is sugar over exactly this: a
subscription wired for the duration of one call and closed when it returns.

`agent.contextFor(conversationId)` is the debugging affordance that comes with
service #2: it answers *what would a call made against this conversation see right
now*, truthfully and without spending a model call, by running the exact same
`ContextPipeline` (project-then-enrich) choreography the engine runs on every
send. See [Context management](#context-management) below for what it does
and doesn't show.

## Typed agents

Every agent is `Agent<I>` over an input vocabulary `I` — typically a sealed
interface of records the application owns. `Harness#agent()` hands back the
degenerate `Agent<String>`; `Harness#agent(Class<I>)` hands back `Agent<I>`
for anything richer. `tell` is the only verb — `Conversation<I>.tell(I)`
renders the input into the outbound message; the sealed `ConversationEvent`
grammar underneath never changes shape, so typing lives in the facade's
generics and ends at the wire.

An `InputRenderer<I>` (`api.message`) does the rendering:
`InputRenderer.text()` is the `String` default — raw text becomes one text
block, byte-for-byte what `tell` always produced. A typed vocabulary defaults
to `InputRenderer.json(mapper)` — a `[snake_case_simple_name]` tag line plus
canonical JSON of the input, over the harness's own mapper — but the
recommended idiom for anything richer is a sealed-switch renderer, one arm per
shape of thing the application may tell the agent:

```java
sealed interface SupportInput permits Question, Escalation {}
record Question(String text) implements SupportInput {}
record Escalation(String orderId, String reason) implements SupportInput {}

InputRenderer<SupportInput> renderer =
    input ->
        switch (input) {
          case Question question -> List.of(new TextBlock(question.text()));
          case Escalation escalation ->
              List.of(
                  new TextBlock(
                      "Escalate order " + escalation.orderId() + ": " + escalation.reason()));
        };

Harness harness = Nessy.harness(anthropic).build();

Agent<SupportInput> support =
    harness.agent(SupportInput.class).model("claude-sonnet-4-5").renderer(renderer).build();

Reply reply = support.converse().tell(new Escalation("o-1", "damaged in transit"));
```

`AgentFacadeTest`'s `Typed_front_door` nested class mirrors this shape end to
end, including the wire-bytes proof that a `String` agent's `tell` is
byte-for-byte what `send` always produced, and that a broken renderer
(throwing, or returning a null/empty block list) fails loud at `tell()` —
before the engine ever sees it — rather than silently degrading.

## How it works

The core is an **effectful reducer**. `reduce(ConversationState, ConversationEvent)` is pure,
synchronous, and total — it returns the next state plus a list of `Effect`s
describing what should happen, and never performs I/O itself. An
`ExecutionEngine` performs those effects and feeds every result back in as a
`ConversationEvent`, so streaming tokens are ordinary events rather than a retrofit, and
`ConversationState` is a plain serializable record — pausing is "stop feeding events,"
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
| SPI | `org.jwcarman.nessy.spi…` | infrastructure extenders: `ExecutionEngine`, `ModelProvider`, `ConversationStore` |
| Internal | `org.jwcarman.nessy.internal` | nobody outside this repo — changes freely, never exported as a contract |

By this rule `Tool` is API — writing tools is everyday application development,
the way implementing a `Servlet` was — while `ConversationStore` is SPI, the way
implementing a JDBC `Driver` is vendor work.

## The seams and their defaults

Every pluggable part ships a default that works out of the box, an upgrade path
Nessy itself will provide, and room for anyone else to extend it.

| Seam | In-core default | Upgrades Nessy provides | Extenders build |
|---|---|---|---|
| `ExecutionEngine` | `InProcessEngine` | `DurableEngine`; Temporal/Restate adapters | custom runtimes |
| `ModelProvider` | `ScriptedModelProvider` (testing) | `nessy-model-anthropic`, `nessy-model-openai` | any vendor |
| `ConversationStore` | `ConversationStore.inMemory()` | `nessy-store-jdbc` | Dynamo, Redis… |
| `Approver` | `allowAll()` / `denyAll()` | console; Slack/webhook | anything human-shaped |
| `TerminationPolicy` | error-ceiling + max-turns | cost budget (post-usage) | custom |
| `UsagePolicy` | `allow()` / `requireApproval()` stated per `ToolGrant#grant` | path/allowlist rules | OPA, corporate policy |
| Declared listening | `listen(type, listener)` sync | `listenAsync(type, listener)` per listener | bridges (SSE, message bus) via `Conversation#events()` |
| Observations | `ObservationRegistry.NOOP` | conventions + starter wiring | any Micrometer handler |
| `ContextPipeline` | no enrichers, no projections | `Context.elideToolResults(keepRecentMessages)`; `ContextEnricher` contributors | RAG, redaction |

Retries are a decorator, not a provider feature: wrap any `ModelProvider` with
`RetryingModelProvider.wrap(provider, RetryPolicy.defaults(),
AnthropicModelProvider.RETRYABLE)` (each provider module publishes its own
retryable-failure predicate) and nothing about calling it changes (the SDKs
also retry internally, so outer attempts multiply).

## Observability

Two channels, a clean division of labor, and no third. **Declared listening**
carries narrative — UI rendering, audit, replay, progress, counters — as plain
records that anyone can emit and anyone can declare a listener for, by type
(see [Declared listening](#declared-listening) above). **Micrometer
Observation** carries structure — spans, traces, timers, context propagation —
adopted directly rather than reinvented, because it is a near-zero-dependency
artifact built for exactly this, it no-ops when unconfigured, and one
instrumentation point fans out to metrics and traces without us writing either
backend.

Delivery is always synchronous at its core: conversation-local subscribers
first, then the frozen declared chain, in order, on the emitting thread, and
**a throwing sync listener stops the operation that emitted** — the veto is
the throw. Sync or async is chosen once, at declaration time: a listener that
has to stand in the way of something (an audit write that must not be lost)
uses `.listen(type, listener)` and lets its exception propagate; a listener
with no business stopping anything uses `.listenAsync(type, listener,
onError)` (a `System.Logger`-backed overload needs no `onError`) instead, and
delivery runs it on a fresh virtual thread, where nothing it throws can reach
the emitting thread. The returned `Subscription` from `Conversation#events()`
is the same type either way. The engine emits
`MessageAppended(conversationId, message, turnUsage)` at every message's
birth — the declaration point for journaling, memory extraction, and anything
else that follows the transcript; see "The journal" below.

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
| `nessy.compaction` | `compact` | ours; semconv has no compaction concept |
| `nessy.context.enrich` | `enrich` | ours; semconv has no context-enrichment concept |

The default summarizing `Compactor` reuses the `nessy.model.call` / `chat
{model}` convention for its own summarization call, nested under
`nessy.compaction` — see "Compaction" below for why that spend surfaces as
telemetry rather than in `ConversationState.usage()`.

Wiring is `.observations(ObservationRegistry)` on the builder, default `NOOP`; the
seams themselves reference Micrometer nowhere. The planned Spring Boot starter
will wire the registry Boot's Actuator already auto-configures, so observability
lights up with no configuration at all in a Spring Boot app — that starter does
not exist yet (see Status).

For streaming a single reply without a standing subscription, `Conversation.tell`
has a tap overload — a natural fit for pushing tokens over SSE:

```java
Conversation<String> conversation = agent.converse();
Reply reply = conversation.tell("what is 2+2?", event -> System.out.println(event));
```

The tap sees only this conversation's events, in order, for the duration of that
one `send` call.

## Context management

Three words, three meanings. **The transcript** is a conversation's entire message
history, forever — append-only, held by whatever `MessageAppended` listener
you declare as a journal, if you declare one. **The working set** is
`ConversationState.messages()`: the ledger's
current, possibly-compacted view — `[summary, …tail]` once compaction has run.
**A `Context`** is what one model call actually sees: a validated, pairing-legal
message sequence minted per request, never smaller in scope than what the
`ContextPipeline` and compaction agree to send.

### Compaction: one seam, `Compactor`

Compaction is on by default. Every `Agent` runs a `Compactor` —
`requiresCompaction(ConversationState)` decides when the working set needs
shrinking, `compact(ConversationState)` shrinks it, seeing the whole ledger rather
than just a message list — and unless you say otherwise that compactor is the
summarizing default assembled by `Compactors.summarizing(summarizer)`: it
triggers once `ConversationState.lastInputTokens()` (the measured input-token count
the model itself reported for the previous turn) reaches 100,000 tokens, and
shrinks by asking the model to summarize everything except the most recent 10
messages, capping that summary reply at 2,048 tokens. The cut always lands on
a message-pair boundary — the pair-safe cut (`Context.pairSafeCut`, used by
the summarizing default) never splits a tool call from its result — so what
survives is always a valid working set. If no such boundary exists old
enough to compact — a tool-heavy transcript with no plain user-text turn far
enough back, for example — the compactor leaves the working set unchanged for
that turn rather than cutting somewhere unsafe.

`.compaction(Compactor)` is the one compaction-related method `AgentBuilder`
exposes — the compactor is built, not configured through the agent. Every
knob the default summarizing compactor has — the summary reply's token cap,
its instructions, the trigger, and how many recent messages survive
verbatim — belongs to a `Summarizer` and a `Compactors.summarizing(...)`
builder assembled explicitly and handed to `.compaction(...)`:

```java
Summarizer summarizer =
    Summarizer.usingProvider(
        provider,
        "fake-model",
        1_024, // cap the summary reply at 1024 tokens
        "Summarize the conversation so far, focusing on open TODOs.",
        ObservationRegistry.NOOP); // or the harness's registry, for a real nessy.model.call span
Compactor compactor =
    Compactors.summarizing(summarizer)
        .triggerTokens(50_000) // trigger at 50k measured input tokens
        .keepRecent(20) // keep the last 20 messages verbatim
        .build();

Agent<String> agent =
    Nessy.harness(provider).build().agent().model("fake-model").compaction(compactor).build();
```

`Summarizer.usingProvider`'s request never carries a system prompt — the
agent's own persona (`.systemPrompt(...)`) is never forwarded to a
summarization call, even though the summarizer shares the agent's provider
and model.

```java
// Replace the mechanism wholesale: your own Compactor, no model call
// required if you don't want one.
Agent<String> agent =
    Nessy.harness(provider).build().agent().model("fake-model").compaction(myCompactor).build();
```

Turn compaction off entirely with `Compactor.disabled()`:

```java
Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .model("fake-model")
        .compaction(Compactor.disabled())
        .build();
```

Compaction is best-effort: if the compactor's own call fails, the turn
proceeds uncompacted rather than blocking the conversation, and every declared
listener sees a `CompactionFailed(conversationId, reason)` event so you can
observe and alert on it like any other emitted event.

**The jurisdiction rule.** `ConversationState.usage()` only ever bills the loop's
own spend — what each conversational turn's `TurnEnded` reports. Whatever a
compactor's own call costs is auxiliary spend, and auxiliary spend is
telemetry's jurisdiction, not the ledger's: the summarizing default
instruments its own model call as a `nessy.model.call` observation (see
"Observability" above), the same convention the engine's own conversational
calls use, nested under `nessy.compaction`. It never shows up in
`ConversationState.usage()` — a custom `Compactor` that calls a model should follow
the same convention rather than inventing a new one.

### Declaring a small model's window

The 100k-token default trigger is safe for 200k-class models and silently
wrong for a small local one — a 32k-window model would sail past its real
ceiling before the default ever fired. Declare the window on the model
binding instead, and the trigger derives itself:

```java
Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .model("fake-model")
        .maxTokens(4_000)
        .contextWindow(32_000) // trigger derives to ~0.8 × (32_000 − 4_000)
        .build();
```

`contextWindow` only shapes the *derived* trigger — an explicit
`.compaction(Compactor)` call always wins over it, declared window or not.

### The journal: a listener you declare

The transcript and the working set are not the same thing, and compaction
never touches the transcript. **The journal is a listener, finally and
fully** (design §17): there is no dedicated store type for it and no builder
knob. The engine emits `MessageAppended(conversationId, message, turnUsage)`
the instant a message is born, unconditionally, before anything read-shaped
(compaction, elision, windowing) gets an opinion — and a journal is simply a
`.listen(MessageAppended.class, ...)` declaration like any other:

```java
List<MessageAppended> journal = new ArrayList<>();
Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .model("fake-model")
        .listen(MessageAppended.class, journal::add)
        .build();
```

(`AgentFacadeTest`'s `a_journal_is_simply_a_declared_listener` mirrors this
verbatim.)

Retention is opt-in, so the zero-config posture stays lean and compaction
genuinely bounds memory: the absence of a `.listen(MessageAppended.class,
...)` declaration is simply the absence of a journal, no sentinel needed.
Declared the default way — `.listen`, synchronous — the journal is
audit-grade and strict: a listener that throws propagates straight out of
`emit` and fails the run outright, the same way a failing model call would,
because a silent gap in the audit trail is worse than a failed turn (the
synchronous spine's veto-by-throw, see "Observability" above), while the
conversation's snapshot already reached the `ConversationStore` still saves.
An application that prefers best-effort journaling declares
`.listenAsync(MessageAppended.class, ...)` instead. Declared on the harness,
the same listener is seeded once into every agent it builds — not once per
agent (see "Declared listening" above). A durable journal — a future
`nessy-store-cassandra` ships a `MessageAppended` listener class, not a
store — persists opaque bytes through a `MessageCodec` (`MessageCodec.json(mapper)`
is the default); encryption at rest is a codec *decorator* over that, not a
separate store implementation, so the same encrypting codec composes over
whichever backing store you choose.

### Projecting and enriching what the model sees: the context pipeline

The Contextualize phase turns the ledger (`ConversationState`) into the `Context`
one model call actually sees, and it is the one phase of the lifecycle that is
fully open, Maven-style: bindings declared once, at build time, in reviewable
code, never registered at runtime through the hub. `.context(...)` configures
it:

```java
Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .model("fake-model")
        .context(pipeline -> pipeline
            .project(ctx -> ctx.elideToolResults(2))    // PROJECT: 0..n, declaration order
            .enrich(graphMemory)                         // ENRICH: 0..n contributors
            .placement(ContextPipeline.Placement.ENRICHMENTS_FIRST))
        .build();
```

A `ContextPipeline` runs in two stages, both in declaration order:

**PROJECT** transforms are `Projection` (`spi.context`): `Context apply(Context
context)` — pure and total, no I/O, same output for the same input. Applied to
the `Context` minted from the conversation's messages, before any enrichment.
Standard projections are written as lambdas over `Context`'s own edit algebra
(`drop`, `map`, `enrich`, and the structural verbs built on them) rather than
opaque classes: `ctx -> ctx.elideToolResults(keepRecentMessages)` replaces the
content of tool
results older than the last `keepRecentMessages` messages with a placeholder,
keeping the recent window verbatim. The empty projection list — no
`.project(...)` calls — is identity: the model sees the whole working set
unchanged, which is why it's the default. Because a projection is
contractually pure, a throwing projection is treated as the application's own
bug and fails loud rather than being absorbed.

Weigh the tradeoff before reaching for elision: the sliding window rewrites
one old message per turn as it advances, and a rewritten message churns the
prompt-cache prefix from that point forward — you're trading cache hits for
context space. That's a fine trade when a tool result is enormous and the
context window is the scarcer resource, and a bad one when you're paying for
cache misses more than you're saving in tokens. Projection never touches
compaction's summarization call either: the summarizer always sees the
un-elided prefix the reducer chose to compact, even when `elideToolResults`
is projecting every other request in the conversation.

**ENRICH** contributors are `ContextEnricher` (`spi.context`): `List<Message>
enrich(ConversationState state)` — I/O sanctioned, so pulling facts from a graph or
vector store outside the conversation's own transcript is a sibling concern to
projection, not a projection itself. Memory is just a `ContextEnricher`.
Enrichers key on the ledger, not the projected `Context` — the context is the
thing that will *include* the enrichment, and projection is a wire concern (an
elided tool result is `"[elided]"` in the projected context but full text in
the working set). Each contributor runs under its own `nessy.context.enrich`
observation and is independently best-effort: a thrown exception, or a
contribution that would break `Context`'s tool-pairing invariant, costs only
that contributor's own contribution — every other contributor still runs, and
every declared listener sees an `EnrichmentFailed(conversationId, reason)`
event per failure.
Contributions concatenate in declaration order. There is no
`ContextEnricher.none()` sentinel: the empty enrichment list — no
`.enrich(...)` calls — is itself "no enrichment", and it costs zero
allocations and zero observations, same as the project-only path.

Project runs before enrich by jurisdiction, not sequence: enrichers key on the
ledger, so ordering costs them nothing, while projections govern the
*transcript's* wire form — enriched material must be outside their reach, or
every projection would need a "don't touch the enrichments" clause.

**Placement** decides where enriched contributions land relative to the
projected transcript: `ContextPipeline.Placement.ENRICHMENTS_FIRST` (the
default) or `ENRICHMENTS_LAST`. Weigh the same cache tradeoff elision carries:
enriched content changes turn to turn, and front-of-prompt injection churns
the prompt-cache prefix every time it changes. A refresh-on-compaction
enrichment strategy — enriching only at the moment compaction already churns
the prefix — aligns the two churns instead of adding a second, independent
one.

`agent.contextFor(conversationId)` runs the exact same `ContextPipeline` instance
the engine consults on every send — against a conversation's current stored state
— and hands back the resulting `Context`: *exactly what a call made right now
would see*, truthfully and without a model call. It still performs
enrichment's I/O to answer, so configured `ContextEnricher` contributors are
genuinely consulted, not skipped for the preview.

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

Early, public, and honest about both. `nessy-core` and `nessy-testing` are
converged to the v2 design, including its §17 conversation convergence: the
effectful reducer, the full self-attributing sealed grammar (the misdelivery
guard rejects a fact addressed to one conversation but folded into another's
state), declared listening (frozen, seeded, scoped — no runtime-subscribable
hub), `TerminationPolicy`, Micrometer Observation instrumentation, and the
`Agent` facade are all implemented and tested end to end against a scripted
model.

Real model providers are **built and live-validated**: `nessy-model-anthropic`
and `nessy-model-openai` wrap each vendor's own Java SDK — native request
assembly, streaming translation, thinking/caching/usage, and a `StopReason`
mapping that fails loudly on anything the audit didn't enumerate rather than
guessing. OpenAI's live suite is fully green against a real key; Anthropic's is
live-validated too, including the empty-system fix (a real empty-system-block
bug the live run surfaced is fixed, with regression tests). `nessy-examples`
ships a runnable two-provider chat app — see [Try it](#try-it) below.

Context management landed too, and converged further: compaction unifies
behind the `Compactor` seam (default `summarizing`, assembled and tuned via
`Compactors.summarizing(summarizer)`'s builder or replaced wholesale), declared
context windows derive their own trigger for small models, the `ContextPipeline`
(`spi.context`)
declares `project`/`enrich` bindings Maven-style for the fully-open
Contextualize phase, and an opt-in journal — a declared `MessageAppended`
listener, strict by default, with `MessageCodec` for at-rest encoding — keeps
the full transcript even after compaction trims the working set. All of it is
implemented and tested end to end.

The harness itself has since landed too: `Harness` reification, per-grant tool
authority (`ToolGrant`/`UsagePolicy`), `ContextEnricher` contributors (memory
is just a `ContextEnricher`), and the context pipeline (`agent.contextFor`)
are all implemented and tested end to end.

The typed front door has landed too: every agent is `Agent<I>` over an
application-owned input vocabulary, `tell` is the only verb (`send` is gone),
and `InputRenderer<I>` renders that vocabulary onto the wire — see
[Typed agents](#typed-agents) above.

Not yet built: a durable execution engine (`DurableEngine`), the Spring Boot
starter, a TUI, the agent-as-a-tool adapter (wrapping an `Agent<I>` as one
tool for a parent agent), and publishing a typed agent's input schema into
the system prompt. See
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
