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

Ask Nessy for a harness; keep it forever; bind any id into a transient
agent; tell it things. Durability is a property of the substrate, not the
API. Export a key and run — this one makes a real network call to a real
model, a fraction of a cent on a small model for a prompt this size:

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

var anthropic = AnthropicModelProvider.fromEnv();   // vendor gateway — one per app

var harness = Nessy.harness(h -> h                  // built once, kept — immortal
        .model(anthropic.model("claude-sonnet-5"))  // the one required dependency
        .systemPrompt("You are a terse assistant.")
        .tools(new AddTool()));                     // bare tools, allow-by-default

harness.bind(AgentId.of("scope-1")).tell("what is 2+2?");
// The answer is 4 — narrated through a TurnObserver, not returned here.
```

This snippet runs — nothing else is required. `OPENAI_API_KEY` and
`OpenAiModelProvider.fromEnv()` are the one-line swap for OpenAI instead,
with nothing else about the shape above changing. `EnvModelProviders.fromEnv()`
(from `nessy-model-env`) reads whichever key is set and picks the model for
you, so an application switches vendors by switching an environment
variable, not its code.

`Nessy.harness(HarnessCustomizer<String>)` is the one front door: the
lambda fills in a live `HarnessConfig`, and Nessy alone turns it into the
finished, kept `Harness` the instant the lambda returns. `harness.bind(id)`
returns a plain, transient `Agent<String>` — thin, never closeable, holding
nothing; `.tell(observation)` enqueues one fact for that scope and
returns immediately. `.tools(Tool<?>...)` grants each tool an
answered-allow policy for you (reach for `ToolGrant.grant(...)` directly,
as in the capability table below, when a tool needs real authority rules).
Every other config default already works: an in-memory `Memory` per scope,
a fresh in-memory `Substrate`, a virtual-thread executor the harness owns
for as long as the process runs. The smallest useful harness is a model, a
system prompt, and nothing else.

The harness is kept, not closed — no `try`-with-resources anywhere in this
example, or any example on the docs site. Its life-support (the delivery
worker, the approval and completion desks, the reaper sweep) runs on
daemon threads for as long as the process does; `harness.shutdown()`
exists for a container's destroy callback, never application hygiene.

The same harness fronts a tool that needs a human, with an `ApprovalDesk`.
`RestartTool` here is an ordinary `Tool<RestartInput>`, shaped just like
`AddTool` above, granted `UsagePolicy.requireApproval()` instead of
`allow()` and an `ActionContributor` (`RESTART_ACTION`) stating what the
call will do:

```java
var pending = new LinkedBlockingQueue<ApprovalRequest>();

var harness =
    Nessy.harness(
        h ->
            h.model(anthropic.model("claude-sonnet-5"))
                .systemPrompt("You are the ops assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                // The default substrate is a fresh InMemorySubstrate — durable only for this
                // process's lifetime. Supply .substrate(Substrate) with a durable implementation
                // in production so a suspended approval survives a restart.
                .approvalNotifier(pending::add));

harness.bind(AgentId.of("ops")).tell("restart prod-1");

ApprovalRequest request = pending.take();
harness.approvals().approve(request.id());
```

`.tell(observation)` enqueues a fact for that scope and returns
immediately; the scope drains it, and if `RestartTool`'s grant requires
approval, the call suspends on a durable computation and `approvalNotifier`
fires once with the `ApprovalRequest` — `request.id()` is
the computation id `harness.approvals().approve(...)`/`.deny(..., reason)`
decides. Nothing here holds a thread open waiting; whether that computation
outlives a restart of the process that opened it depends entirely on the
`Substrate` behind `.substrate(...)` — the in-memory default does not, a
durable implementation does. See
[Getting Started](https://jwcarman.github.io/nessy/guides/getting-started/) on
the docs site for the rest of the walkthrough, and
[Storage](https://jwcarman.github.io/nessy/concepts/storage/) for the
substrate underneath every store.

An agent is assembled in three tiers: a **substrate** holds the durable
state — one `Substrate` (in-memory here, JDBC or another durable backend in
production) — and can be shared across many processes; a **harness** is a
recipe compiled once per agent type, immortal for the life of the process
that holds it, carrying the model-call and tool-call machinery plus its own
life-support (the worker, the desks, the reaper); and a **binding** straps
one scope's id to that harness on demand, for the length of a single
delivery. `.substrate(Substrate)`, and the `memoryFactory` override that
rides it, each hand back a view over the shared substrate rather than fresh
state, which is what makes a scope's history survive from one delivery to
the next.

## Try it

`nessy-examples` has three runnable modules, each consumer code against the
public API only — no key, no network, scripted providers throughout:

```bash
# hello: one tool, one turn, the five-minute promise above, for real
./mvnw -q -pl nessy-examples/hello -am compile exec:java -Dexec.args=--scripted

# approvals: a harness plus an approval desk — a restart parks for a human
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

  <!-- The agent runtime and both front doors: Nessy.harness(...) and Nessy.cli(). -->
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

  <!-- ScriptedModel: the offline, no-key test double — see the docs
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
| Providers — a vendor gateway per model provider, plus every OpenAI-compatible endpoint | [Providers](https://jwcarman.github.io/nessy/guides/providers/) |
| MCP — import a remote server's tools as ordinary grants | [MCP Clients](https://jwcarman.github.io/nessy/guides/mcp-clients/) |
| The harness — kept, not closed; `bind`/`tell`, approval desks, durable backends | [The Harness](https://jwcarman.github.io/nessy/guides/harness/) |
| Observability — turn narration, shell narration, and the authorization report | [Observability](https://jwcarman.github.io/nessy/guides/observability/) |

A few seams the site doesn't have a dedicated page for yet:
`ModelSettings.contextWindow()`, a declared-but-unconsumed token-budget
dial reserved for a future token-aware `Memory`. It exists in `nessy-spi`
today; see the Javadoc until it gets a home on the site.

A standalone examples module is planned but not yet in the tree.
Until it lands, the runnable proofs live in `nessy-agent`'s test sources:
five `*Demo` classes (`HarnessDemo`, `DurableParkDemo`, `TypedIntentDemo`,
`GovernedTurnDemo`, `HarnessApprovalDemo`) exercise a whole turn each
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
