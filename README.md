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

The [documentation site](https://jwcarman.github.io/nessy/) is the manual:
concepts, guides, examples, and reference, in one place. This README is just
the front door: enough to run something real and decide what to install.

## The five-minute example

Build a harness once, keep it, tell it things. A PostgreSQL `DataSource` and
a key, and this makes a real call:

```bash
export ANTHROPIC_API_KEY=...
```

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public ToolName name() { return new ToolName("add"); }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }

    public Awaited<ToolResult> call(ToolCallRequest<Add> request) {
        Add input = request.input();
        return Awaited.ready(ToolResult.ok(new Block.Text(String.valueOf(input.left() + input.right()))));
    }
}

Schemas.initialize(dataSource);

DefaultHarnessFactory factory = new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(AnthropicInferenceProvider.fromEnv(), InferenceOptions.of("claude-sonnet-5")));

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("assistant"))
        .systemPrompt("You are a terse assistant.")
        .listener(AgentEventListener.of(on -> on
                .onContentDelta((type, id, delta) -> System.out.print(delta.text()))))
        .tool(new AddTool()));

harness.observe(AgentId.random(), "what is 2+2?");
```

`observe` is a post, not a call: it returns as soon as the observation is
durable, and the answer is **narrated** to listeners rather than returned.

For a terminal agent, one call does the whole bootstrap: database, provider,
harness and loop:

```java
public static void main(String[] args) {
    Repl.run(config -> config
            .systemPrompt("You are a helpful assistant.")
            .tool(new AddTool())
            .tool(new SendEmailTool(), binding -> binding
                    .approver(ConsoleApprover.atTheTerminal())
                    .action(email -> "Send an email to " + email.to())));
}
```

## Try it

`nessy-examples` has five modules. The ones that talk to a model want an
OpenAI-compatible endpoint, [LM Studio](https://lmstudio.ai) on `:1234`
works and costs nothing, and a PostgreSQL to keep the agents in:

```bash
export OPENAI_API_KEY=not-needed
export OPENAI_BASE_URL=http://localhost:1234/v1
export NESSY_MODEL=<a model id your endpoint serves>
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/nessy
export SPRING_DATASOURCE_USERNAME=nessy
export SPRING_DATASOURCE_PASSWORD=secret
```

**`chat-cli`**: a terminal agent with a notebook, a plan and a templated
prompt, gated on a tool that asks at the prompt:

```bash
./mvnw -q -pl :nessy-example-chat-cli -am compile exec:java
```

**`chat-web`**: the same agent as a page, plus a head summariser that folds
old turns into one rolling summary: streamed answers over SSE, an approval
desk you click, and `Last-Event-ID` resume when a browser reconnects.

```bash
cd nessy-examples/chat-web && ../../mvnw spring-boot:run
```

**`mcp`**: a terminal agent whose tools come from somebody else's server.
It connects to DeepWiki's public MCP endpoint, grants the three tools it
advertises, and gates the one that spends DeepWiki's own model budget:

```bash
./mvnw -q -pl :nessy-example-mcp -am compile exec:java
```

**`policy`**: the gate as data. A `PolicyApprover` that asks Open Policy
Agent, so the rules are Rego reviewed by whoever owns the risk rather than
Java that ships with a release. No model and no key: it runs the real OPA
binary in a container.

```bash
./mvnw -pl :nessy-example-policy test -Dnessy.excludedGroups=
```

**`watchman`**: the Spring Boot soak. It lives on a real box, does rounds on
a timer, proposes remediations it is not allowed to run itself, and waits
for a person to answer through a page. `nessy-examples/watchman/soak.sh`
runs it and then **asserts what happened**, including that something
actually parked.

## Install

Nessy has not yet made a public release to Maven Central: until then, build
locally (`./mvnw install`) and depend on `0.1.0-SNAPSHOT`. Every module
shares `groupId` `org.jwcarman.nessy`.

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

Tool and policy authors compile against `nessy-api` alone; adapter authors
add `nessy-spi`; an application building an agent depends on
`nessy-engine`, which pulls both in, plus one provider adapter.

| Artifact | What it is for |
|---|---|
| `nessy-api` | the shared vocabulary: `Tool`, `Approver`, `Awaited`, blocks, `AgentEvent`, `AgentEventListener` |
| `nessy-spi` | adapter authors: `InferenceProvider`, and `Schemas` |
| `nessy-engine` | the engine: `DefaultHarnessFactory`, the durable stores |
| `nessy-inference-anthropic`, `nessy-inference-openai`, `nessy-inference-gemini`, `nessy-inference-bedrock` | the provider adapters; the OpenAI one reaches every OpenAI-compatible endpoint |
| `nessy-console` | terminal applications: `Repl.run` |
| `nessy-spring-boot-starter` | the one dependency a Boot application adds; no code of its own |
| `nessy-spring-boot-autoconfigure` | the beans, the `nessy.*` properties, and every optional module's auto-configuration |
| `nessy-prompt`, `nessy-prompt-spring`, `nessy-prompt-mustache` | prompts as templates, and two engines |
| `nessy-memory-notebook` | notes an agent keeps and recalls by heading |
| `nessy-memory-summarizing` | one rolling summary per agent, replaced as the story grows |
| `nessy-planning` | the Planning pattern: a plan an agent writes and works through across turns |
| `nessy-lease` | background work that must run once across processes |
| `nessy-narration-odyssey` | agent events as resumable streams, for a browser |
| `nessy-approval-risk` | the risk gate: two thresholds with a person in between |
| `nessy-approval-intent` | the declared-intent claim channel |
| `nessy-approval-policy` | deciding a tool call by policy: approve, deny, or delegate |
| `nessy-approval-policy-opa` | an engine backed by Open Policy Agent |
| `nessy-tool-mcp` | importing a remote MCP server's tools |

## What Nessy gives you

| Capability | Site page |
|---|---|
| Agent as scope: one locked row per id, durable state instead of a live instance | [Agent as Scope](https://jwcarman.github.io/nessy/concepts/agent-as-scope/) |
| Durable computation: effects as rows, deadlines as columns, recovery without a sweep | [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/) |
| Tools: structured calls, typed inputs, and deferring to the world | [Tools](https://jwcarman.github.io/nessy/concepts/tools/) |
| Authorization: approvers, reply tokens, and describing what a person is consenting to | [Authorization](https://jwcarman.github.io/nessy/concepts/authorization/) |
| Risk: an assessment over the NIST SP 800-30 matrix, and two thresholds with a person in between | [Authorization](https://jwcarman.github.io/nessy/concepts/authorization/#gating-on-risk) |
| Intent: the claim channel a model states and an approver may trust | [Intent](https://jwcarman.github.io/nessy/concepts/intent/) |
| Memory: summaries, the tail and ambient, and a head summariser that runs itself | [Memory](https://jwcarman.github.io/nessy/concepts/memory/) |
| Planning: a plan the model holds, and the family of patterns to come | [Planning](https://jwcarman.github.io/nessy/concepts/planning/) |
| Storage: a table per thing, a codec seam for encryption, every model call on record | [Storage](https://jwcarman.github.io/nessy/concepts/storage/) |
| Providers: four adapters, every OpenAI-compatible endpoint, and thinking as a provider setting | [Providers](https://jwcarman.github.io/nessy/guides/providers/) |
| Prompts: templates with holes, and sources for the values | [Prompts](https://jwcarman.github.io/nessy/guides/prompts/) |
| Events: listeners, the builder, and streams a browser can resume | [Events](https://jwcarman.github.io/nessy/guides/events/) |
| MCP: import a remote server's tools as ordinary tools | [MCP Clients](https://jwcarman.github.io/nessy/guides/mcp-clients/) |
| The harness: kept, not closed; observing, coalescing, and approval desks | [The Harness](https://jwcarman.github.io/nessy/guides/harness/) |
| Observability: GenAI semantic conventions, traces that cross the outbox, and the record | [Observability](https://jwcarman.github.io/nessy/guides/observability/) |
| Spring Boot: a harness and every optional module from `nessy.*` properties and beans | [Spring Boot](https://jwcarman.github.io/nessy/guides/spring-boot/) |

## Roadmap

Where this is all headed lives in [ROADMAP.md](ROADMAP.md): memory that
learns, planning that replans, delegation that scales, and the road to a
first release.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for how to
get started, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the standards
we hold this project to. Please report security issues per
[SECURITY.md](SECURITY.md) rather than filing a public issue.

## License

Nessy is licensed under the [Apache License 2.0](LICENSE).

## The name

> **What's in a name?** Look at the middle of the word *har****ness***: the
> name was hiding inside the thing the whole time. And once your agent
> framework is named Nessy, the mascot picks itself: a certain famously
> elusive resident of Loch Ness, here wearing (what else?) a harness. Like
> her namesake, she's mostly calm water on the surface with a great deal
> going on underneath.
