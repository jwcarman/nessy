# Nessy

An AI agent harness framework for Java.

Nessy supplies the machinery that turns a model API into an agent — the loop, the
tool plumbing, the approval gate, the session lifecycle — and exposes every
pluggable part of it as a seam.

## Status

Early. `nessy-core` and `nessy-testing` are implemented: a complete streaming,
tool-calling loop with an approval gate, tested end to end against a scripted
model. Provider modules, durable execution, the Spring Boot starter, and the TUI
are not built yet.

## Requirements

JDK 25 and Maven.

## The smallest agent

Provider modules are not built yet, so this uses `ScriptedModelProvider` from
`nessy-testing` — no key, no network, and it compiles against what ships today.
Swap it for a real `ModelProvider` when one lands.

```java
record Add(int left, int right) {}

Tool<Add> add = new Tool<>() {
    public String name() { return "add"; }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }
    public boolean requiresApproval() { return false; }

    public Awaited<ToolResult> execute(Add input, ToolContext context) {
        return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
};

ModelProvider provider = ScriptedModelProvider.builder()
        .text("The answer is 4.")
        .endTurn()
        .build();

ExecutionEngine engine = Nessy.builder()
        .model(provider)
        .modelName("some-model")
        .systemPrompt("You are a helpful assistant.")
        .tools(MapToolRegistry.of(add))
        .approver(new ApproveEverything())
        .build();

RunOutcome outcome = engine.run(SessionId.random(), new Event.UserSaid("what is 2+2?"));
```

## How it works

The core is an **effectful reducer**. `reduce(SessionState, Event)` is pure,
synchronous, and does no I/O — it returns the next state plus a list of `Effect`s
describing what should happen. An `ExecutionEngine` performs those effects and
feeds every result back in as an `Event`.

Streaming tokens are ordinary events, so the loop streams natively rather than by
retrofit. `SessionState` is a plain serializable record, so pausing is "stop
feeding events" and resuming is "load the state and keep feeding" — whether the
gap is 200 milliseconds or two days.

Every seam is a plain blocking interface. On virtual threads that is cheaper and
far more readable than a callback protocol.

## The seams

| Seam | What you plug in |
|---|---|
| `ExecutionEngine` | how the loop runs: in-process, durable, or on a workflow engine |
| `ModelProvider` | where tokens come from, with explicit capability negotiation |
| `Tool` / `ToolRegistry` | what the agent can do; schemas derive from records |
| `Approver` | the safety gate the model cannot route around |
| `SessionStore` | where a session lives between steps |
| `AgentEventListener` | how a front-end sees inside the loop |

## Building

```bash
./mvnw verify
```

The default build needs no API key and makes no network calls. Tests that spend
real tokens are tagged `live` and are excluded by default. To run them, clear the
exclusion:

```bash
./mvnw test -Dnessy.excludedGroups=
```

## Design

See `docs/superpowers/specs/2026-08-08-nessy-agent-harness-design.md`.

## License

Nessy is licensed under the [Apache License 2.0](LICENSE).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for how to get
started, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for the standards we hold
this project to. Please report security issues per [SECURITY.md](SECURITY.md)
rather than filing a public issue.
