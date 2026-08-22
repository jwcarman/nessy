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
        // The default substrate is a fresh InMemorySubstrate — durable only for this
        // process's lifetime. Supply .substrate(Substrate) with a durable implementation
        // in production so a suspended approval survives a restart.
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
process that opened it depends entirely on the `Substrate` behind
`.substrate(...)` — the in-memory default does not, a durable implementation
does. See
[Getting Started](https://jwcarman.github.io/nessy/guides/getting-started/) on
the docs site for the rest of the walkthrough, and
[Storage](https://jwcarman.github.io/nessy/concepts/storage/) for the
substrate underneath every store.

Under both front doors, an agent is assembled in four tiers: a **substrate**
holds the durable state — one `Substrate` (in-memory here, JDBC or another
durable backend in production) — and can be shared across many hosts; a
**host** is one process's assembly around a substrate — the doors, desks,
and dispatcher shown above; a **harness** is a recipe compiled once per
agent type, holding the model-call and tool-call machinery; and a
**binding** straps one scope's id to that harness for the length of a
single delivery. `.substrate(Substrate)`, and the `memoryFactory` override
that rides it, each hand back a view over the shared substrate rather than
fresh state, which is what makes a scope's history survive from one
delivery to the next.

## Try it

`nessy-examples` has three runnable modules, each consumer code against the
public API only — no key, no network, scripted providers throughout:

```bash
# hello: one tool, one turn, the five-minute promise above, for real
./mvnw -q -pl nessy-examples/hello -am compile exec:java -Dexec.args=--scripted

# approvals: the autonomous door plus a desk — a restart parks for a human
./mvnw -q -pl nessy-examples/approvals -am compile exec:java -Dexec.args=--scripted

# governed: the full gate — declared intent, risk threshold, one narrated turn
./mvnw -q -pl nessy-examples/governed -am compile exec:java -Dexec.args=--scripted
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

Tool, policy, and enricher authors compile against `nessy-api` alone; adapter
authors — a custom `Memory`, `Substrate`, or approver — add `nessy-spi`; an
application just building an agent depends on `nessy-agent`, which pulls both
in. The declared-intent claim channel is its own artifact, `nessy-intent`,
for applications that want it.

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

  <!-- Outsider seams: the model provider SPI, Memory, Substrate, the approver trio. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-spi</artifactId>
  </dependency>

  <!-- The agent runtime and both front doors: Nessy.cli() and Nessy.autonomous(). -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-agent</artifactId>
  </dependency>

  <!-- Optional: the declared-intent claim channel — IntentTool, IntentStore, IntentEnricher. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-intent</artifactId>
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
| Agent as scope — a recipe bound to an id, durable state instead of a live instance | [Agent as Scope](https://jwcarman.github.io/nessy/concepts/agent-as-scope/) |
| Durable computation — the shell's pure fold, effects, at-least-once replay | [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/) |
| Tools — structured calls, sealed inputs, completion policy | [Tools](https://jwcarman.github.io/nessy/concepts/tools/) |
| Authorization — a ladder from a static verdict to typed actions, enrichers, and intent, with a self-documenting report | [Authorization](https://jwcarman.github.io/nessy/concepts/authorization/) |
| Intent — the claim channel a model states and an enricher may trust | [Intent](https://jwcarman.github.io/nessy/concepts/intent/) |
| Memory — the SPI a model call's context is actually built from | [Memory](https://jwcarman.github.io/nessy/concepts/memory/) |
| Storage — the two-shape kernel every store in Nessy is a recipe over | [Storage](https://jwcarman.github.io/nessy/concepts/storage/) |
| Providers — four native model providers plus every OpenAI-compatible endpoint | [Providers](https://jwcarman.github.io/nessy/guides/providers/) |
| MCP — import a remote server's tools as ordinary grants | [MCP Clients](https://jwcarman.github.io/nessy/guides/mcp-clients/) |
| Autonomous agents — the posting door, approval desks, durable backends | [Autonomous Agents](https://jwcarman.github.io/nessy/guides/autonomous-agents/) |
| Observability — turn narration, shell narration, and the authorization report | [Observability](https://jwcarman.github.io/nessy/guides/observability/) |

A few seams the site doesn't have a dedicated page for yet: the
`RetryingModelProvider` decorator for wrapping any `ModelProvider` with a
retry policy, and `ModelSettings.contextWindow()`, a declared-but-unconsumed
token-budget dial reserved for a future token-aware `Memory`. Both exist in
`nessy-spi` today; see the Javadoc until they get a home on the site.

A standalone examples module is planned but not yet in the tree.
Until it lands, the runnable proofs live in `nessy-agent`'s test sources:
five `*Demo` classes (`HarnessDemo`, `DurableParkDemo`, `TypedIntentDemo`,
`GovernedTurnDemo`, `AutonomousApprovalDemo`) exercise a whole turn each
under `./mvnw test`, and `ApprovalPlayground` is an IDE-run tinker door — a
real provider key, a restart tool gated on human approval, typed at the
console.

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
