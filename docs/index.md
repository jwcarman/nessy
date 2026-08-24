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
the two together and hands back a transient `Agent<O>`, built fresh on
demand — cheap to build, safe to discard, and never trusted to hold state
across a crash. History and phase live in durable stores keyed by the id,
not in the instance. See
[Agent as Scope](concepts/agent-as-scope.md) for the whole model.

**Prior art, in one paragraph.** Agent-as-scope is the virtual-actor model
with one deliberate deviation: `(AgentType, AgentId)` plays the role of
Orleans' `(grain type, grain key)`, and a binding is a grain activation
*minus* the single-activation guarantee — safety comes from an optimistic
version check and at-least-once idempotence instead, which is what lets any
node in a cluster answer for any scope. On the durable side, a parked tool
call is what Restate or DBOS would call a durable promise. "Host" retires
to meaning your process — the JVM that keeps a harness reference alive,
nothing more; MCP's own architecture noun agrees. The harness itself now
carries what a separate host tier used to: the delivery worker, the
approval and completion desks, and the computation scheduler driving their
pumps.

## One door

Ask Nessy for a harness; keep it forever; bind any id into a transient
agent; tell it things. Durability is a property of the substrate, not the
API. Export a key and the snippet below makes a real call:

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

var anthropic = AnthropicModelProvider.fromEnv();   // vendor gateway — one per app

var harness = Nessy.harness(h -> h                  // built once, kept — immortal
        .model(anthropic.model("claude-sonnet-5"))  // the one required dependency
        .systemPrompt("You are a terse assistant.")
        .tools(new AddTool()));                     // bare tools, allow-by-default

harness.bind(AgentId.of("scope-1")).tell("what is 2+2?");
```

This snippet runs — nothing else is required. `harness.bind(id)` returns a
plain, transient `Agent<String>` — thin, never closeable, holding nothing.
`.tell(observation)` enqueues one fact for that scope and returns
immediately; the reply is narrated, not returned — see
[Observability](guides/observability.md). `.tools(Tool<?>...)` grants each
tool an answered-allow policy for you; reach for `ToolGrant.grant(...)`
directly, as below, when a tool needs a real authority rule instead.

The harness is kept, not closed: no `try`-with-resources here, and none in
any example on this site. Its life-support — the delivery worker, the
approval and completion desks, and the `ComputationScheduler` driving their
pumps — runs on daemon threads for as long as the process does. A single
tool that needs a human decides
that through the same harness, fronted with an `ApprovalDesk`:

```java
var pending = new LinkedBlockingQueue<ApprovalRequest>();

var harness =
    Nessy.harness(
        h ->
            h.model(claude)
                .systemPrompt("You are the ops assistant.")
                .grants(ToolGrant.grant(new RestartTool(), RESTART_ACTION, UsagePolicy.requireApproval()))
                .approvalNotifier(pending::add));

harness.bind(AgentId.of("ops")).tell("restart prod-1");

ApprovalRequest request = pending.take();
harness.approvals().approve(request.id());
```

If `RestartTool`'s grant requires approval, the call suspends on a durable
computation and `approvalNotifier` fires once with the `ApprovalRequest`
that `harness.approvals().approve(...)` or `.deny(...)` decides. Whether
that computation survives a restart of the process that opened it depends
on the `Substrate` behind `.substrate(...)` — the default in-memory
substrate does not, a durable implementation does. See
[Storage](concepts/storage.md).

See [Getting Started](guides/getting-started.md) for this door walked
through line by line.

## The module map

Four modules, each with a persona in mind:

| Module | Depends on | Who compiles against it |
|---|---|---|
| `nessy-api` | — | tool, policy, and enricher authors — the shared vocabulary: `Tool`, `ToolGrant`, messages, the authorization grammar, and the durable-computation primitive (`ComputationId` and friends) everything else builds on |
| `nessy-spi` | `nessy-api` | adapter authors — a custom `Memory`, approver, or `Substrate` implementation |
| `nessy-agent` | `nessy-api`, `nessy-spi` | application builders — the machine, the `Nessy` front door, and the shipped kit (`VerbatimMemory`, `InMemorySubstrate`, the storage recipes) |
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

- **[The Tiers](concepts/the-four-tiers.md)**

    Substrate, harness, binding — how the pieces above compose, and
    what makes a binding cheap enough to throw away.

- **[Storage](concepts/storage.md)**

    `Substrate`: two shapes, one batch, and the recipes — state, memory,
    intent, backlog, durable computation — built on top of it.

- **[Memory](concepts/memory.md)**

    The `Memory` SPI, `VerbatimMemory`, and what "the memory owns history"
    means for a model call.

- **[Getting Started](guides/getting-started.md)**

    The harness door, explained line by line.

</div>
