# Getting Started

Ask Nessy for a harness; keep it forever; bind any id into a transient
agent; tell it things. Durability is a property of the substrate, not the
API:

```java
var anthropic = AnthropicModelProvider.fromEnv();   // vendor gateway — one per app

var harness = Nessy.harness(h -> h                  // built once, kept — immortal
        .model(anthropic.model("claude-sonnet-5"))  // the one required dependency
        .systemPrompt("You are the ops assistant.")
        .tools(restart, diagnose)                   // bare tools, allow-by-default
        .substrate(jdbc));                           // default: in-memory

harness.bind(AgentId.of("ops-agent-1")).tell("restart prod-eu");
```

This snippet runs — nothing else is required. The identical program is a
toy on the in-memory substrate and a durable, resumable, any-host system on
JDBC — one line differs. That is the whole pitch, and this page walks it
piece by piece.

## Install

Nessy has not yet made a public release to Maven Central: build locally
(`./mvnw install`) and depend on `0.1.0-SNAPSHOT`. Every module shares
`groupId` `org.jwcarman.nessy`.

Import the BOM to align versions:

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

Then pick the artifacts the application actually needs. An application
building an agent depends on `nessy-agent`, which pulls in `nessy-api` (the
shared vocabulary — `Tool`, `ToolGrant`) and `nessy-spi` (the seams an
outsider implements — `Model`, `Memory`, `Substrate`) for free:

```xml
<dependencies>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-agent</artifactId>
  </dependency>

  <!-- A model provider gateway — pick one. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-anthropic</artifactId>
  </dependency>
</dependencies>
```

`nessy-model-openai`, `nessy-model-gemini`, and `nessy-model-bedrock` are the
other provider gateways; `nessy-model-env` picks whichever key the
environment has set, so an application switches vendors by switching an
environment variable rather than its code — see [Providers](providers.md).
Tool, policy, and enricher authors compile against `nessy-api` alone;
adapter authors — a custom `Memory` or `Substrate` — add `nessy-spi`.

## The one required dependency: a model

Export a key:

```bash
export ANTHROPIC_API_KEY=...
```

`AnthropicModelProvider` is the vendor gateway — one per application, the
application singleton holding the SDK client and credentials.
`.fromEnv()` reads `ANTHROPIC_API_KEY`. The gateway itself never runs a
request; `.model(id)` binds a cheap, immutable handle to one model id, and
that handle — not the gateway — is what a harness consumes:

```java
var anthropic = AnthropicModelProvider.fromEnv();
Model claude = anthropic.model("claude-sonnet-5");
```

`.model(Model)` is the harness's one required dependency, with no
environment fallback — the thing every caller must supply explicitly stays
visible. `.systemPrompt(String)` is required alongside it, harness-level
configuration rather than a field buried on a settings object. Everything
else is optional, with honest defaults.

## Tools

A tool built with `Tool.of` is three lines: an input record, a description,
and a handler.

```java
record Add(int left, int right) {}

Tool<Add> addTool =
    Tool.of(Add.class, t -> t.description("Adds two integers")
        .executes(cmd -> cmd.left() + cmd.right()));
```

`.tools(Tool<?>...)` on the harness config grants each tool
`UsagePolicy.allow()` for you — allow-by-default sugar. Reach for
`.grants(ToolGrant...)` directly when a tool needs a real authority rule;
see [Authorization](../concepts/authorization.md).

## The smallest harness

```java
var provider = AnthropicModelProvider.fromEnv();

var harness =
    Nessy.harness(
        h ->
            h.model(provider.model("claude-sonnet-5"))
                .systemPrompt("You are a terse assistant.")
                .tools(addTool));

harness.bind(AgentId.of("scope-1")).tell("what is 2+2?");
```

`Nessy.harness(HarnessCustomizer<String>)` is the one door: the lambda
fills in a live `HarnessConfig`, and Nessy — never the caller — turns it
into the finished `Harness` the instant the lambda returns. There is no
half-configured builder object in your hands, and no public `build()` to
call.

Every other setting already has a working default: an in-memory `Memory`
per scope, a fresh in-memory `Substrate`, a virtual-thread executor the
harness owns for as long as the process runs, `"agent"` as the recipe's
type name. The smallest useful harness is a model, a system prompt, and
nothing else.

`harness.bind(id)` returns a plain, transient `Agent<String>` — it holds
nothing, so there is nothing to leak by dropping it. `.tell(...)`
enqueues one fact for that scope and returns immediately; the reply is
narrated, not returned — see
[Observability](observability.md) for wiring up a `TurnObserver` to watch
turns happen, and [the harness guide](harness.md) for `bind`/`tell`,
`approvals()`/`completions()`, and everything else the harness carries.

## The durability move

Nothing about the snippet above changes to make it durable — only the
substrate does:

```java
var harness =
    Nessy.harness(
        h ->
            h.model(provider.model("claude-sonnet-5"))
                .systemPrompt("You are a terse assistant.")
                .tools(addTool)
                .substrate(jdbcSubstrate));
```

`.substrate(Substrate)` defaults to a fresh `InMemorySubstrate` — durable
only for the process's lifetime. Every scope's state, memory, and backlog
live as documents in whichever `Substrate` the harness is given; point it
at a JDBC (or other durable) implementation and the same program survives a
restart, resumes a parked approval days later, and answers from any node
holding the same harness's type — see [Storage](../concepts/storage.md).

## Verify it against the real test

`NessyHarnessDoorTest` in `nessy-agent` exercises exactly this shape — the
bare `.model(...).systemPrompt(...).tools(...)` minimum accepting an
observation and completing a turn — and is part of the default,
network-free build:

```bash
./mvnw -q -pl nessy-agent -am test -Dtest=NessyHarnessDoorTest
```

Or run a runnable proof with no key at all:
`./mvnw -q -pl nessy-examples/hello -am compile exec:java -Dexec.args=--scripted`
(`nessy-examples/hello` in the repo).

## Where next

- [The harness guide](harness.md) — kept-not-closed, `bind`/`tell`,
  `approvals()`/`completions()`, and the one-type-per-harness contract.
- [Durable Computation](../concepts/durable-computation.md) — the
  ownership-transfer pipeline the harness's worker and desks are built on.
- [The Tiers](../concepts/the-four-tiers.md) — how a substrate, a
  harness, and a binding compose into the agent this page just built.
