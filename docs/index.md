<p align="center">
  <img src="assets/brand/nessy-mascot-512.png" alt="Nessy" width="300">
</p>

# Nessy

An agent harness framework for Java.

## The elevator pitch

An agent, in Nessy, is a recipe bound to an id. The recipe is an
`AgentType` — system prompt, tools, model, memory — compiled once into a
`Harness` and shared by every id that uses it. The id is an `AgentId`: a
plain string naming one conversation, one tenant, one ticket, whatever your
domain calls a "who."

You tell a harness things. It has no per-agent handle to hold, because a
handle is a thing that can go stale, and sharding already knows where an
agent lives.

```java
harness.observe(AgentId.of("house-12"), "the porch light came on");
```

One agent is one sharded, durable actor, and it works one turn at a time.
What it persists is a turn id, a phase and two claim ids — around 260 bytes,
measured, and it does not grow with what the agent does. See
[Agent as Scope](concepts/agent-as-scope.md) for the model and
[Durable Computation](concepts/durable-computation.md) for what survives a
crash.

**Prior art, in one paragraph.** `(AgentType, AgentId)` plays the role of
Orleans' `(grain type, grain key)`, and cluster sharding gives the
single-activation guarantee outright — there is exactly one actor per id, so
two callers cannot corrupt one agent's state. On the durable side, a parked
tool call is what Restate or DBOS would call a durable promise: it survives
the process that opened it, because its deadline is a database row rather
than a timer in memory.

## One door

Build a harness once, keep it, tell it things.

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

var factory = new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)
        .models(AnthropicModelProvider.fromEnv()));

Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("assistant"))
        .systemPrompt("You are a terse assistant.")
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .tool(new AddTool()));

harness.observe(AgentId.of("scope-1"), "what is 2+2?");
```

`observe` is a post, not a call: it returns as soon as the observation is
durable, and the answer is **narrated** rather than returned. Subscribe to
hear it:

```java
try (var subscription = harness.subscribe(agentId, event -> {
        if (event instanceof AgentEvent.TextDelta delta) {
            System.out.print(delta.text());
        }
    })) {
    harness.observe(agentId, "what is 2+2?");
}
```

Hand the engine no `DataSource` and it builds an in-memory H2 and
initializes it, so the snippet above runs with nothing else configured. Hand
it one and it uses that — and never touches it uninvited. See
[Storage](concepts/storage.md).

For a terminal agent, `Repl.run` does the whole bootstrap in one call; see
[The Harness](guides/harness.md#the-console-the-whole-application-in-one-call).

## Gating a tool on a person

A tool that needs a decision gets an approver. It can answer now, or defer
and let a person answer days later:

```java
Approver desk = (request, context) -> {
    pending.save(request, context.replyToken());        // hand out the address
    return Awaited.deferred(clock.instant().plus(Duration.ofDays(3)));
};

Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("ops"))
        .systemPrompt("You are the ops assistant.")
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .tool(new RestartTool(), binding -> binding
                .approver(desk)
                .action(input -> "restart " + input.host())));

harness.observe(AgentId.of("ops"), "restart prod-1");
```

Deferring parks the call, arms a durable alarm, and frees the agent. The
`ReplyToken` is the address the answer comes back to:

```java
replies.approve(token, ApprovalResult.approved());
```

That works after a restart, because the deadline is a row and the token
names logical coordinates rather than an object.

When "which tool is it" is too blunt a question, gate on how bad the call
would be instead:

```java
binding.approver(
    Risk.assessing(assessor)
        .approvingBelow(RiskLevel.MODERATE)      // runs unasked
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)   // refused without waking anybody
        .otherwiseAsking(desk));                 // and the middle band is what a person is for
```

See [Authorization](concepts/authorization.md).

## The module map

| Module | Who compiles against it |
|---|---|
| `nessy-api` | tool and policy authors — the shared vocabulary: `Tool`, `Approver`, `Awaited`, messages, `AgentEvent` |
| `nessy-spi` | adapter authors — a custom `Memory` or `Model`, and `Schemas` |
| `nessy-engine` | application builders — `PekkoHarnessFactory`, the actor, the stores |
| `nessy-console` | terminal applications — `Repl.run` |
| `nessy-spring-boot-starter` | Boot applications — one dependency, no code of its own |
| `nessy-spring-boot-autoconfigure` | the beans behind it, if you would rather assemble the starter yourself |
| `nessy-memory-notebook`, `nessy-memory-plan`, `nessy-memory-pipeline` | agents that keep notes, hold a plan, or shape their own context |
| `nessy-intent` | applications that want the declared-intent claim channel |
| `nessy-tool-mcp` | agents that call MCP servers |

A model provider module (`nessy-model-anthropic`, `nessy-model-openai`,
`nessy-model-gemini`, or `nessy-model-bedrock`) sits alongside
`nessy-engine` in every application's dependency list;
`nessy-model-discovery` resolves the one you shipped from the environment.

## Where to go next

<div class="grid cards" markdown>

- **[Agent as Scope](concepts/agent-as-scope.md)**

    The core model: one actor per agent, phases as data, and why recovery
    runs on every activation rather than only after a crash.

- **[Durable Computation](concepts/durable-computation.md)**

    What survives a crash: parked calls, reminders as rows, and answers
    addressed to a place rather than an object.

- **[Storage](concepts/storage.md)**

    The tables, why there is no abstraction over them, and how to apply
    the schema to your own database.

- **[Memory](concepts/memory.md)**

    The `Memory` SPI, and what "the memory owns history" means for a
    model call.

- **[Getting Started](guides/getting-started.md)**

    The harness door, explained line by line.

</div>
