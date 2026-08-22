<p align="center">
  <img src="assets/brand/nessy-mascot-512.png" alt="Nessy" width="300">
</p>

# Nessy

An agent harness framework for Java.

## The elevator pitch

An agent, in Nessy, is a recipe bound to a scope. The recipe is an
`AgentType` — system prompt, tools, model, wiring — compiled once into a
`Harness` and shared by every scope that uses it. The scope is an `AgentId`
— a plain string wrapped in a record, naming one conversation, one tenant,
one ticket, whatever your domain calls a "who." `harness.bind(id)` straps
the two together into a `Binding`, and an agent instance is built fresh over
that binding on demand — cheap to build, safe to discard, and never trusted
to hold state across a crash. History and phase live in durable stores keyed
by the id, not in the instance. See
[Agent as Scope](concepts/agent-as-scope.md) for the whole model.

**Prior art, in one paragraph.** Agent-as-scope is the virtual-actor model
with one deliberate deviation: `(AgentType, AgentId)` plays the role of
Orleans' `(grain type, grain key)`, and a binding is a grain activation
*minus* the single-activation guarantee — safety comes from an optimistic
version check and at-least-once idempotence instead, which is what lets any
node in a cluster answer for any scope. On the durable side, a parked tool
call is what Restate or DBOS would call a durable promise. And a host — the
process-level assembly of harnesses, dispatch, and delivery doors — agrees
with MCP's own architecture noun for the same role.

## Two doors

Both doors below are built the same way: a `ModelProvider`, a
`ModelSettings`, and a `Nessy` builder. Export a key and the first one makes
a real call:

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

AnthropicModelProvider provider = AnthropicModelProvider.fromEnv();
ModelSettings settings = new ModelSettings(
    "claude-haiku-4-5-20251001", "You are a terse assistant.", 1024, Set.of(), null);

try (CliAgent agent = Nessy.cli().provider(provider).settings(settings).tools(new AddTool()).build()) {
    String reply = agent.converse("what is 2+2?");
    System.out.println(reply);
    // The answer is 4.
}
```

`Nessy.cli()` is the interactive front door: one scope for the process, one
turn at a time, the caller's thread parks on the reply — the shape a REPL or
a one-shot script both want. `.tools(Tool<?>...)` grants each tool an
answered-allow policy for you; reach for `ToolGrant.grant(...)` directly, as
below, when a tool needs a real authority rule instead.

For a host that keeps running without a human driving each turn, there's a
second front door — `Nessy.autonomous()` — built the same way, but posting
observations instead of blocking calls, and fronting whatever a tool's
policy decides needs a human with an `ApprovalDesk`:

```java
var pending = new LinkedBlockingQueue<ApprovalRequest>();

try (AutonomousHost host =
    Nessy.autonomous()
        .provider(provider)
        .settings(settings)
        .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
        .approvalNotifier(pending::add)
        .build()) {

    host.post("ops", "restart prod-1");

    ApprovalRequest request = pending.take();
    host.approvals().approve(request.address().approval());
}
```

`post(agentId, observation)` enqueues one fact for that scope and returns
immediately. If `RestartTool`'s grant requires approval, the call suspends
on a durable slot and `approvalNotifier` fires once with the
`ApprovalRequest` that `host.approvals().approve(...)` or `.deny(...)`
decides. Whether that slot survives a restart of the process that opened it
depends on the `Substrate` behind `.substrate(...)` — the default in-memory
substrate does not, a durable implementation does. See
[Storage](concepts/storage.md).

See [Getting Started](guides/getting-started.md) for the CLI door walked
through line by line.

## The module map

Five modules, each with a persona in mind:

| Module | Depends on | Who compiles against it |
|---|---|---|
| `nessy-durable` | — | the durable-computation primitive everything else builds on |
| `nessy-api` | `nessy-durable` | tool, policy, and enricher authors — the shared vocabulary: `Tool`, `ToolGrant`, messages, the authorization grammar |
| `nessy-spi` | `nessy-api` | adapter authors — a custom `Memory`, approver, or `Substrate` implementation |
| `nessy-agent` | `nessy-api`, `nessy-spi` | application builders — the machine, both host doors, and the shipped kit (`VerbatimMemory`, `InMemorySubstrate`, the storage recipes) |
| `nessy-intent` | `nessy-api`, `nessy-spi` | applications that want the declared-intent claim channel — `IntentTool`, `IntentStore`, `IntentEnricher` |

A model provider module (`nessy-model-anthropic`, `nessy-model-openai`,
`nessy-model-gemini`, `nessy-model-bedrock`, or `nessy-model-env` to pick
between them from the environment) sits alongside `nessy-agent` in every
application's dependency list.

## Where to go next

<div class="grid cards" markdown>

- **[Agent as Scope](concepts/agent-as-scope.md)**

    The core model: phases that carry their own data, the decide-commit-
    save-dispatch transition, and why recovery is `drive()`'s second arm
    rather than a separate code path.

- **[The Four Tiers](concepts/the-four-tiers.md)**

    Substrate, host, harness, binding — how the pieces above compose, and
    what makes a binding cheap enough to throw away.

- **[Storage](concepts/storage.md)**

    `Substrate`: two shapes, one batch, and the recipes — state, memory,
    intent, backlog, durable computation — built on top of it.

- **[Memory](concepts/memory.md)**

    The `Memory` SPI, `VerbatimMemory`, and what "the memory owns history"
    means for a model call.

- **[Getting Started](guides/getting-started.md)**

    The CLI door, explained line by line.

</div>
