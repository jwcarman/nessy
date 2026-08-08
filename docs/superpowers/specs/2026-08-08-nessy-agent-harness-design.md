# Nessy — An AI Agent Harness Framework for Java

**Status:** approved design
**Date:** 2026-08-08

## What Nessy is

Nessy is an AI agent harness framework for Java. It supplies the machinery that
turns a model API into an agent — the loop, the tool plumbing, the approval
gate, the session lifecycle — and exposes every pluggable part of that machinery
as a seam.

It is deliberately not a model client. LangChain4j, Spring AI, Embabel, and the
Google ADK all cover "call a model with tools bound." None of them model the
concerns that make a harness feel like a harness: an approver the model cannot
route around, a tool registry with permission metadata, sessions that pause and
resume, and a loop you can drive from a terminal, an HTTP request, or a test.
That gap is Nessy's reason to exist.

Nessy sits alongside two existing projects. `agentic-agency` teaches you to
build this engine by hand and argues against adapter layers; `agency` handles
distributed multi-agent orchestration. Nessy is the packaged engine — the thing
`agentic-agency` teaches, made reusable.

### On the name

Nessy keeps the deliberate misspelling. "Nessie" collides with Project Nessie
(`org.projectnessie`), the Git-like transactional catalog for Apache Iceberg —
an established JVM project that would dominate Maven and GitHub search. "Nessy"
keeps the sea monster and the harness pun while remaining findable.

## Guiding principles

