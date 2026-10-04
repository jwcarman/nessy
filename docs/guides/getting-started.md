# Getting Started

Build a direct harness once, keep it, ask it things. This page walks that
door through line by line, then shows the other one.

## Install

Nessy needs Java 25. Import the BOM to align every Nessy module's version,
then pick the artifacts the application actually needs.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.jwcarman.nessy</groupId>
      <artifactId>nessy-bom</artifactId>
      <version>0.4.0</version>
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
    <artifactId>nessy-backend-inmemory</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.nessy</groupId>
    <artifactId>nessy-inference-anthropic</artifactId>
  </dependency>
  <dependency>
    <groupId>org.jwcarman.codec</groupId>
    <artifactId>codec-jackson</artifactId>
    <version>0.10.0</version>
  </dependency>
</dependencies>
```

The BOM manages Nessy's own artifacts only. `codec-jackson` belongs to the
codec library and is not a dependency of `nessy-engine`, so it carries its
own version.

`nessy-engine` pulls in `nessy-api` (the vocabulary you write tools against)
and `nessy-inference-spi` (the seam you write provider adapters against). A
backend is a separate dependency, because which one you pick is a decision
the engine does not make for you.

The snippets on this page use these imports. `JsonMapper` is the Jackson 3
class, in `tools.jackson`:

```java
import org.jwcarman.codec.CodecFactory;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessFactory;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import tools.jackson.databind.json.JsonMapper;
```

## What the factory needs

**A backend.** `nessy-backend-inmemory` keeps an agent's state, its story
and its outstanding work in the process's own heap — nothing here is
durable, and nothing here needs to be, for a first run:

```java
CodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
DirectBackend backend = new InMemoryDirectBackend(codecs);
```

`nessy-backend-jdbc` implements the same `DirectBackend` seam over
PostgreSQL rows instead, for anything that must survive a restart — bring a
`DataSource` and a Spring `PlatformTransactionManager`, call
`Schemas.initialize(dataSource)` once, and hand
`new JdbcDirectBackend(dataSource, transactionManager, codecs)` to the
factory instead. See [Storage](../concepts/storage.md).

**A provider.** An `InferenceProvider` is a vendor adapter, one per
application:

```java
InferenceProvider provider = AnthropicInferenceProvider.fromEnv();   // ANTHROPIC_API_KEY
```

Four ship: Anthropic, OpenAI, Gemini and Bedrock, one module each. The
OpenAI one speaks both Chat Completions and the Responses API, and reaches
anything that speaks OpenAI's Chat Completions protocol, a local LM Studio
included; see [Providers](providers.md).

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

**The factory** is one per process, built from the backend and every
provider it registers, each under a name:

```java
DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(backend)
        .provider(ProviderId.of("anthropic"), provider));
```

**A harness** is one per agent type, and states its own provider and
model — there is no factory-wide default to fall back on unless the
factory sets one with `inference(ProviderId, InferenceOptions)`:

```java
DirectHarness<String, String> harness = factory.<String>create(
        new AgentType("assistant"),
        config -> config
                .systemPrompt("You are a terse assistant.")
                .inference(in -> in.provider("anthropic").model("claude-sonnet-5-5"))
                .tool(new AddTool()));
```

`create` with no answer type is the `String` overload: the model's prose,
joined, unparsed. Ask for a Java type instead when the caller wants a
shape, and the shape reaches the provider as a schema:

```java
record Verdict(boolean approved, String reason) {}

DirectHarness<String, Verdict> reviewer = factory.<String, Verdict>create(
        new AgentType("reviewer"), Verdict.class,
        config -> config
                .systemPrompt("You review a request and decide.")
                .inference(in -> in.provider("anthropic").model("claude-sonnet-5-5")));
```

## Asking, and getting an outcome

`ask` runs the whole turn on the calling thread and hands back an
`Outcome<O>` — nothing here throws for anything it understands:

```java
Outcome<String> outcome = harness.ask(AgentId.random(), "what is 2+2?");

switch (outcome) {
    case Outcome.Answered<String>(String said, _) -> System.out.println(said);
    case Outcome.Refused<String>(String category, _) -> System.out.println("refused: " + category);
    case Outcome.Failed<String>(String reason, _) -> System.out.println("failed: " + reason);
    case Outcome.Busy<String> _ -> System.out.println("busy; try again");
}
```

`Answered`, `Refused` and `Failed` each carry a `TurnStats` as their last
component, what the turn did and what it cost; the `_` ignores it here.

`Busy` means no turn ran at all — somebody else already holds this agent —
and is the only arm worth simply retrying. See [The Harness](harness.md#outcome)
for what each arm carries and why.

A listener is still worth attaching even though the answer comes back
directly: it is the only way to watch the deltas arrive before `ask`
returns.

```java
.listener(NarrationListener.of(on -> on
        .onContentDelta((narrated, delta) -> System.out.print(delta.text()))))
```

## The console door

For a terminal agent, one call does the whole bootstrap: the provider from
the environment, the harness and the read-line loop. Add `nessy-console` and
one provider adapter to the dependencies above; `nessy-inference-openai`
serves the local presets below.

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

`SendEmailTool` is a tool of your own, written like `AddTool`. The snippet
also imports `org.jwcarman.nessy.console.Repl` and
`org.jwcarman.nessy.console.ConsoleApprover`.

Run it against a local model with no key and no cost, using the `lmstudio`
or `ollama` preset:

```bash
export NESSY_PROVIDERS_LMSTUDIO_ENABLED=true
export NESSY_PROVIDER=lmstudio
export NESSY_MODEL=<a model id your endpoint serves>
```

Or with Ollama:

```bash
export NESSY_PROVIDERS_OLLAMA_ENABLED=true
export NESSY_PROVIDER=ollama
export NESSY_MODEL=<a model id your Ollama instance serves>
```

The conversation stays in memory and ends with the process. A database is
only for tools that bring their own store, such as a notebook.
`nessy-examples/chat-cli` is the longer version: a Spring Boot application
that hands `Repl.run` the starter's factory, over PostgreSQL, with a
notebook, a plan and the date, given as ambient background, added.

## Telling it something instead

Not every caller is waiting. `QueuedHarness<I>.tell` always accepts and
returns nothing; the turn happens later, on the harness's own dispatcher,
and the answer is narrated to listeners rather than returned:

```java
QueuedHarnessFactory factory = DefaultQueuedHarnessFactory.of(config -> config
        .backend(new InMemoryQueuedBackend(codecs))
        .provider(ProviderId.of("anthropic"), provider)
        .inference(ProviderId.of("anthropic"), InferenceOptions.of("claude-sonnet-5-5")));

QueuedHarness<String> harness = factory.create(new AgentType("watchman"), config -> config
        .systemPrompt("You watch a house."));

harness.tell(AgentId.random(), "the porch light came on");
```

See [The Harness](harness.md) for the queued door's backlog policy, its
approvals that can genuinely wait on a person, and the rest of its
configuration surface.

## Where next

- [The Harness](harness.md), both doors and their full configuration surface
- [Agent as Scope](../concepts/agent-as-scope.md), one lock per id, phases as data
- [Tools](../concepts/tools.md), deferring, and answering from outside
- [Authorization](../concepts/authorization.md), approvers and reply tokens
- [Spring Boot](spring-boot.md), the starter
