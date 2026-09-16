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
    <artifactId>nessy-inference-anthropic</artifactId>
  </dependency>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
  </dependency>
</dependencies>
```

`nessy-engine` pulls in `nessy-api` (the vocabulary you write tools against)
and `nessy-spi` (the seams you write adapters against).

## Two things the engine needs

**A database.** The engine is PostgreSQL rows: an agent's state, its story,
the work it owes. Bring a `DataSource` and apply the schema once:

```java
DataSource dataSource = ...;          // any PostgreSQL DataSource
Schemas.initialize(dataSource);       // every module's nessy-schema.sql, once
```

There is no in-memory fallback. The queries the engine rests on are
PostgreSQL's, so a fallback would not run a degraded Nessy, it would run one
that fails on the first turn. See [Storage](../concepts/storage.md).

**A provider.** An `InferenceProvider` is a vendor adapter, one per
application:

```java
InferenceProvider provider = AnthropicInferenceProvider.fromEnv();   // ANTHROPIC_API_KEY
```

Four ship: Anthropic, OpenAI, Gemini and Bedrock, one module each. The
OpenAI one also speaks to anything with OpenAI's wire protocol, a local LM
Studio included; see [Providers](providers.md).

## A tool

A tool is a name, a description, an input type, and a method.

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
```

The input type becomes the JSON schema the model is shown, so a record with
good field names *is* the documentation. `Awaited.ready` answers now;
`Awaited.deferred` parks the call and lets the world answer later. See
[Tools](../concepts/tools.md).

## The smallest harness

Two configurations, and the difference matters. **The engine** is one per
process:

```java
DefaultHarnessFactory factory = new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(provider, InferenceOptions.of("claude-sonnet-5")));
```

**A harness** is one per agent type:

```java
Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("assistant"))
        .systemPrompt("You are a terse assistant.")
        .tool(new AddTool()));
```

`create(customizer)` is the `String` observation type. Use your own record
when a string is not the honest shape, and say how it reads to the model:

```java
record HouseEvent(String room, String what) {}

Harness<HouseEvent> house = factory.create(HouseEvent.class, config -> config
        .agentType(new AgentType("watchman"))
        .systemPrompt("You watch a house.")
        .observationRenderer(event -> List.of(new Block.Text(event.room() + ": " + event.what()))));
```

The model and the token cap come from the engine unless a harness says
otherwise:

```java
config.inference(in -> in.model("claude-haiku-4-5").maxTokens(1024));
```

## Telling it something, and hearing back

`observe` is a post, not a call. It returns as soon as the observation is
durable; the answer is **narrated** to listeners.

```java
AgentId agentId = AgentId.random();

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("assistant"))
        .systemPrompt("You are a terse assistant.")
        .listener(AgentEventListener.of(on -> on
                .onContentDelta((type, id, delta) -> System.out.print(delta.text()))
                .onTurnEnded((type, id, ended) -> System.out.println())))
        .tool(new AddTool()));

harness.observe(agentId, "what is 2+2?");
```

A listener hears every agent of its harness, in order, off the thread that
folds the turn. Attach one to the engine instead to hear every harness. See
[Events](events.md) for the eighteen event kinds and for streaming them to a
browser.

## The console door

For a terminal agent, one call does the whole bootstrap: the database, the
provider from the environment, the harness and the read-line loop.

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

Run it against a local model with no key and no cost:

```bash
export OPENAI_API_KEY=not-needed
export OPENAI_BASE_URL=http://localhost:1234/v1
export NESSY_MODEL=<a model id your endpoint serves>
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/nessy
export SPRING_DATASOURCE_USERNAME=nessy
export SPRING_DATASOURCE_PASSWORD=secret
```

`nessy-examples/chat-cli` is exactly this, with a notebook, a plan and a
templated prompt added.

## Where next

- [The Harness](harness.md), the full configuration surface
- [Agent as Scope](../concepts/agent-as-scope.md), one locked row per id, phases as data
- [Tools](../concepts/tools.md), deferring, and answering from outside
- [Authorization](../concepts/authorization.md), approvers and reply tokens
- [Spring Boot](spring-boot.md), the starter
