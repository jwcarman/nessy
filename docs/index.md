<p align="center">
  <img src="assets/brand/nessy-mascot-512.png" alt="Nessy" width="300">
</p>

# Nessy

Nessy is a durable agent harness framework for Java. It turns a model API into
an agent: the effectful loop, the tool plumbing, an approval gate the model
cannot route around, streaming as a first-class citizen, and observability
built in rather than bolted on.

The word "durable" is load-bearing. A conversation's history lives in a
[`Transcript`](concepts/storage.md), not in a field on some object holding
your process open — so a crash between turns loses nothing, and a turn that
asks a human for approval can sit **parked** for as long as that takes,
surviving a restart of the process that started it. Every turn folds onto the
transcript idempotently, so re-delivering an event (a queue redelivering a
message, a retried webhook) never double-applies it. That single property —
replay safety — shapes every API in the framework: the loop is at-least-once
by design, and correctness comes from the fold, not from careful
once-only delivery.

## An agent in about twenty lines

This runs with no API key, no network, and no real model:
`ScriptedModelProvider` plays back a scripted conversation, so the example
compiles and runs against exactly what ships today. Real providers are
`nessy-model-*` modules (`nessy-model-anthropic`, `nessy-model-openai`); swap
one in and nothing else about this shape changes — see the
[Getting Started](guides/getting-started.md) guide.

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

ObjectNode args = JsonNodeFactory.instance.objectNode();
args.put("left", 2);
args.put("right", 2);

ScriptedModelProvider provider = ScriptedModelProvider.builder()
        .toolUse("c1", "add", args)
        .endWithToolUse()
        .text("The answer is 4.")
        .endTurn()
        .build();

Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .name("hello")
        .model("fake-model")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .build();

StringBuilder text = new StringBuilder();
RunOutcome outcome =
    agent
        .converse()
        .tell(
            "what is 2+2?",
            TurnObserver.builder().onTextDelta(delta -> text.append(delta.text())).build());

System.out.println(text + " (" + outcome.state().status() + ")");
// The answer is 4. (COMPLETE)
```

The wiring itself — provider, harness, agent, `tell` — is about twenty lines;
`AddTool` is another dozen, and it's the part that changes from agent to
agent.

`.name("hello")` isn't decoration — every agent identifies itself, and that
identity is what a [parked callback](concepts/parks-and-callbacks.md) checks
before letting a resume through. `Memory` here is the builder's default —
an in-memory [pipeline](concepts/memory-and-the-pipeline.md) over the
transcript — swapped for a durable one only when you ask.

## Where to go next

<div class="grid cards" markdown>

- **[Concepts](concepts/durable-loop.md)**

    The vocabulary: the durable loop, tools and grants, parks and callbacks,
    the memory pipeline, planning, and storage.

- **[Guides](guides/getting-started.md)**

    Task-shaped walkthroughs: getting started, providers, durable persistence,
    console apps, MCP clients, and more.

- **[Examples](examples/index.md)**

    A tour of the shipped example modules — `hello` through `order-desk` —
    what each one teaches.

- **[Reference](reference/configuration.md)**

    Configuration properties, the storage TCK, the changelog, and the source
    tree.

</div>
