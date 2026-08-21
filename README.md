<p align="center">
  <img src="brand/mascot/nessy-mascot-512.png" alt="Nessy mascot" width="320">
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

An agent harness framework for Java.

## Read the docs

The [documentation site](https://jwcarman.github.io/nessy/) is the manual —
concepts, guides, examples, and reference, in one place. This README is just
the front door: enough to run something real and decide what to install.

## The five-minute example

Export a key and run — this one makes a real network call to a real model,
a fraction of a cent on a small model for a prompt this size:

```bash
export ANTHROPIC_API_KEY=...
```

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

AnthropicModelProvider provider = AnthropicModelProvider.fromEnv();
ModelSettings settings = new ModelSettings(
    "claude-haiku-4-5-20251001", "You are a terse assistant.", 1024, Set.of(), null);

try (CliAgent agent = Nessy.cli().provider(provider).settings(settings).tools(new AddTool()).build()) {
    String reply = agent.converse("what is 2+2?");
    System.out.println(reply);
    // The answer is 4.
}
```

`OPENAI_API_KEY` and `OpenAiModelProvider.fromEnv()` are the one-line swap for
OpenAI instead, with nothing else about the shape above changing.
`EnvModelProviders.fromEnv()` (from `nessy-model-env`) reads whichever key is
set and picks the provider for you, so an application switches providers by
switching an environment variable, not its code.

`Nessy.cli()` is the interactive front door: one scope for the process, one
turn at a time, the caller's thread parks on the reply — the shape a REPL or
a one-shot script both want. `CliAgent#converse(String)` sends one line and
blocks for the answer; `.tools(Tool<?>...)` grants each tool an answered-allow
policy for you (reach for `ToolGrant.grant(...)` directly, as in the capability
table below, when a tool needs real authority rules). Every config default
already works: an in-memory `Memory`, a fresh virtual-thread executor the
`try`-with-resources closes for you. The smallest useful agent is a provider,
`ModelSettings`, and nothing else.

For a host that keeps running without a human driving each turn, there's a
second front door — `Nessy.autonomous()` — built the same way, but posting
observations instead of blocking calls, and fronted by an `ApprovalDesk` for
whatever a tool's policy decides needs a human. `RestartTool` here is an
ordinary `Tool<RestartInput>`, shaped just like `AddTool` above, granted
`UsagePolicy.requireApproval()` instead of `allow()` and an `ActionContributor`
(`RESTART_ACTION`) stating what the call will do:

```java
var pending = new LinkedBlockingQueue<ApprovalRequest>();

try (AutonomousHost host =
    Nessy.autonomous()
        .provider(provider)
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        // The default backend and the default memoryFactory/storeFactory are in-memory —
        // durable only for this process's lifetime. Swap in a durable backend and durable
        // factories in production so a suspended approval survives a restart.
        .backend(new InMemoryDurableComputationBackend())
        .approvalNotifier(pending::add)
        .build()) {

    host.post("ops", "restart prod-1");

    ApprovalRequest request = pending.take();
    host.approvals().approve(request.address().approval());
}
```

`post(agentId, observation)` enqueues one fact for that scope and returns
immediately; the scope drains it, and if `RestartTool`'s grant requires
approval, the call suspends on a durable slot and `approvalNotifier` fires
once with the `ApprovalRequest` — `request.address().approval()` is the slot
id `host.approvals().approve(...)`/`.deny(..., reason)` decides. Nothing here
holds a thread open waiting; whether the slot outlives a restart of the
process that opened it depends on the `.backend(...)` and the
`.memoryFactory(...)`/`.storeFactory(...)` supplied above — the in-memory ones
shown here do not, a durable implementation does. See
[Getting Started](https://jwcarman.github.io/nessy/guides/getting-started/) on
the docs site for the rest of the walkthrough.

Under both front doors, an agent is assembled in four tiers: a **substrate**
holds the durable state (in-memory here, JDBC or another durable backend in
production) and can be shared across many hosts; a **host** is one process's
assembly around a substrate — the doors, desks, and dispatcher shown above; a
**harness** is a recipe compiled once per agent type, holding the model-call
and tool-call machinery; and a **binding** straps one scope's id to that
harness for the length of a single delivery. `storeFactory`/`memoryFactory`
each hand back a thin view over the shared substrate rather than fresh state,
which is what makes a scope's history survive from one delivery to the next.

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

Tool, policy, and enricher authors compile against `nessy-api` alone; adapter
authors — a custom `Memory`, `IntentStore`, or approver — add `nessy-spi`; an
application just building an agent depends on `nessy-agent`, which pulls both
in.

```xml
<dependencies>
  <!-- The durable computation primitive — nessy-api and nessy-agent both build on this. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-durable</artifactId>
  </dependency>

  <!-- The shared vocabulary: Tool, ToolGrant, UsagePolicy, the authorization chokepoint. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-api</artifactId>
  </dependency>

  <!-- Outsider seams: the model provider SPI, Memory, IntentStore, the approver trio. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-spi</artifactId>
  </dependency>

  <!-- The agent runtime and both front doors: Nessy.cli() and Nessy.autonomous(). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-agent</artifactId>
  </dependency>

  <!-- A model provider — pick one (or more). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-anthropic</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-openai</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-gemini</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-bedrock</artifactId>
  </dependency>

  <!-- Optional: all four providers non-optionally, switched by which API key is set (Bedrock is
       the one exception: it has no key of its own and is only ever chosen explicitly, via
       NESSY_PROVIDER=bedrock). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-env</artifactId>
  </dependency>

  <!-- ScriptedModelProvider: the offline, no-key test double — see the docs
       site's Testing guide. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-testing</artifactId>
    <scope>test</scope>
  </dependency>

  <!-- Optional: wrap an MCP server's tools as nessy Tools. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-tool-mcp</artifactId>
  </dependency>
</dependencies>
```

## What Nessy gives you

Each of these is a seam with a working default — swap the piece, keep the
framework. The docs site page teaches the whole story; this is just the map.

| Capability | Site page |
|---|---|
| The durable loop — a pure fold, effects, at-least-once replay | [The Durable Loop](https://jwcarman.github.io/nessy/concepts/durable-loop/) |
| Tools and grants — structured calls, per-tool authority | [Tools and Grants](https://jwcarman.github.io/nessy/concepts/tools-and-grants/) |
| Authorization — a ladder from a static verdict to typed effects, enrichers, and intent, with a self-documenting report | [Authorization](https://jwcarman.github.io/nessy/concepts/authorization/) |
| Parks and callbacks — conversations that pause and resume across processes | [Parks and Callbacks](https://jwcarman.github.io/nessy/concepts/parks-and-callbacks/) |
| Memory and the context pipeline — what a model call actually sees | [Memory and the Pipeline](https://jwcarman.github.io/nessy/concepts/memory-and-the-pipeline/) |
| Planning — a model-maintained task list, recalled every turn | [Planning](https://jwcarman.github.io/nessy/concepts/planning/) |
| The notebook — model-gated memory: an always-present index, bodies on demand | [Notebook](https://jwcarman.github.io/nessy/concepts/notebook/) |
| Reflection — a critic that turns a settled conversation into a lesson the next one recalls | [Reflection](https://jwcarman.github.io/nessy/concepts/reflection/) |
| Subagents — delegation as an ordinary tool call, with the same replay and parking guarantees | [Subagents](https://jwcarman.github.io/nessy/concepts/subagents/) |
| Storage and JDBC dialects — five vendors, one code path | [Storage](https://jwcarman.github.io/nessy/concepts/storage/) |
| MCP — import a remote server's tools as ordinary grants | [MCP Clients](https://jwcarman.github.io/nessy/guides/mcp-clients/) |
| Console apps — a terminal REPL for any `Agent<String>` in one line | [Console Apps](https://jwcarman.github.io/nessy/guides/console-apps/) |
| Triggers — start a turn from a person, a clock, a queue, or a webhook | [Triggers](https://jwcarman.github.io/nessy/guides/triggers/) |
| Testing — no mocking library, ever | [Testing](https://jwcarman.github.io/nessy/guides/testing/) |

A few seams the site doesn't have a dedicated page for yet: a cost/call
budget (`TerminationPolicy`, the wallet guard against runaway loops), a
`RetryingModelProvider` decorator for wrapping any `ModelProvider` with retry
policy, and `AgentConfig#contextWindow(long)`, a declared-but-unconsumed
token-budget dial reserved for a future token-aware `AgentMemory`. All three exist
in `nessy-spi` today; see the Javadoc until they get a home on the site.

## Examples

`nessy-examples` is a family of eight runnable apps — a hello-world starter, a
CLI, an MCP toolbox import, a Spring Boot chat app with HITL, a scheduled
agent, a queue-driven agent, a durable-parks HTTP dispatcher, and a
subagent delegation demo. See the
[Examples](https://jwcarman.github.io/nessy/examples/) page on the docs site
for the full tour and run commands.

## Roadmap

Where this is all headed lives in [ROADMAP.md](ROADMAP.md) — memory that
learns, delegation that scales, and the road to a first release.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to
get started, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the standards we
hold this project to. Please report security issues per
[SECURITY.md](SECURITY.md) rather than filing a public issue.

## License

Nessy is licensed under the [Apache License 2.0](LICENSE).

## The name

> **What's in a name?** Look at the middle of the word *har****ness***: the name
> was hiding inside the thing the whole time. And once your agent framework is
> named Nessy, the mascot picks itself — a certain famously elusive resident of
> Loch Ness, here wearing (what else?) a harness. Like her namesake, she's
> mostly calm water on the surface with a great deal going on underneath.