**12-factor agents.** Nessy adopts
[12-factor-agents](https://github.com/humanlayer/12-factor-agents) as its spine,
including the two deviations already established in `agentic-agency`:

- **Factor 7: keep its structure, reject its trigger.** The factor makes two
  claims that are worth separating. Its *mechanism* — human contact as a
  structured request that is persisted, breaks the loop, and resumes on a
  webhook — is right, and Nessy implements it directly as `Awaited.Parked`,
  `ParkToken`, and `ExecutionEngine.resume`. Its *trigger* — the model deciding
  when to reach a human — is right for clarification and unsafe for approval: a
  model that never emits the intent simply never asks, and that is
  indistinguishable from a question that was answered. So approval is a
  harness-side interceptor the model cannot see or route around, even though the
  waiting machinery underneath it is exactly Factor 7's. Nessy ships both
  triggers and keeps them distinct: `Approver` for approval, and an optional
  model-callable ask-the-human tool for clarification.
- **Factor 5 stays partial.** Unifying execution and business state only fully
  lands inside a business domain. Nessy provides the hooks; applications land it.

**Seams must be earned.** Every abstraction ships with at least two genuinely
different implementations, or it ships with a default and no interface. An
interface with one implementation is a guess.

**Capability-aware, not lowest-common-denominator.** Providers declare what they
support. The harness asks rather than assumes. Unsupported features degrade
explicitly, never silently. This is the specific failure that destroyed the
2023-era model abstractions.

**Plain Java core.** `nessy-core` depends on the JDK and Jackson. No Spring, no
reactive types. Spring support is a separate starter module.

**Java 25.** Records, sealed interfaces, pattern matching, and virtual threads
are load-bearing, not stylistic.

## Architecture

### The core is an effectful reducer

The loop performs no I/O. It does not call the model; it *asks* for the model to
be called.

```java
sealed interface Event  { }   // something happened: TokenDelta, ToolResult, ApprovalDecision, UserMessage…
sealed interface Effect { }   // something should happen: CallModel, ExecuteTool, RequestApproval…

record Step(SessionState state, List<Effect> effects) { }

Step reduce(SessionState state, Event event);   // pure, synchronous, total
```

`SessionState` is a record: session id, message history, pending tool calls, the
in-flight assistant message being assembled, a consecutive-error count, and a
status. Nothing else. It is serializable by construction because it is data.

An `ExecutionEngine` owns the impure half: it executes effects, drives the
provider's stream, and feeds every arrival back in as an `Event`. Streaming
tokens are ordinary events, not a special code path.

This buys:

- **Factor 12** — a genuine stateless reducer, no asterisk
- **Streaming** — native from day one, not retrofitted
- **Factor 6** — pause is "stop feeding events"; resume is "load the state, keep
  feeding," whether the gap is 200ms or two days
- **Testing** — a hundred loop steps in a plain unit test with no model, no
  network, no clock

The cost, stated honestly: `reduce` is a switch over event types rather than a
readable narrative `while` loop. That is a real readability tax and the price of
every property above.

### Virtual threads, not async plumbing

Every seam is a plain blocking interface. On Java 25, blocking is cheap, so
`CompletableFuture` and `Flow.Publisher` would be ceremony without benefit. A
human approval that takes an hour is a parked virtual thread: a few hundred
bytes of heap, no pool, no callbacks.

Cancellation improves too — interrupting the thread closes the model stream via
try-with-resources, which is more obvious than unsubscribing a publisher.

Virtual threads do not survive a JVM restart, which is why parking is a
first-class concept rather than an afterthought.

### Parking: unmounting a session from a process

Virtual threads unmount a task from a carrier thread. Nessy unmounts a session
from a process. Anything that can wait returns a sealed result:

```java
sealed interface Awaited<T> {
    record Ready <T>(T value)      implements Awaited<T> { }
    record Parked<T>(ParkToken tok) implements Awaited<T> { }
}
```

An interactive approver blocks on its virtual thread and returns `Ready` — the
simple, readable path. A durable approver returns `Parked`: the engine persists
`SessionState`, unwinds cleanly, and returns control to the caller. Later, on any
machine, a resume feeds the decision back in as an ordinary `Event`.

This is not approval-specific. Tools park identically, so a two-hour build, a
human task, or a webhook all use one mechanism.

**`ParkToken` must be single-use.** Resume delivery is at-least-once in every
real transport — webhooks retry, queues redeliver — so the store rejects a second
resume against a consumed token. Otherwise a duplicate Slack click replays a tool
call.

## The seams

Seven interfaces in v1: `ExecutionEngine`, `ModelProvider`, `Tool`,
`ToolRegistry`, `Approver`, `SessionStore`, `AgentEventListener`. All live in
`nessy-core`. All are consulted by the engine, never by `reduce`.

### ExecutionEngine

```java
sealed interface RunOutcome {
    record Completed(SessionState state)                implements RunOutcome { }
    record Parked   (SessionState state, ParkToken tok) implements RunOutcome { }
}

public interface ExecutionEngine {
    RunOutcome run   (SessionId id, Event input);
    RunOutcome resume(SessionId id, ParkToken tok, Event resolution);
}
```

This draws the sharpest line in the design: **`reduce` is the semantics, the
engine is the execution strategy.** Swapping engines changes durability, retry,
and concurrency characteristics — never agent behavior.

The engine owns effect dispatch, parking and resuming, retries and idempotency,
timeouts, cancellation, and where `SessionState` lives between steps.

| Engine | Module | Parking | Survives |
|---|---|---|---|
| `InProcessEngine` (default) | `nessy-core` | virtual thread blocks | nothing |
| `DurableEngine` | `nessy-core` | `Parked` + `SessionStore` | restart, redeploy, machine loss |
| `TemporalEngine` | `nessy-engine-temporal` | workflow await | whatever Temporal survives |
| `RestateEngine` | `nessy-engine-restate` | journal suspend | whatever Restate survives |

Temporal and Restate both require deterministic workflow code with side effects
quarantined into activities — exactly the shape Nessy already has. `reduce` is
workflow logic; `Effect` execution is activities. The design was not bent to
accommodate them; they fit because pure-reducer-plus-effects is the same
discovery those systems made.

The interface stays at two methods in v1. `cancel`, `status`, and `list` will all
feel obvious to add and are all guesses until a front-end needs them.

### ModelProvider

```java
public interface ModelProvider {
    ModelStream stream(ModelRequest request);   // AutoCloseable Iterable<ModelEvent>
    Set<Capability> capabilities();             // THINKING, PROMPT_CACHING, PARALLEL_TOOL_CALLS, IMAGE_INPUT…
}
```

`ModelStream` as an `AutoCloseable Iterable` maps directly onto what the Anthropic
and OpenAI Java SDKs already hand you, so provider modules stay thin.

`capabilities()` is the anti-rot mechanism. A `ModelRequest` may ask for prompt
caching; a provider that cannot do it says so rather than silently dropping it.

Each provider module exposes a builder factory hiding its SDK's mechanics — auth,
retries, request assembly, block marshalling — without flattening what it can do.

### Tool and ToolRegistry

```java
public interface Tool {
    ToolSpec spec();                                    // name, description, JSON schema
    Awaited<ToolResult> execute(ToolCall call, ToolContext ctx);
}

public interface ToolRegistry {
    Optional<Tool> find(String name);
    List<ToolSpec> specs();
}
```

Schemas derive from records, never hand-written JSON (factor 4):
`Tools.of("read_file", "Read a file", ReadFile.class, (args, ctx) -> …)`.

### Approver

```java
public interface Approver {
    Awaited<Decision> approve(ApprovalRequest request);
}
```

The interceptor. Invisible to the model. Permission modes are not core config —
they are `Approver` implementations. The TUI ships three: approve-everything,
ask-every-time, and deny-everything.

### SessionStore

```java
public interface SessionStore {
    Optional<SessionState> load(SessionId id);
    void save(SessionState state);
    boolean consumeToken(ParkToken tok);   // false if already consumed
}
```

In-memory by default. Because state is already a serializable record, durable
resume is a store implementation rather than an engine change.

### AgentEventListener

```java
public interface AgentEventListener {
    void onEvent(SessionId id, Event event, SessionState state);
}
```

Every front-end's window into the loop: the TUI renders from it, an HTTP service
maps it to SSE, tests assert on it.

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `nessy-core` | reducer, seams, events, effects, `InProcessEngine`, `DurableEngine` | JDK 25, Jackson |
| `nessy-model-anthropic` | native Anthropic SDK provider | core |
| `nessy-model-openai` | OpenAI-wire provider, configurable base URL | core |
| `nessy-testing` | `ScriptedModelProvider`, state assertions | core |
| `nessy-spring-boot-starter` | auto-config, `@Tool` bean scanning, properties | core |
| `nessy-tui` | the demo agent | core, providers |

Two provider modules cover the field. OpenRouter has no official Java SDK — its
SDK is TypeScript-only — and its API is OpenAI Chat Completions with a router in
front. So `nessy-model-openai` with a configurable base URL reaches OpenAI,
OpenRouter, Groq, Together, vLLM, Ollama, and LM Studio.

Making the OpenAI wire format *canonical* was considered and rejected: it would
permanently shape the core like one vendor's 2023 chat API, flattening thinking
blocks, `cache_control`, content-block tool results, and server-side tools.
Anthropic-native plus OpenAI-shaped are maximally different, which is what forces
the capability-negotiation question in week one instead of year two.

## Data flow

One turn — "read pom.xml and summarize it":

1. Front-end calls `engine.run(id, new UserMessage(...))`
2. `reduce` appends the message → effects `[CallModel]`
3. Engine opens `ModelProvider.stream(...)` and iterates on a virtual thread.
   Each `TokenDelta` goes through `reduce` and out to listeners — the TUI paints
   live. The stream ends carrying a `tool_use`.
4. `reduce` → effects `[RequestApproval(read_file)]`. Engine calls `Approver`,
   which blocks on a keypress and returns `Ready(ALLOW)`.
5. `reduce` → effects `[ExecuteTool]`. Engine runs it, feeds back `ToolResult`.
6. `reduce` appends the result → effects `[CallModel]`. Loop.
7. Final text, no tool calls → effects `[]`, status `COMPLETE`. `run` returns
   `Completed(state)`.

Every arrow is either a pure function or a blocking call on a virtual thread.

## Error handling

Errors split in two. Getting this line wrong is how harnesses end up showing
stack traces to the model.

**Model-visible** — a tool throws, a file is missing, a command exits nonzero.
Becomes a `ToolResult` with `isError`, flows through `reduce`, lands in context,
and the model recovers. This is factor 9. Bounded by a consecutive-error count in
`SessionState`; past the limit the session fails rather than burning tokens.

**Infrastructure** — 429s, 5xx, socket resets. The engine's business, never the
reducer's: retry with backoff, and the model never learns it happened. Fatal
errors (auth, malformed request) propagate out of `run` as exceptions.

## Testing

- `reduce` tests need no mocks — a pure function over records.
- `ScriptedModelProvider` in `nessy-testing` replays canned turns including tool
  calls and failures, so the whole loop is assertable with no key and no network.
- Live tests are tagged `live` and excluded from the default build. `mvn verify`
  stays green with no `ANTHROPIC_API_KEY`, matching the `agentic-agency`
  convention.

## The TUI demo

The TUI knows exactly two things: it implements `AgentEventListener` to render,
and `Approver` to prompt. It calls `run` on a virtual thread; Esc interrupts it.
It has no knowledge of the reducer, effects, or the engine.

That constraint is the proof of the framework's central claim — replace the TUI
with an HTTP controller and the engine is untouched.

A Claude Code-grade TUI is its own project: JLine provides raw mode and line
editing, but diff rendering, streaming redraw, and the status line are hand-built.
It is scoped as a separate deliverable so it cannot hold the framework hostage.

## v1 scope

**In:**

- the reducer, `SessionState`, `Event`, `Effect`, `Awaited`, `ParkToken`
- `ExecutionEngine` with `InProcessEngine` (default: virtual threads, in-memory
  store) and `DurableEngine`
- `ModelProvider` with Anthropic and OpenAI-wire implementations
- `Tool`, `ToolRegistry`, `Approver`, `SessionStore`, `AgentEventListener`
- `nessy-testing`
- `nessy-spring-boot-starter`
- the TUI demo

**Deferred, shipping as defaults rather than interfaces:**

| Deferred | v1 default |
|---|---|
| `ContextCompactor` (factors 3, 9) | no compaction; fail loudly on overflow |
| `HookRegistry` | none; approval is the only interception point |
| `SubagentDispatcher` (factor 10) | none |
| `PromptSource` (factor 2) | prompts are strings you own |
| `TemporalEngine`, `RestateEngine` | not built |

Compaction and subagents matter enormously — they are much of what makes a
harness feel good. They are deferred because both are far easier to design once
real sessions run through the reducer, and both slot into boundaries the reducer
already has. Shipping them as guessed-at interfaces is the exact failure mode
this design is organized to avoid.

## Risks

**The engine seam is the largest surface and the most likely fiction.**
`InProcessEngine` and `DurableEngine` are genuinely different — one parks a
thread, one parks a session — so together they should expose whether `run`/
`resume` is the right shape. `TemporalEngine` is the real test. If writing it
forces an interface change, that is the seam earning its keep.

**`ModelProvider` is the historically doomed abstraction.** The mitigation is
`capabilities()` plus the deliberate choice of two maximally different providers.
If the capability set starts growing a flag per provider quirk, that is the
signal the seam is rotting.

**Readability tax.** The reducer is less immediately legible than a blocking
`while` loop. Accepted, and worth documenting prominently for newcomers.

## Open questions for implementation

None blocking. The first plan should start with `nessy-core`: `SessionState`,
`Event`, `Effect`, `reduce`, and `InProcessEngine`, driven entirely by
`ScriptedModelProvider` — no real network until the loop is provably correct.
