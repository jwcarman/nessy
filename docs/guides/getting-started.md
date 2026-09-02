# Getting Started

Build a harness once, keep it, tell it things. This page walks that door
through line by line.

## Install

Nessy has not yet released to Maven Central. Build locally
(`./mvnw install`) and depend on `0.1.0-SNAPSHOT`.

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

<dependencies>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-engine</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-model-anthropic</artifactId>
  </dependency>
</dependencies>
```

`nessy-engine` pulls in `nessy-api` (the vocabulary you write tools against)
and `nessy-spi` (the seams you write adapters against).

## The one required dependency: a model

A `ModelProvider` is a vendor gateway — one per application, not per agent.

```java
var models = AnthropicModelProvider.fromEnv();   // reads ANTHROPIC_API_KEY
```

Every provider module ships one: `AnthropicModelProvider`,
`OpenAiModelProvider` (which also speaks to any OpenAI-compatible endpoint,
including a local LM Studio), `GeminiModelProvider`, `BedrockModelProvider`.
If you would rather resolve whichever one you shipped from the environment,
add `nessy-model-discovery`.

## A tool

A tool is a name, a description, an input type, and a method.

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public String name() { return "add"; }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }

    public Awaited<ToolResult> execute(ToolCallRequest<Add> call) {
        Add input = call.input();
        return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
}
```

The input type becomes the JSON schema the model is shown, so a record with
good field names *is* the documentation. `Awaited.ready` answers now;
`Awaited.deferred` parks the call and lets the world answer later — see
[Tools](../concepts/tools.md).

## The smallest harness

Two configurations, and the difference matters. **The engine** is one per
process:

```java
var factory = new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(models));
```

**A harness** is one per agent type:

```java
Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("assistant"))
        .systemPrompt("You are a terse assistant.")
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .tool(new AddTool()));
```

`String.class` is the **observation type** — whatever your domain tells this
agent about. `renderer` says how one becomes a message the model can read.
Use your own record when a string is not the honest shape:

```java
record HouseEvent(String room, String what) {}

factory.createHarness(HouseEvent.class, config -> config
        .renderer(event -> UserMessage.of(event.room() + ": " + event.what()))
        ...);
```

## Storage: nothing to configure, until it matters

Hand the engine no `DataSource` and it builds an in-memory H2 **and
initializes it**, so everything above runs with nothing else set up. It
announces that it did, because an application that forgot its database
should find out at startup rather than the first time a restart loses a
conversation.

Hand it one and it uses that — and never touches it uninvited:

```java
new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(models)
        .dataSource(dataSource));
```

You apply the schema, once, however your operators prefer:

```java
Schemas.initialize(dataSource);
```

See [Storage](../concepts/storage.md).

## Telling it something, and hearing back

`observe` is a post, not a call. It returns as soon as the observation is
durable; the answer is **narrated**.

```java
var agentId = AgentId.of("scope-1");

try (AgentSubscription subscription = harness.subscribe(agentId, event -> {
        switch (event) {
            case AgentEvent.TextDelta delta -> System.out.print(delta.text());
            case AgentEvent.TurnEnded ended -> System.out.println();
            default -> { }
        }
    })) {
    harness.observe(agentId, "what is 2+2?");
}
```

Close the subscription — an unclosed one leaks a routing entry. Every event
carries a time-ordered id, so a listener that drops off can resume from the
last one it saw.

## The console door

For a terminal agent, one call does the whole bootstrap — actor system,
cluster-of-one, reply tokens, harness, and the read-line loop:

```java
public static void main(String[] args) {
    Repl.run(config -> config
            .systemPrompt("You are a helpful assistant.")
            .tool(new AddTool())
            .tool(new SendEmailTool(), binding -> binding
                    .approver(ConsoleApprover.atTheTerminal())
                    .describer(email -> "Send an email to " + email.to())));
}
```

Run it against a local model with no key and no cost:

```bash
export OPENAI_API_KEY=not-needed
export OPENAI_BASE_URL=http://localhost:1234/v1
export NESSY_MODEL=<a model id your endpoint serves>
```

`nessy-examples/chat-cli` is exactly this, with a notebook and a plan added.

## Where next

- [The Harness](harness.md) — the full configuration surface
- [Agent as Scope](../concepts/agent-as-scope.md) — one actor per id, phases as data
- [Tools](../concepts/tools.md) — deferring, and answering from outside
- [Authorization](../concepts/authorization.md) — approvers and reply tokens
- [Spring Boot](spring-boot.md) — the starter
