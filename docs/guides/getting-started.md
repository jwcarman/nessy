# Getting Started

This page installs Nessy, builds the smallest agent that actually calls a
tool against a real model, and points at what to read next.

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
outsider implements — a model provider, `Memory`) for free:

```xml
<dependencies>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-agent</artifactId>
  </dependency>

  <!-- A model provider — pick one. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-anthropic</artifactId>
  </dependency>
</dependencies>
```

`nessy-model-openai`, `nessy-model-gemini`, and `nessy-model-bedrock` are the
other providers; `nessy-model-env` picks whichever key the environment has
set, so an application switches providers by switching an environment
variable rather than its code. Tool, policy, and enricher authors compile
against `nessy-api` alone; adapter authors — a custom `Memory` or approver —
add `nessy-spi`.

## The smallest agent

Export a key:

```bash
export ANTHROPIC_API_KEY=...
```

`Nessy.cli()` is the interactive front door — one scope for the process, one
turn at a time, the caller's thread parks on the reply. A tool built with
`Tool.of` is three lines: an input record, a description, and a handler.

```java
record Add(int left, int right) {}

Tool<Add> addTool =
    Tool.of(Add.class, t -> t.description("Adds two integers")
        .executes(cmd -> cmd.left() + cmd.right()));

AnthropicModelProvider provider = AnthropicModelProvider.fromEnv();
ModelSettings settings = new ModelSettings(
    "claude-haiku-4-5-20251001", "You are a terse assistant.", 1024, Set.of(), null);

try (CliAgent agent =
    Nessy.cli().provider(provider).settings(settings).tools(addTool).build()) {
  String reply = agent.converse("what is 2+2?");
  System.out.println(reply);
  // The answer is 4.
}
```

`.tools(Tool<?>...)` grants each tool `UsagePolicy.allow()` for you — reach
for `ToolGrant.grant(...)` directly when a tool needs real authority rules;
see [Authorization](../concepts/authorization.md). Every other config
default already works here: an in-memory `Memory`, a fresh virtual-thread
executor the `try`-with-resources closes. The smallest useful agent is a
provider, `ModelSettings`, and nothing else.

`OPENAI_API_KEY` and `OpenAiModelProvider.fromEnv()` are the one-line swap
for OpenAI, with nothing else about the shape changing.
`EnvModelProviders.fromEnv()` (from `nessy-model-env`) reads whichever key is
set and picks the provider for you — see [Providers](providers.md).

## Converse

`CliAgent#converse(String)` sends one line and blocks for the answer, which
is what makes it the shape a REPL or a one-shot script both want:

```java
System.out.println(agent.converse("and what about 10+32?"));
// The answer is 42.
```

Each call to `converse` is a full turn: the model sees the whole
conversation so far, including the first exchange, because `Memory` recalls
it. A still-in-flight turn refuses a new line rather than interleaving with
it — call `converse` again once the previous one returns.

## Verify it against the real CLI test

`CliLiveSmokeTest` in `nessy-agent` runs exactly this shape against whatever
provider key is set in the environment, and is excluded from the default
build so a keyless `./mvnw verify` never touches the network. Point one of
`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`/`GOOGLE_API_KEY`, or
`XAI_API_KEY` at a real key and run:

```bash
./mvnw test -pl nessy-agent -Dtest=CliLiveSmokeTest -Dnessy.excludedGroups=
```

Or run the shape this page just built, for real, with no key at all:
`./mvnw -q -pl nessy-examples/hello -am compile exec:java -Dexec.args=--scripted`
(`nessy-examples/hello` in the repo).

## Where next

- [Autonomous Agents](autonomous-agents.md) — the second front door, for a
  host that keeps running without a human driving each turn, with an
  `ApprovalDesk` for whatever a tool's policy sends to a human.
- [Durable Computation](../concepts/durable-computation.md) — the slot
  primitive both front doors are built on, and why a parked call survives
  the instance that opened it.
- [The Four Tiers](../concepts/the-four-tiers.md) — how a substrate, a host,
  a harness, and a binding compose into the agent this page just built.
