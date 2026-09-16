<p align="center">
  <img src="assets/brand/nessy-mascot-512.png" alt="Nessy" width="300">
</p>

# Nessy

An agent harness framework for Java.

## The elevator pitch

An agent, in Nessy, is a recipe bound to an id. The recipe is an
`AgentType`: system prompt, tools, model, context policy, compiled once into
a `Harness` and shared by every id that uses it. The id is an `AgentId`
naming one conversation, one tenant, one ticket, whatever your domain calls
a "who."

You tell a harness things. It has no per-agent handle to hold, because a
handle is a thing that can go stale, and the agent's row already knows
where it lives.

```java
harness.observe(agentId, "the porch light came on");
```

One agent is one locked row in PostgreSQL, and it works one turn at a time.
Its state is a phase, a position in its story and the calls it is waiting
on, a few hundred bytes that do not grow with what the agent does. See
[Agent as Scope](concepts/agent-as-scope.md) for the model and
[Durable Computation](concepts/durable-computation.md) for what survives a
crash.

**Prior art, in one paragraph.** `(AgentType, AgentId)` plays the role of
Orleans' grain type and key, and a `SELECT ... FOR UPDATE` on the agent's
row gives the single-activation guarantee outright: exactly one worker
touches an agent at a time, from any process that can reach the database.
On the durable side, a parked tool call is what Restate or DBOS would call a
durable promise: it survives the process that opened it, because its
deadline is a database row rather than a timer in memory.

## One door

Build a harness once, keep it, tell it things.

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

DefaultHarnessFactory factory = new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(AnthropicInferenceProvider.fromEnv(), InferenceOptions.of("claude-sonnet-5")));

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("assistant"))
        .systemPrompt("You are a terse assistant.")
        .listener(AgentEventListener.of(on -> on
                .onContentDelta((type, id, delta) -> System.out.print(delta.text()))))
        .tool(new AddTool()));

harness.observe(AgentId.random(), "what is 2+2?");
```

`observe` is a post, not a call: it returns as soon as the observation is
durable, and the answer is **narrated** to listeners rather than returned.
See [Events](guides/events.md).

For a terminal agent, `Repl.run` does the whole bootstrap in one call; see
[The Harness](guides/harness.md#the-console-the-whole-application-in-one-call).

## Gating a tool on a person

A tool that needs a decision gets an approver. It can answer now, or defer
and let a person answer days later:

```java
Approver desk = request -> {
    pending.save(request, request.replyToken());        // hand out the address
    return Awaited.deferred();
};

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("ops"))
        .systemPrompt("You are the ops assistant.")
        .tool(new RestartTool(), binding -> binding
                .approver(desk, terms -> terms.timeout(Duration.ofDays(3)))
                .action(input -> "restart " + input.host())));
```

Deferring parks the call and frees the agent. The `ReplyToken` is the
address the answer comes back to:

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
| `nessy-api` | tool and policy authors: `Tool`, `Approver`, `Awaited`, `AgentEvent`, `AgentEventListener`, the block vocabulary |
| `nessy-spi` | adapter authors: `InferenceProvider`, and `Schemas` |
| `nessy-engine` | application builders: `DefaultHarnessFactory`, the durable stores |
| `nessy-inference-anthropic`, `nessy-inference-openai`, `nessy-inference-gemini`, `nessy-inference-bedrock` | the provider adapters; the OpenAI one reaches every OpenAI-compatible endpoint |
| `nessy-console` | terminal applications: `Repl.run` |
| `nessy-spring-boot-starter` | Boot applications: one dependency, no code of its own |
| `nessy-spring-boot-autoconfigure` | the beans behind it, and every optional module's auto-configuration |
| `nessy-prompt`, `nessy-prompt-spring`, `nessy-prompt-mustache` | prompts as templates, and two engines |
| `nessy-embedding-api`, `nessy-embedding-openai`, `nessy-embedding-gemini`, `nessy-embedding-bedrock`, `nessy-embedding-voyage` | text into vectors: the `Embedder` seam, and four embedders; the OpenAI one reaches any OpenAI-compatible endpoint |
| `nessy-memory-notebook` | agents that keep notes |
| `nessy-memory-summarizing` | long-lived agents: one rolling summary per agent, replaced as the story grows |
| `nessy-memory-episodic` | the story cut into episodes the model names; each summarised when it closes and shown again when it is relevant, ranked by embedding when the store has one |
| `nessy-planning` | agents that write a plan and work through it |
| `nessy-lease` | background work that must run once across processes |
| `nessy-narration-odyssey` | events as resumable streams, for a browser |
| `nessy-approval-risk` | the risk gate: two thresholds with a person in between |
| `nessy-approval-intent` | the declared-intent claim channel |
| `nessy-approval-policy`, `nessy-approval-policy-opa` | deciding a call by policy; asking Open Policy Agent |
| `nessy-tool-mcp` | agents that call MCP servers |

## Where to go next

<div class="grid cards" markdown>

- **[Getting Started](guides/getting-started.md)**

    The harness door, explained line by line.

- **[Agent as Scope](concepts/agent-as-scope.md)**

    The core model: one locked row per agent, phases as data, and a fold
    that is a pure function.

- **[Durable Computation](concepts/durable-computation.md)**

    What survives a crash: effects as rows, deadlines as columns, and
    answers addressed to a place rather than an object.

- **[Memory](concepts/memory.md)**

    Summaries, the tail and ambient: what a model call is built from.

- **[Leases](concepts/leases.md)**

    Background work that runs once across every instance, and why it is not
    an effect.

- **[Storage](concepts/storage.md)**

    The tables, the codec seam, and how to apply the schema to your own
    database.

</div>
