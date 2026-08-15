<p align="center">
  <img src="docs/assets/nessy-hero.png" width="360" alt="Nessy — a friendly sea monster wearing a harness"/>
</p>

# Nessy

[![CI](https://github.com/jwcarman/nessy/actions/workflows/maven.yml/badge.svg)](https://github.com/jwcarman/nessy/actions/workflows/maven.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/dynamic/xml?url=https://raw.githubusercontent.com/jwcarman/nessy/main/pom.xml&query=//*[local-name()='maven.compiler.release']/text()&label=Java&color=orange)](https://openjdk.org/)

[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jwcarman_nessy&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jwcarman_nessy)

An AI agent harness framework for Java.

> **What's in a name?** Look at the middle of the word *har****ness***: the name
> was hiding inside the thing the whole time. And once your agent framework is
> named Nessy, the mascot picks itself — a certain famously elusive resident of
> Loch Ness, here wearing (what else?) a harness. Like her namesake, she's
> mostly calm water on the surface with a great deal going on underneath.

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
        .name("hello")
        .model("fake-model")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .build();

StringBuilder text = new StringBuilder();
RunOutcome outcome =
    agent
        .converse()
        .tell(
            "what is 2+2?",
            TurnObserver.builder().onTextDelta(delta -> text.append(delta.text())).build());

text.toString(); // "The answer is 4."
outcome.state().status(); // ConversationStatus.COMPLETE
```

This exact example is a runnable module, `nessy-examples/hello` — no key, no
network, no Docker:

```bash
./mvnw -q -pl nessy-examples/hello -am compile exec:java
```

`Nessy.harness(provider)` is the only front door — the provider is the
harness's one required thing, enforced by signature rather than discovered
later at `build()`. `Agent<I>` is a configured, reusable handle over its input
vocabulary `I`; `converse()` opens a conversation and returns a `Conversation<I>`,
whose `tell(I, TurnObserver)` narrates the model's prose and tool activity live
as `TurnEvent`s and returns a `RunOutcome` — `Completed` or `Parked` — carrying
the settled `ConversationState`. `.agent()` gives you `Agent<String>`, the
degenerate case where `I` is plain text — see [Typed agents](#typed-agents)
below for an application's own input vocabulary. Every builder default already
works: in-memory conversation store, in-memory `Memory`, an allow-all approver
(replace it before you point real tools at anything), no-op observations. The
smallest useful agent is a provider and a model name.

### The same example, for real

Swap `ScriptedModelProvider` for `AnthropicModelProvider.builder().fromEnv()`
and nothing else about the shape above changes. Set `ANTHROPIC_API_KEY` first —
this one makes a real network call and spends real tokens (a couple of cents at
most for a prompt this size on a small model):

```java
AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();

Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .name("adder")
        .model("claude-haiku-4-5-20251001")
        .build();
RunOutcome outcome =
    agent
        .converse()
        .tell(
            "what is 2+2?",
            TurnObserver.builder().onTextDelta(delta -> System.out.print(delta.text())).build());
```

## Install

Nessy has not yet made a public release to Maven Central: until then, build
locally (`./mvnw install`) and depend on `0.1.0-SNAPSHOT`. Every module shares
`groupId` `org.jwcarman.nessy`.

Import the BOM to align versions, then pick the artifacts your application
actually needs:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```xml
<dependencies>
  <!-- The core API and loop — every application needs this. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-core</artifactId>
  </dependency>

  <!-- Spring Boot: one starter wires autoconfiguration for you. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-spring-boot-starter</artifactId>
  </dependency>

  <!-- A model provider — pick one (or both). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-anthropic</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-openai</artifactId>
  </dependency>

  <!-- Scripted, no-key, no-network tests — see the five-minute example above. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-testing</artifactId>
    <scope>test</scope>
  </dependency>

  <!-- Durable conversations and parks across a restart (design's store rework). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-store-jdbc</artifactId>
  </dependency>
</dependencies>
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
        .name("guardian")
        .model("claude-sonnet-4-5")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .approver(Approver.denyAll("would fail if ever asked"))
        .build();
```

Two agents built from the same harness share its conversation store by
construction — one store holds every agent's conversations — but each keeps
its own callback doors: `resume`/`progress`/`approve`/`deny`/`peek` verify a
park's stamp against the agent they're called on (`.name(...)`, required at
`build()`) and refuse a call minted by the other agent rather than silently
answering it. Cross-agent observability is a harness-declared listener (see
[Declared listening](#declared-listening) below): declare it once on the
harness and it is seeded into every agent's own frozen chain, so the same
listener instance fires for every agent's traffic without a shared, mutable
hub object anywhere. `.tools(ToolGrant.grant(tool, policy))` is the
security statement, structurally: a grant declares which tool an agent may
call and the `UsagePolicy` the loop consults before it runs, together, per
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
| 1 | Turn-taking | `ConversationState#fold`, the loop, `EffectExecutors` |
| 2 | What the model sees | `Memory`, `Context` |
| 3 | A memory of record | `ConversationStore` (in-flight session state), `Memory` (the settled transcript) |
| 4 | Safe hands | `ToolRegistry`, the invoker (Factor 9) |
| 5 | Guardrails | `ToolGrant`/`UsagePolicy`, `Approver` |
| 6 | A wallet guard | `TerminationPolicy` |
| 7 | Witnesses | declared listeners (`listen`/`listenAsync`), `Conversation#events()`, `TurnObserver` |
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
        .name("auditor")
        .model("claude-sonnet-4-5")
        .onToolFinished(journal::append)          // sync: veto-by-throw
        .onApprovalRequestedAsync(ui::renderPending) // async: never vetoes
        .build();
```

The `on*`/`on*Async` methods are per-type sugar over `listen`/`listenAsync` —
one pair for each of the four conversation facts plus `ToolProgress` and
`ApprovalRequested`. `TurnObserver.builder()`'s hooks (`onTextDelta`,
`onThinkingDelta`, `onRedactedThinking`, `onToolCallRequested`,
`onToolCallDecided`, `onToolCallCompleted`, the durable generation's
addition — `onToolCallProgressed`, narrating a running tool's `ToolProgress`
onto the live segment — and, this generation's own addition, `onToolCallParked`,
narrating the moment a call's save commits) are a *different* vocabulary, not
the same one restated: declared listeners report settled **facts**
(`ConversationEvent`s — what happened, durable, replayable), while a
`TurnObserver` narrates one call's live **texture** (streamed deltas, requests,
decisions — ephemeral, scoped to the `tell` that's in flight). Reach for
declared listeners when something else in the system needs to react — audit,
journaling, another service; reach for a `TurnObserver` when a human is
watching — a UI, a log line, a spinner. The class-keyed primitives remain for
anything else — including the `.listen(ConversationEvent.class, ...)`
catch-all above, which deliberately has no sugar.

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
needed. It stands independently of the `TurnObserver` passed to
`Conversation#tell(input, observer)` — the observer narrates one call's live
turn texture (streamed text, tool requests), while `events()` taps the
settled fact log (`ConversationEvent`) for as long as the subscription stays
open, across as many `tell` calls as it likes.

`agent.contextFor(conversationId)` is the debugging affordance that comes with
service #2: it answers *what would a call made against this conversation see right
now*, truthfully and without spending a model call, by calling the exact same
`Memory#recall` the loop's own model-call executor consults on every send. See
[Context management](#context-management) below for what it does and doesn't
show.

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
          case Question(String text) -> List.of(new TextBlock(text));
          case Escalation(String orderId, String reason) ->
              List.of(new TextBlock("Escalate order " + orderId + ": " + reason));
        };

Harness harness = Nessy.harness(anthropic).build();

Agent<SupportInput> support =
    harness
        .agent(SupportInput.class)
        .name("support")
        .model("claude-sonnet-4-5")
        .renderer(renderer)
        .build();

RunOutcome outcome = support.converse().tell(new Escalation("o-1", "damaged in transit"));
```

`AgentFacadeTest`'s `Typed_front_door` nested class mirrors this shape end to
end, including the wire-bytes proof that a `String` agent's `tell` is
byte-for-byte what `send` always produced, and that a broken renderer
(throwing, or returning a null/empty block list) fails loud at `tell()` —
before the loop ever sees it — rather than silently degrading.

## How it works

The core is a **fold**. `ConversationState#fold(ConversationEvent)` is pure,
synchronous, and total — parameter-free beyond the one fact it folds — and
returns a `Step`: the next state, the messages born this fold, and a list of
`Effect`s describing what should happen next, never performing I/O itself.
`EffectExecutors` performs those effects and feeds every result back in as a
`ConversationEvent`, so a tool call and a model call are both just "perform an
effect, get a fact back" — except when an effect **parks** instead: a tool (or
an approver) that must outlive the process hands back a `ParkToken`, and the
fact arrives whenever `agent.resume(token, …)` delivers it, from any process,
however much later; `TurnEvent`s narrate the texture in between (streamed
tokens, tool requests, the park itself) to whatever `TurnObserver` is watching,
independent of the fold. `ConversationState` is a plain serializable record — pausing is "stop
feeding facts," resuming is "load the state and keep feeding," whether the gap
is 200 milliseconds or two days — while `Memory` (not `ConversationState`)
holds the settled transcript a model call actually sees. See
[`docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`](docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md)
and its amendments,
[`docs/superpowers/specs/2026-08-11-conversation-essence-design.md`](docs/superpowers/specs/2026-08-11-conversation-essence-design.md)
and
[`docs/superpowers/specs/2026-08-12-durable-execution-design.md`](docs/superpowers/specs/2026-08-12-durable-execution-design.md),
for the full design.

## The zones

The codebase is organized by *who needs to read which package*, in the idiom Java
developers already know (`org.slf4j.spi`, JDBC drivers). The rule that sorts every
type: **if writing an agent requires it, it's API; if hosting agents requires it,
it's SPI.**

| Zone | Package | Audience |
|---|---|---|
| Front door | `org.jwcarman.nessy` | everyone's first five minutes — `Nessy`, `Agent`, `Conversation` |
| API | `org.jwcarman.nessy.api…` | application developers: `Tool`, `Approver`, the message/event/turn grammar, `RunOutcome` |
| SPI | `org.jwcarman.nessy.spi…` | infrastructure extenders: `EffectExecutors`, `ModelProvider`, `ConversationStore`, `Memory` |
| Internal | `org.jwcarman.nessy.internal` | nobody outside this repo — changes freely, never exported as a contract |

By this rule `Tool` is API — writing tools is everyday application development,
the way implementing a `Servlet` was — while `ConversationStore` is SPI, the way
implementing a JDBC `Driver` is vendor work.

## The seams and their defaults

Every pluggable part ships a default that works out of the box, an upgrade path
Nessy itself will provide, and room for anyone else to extend it.

| Seam | In-core default | Upgrades Nessy provides | Extenders build |
|---|---|---|---|
| `ModelProvider` | `ScriptedModelProvider` (testing) | `nessy-model-anthropic`, `nessy-model-openai` | any vendor |
| `Memory` | `TranscriptMemory` (verbatim, over `Transcript.inMemory()`) | `nessy-store-jdbc`'s durable `Transcript`; `SummarizingMemory` | RAG, redaction, external stores |
| `ConversationStore` | `ConversationStore.inMemory()` | `nessy-store-jdbc` | Dynamo, Redis… |
| `Approver` | `allowAll()` / `denyAll(String)` / `parkAll()` (the durable-HITL posture: every approval parks, the UI is the approver) | console; Slack/webhook | anything human-shaped |
| `TerminationPolicy` | error-ceiling + max-model-calls | cost budget (post-usage) | custom |
| `UsagePolicy` | `allow()` / `requireApproval()` stated per `ToolGrant#grant` | path/allowlist rules | OPA, corporate policy |
| Declared listening | `listen(type, listener)` sync | `listenAsync(type, listener)` per listener | bridges (SSE, message bus) via `Conversation#events()` |
| Observations | `ObservationRegistry.NOOP` | `nessy-spring-boot-starter` wires Boot's registry automatically | any Micrometer handler |

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
uses `.listen(type, listener)` — or its per-type sugar, `.onToolFinished(...)`
and kin — and lets its exception propagate; a listener with no business
stopping anything uses `.listenAsync(type, listener, onError)` (the
`on*Async(...)` sugar and a logger-backed overload need no `onError`) instead, and
delivery runs it on a fresh virtual thread, where nothing it throws can reach
the emitting thread. The returned `Subscription` from `Conversation#events()`
is the same type either way. The loop emits each of the four
`ConversationEvent` facts (`AgentTold`, `ModelResponded`, `ModelCallFailed`,
`ToolFinished`) on this same system channel, so `.listen(ConversationEvent
.class, ...)` is the declaration point for journaling, fact-log persistence,
and anything else that wants the settled record of what happened — see
[Context management](#context-management) below for what `Memory` separately
retains.

The loop's observation names are Nessy's stable metric identity; their
contextual (span) names follow the OpenTelemetry GenAI *agent* span conventions,
so metrics stay stable even as those still-evolving conventions do not:

| Observation (metric name) | Span (contextual name) | Key attributes |
|---|---|---|
| `nessy.run` | `invoke_agent` | `gen_ai.operation.name=invoke_agent`, `gen_ai.conversation.id` |
| `nessy.model.call` | `chat {model}` | `gen_ai.operation.name=chat`, `gen_ai.request.model`, `gen_ai.usage.*` |
| `nessy.tool.call` | `execute_tool {tool}` | `gen_ai.operation.name=execute_tool`, `gen_ai.tool.name`, `gen_ai.tool.call.id` |
| `nessy.approval.wait` | `nessy.approval.wait` | `gen_ai.tool.name` — ours; semconv has no human-approval concept |

Wiring is `.observations(ObservationRegistry)` on the builder, default `NOOP`; the
seams themselves reference Micrometer nowhere. `nessy-spring-boot-starter`
(see [Spring Boot](#spring-boot) below) wires the registry Boot's Actuator
already auto-configures straight into the autoconfigured `Harness`, so
observability lights up with no configuration at all in a Spring Boot app. The
`chat-web` example ([Examples](#examples)) is the dogfood: it takes that
autoconfigured `Harness` with zero wiring of its own, OTLP goes out to a local
Grafana/Tempo/Loki stack, and a chat turn reads as one trace — the HTTP POST
down through `nessy.model.call`, `nessy.tool.call`, and the JDBC saves either
side of them.

For narrating a single `tell` live without a standing subscription,
`Conversation.tell` has a `TurnObserver` overload — a natural fit for pushing
tokens over SSE:

```java
Conversation<String> conversation = agent.converse();
RunOutcome outcome =
    conversation.tell(
        "what is 2+2?",
        TurnObserver.builder()
            .onTextDelta(delta -> sse.send("text", delta.text()))
            .onToolCallRequested(call -> sse.send("tool", call.call().name()))
            .build());
```

The observer sees only this one call's `TurnEvent`s, in order, for the
duration of that one `tell`. Three ways to make one: a bare lambda when a
single concern covers every event, `TurnObserver.builder()` to compose
per-variant consumers (repeat registrations chain, so a journal and a renderer
can both listen), or extend `TurnObserverAdapter` and override only the hooks
you watch.

## Spring Boot

`nessy-spring-boot-starter` (plus `nessy-autoconfigure`, both in the BOM)
wires the durable stack by classpath. Add a provider module
(`nessy-model-anthropic` and/or `nessy-model-openai`) and a `ModelProvider`
bean is autoconfigured from `nessy.provider`/`nessy.{anthropic,openai}.*`
properties layered over the SDK's own `fromEnv()` resolution — those
properties are overrides, not replacements, and an explicit one outranks an
ambient env var; both jars present and neither disambiguated fails fast,
naming the property. Add `nessy-store-jdbc` next to a `DataSource` bean and a
Postgres-backed `ConversationStore`/`Memory` pair is autoconfigured too
(`nessy.jdbc.enabled` is the master switch; `nessy.jdbc.bootstrap-schema`
picks DDL-on-startup vs. bring-your-own-schema). Either way, a `Harness` is
then autoconfigured from whatever provider, store, `ObservationRegistry`, and
`ObjectMapper` beans are in context, seeded from `nessy.default-model`.
**Agents are never autoconfigured** — identity (model, prompt, tools,
policies) stays the application's own `Harness#agent()` call, always; that
razor is deliberate, not an oversight. Any autoconfigured bean backs off the
moment the application declares its own: a hand-declared `Harness` suppresses
the *provider* autoconfiguration outright (each provider bean backs off the
moment either a `ModelProvider` or a `Harness` bean is already present, since
an app that supplied its own `Harness` has, by construction, already brought
its own provider). Persistence is not part of that suppression — it wires
independently, from classpath plus `DataSource` plus property alone, with no
back-off for a hand-declared `Harness` — so a hand-declared `Harness` may
still consume the autoconfigured store the same way the harness
autoconfiguration itself does. A `Harness` is also fine with no store at all:
`ConversationStore`/`Memory` are each `ObjectProvider`-optional, defaulting to
an in-memory implementation when neither the JDBC autoconfiguration nor the
application supplies one.

With `spring-webmvc` on the classpath, a `TurnRunner` bean also appears: it
runs a turn on a virtual thread with the request's Micrometer context
propagated onto it, handing back the `SseEmitter` an application's own
controller streams from. `TurnEventSse` maps that turn's `TurnEvent`s onto a
stable wire vocabulary for a browser to key off of: `delta`, `thinking`,
`tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`,
`tool-parked` (`{token, tool, args}`), `message` (`{text}`, the settled
assistant message's joined prose — skipped when blank), and `done`
(`{status[, failureReason]}`). `TurnEventSse` emits `done` itself, from
`TurnEvent.TurnEnded` — an application's own controller no longer builds it
by hand; `TurnRunner` synthesizes one more `done` only when an exception
escapes the turn before any ending was ever narrated (`{status: "ERROR",
failureReason}`, a wire sentinel, not a `ConversationStatus` value). Note for
browser clients: `message` is also the `EventSource` API's DEFAULT event
name, so an `onmessage` catch-all receives those frames with no listener
registration at all — named listeners remain the intended pattern for every
event on this wire, `message` included.

The whole property surface is deliberately this small — everything more
exotic rides `fromEnv()`'s own ambient resolution or a hand-declared bean:

| Property | Default | Meaning |
|---|---|---|
| `nessy.provider` | (none) | required only when both provider jars are present |
| `nessy.anthropic.api-key` / `base-url` | SDK env | provider credentials, layered over `fromEnv()` |
| `nessy.openai.api-key` / `base-url` | SDK env | provider credentials, layered over `fromEnv()` |
| `nessy.default-model` | (none) | harness-level default model, optional |
| `nessy.jdbc.enabled` | `true` | JDBC wiring master switch |
| `nessy.jdbc.bootstrap-schema` | `true` | run the idempotent DDL at startup |

See the `chat-web` example ([Examples](#examples)) for the whole stack —
provider, persistence, harness, and the SSE bridge — in one Spring Boot app
with a single application-owned bean.

## Context management

**`Memory`** (`spi.memory`) owns what a model call actually sees. It is told
every message-grade happening — the user message, the assistant message, and
the batched tool-results message once the last pending call clears, a closed
list of exactly three tellings — and `Memory#recall(conversationId)` answers
with the `Context` (`api.message`) the next model call gets: a validated,
pairing-legal message sequence. What happens between being told and being
asked is entirely the implementation's business — verbatim retention
(`TranscriptMemory`, the default), summarization (`SummarizingMemory`),
checkpointing, embedding — as long as `recall` returns something legal and a
tool-use/tool-result pair is never split or reordered.
`AgentBuilder#memory(Memory)` replaces the default outright.

`Memory` is built on **`Transcript`** (`spi.memory`) — an append-only,
versioned, per-conversation message log, the storage primitive some memories
are based on and the read surface audit and chat history need.
`TranscriptMemory` remembers everything verbatim through a `Transcript` and
recalls it whole; `SummarizingMemory` keeps only a bounded tail of the
transcript verbatim, folding everything older into a running summary (its
`SummaryStore` watermark) once the tail grows past a threshold — a crash
between summarizing and saving just means the next recall re-summarizes the
same tail, never loses words, since the transcript itself is the truth.

`Context` (`api.message`) owns the pairing invariant's safe edits so raw list
surgery never happens in application code: the trusted kernel is
`drop(Predicate<Message>)` (pair-atomic), `map(UnaryOperator<Message>)`
(revalidating), and `enrich(ContentBlock...)`; built on that kernel are
`elideToolResults(int)`, `keepRecent(int)`, and `limitTokens(long,
TokenEstimator)` — useful building blocks for a `Memory` implementation that
wants to trim what it hands back from `recall`, even though `Memory` itself
decides when and whether to reach for them.

`agent.contextFor(conversationId)` calls the exact same `Memory#recall` the
loop's own model-call executor consults on every send — *exactly what a call
made right now would see*, truthfully and without spending a model call.

### Declaring a small model's window

`AgentBuilder#contextWindow(long)` declares the model's total token budget on
`ModelSettings`, alongside `.maxTokens(int)`:

```java
Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .name("budgeted")
        .model("fake-model")
        .maxTokens(4_000)
        .contextWindow(32_000)
        .build();
```

`contextWindow` is not consumed by anything in the loop today — it is a
declared, reserved dial for a future token-aware `Memory` implementation to
read when deciding what to keep. A custom `Memory` is free to ignore it
entirely, or to derive its own retention trigger from it, the way a future
in-core implementation will.

## Durable, autonomous agents

A conversation is a plain serializable record and a durable inbox, so an
agent's conversation can run on **any node**, be driven by
whatever process gets to it next, and pick up a wait that started days ago
and a process ago. Nothing about the shape above changes to get this —
`tell` already appends and drives; the durable generation adds one more entry
point that does the same thing for the other direction a conversation moves:

```java
RunOutcome outcome = agent.resume(token, ToolResolution.completed(result));
```

`agent.resume(token, resolution[, observer])` answers a parked call by
token — the `ParkToken` a tool (or an `Approver`) handed back when it parked
— appends the resolution to the conversation's inbox, and drives, exactly
the way `tell` does. Appending always succeeds: a tell or a resolution is
never refused for arriving while the conversation is busy, mid-turn, or even
parked — it joins the durable inbox and the next drive (this call's own,
or a re-drive from any other node) picks it up. `agent.progress(token,
message)` is `resume`'s non-terminal sibling: it never consumes the token,
only narrates a still-running tool's progress to whoever is listening for
`ToolProgress`. `agent.approve(token[, observer])` and `agent.deny(token,
reason[, observer])` are sugar over `resume` for the common human-in-the-loop
case — allow or refuse the gated call by token, without hand-building a
`ToolResolution`. `agent.peek(token)` reads a park without consuming it —
an `Optional<ParkedCall>`, empty only for a token this registry never
minted; the registry entry survives resolution by design, so a token
naming a wait that has already settled still reads back present, useful
for an ops surface that wants to describe a parked (or once-parked)
conversation before or after anyone acts on it. Every one of these five doors lives on `Agent`, not on
`Harness`: the token is the whole correlation contract, and transport home
(a webhook, a queue, a cron poll) is the tool author's business. Each door
also verifies the token's park was minted by *this* agent before touching
anything — a mismatch throws `WrongAgentException`, naming both the agent
that parked the call and the one the callback landed on, before any state
changes. An agent's `.name(...)` (required at `build()`) is a durable wire
contract exactly like the `ParkToken` these doors verify against — a rename
with parks in flight orphans them, so the name deserves the same care as a
queue name or a callback URL, not a cosmetic label.

Two write disciplines carry this: a version-fenced control block (one writer
wins; a stale writer reloads and re-drives, never overwrites) and the inbox,
which the fence doesn't gate, so a chatty world can never fence-fail a
working driver. `ConversationStatus.PARKED` joins the other
statuses — a parked conversation self-describes to any ops surface: no
driver, no lease, durable patience.

`ConversationStore.inMemory()` (the default) does not survive a process
restart; `nessy-store-jdbc` does, against one Postgres, no cluster membership
required:

```java
ConversationStore store =
    JdbcConversationStore.create(dataSource, objectMapper); // idempotent schema bootstrap
Parks parks = JdbcParks.create(dataSource, objectMapper); // same discipline, same lifespan
Transcript transcript = JdbcTranscript.create(dataSource, objectMapper); // same discipline, same lifespan
Memory memory = new TranscriptMemory(transcript);

Harness harness = Nessy.harness(anthropic).store(store).parks(parks).build();
Agent<String> agent =
    harness.agent().name("durable").model("claude-sonnet-4-5").memory(memory).build();
```

In a Spring Boot app the wiring above is optional: add
`nessy-spring-boot-starter` and `nessy-store-jdbc` next to a `DataSource`
bean, and the store, parks, transcript, memory, and harness above are all
autoconfigured — the application declares one bean, the agent. See [Spring
Boot](#spring-boot) above for the whole story.

Restart survival needs three doors now: the `ConversationStore` keeps the
control block (status) and inbox; `Parks` keeps the registry of outstanding
waits a callback's token must translate back into a conversation and call;
and `TranscriptMemory` over a durable `Transcript` keeps the message log the
`Memory` seam owns — `TranscriptMemory` over `Transcript.inMemory()`, the
in-core default, dies with the JVM. The `chat-web` example
([Examples](#examples)) demonstrates the trio surviving a kill mid-approval.

`nessy-store-jdbc`'s own test suite includes container-backed tests against a
real `postgres:17-alpine` (via Testcontainers), tagged `container` and
excluded from the default build the same way `live` tests are — `./mvnw
verify` needs no Docker daemon. `./mvnw test -Dnessy.excludedGroups=live`
runs them (needs a Docker daemon); clearing the exclusion entirely
(`-Dnessy.excludedGroups=`) runs both `container` and `live`.

## Testing

**You will never need a mocking library to test a Nessy agent.** The fold is
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
converged to the v2 design and its conversation-essence amendment: the
two-effect/four-fact core loop (`ConversationState#fold`, `EffectExecutors`),
the full self-attributing sealed fact grammar (the misdelivery guard rejects a
fact addressed to one conversation but folded into another's state), live
`TurnEvent` narration via `TurnObserver`, `Memory` as the one content
jurisdiction, declared listening (frozen, seeded, scoped — no
runtime-subscribable hub), `TerminationPolicy`, Micrometer Observation
instrumentation, and the `Agent` facade (`tell` returning `RunOutcome`) are
all implemented and tested end to end against a scripted model.

Real model providers are **built and live-validated**: `nessy-model-anthropic`
and `nessy-model-openai` wrap each vendor's own Java SDK — native request
assembly, streaming translation, thinking/caching/usage, and a `StopReason`
mapping that fails loudly on anything the audit didn't enumerate rather than
guessing. OpenAI's live suite is fully green against a real key; Anthropic's is
live-validated too, including the empty-system fix (a real empty-system-block
bug the live run surfaced is fixed, with regression tests). `nessy-examples`
ships a runnable two-provider chat CLI, a Spring Boot chat-web app, a
scheduled night-watchman agent, and an HTTP dispatcher over durable parks —
see [Examples](#examples) below.

The harness landed too: `Harness` reification, per-grant tool authority
(`ToolGrant`/`UsagePolicy`), and `agent.contextFor(conversationId)` (now
backed directly by `Memory#recall`) are all implemented and tested end to
end.

The typed front door has landed too: every agent is `Agent<I>` over an
application-owned input vocabulary, `tell` is the only verb (`send` is gone),
and `InputRenderer<I>` renders that vocabulary onto the wire — see
[Typed agents](#typed-agents) above.

Context management converged again with the conversation-essence amendment:
what used to be three separate seams (compaction, the context pipeline, and
the transcript-store/journal family) collapsed into one, `Memory` — told
every message-grade happening, asked for the finished `Context` a model call
sees, free to summarize, checkpoint, or embed behind that one contract. The
declared `contextWindow` dial survives, deliberately unconsumed by anything
in the loop today, reserved for a future token-aware `Memory` to read.

The durable kernel has landed too: every entry — a `tell`, a `resume` —
appends to the conversation's durable inbox and drives with the same
re-entrant verb, `PARKED` conversations wait for an `agent.resume`/
`agent.progress` from any node, and `nessy-store-jdbc` gives that three
real Postgres-backed doors — `ConversationStore`, `Parks`, and `Memory`
(`TranscriptMemory` over `JdbcTranscript`, the durable transcript) — see
[Durable, autonomous agents](#durable-autonomous-agents) above and the
`chat-web` example ([Examples](#examples) below), which dogfoods all three
against a real browser UI and a kill-and-restart.

The Spring Boot starter has landed too: `nessy-autoconfigure` (every
`@AutoConfiguration` class, every feature dependency optional) and
`nessy-spring-boot-starter` (the dependency-only aggregator, Boot's own
convention), both in the BOM — provider, persistence, and harness beans all
arrive by classpath, agents never do, and `chat-web` is the starter's own
acceptance test, rewired down to one application-owned bean. See [Spring
Boot](#spring-boot) above.

Not yet built: a TUI, the agent-as-a-tool adapter (wrapping
an `Agent<I>` as one tool for a parent agent), and publishing a typed agent's
input schema into the system prompt. See
[`docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`](docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md)
§14 for the sequencing.

## Examples

`nessy-examples` is a family of six runnable apps, all real key required
except `hello` (the five-minute example above, in its own runnable
module — no key, no network, no Docker). No mocking, nothing hand-waved.
The matrix: `hello` (the five-minute example, standalone), `chat-cli`
(plain + interactive), `chat-web` (Boot web + HITL), `night-watchman`
(Boot + scheduled autonomy), `order-desk` (Boot + message-driven
autonomy), `dispatcher` (Boot web + durable parks over HTTP).

Several examples share Docker containers on fixed host ports; run more than
one stack at once and here's what's listening where:

| Port(s)     | What                          |
| ----------- | ----------------------------- |
| 5432        | `chat-web`'s Postgres         |
| 5433        | `order-desk`'s Postgres       |
| 5434        | `dispatcher`'s Postgres       |
| 5672, 15672 | `order-desk`'s RabbitMQ (AMQP, management UI) |
| 8080        | `chat-web` (HTTP)             |
| 8081        | `dispatcher` (HTTP)           |
| 3000, 4317, 4318 | `chat-web`'s `otel-lgtm` (Grafana UI, OTLP gRPC, OTLP HTTP) |

**`chat-cli`** — a terminal chat loop, one agent definition run against
either provider:

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.AnthropicChat
```

```bash
OPENAI_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.OpenAiChat
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

**`chat-web`** — the first non-toy dogfood: a Spring Boot chat app against a
real Postgres, with a browser UI, a tool gated behind human approval, and
full observability. `nessy-spring-boot-starter` autoconfigures the provider,
persistence, and harness; the whole nessy wiring an application declares
itself is one bean, the agent. The demo script survives killing and
restarting the app mid-approval — the transcript and the pending approval are both durable
rows, not JVM state. See
[`nessy-examples/chat-web/README.md`](nessy-examples/chat-web/README.md) for
the full walkthrough, including the observability tour (Grafana/Tempo/Loki
via `grafana/otel-lgtm`). To run it:

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/chat-web spring-boot:run
```

then open <http://localhost:8080>.

**`night-watchman`** — the time-triggered agent: `@Scheduled` cron initiates
each turn of one continuous conversation, and a windowing `Memory` keeps
endless rounds from growing the model call. The leanest example — no web, no
database, no Docker. See
[`nessy-examples/night-watchman/README.md`](nessy-examples/night-watchman/README.md)
for the full story. To run it:

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/night-watchman spring-boot:run
```

**`order-desk`** — the queue as driver: a message on RabbitMQ's `orders`
queue initiates each turn, no human and no clock involved. The first
typed-vocabulary agent in the family (`Agent<OrderEvent>` over a sealed
event grammar, not `Agent<String>`), and the first to demonstrate
at-least-once redelivery on a real broker misbehaving on cue — kill the app
mid-turn, restart, and nothing is lost: a redelivered reply is absorbed as
stale mail, a redelivered order event is honestly re-told. See
[`nessy-examples/order-desk/README.md`](nessy-examples/order-desk/README.md)
for the full demo script. To run it:

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/order-desk spring-boot:run
```

then open <http://localhost:15672> (guest/guest) to publish order events.

**`dispatcher`** — the inbox's two trigger models over plain HTTP:
`POST /signals` is fire-and-forget (202, driven on a virtual thread);
`POST /callbacks/{token}` and `.../progress` are the crew reporting back into
a parked turn. The headline scene kills the app mid-park and resumes it in a
fresh process — `JdbcParks` earning its keep, curl as the only client. See
[`nessy-examples/dispatcher/README.md`](nessy-examples/dispatcher/README.md)
for the full script. To run it:

```bash
ANTHROPIC_API_KEY=… ./mvnw -pl nessy-examples/dispatcher spring-boot:run
```

## License

Nessy is licensed under the [Apache License 2.0](LICENSE).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to get
started, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the standards we hold
this project to. Please report security issues per [SECURITY.md](SECURITY.md)
rather than filing a public issue.
