<p align="center">
  <img src="assets/brand/nessy-mascot-512.png" alt="Nessy" width="300">
</p>

# Nessy

An agent harness framework for Java.

## The elevator pitch

An agent, in Nessy, is a recipe bound to an id. The recipe is an
`AgentType`: system prompt, tools, model, context policy, compiled once into
a harness and shared by every id that uses it. The id is an `AgentId`
naming one conversation, one tenant, one ticket, whatever your domain calls
a "who."

You tell a harness things. It has no per-agent handle to hold, because a
handle is a thing that can go stale, and the agent's row already knows
where it lives.

```java
harness.tell(agentId, "the porch light came on");
```

One agent is one id under a PostgreSQL advisory lock, and it works one turn
at a time. Its state is a phase, a position in its story and the calls it
is waiting on, a few hundred bytes that do not grow with what the agent
does. See
[Agent as Scope](concepts/agent-as-scope.md) for the model and
[Durable Computation](concepts/durable-computation.md) for what survives a
crash.

**Prior art, in one paragraph.** `(AgentType, AgentId)` plays the role of
Orleans' grain type and key, and a PostgreSQL advisory transaction lock on
the agent's id gives the single-activation guarantee outright: exactly one worker
touches an agent at a time, from any process that can reach the database.
On the durable side, a parked tool call is what Restate or DBOS would call a
durable promise: it survives the process that opened it, because its
deadline is a database row rather than a timer in memory.

## Two doors

Build a harness once per agent type, keep it, and pick the door your
caller needs. `DirectHarness<I, O>.ask` runs a turn on the calling thread
and hands back an `AskOutcome<O>` — for a caller standing there waiting.
`QueuedHarness<I>.tell` always accepts and returns nothing — for work
nobody is waiting on. Neither is a special case of the other.

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

DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(backend)
        .provider(ProviderId.of("anthropic"), AnthropicInferenceProvider.fromEnv()));

DirectHarness<String, String> harness = factory.<String>create(
        new AgentType("assistant"),
        config -> config
                .systemPrompt("You are a terse assistant.")
                .inference(in -> in.provider("anthropic").model("claude-sonnet-5-5"))
                .tool(new AddTool()));

AskOutcome<String> outcome = harness.ask(AgentId.random(), "what is 2+2?");
```

`backend` is a `DirectBackend`; [Getting Started](guides/getting-started.md)
builds an in-memory one in two lines and lists the dependencies and imports.
Each harness names its provider and model, because the factory has no
default unless it is given one.

`ask` never throws for anything it understands: a model declining, a turn
running out of budget or the agent already being busy are `AskOutcome` arms to
branch on, not faults. See [The Harness](guides/harness.md) for both doors
and [Getting Started](guides/getting-started.md) for the full walkthrough.

For a terminal agent, `Repl.run` does the whole bootstrap in one call; see
[The Harness](guides/harness.md#the-console-the-whole-application-in-one-call).

## Gating a tool on a person

A tool that needs a decision gets an approver. It can answer now, or defer
and let a person answer days later — which only the queued door can honour,
since a caller at the direct door is already waiting and has nowhere for a
late answer to arrive:

```java
Approver desk = request -> {
    notifier.send("Approve: " + request.action());
    return Awaited.deferred();
};

QueuedHarness<String> harness = factory.create(new AgentType("ops"), config -> config
        .systemPrompt("You are the ops assistant.")
        .tool(new RestartTool(), binding -> binding
                .approver(desk, terms -> terms.timeout(Duration.ofDays(3)))
                .action(input -> "restart " + input.host())));
```

Deferring parks the call and frees the agent. The approver keeps nothing: the
approvals waiting on a person are read from `AgentWork.waitingApprovals()`. The
agent type, the agent id and the call's idempotency key are the address the
answer comes back to, through the queued factory's `Replies`:

```java
factory.replies().approve(agentType, agentId, key, ApprovalResult.approved());
```

That works after a restart, because the deadline is a row and the three values
name the call rather than an object. Nessy does not check who is answering, so
guard the endpoint that calls `Replies`.

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
| `nessy-api` | tool and policy authors: `Tool`, `Approver`, `Awaited`, `NarrationListener`, `AskOutcome`, the block vocabulary |
| `nessy-inference-spi` | adapter authors: `InferenceProvider` |
| `nessy-backend-spi` | backend authors: `DirectBackend`, `QueuedBackend`, `Chapters` for an agent's closed chapters, and `Leases` for background work that must run once across processes |
| `nessy-backend-jdbc` | one PostgreSQL `DataSource` behind either door, and `Schemas` |
| `nessy-backend-inmemory` | the same stores with nothing behind them but the process |
| `nessy-engine` | application builders: `DefaultDirectHarnessFactory`, `DefaultQueuedHarnessFactory`, and the fold behind them |
| `nessy-inference-anthropic`, `nessy-inference-openai`, `nessy-inference-gemini`, `nessy-inference-bedrock` | the provider adapters; the OpenAI module speaks Chat Completions and the Responses API, and reaches every OpenAI-compatible endpoint |
| `nessy-console` | terminal applications: `Repl.run` |
| `nessy-spring-boot-starter` | Boot applications: one dependency, no code of its own |
| `nessy-spring-boot-autoconfigure` | the beans behind it, and every optional module's auto-configuration |
| `nessy-prompt`, `nessy-prompt-spring`, `nessy-prompt-mustache` | prompts as templates, and two engines |
| `nessy-embedding-spi`, `nessy-embedding-openai`, `nessy-embedding-gemini`, `nessy-embedding-bedrock`, `nessy-embedding-voyage` | text into vectors: the `Embedder` seam, and four embedders; the OpenAI one reaches any OpenAI-compatible endpoint |
| `nessy-memory-notebook` | agents that keep notes |
| `nessy-planning` | agents that write a plan and work through it |
| `nessy-narration-odyssey` | events as resumable streams, for a browser |
| `nessy-approval-risk` | the risk gate: two thresholds with a person in between |
| `nessy-approval-policy`, `nessy-approval-policy-opa` | deciding a call by policy; asking Open Policy Agent |
| `nessy-tool-mcp` | agents that call MCP servers |

## Where to go next

<div class="grid cards" markdown>

- **[Getting Started](guides/getting-started.md)**

    The direct door, explained line by line.

- **[The Harness](guides/harness.md)**

    Both doors in full: outcomes, termination, coalescing and configuration.

- **[Agent as Scope](concepts/agent-as-scope.md)**

    The core model: one lock per agent, phases as data, and a fold
    that is a pure function.

- **[Durable Computation](concepts/durable-computation.md)**

    What survives a crash: effects as rows, deadlines as columns, and
    answers addressed to a place rather than an object.

- **[Context](concepts/context.md)**

    The six strata of a model call, history cut into summarised chapters, and
    keeping the provider's cache.

- **[Memory](concepts/memory.md)**

    What an agent recalls: memory and state sources, notes, and embeddings.

- **[Leases](concepts/leases.md)**

    Background work that runs once across every instance, and why it is not
    an effect.

- **[Storage](concepts/storage.md)**

    The tables, the codec seam, and how to apply the schema to your own
    database.

- **[12-Factor Agents](concepts/twelve-factor-agents.md)**

    A factor-by-factor check of Nessy against the 12-Factor Agents
    principles: what it meets, what it does in part, and what it does not.

</div>
