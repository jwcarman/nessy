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

```java
ExecutionEngine engine = Nessy.builder()
        .model(someProvider)
        .modelName("some-model")
        .systemPrompt("You are a helpful assistant.")
        .tools(MapToolRegistry.of(new ReadFileTool()))
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
mvn verify
```

The default build needs no API key and makes no network calls. Tests that spend
real tokens are tagged `live` and excluded:

```bash
mvn test -Dgroups=live
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
