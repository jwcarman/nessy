# Getting Started

An `Agent<I>` is a reusable identity — a model, a system prompt, a set of granted
tools — built once from a `Harness` and then told things. This page builds the
smallest one that actually calls a tool, against a real model, then points at
the next few steps: an interactive console, and a restart that doesn't lose
anything.

## The shape

Every agent starts the same way: a `ModelProvider` builds a `Harness`, the
harness builds an `Agent`, and the agent is told things through a
`Conversation`. Export a key and this one makes a real call:

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

System.out.println(text + " (" + outcome.state().status() + ")");
// The answer is 4. (COMPLETE)
```

The wiring itself — provider, harness, agent, `tell` — is about twenty lines.
`AddTool` is another dozen, and it's the part that changes from agent to
agent; the wiring around it barely does.

A few things worth naming:

- `.name("adder")` is not a label. It's the durable stamp every parked call
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
  `Parked`.

`OPENAI_API_KEY` and `OpenAiModelProvider.builder().fromEnv().build()` swap
providers with no other change to this shape; `EnvModelProviders.fromEnv()`
(from `nessy-model-env`) picks whichever key is set for you — see
[Providers](providers.md).

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

- [Testing](testing.md) — running this same shape with no key at all,
  against `ScriptedModelProvider`.
- [Console Apps](console-apps.md) — turning an `Agent<String>` into an
  interactive terminal REPL.
- [The Durable Loop](../concepts/durable-loop.md) — the fold, `Awaited`, and
  why every API here is built for at-least-once delivery.
