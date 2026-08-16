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

## [Read the docs](https://jwcarman.github.io/nessy/)

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

AnthropicModelProvider provider = AnthropicModelProvider.builder().fromEnv().build();

Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .name("adder")
        .model("claude-haiku-4-5-20251001")
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

`OPENAI_API_KEY` and `OpenAiModelProvider.builder().fromEnv().build()` are the
one-line swap for OpenAI instead, with nothing else about the shape above
changing. `EnvModelProviders.fromEnv()` (from `nessy-model-env`) reads
whichever key is set and picks the provider for you, so an application
switches providers by switching an environment variable, not its code.

`Nessy.harness(provider)` is the only front door — the provider is the
harness's one required thing, enforced by signature rather than discovered
later at `build()`. `Agent<I>` is a configured, reusable handle over its input
vocabulary `I`; `converse()` opens a conversation and returns a `Conversation<I>`,
whose `tell(I, TurnObserver)` narrates the model's prose and tool activity live
as `TurnEvent`s and returns a `RunOutcome` — `Completed` or `Parked` — carrying
the settled `ConversationState`. Every builder default already works:
in-memory conversation store, in-memory `Memory`, an allow-all approver
(replace it before you point real tools at anything), no-op observations. The
smallest useful agent is a provider and a model name. See
[Getting Started](https://jwcarman.github.io/nessy/guides/getting-started/) on
the docs site for the rest of the walkthrough — typed agents, talking back and
forth, surviving a restart.

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

  <!-- Optional: all three providers non-optionally, switched by which API key is set. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-env</artifactId>
  </dependency>

  <!-- Optional: an SGR-styled terminal REPL for any Agent<String>, one line to run. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-console</artifactId>
  </dependency>

  <!-- ScriptedModelProvider: the offline, no-key test double — see the docs
       site's Testing guide. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-testing</artifactId>
    <scope>test</scope>
  </dependency>

  <!-- Durable conversations and parks across a restart. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-jdbc</artifactId>
  </dependency>

  <!-- Optional: certify your own ConversationStore/Parks/Transcript/SummaryStore
       implementation against the same contracts nessy-jdbc must pass. -->
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-tck</artifactId>
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
| Parks and callbacks — conversations that pause and resume across processes | [Parks and Callbacks](https://jwcarman.github.io/nessy/concepts/parks-and-callbacks/) |
| Memory and the context pipeline — what a model call actually sees | [Memory and the Pipeline](https://jwcarman.github.io/nessy/concepts/memory-and-the-pipeline/) |
| Planning — a model-maintained task list, recalled every turn | [Planning](https://jwcarman.github.io/nessy/concepts/planning/) |
| The notebook — model-gated memory: an always-present index, bodies on demand | [Notebook](https://jwcarman.github.io/nessy/concepts/notebook/) |
| Storage and JDBC dialects — five vendors, one code path | [Storage](https://jwcarman.github.io/nessy/concepts/storage/) |
| MCP — import a remote server's tools as ordinary grants | [MCP Clients](https://jwcarman.github.io/nessy/guides/mcp-clients/) |
| Console apps — a terminal REPL for any `Agent<String>` in one line | [Console Apps](https://jwcarman.github.io/nessy/guides/console-apps/) |
| Triggers — start a turn from a person, a clock, a queue, or a webhook | [Triggers](https://jwcarman.github.io/nessy/guides/triggers/) |
| Testing — no mocking library, ever | [Testing](https://jwcarman.github.io/nessy/guides/testing/) |

A few seams the site doesn't have a dedicated page for yet: a cost/call
budget (`TerminationPolicy`, the wallet guard against runaway loops), a
`RetryingModelProvider` decorator for wrapping any `ModelProvider` with retry
policy, and `AgentBuilder#contextWindow(long)`, a declared-but-unconsumed
token-budget dial reserved for a future token-aware `Memory`. All three exist
in `nessy-core` today; see the Javadoc until they get a home on the site.

## Examples

`nessy-examples` is a family of seven runnable apps — a hello-world starter, a
CLI, an MCP toolbox import, a Spring Boot chat app with HITL, a scheduled
agent, a queue-driven agent, and a durable-parks HTTP dispatcher. See the
[Examples](https://jwcarman.github.io/nessy/examples/) page on the docs site
for the full tour and run commands.

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
