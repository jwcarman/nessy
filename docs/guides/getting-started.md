# Getting Started

An `Agent<I>` is a reusable identity — a model, a system prompt, a set of granted
tools — built once from a `Harness` and then told things. This page builds the
smallest one that actually calls a tool, using no API key and no network, then
points at the next three steps: a real provider, an interactive console, and a
restart that doesn't lose anything.

## The shape

Every agent starts the same way: a `ModelProvider` builds a `Harness`, the
harness builds an `Agent`, and the agent is told things through a
`Conversation`. The example below uses `nessy-testing`'s `ScriptedModelProvider`
instead of a real provider, so it compiles and runs offline — the same shape
`Hello`, the runnable example behind this page, ships as
`nessy-examples/hello`:

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

ScriptedModelProvider provider =
    ScriptedModelProvider.builder()
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

The wiring itself — provider, harness, agent, `tell` — is about twenty lines.
`AddTool` is another dozen, and it's the part that changes from agent to
agent; the wiring around it barely does.

A few things worth naming:

- `.name("hello")` is not a label. It's the durable stamp every parked call
  and every callback door checks a resolution against — see
  [The Durable Loop](../concepts/durable-loop.md).
- `.tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))` is the whole
  capability story: a tool the model can see is one this call explicitly
  granted, with a policy (`allow()` here; `requireApproval()` gates it
  instead) — see [Tools and Grants](../concepts/tools-and-grants.md).
- `Memory` isn't set here, so the builder's default applies: an in-memory
  pipeline over the transcript. It disappears when the process does — see
  [Durable Persistence](durable-persistence.md) for the version that
  doesn't.
- `agent.converse().tell(...)` returns a `RunOutcome`, either `Completed` or
  `Parked`. `ScriptedModelProvider` never parks anything, so this one is
  always `Completed`.

## Running it for real

Swap `ScriptedModelProvider` for a real `ModelProvider` and nothing else
about this shape changes. `nessy-model-anthropic` and `nessy-model-openai`
each build one from an API key; `nessy-model-env` picks between them from
whichever key is set in the environment — see [Providers](providers.md).

## Talking back and forth

A one-shot `tell` proves the wiring works, but a real agent holds a
conversation. `nessy-console`'s `ConsoleRepl` turns any `Agent<String>` into a
terminal chat loop in a few lines, streaming, spinner, and approval prompts
included — see [Console Apps](console-apps.md).

## Surviving a restart

Everything above dies with the JVM: `ConversationStore.inMemory()` and
`Transcript.inMemory()` are the harness's defaults precisely because they
need no setup. Swapping in `nessy-jdbc` makes the same conversation survive a
crash or a restart with no change to the agent's own shape — see
[Durable Persistence](durable-persistence.md).

## Where next

- [Providers](providers.md) — a real `ModelProvider`, and switching between
  Anthropic and OpenAI by environment variable.
- [Console Apps](console-apps.md) — turning an `Agent<String>` into an
  interactive terminal REPL.
- [The Durable Loop](../concepts/durable-loop.md) — the fold, `Awaited`, and
  why every API here is built for at-least-once delivery.
