# Nessy — An AI Agent Harness Framework for Java (v2)

**Status:** approved design, revision 2
**Date:** 2026-08-09
**Supersedes:** [2026-08-08-nessy-agent-harness-design.md](2026-08-08-nessy-agent-harness-design.md)

v1 defined the engine: a pure reducer, effectful execution, and seven seams. That
architecture is implemented, reviewed, and green (93 tests, no network, no key), and
it carries forward unchanged in substance. v2 defines the *product* around that
engine: who it serves, how the codebase is zoned so its audiences can navigate it,
how it is named, how it emits events, how it is observed, and what must be finished
before the vocabulary freezes at 1.0.

---

## 1. What Nessy is

Nessy is an AI agent harness framework for Java. It supplies the machinery that
turns a model API into an agent — the loop, the tool plumbing, the approval gate,
the session lifecycle — and exposes every pluggable part of that machinery as a
deliberate seam.

It is not a model client. LangChain4j, Spring AI, Embabel, and the Google ADK cover
"call a model with tools bound." Nessy models the concerns that make a harness a
harness: an approval gate the model cannot route around, sessions that pause and
resume across processes, streaming as a first-class citizen, and a loop you can
drive from a terminal, an HTTP request, or a test — with world-class observability
built in, not bolted on.

**The default posture is a fully in-memory, single-node system that executes
everything itself with zero required configuration beyond a model provider.** Every
piece of that default can then be upgraded — a durable store, a distributed engine,
a policy engine, tracing backends — by swapping implementations, never by
restructuring the application.

## 2. Who Nessy is for

Three concentric audiences, in priority order:

1. **Application developers** — Java developers, predominantly Spring shops,
   embedding an agent in a real system. They write tools, wire a builder, render
   events. They judge Nessy by their first thirty minutes. They are the primary
   audience, and every ergonomic decision is made for them.
2. **Front-end and integration builders** — people putting a TUI, an SSE endpoint,
   or a chat surface on the engine. They implement `Approver` and subscribe to the
   event hub.
3. **Infrastructure extenders** — people writing a DynamoDB session store, a new
   model provider, a Temporal engine. Small in number, strategic in impact. They
   implement the SPI.

**The zone rule** that sorts every type: *if writing an agent requires it, it is
API; if hosting or backing agents requires it, it is SPI.* By this rule `Tool` is
API — writing tools is everyday application development, the way implementing a
`Servlet` was — while `SessionStore` is SPI, the way implementing a JDBC `Driver`
is vendor work.

## 3. Guiding principles

**12-factor agents** ([12-factor-agents](https://github.com/humanlayer/12-factor-agents))
remains the spine, with the two deviations established in v1 carried forward
verbatim:

- **Factor 7: keep its structure, reject its trigger.** The factor's mechanism —
  human contact as a persisted request that breaks the loop and resumes on a
  webhook — is right, and Nessy implements it as `Awaited.Parked`, `ParkToken`,
  and `ExecutionEngine.resume`. Its trigger — the model deciding when to reach a
  human — is right for clarification and unsafe for approval: a model that never
  emits the intent simply never asks, indistinguishable from a question that was
  answered. Approval is a harness-side interceptor the model cannot see, name, or
  route around.
- **Factor 5 stays partial.** Unifying execution and business state fully lands
  only inside a business domain. Nessy provides the hooks; applications land it.

**Open where implementations diverge; closed where semantics live.** The seams are
open because reasonable implementations genuinely differ. The reducer and its
sealed vocabulary are closed because they *are* Nessy — swapping the reducer yields
a different framework, not a configured one.

**Abstract where implementations diverge; adopt where the ecosystem has
converged.** Model APIs diverge — capability-aware seam, ours. JSON handling
converged — Jackson, adopted. Instrumentation converged — Micrometer Observation,
adopted. The 2023-era frameworks died abstracting the divergent thing; the
not-invented-here failure mode is re-abstracting the converged thing.

**Seams must be earned — by one of exactly two justifications:**

1. **Extension seams**: at least two genuinely different production
   implementations. They live in `spi`, are advertised for implementation, and
   evolve only by `default` methods.
2. **Test seams**: a demonstrable testing burden the interface removes (real time,
   real randomness, heavyweight construction). One production implementation, no
   outside implementors intended. They live in `internal` or are package-private,
   are deliberately *not* advertised, and may change freely. Promotion from
   internal to `spi` is a deliberate act taken on evidence of external demand —
   never the reverse, because interfaces are easy to publish and impossible to
   unpublish.

Anything not earning its interface either way ships as a concrete default with no
interface. The guard against seam-itis is a preference hierarchy applied in order:
*purity first* (the best test seam is no seam — the reducer proves it), *cheap real
objects second* (`InMemorySessionStore` needs no fake because the real thing is
zero-ceremony), *a dedicated internal test seam third*.

**No magic in core.** Explicit builder wiring only. Every object is traceable from
construction to use by cmd-click: no reflection-driven discovery, no classpath
scanning, no annotation processing in `nessy-core`. Discovery belongs to outer
layers — the Spring starter discovers via DI; a CLI may use `ServiceLoader` to
*list* providers. Core never does.

**Dogfood the SPI.** Every upgrade Nessy itself ships goes through the same public
seams with zero privileged hooks. Retry ships as a `ModelProvider` decorator.
Tracing of a specific store ships as a `SessionStore` decorator. If we need a
private hook to build a feature, the seam is wrong and gets fixed first.

**Ship the double.** For every extension seam, `nessy-testing` provides a
first-class test double: `ScriptedModelProvider` for providers, the in-memory
store for stores, `Approver.allowAll()`/`denyAll(...)` for approvers, a recording
subscriber for the hub, Micrometer's own `TestObservationRegistry` for
observations. The falsifiable promise, stated in the README: **you will never need
a mocking library to test a Nessy agent.** The current suite honors it — the only
test dependencies are JUnit and AssertJ. A seam that cannot be given a first-class
double is a design smell in the seam.

**Plain Java core, Java 25.** `nessy-core` depends on the JDK, Jackson, victools,
and `micrometer-observation` (§11 records why that fourth dependency is
principled). Records, sealed interfaces, pattern matching, and virtual threads are
load-bearing. Every seam is a plain blocking interface — no `CompletableFuture`,
no `Flow.Publisher`, no reactive types anywhere.

**Coordinates.** groupId `org.jwcarman.nessy`, base package `org.jwcarman.nessy`,
matching the sibling `substrate` project's convention. Apache License 2.0.

## 4. The shape of the codebase

### 4.1 Zones

Three zones, structurally identifiable by package, in the idiom Java developers
already know (`org.slf4j.spi`, JDBC drivers):

| Zone | Package | Audience | Evolution contract |
|---|---|---|---|
| **Front door** | `org.jwcarman.nessy` | everyone's first five minutes | additive |
| **API** | `org.jwcarman.nessy.api…` | application developers: call it, and implement the everyday interfaces (`Tool`, `Approver`) | additive; interfaces users implement grow only by `default` methods |
| **SPI** | `org.jwcarman.nessy.spi…` | infrastructure extenders: "if you want to extend this thing, here is what you implement" | frozen per major; grows only by `default` methods |
| **Internal** | `org.jwcarman.nessy.internal` | nobody outside this repo | none — changes freely |

### 4.2 Package map

```
org.jwcarman.nessy               Nessy, Agent, AgentBuilder, Conversation, Reply
org.jwcarman.nessy.api           Message, Role, ContentBlock (sealed: TextBlock, ThinkingBlock,
                                 RedactedThinkingBlock, ImageBlock, ToolUseBlock, ToolResultBlock),
                                 ToolCall, ToolResult, Usage, StopReason,
                                 SessionId, SessionState, SessionStatus,
                                 Event (sealed), Decision (sealed), Awaited (sealed), ParkToken,
                                 RunOutcome (sealed), TerminationPolicy
org.jwcarman.nessy.api.tool      Tool, ToolContext, ToolRegistry, ToolSpec
org.jwcarman.nessy.api.approval  Approver, ApprovalRequest        [Policy lands here, §10.5]
org.jwcarman.nessy.api.event     EventEmitter, EventHub, Subscription, SessionEvent, ToolProgress
org.jwcarman.nessy.spi           ExecutionEngine, Reducer, Effect (sealed), Step, InProcessEngine
org.jwcarman.nessy.spi.model     ModelProvider, ModelRequest, ModelEvent (sealed), ModelStream,
                                 Capability, ModelSettings
org.jwcarman.nessy.spi.session   SessionStore
org.jwcarman.nessy.internal      ToolInvoker, Schemas, observation conventions, engine machinery
```

Placement decisions worth their reasoning:

- **`Reducer`, `Effect`, `Step` are the SPI's centerpiece.** Users never touch
  them; engine implementors *must* — they are the semantics an engine executes.
  Neither user API nor internal: precisely SPI.
- **`Awaited` and `ParkToken` are API**, because `Tool.execute` returns
  `Awaited<ToolResult>` and tools are everyday code; `RunOutcome.Parked` hands
  users a `ParkToken`. The SPI references them inward (`spi → api` is the allowed
  direction).
- **`TerminationPolicy` is API**: configuring budgets is everyday agent-writing,
  not hosting.
- **Default implementations live beside their seams**, reachable through static
  factories on the seam interface itself (§5). Only non-contractual machinery goes
  `internal`.

### 4.3 Dependency direction

```
implementations (nessy-model-*, nessy-store-*, nessy-engine-*)
        │ depend on
        ▼
      spi ──► api ◄── front door
        ▲
        │ implemented by
     internal (default implementations, machinery)
```

`api` depends on nothing but the JDK, Jackson types, and (for `Tool.spec()`'s
schema derivation) internal machinery within the same artifact. Nothing outside
this repository may depend on `internal`, and JPMS enforces it:

### 4.4 JPMS

`nessy-core` ships a `module-info.java` exporting the front door, `api…`, and
`spi…` packages and **not** exporting `internal` — the strongest structural
statement Java can make that the SPI is the extension surface and internals are
nobody's contract. Contingency: if victools' or Micrometer's automatic-module
metadata makes a full module graph impractical, the module descriptor is deferred
to a pre-1.0 task and the zoning stands on package convention alone; the decision
and evidence get recorded in the CHANGELOG either way.

### 4.5 Module ladder

Artifacts follow `nessy-<family>-<implementation>` so an upgrader can guess the
name: `nessy-model-anthropic`, `nessy-model-openai`, `nessy-store-jdbc`,
`nessy-engine-temporal`. `nessy-core` is complete for the single-node in-memory
posture; `nessy-testing` ships the doubles; `nessy-bom` pins versions;
`nessy-spring-boot-starter` wires the Spring world. All wiring converges on the
one builder.

## 5. Naming

The conventions, uniformly applied:

- **No `I`-prefixes, no `-Impl` suffixes, ever.**
- **Defaults are named by strategy, not by data structure**: `InMemorySessionStore`,
  `InProcessEngine`, `ScriptedModelProvider`. (`MapToolRegistry` violated this and
  is renamed away — see ledger.)
- **The seam interface is the front door to its own defaults** via static
  factories: `ToolRegistry.of(tools…)`, `Approver.allowAll()`,
  `Approver.denyAll(reason)`, `SessionStore.inMemory()`, `EventHub.synchronous()`,
  `TerminationPolicy.maxTurns(n)`. One obvious place to look; core default classes
  may be package-private behind them. External modules ship public classes
  (`JdbcSessionStore`) — the asymmetry is deliberate: core defaults are reachable
  without knowing a class name, external implementations are named products.
- **Events are facts, named in the past tense or as observations** (`ToolFinished`,
  `UserSaid`, `TextDelta`); **effects are orders, named imperatively**
  (`CallModel`, `ExecuteTool`); **statuses are states** (`AWAITING_MODEL`).
- **One front door.** `Nessy.agent()` is the only entry point. There is no second
  `Nessy.builder()`; the engine-level API is reached *through* the built `Agent`.

### Rename ledger (v1 → v2)

| v1 | v2 | Why |
|---|---|---|
| `org.jwcarman.nessy.core.*` | dissolved into `api` / `spi` | "core" named nothing; zones name audiences |
| `Nessy` in `.engine` | `Nessy` at root | the front door belongs at the front |
| `Builder.model(ModelProvider)` + `.modelName(String)` | `.provider(ModelProvider)` + `.model(String)` | two colliding names for two different concepts |
| `MapToolRegistry` | package-private behind `ToolRegistry.of(…)` | named after its data structure; now no name to learn at all |
| `ApproveEverything` / `DenyEverything` | package-private behind `Approver.allowAll()` / `denyAll(reason)` | factory idiom; call sites read as policy |
| `AgentConfig` | `ModelSettings` in `spi.model` | it is the static half of a `ModelRequest`; name it honestly |
| `AgentEventListener` | deleted — replaced by the event hub (§9) | fixed at build time, single-emitter, throw-fragile |
| `RecordingEventListener` | `RecordingSubscriber` (nessy-testing) | follows the hub |
| `ToolInvoker`, `Schemas` | `internal` | wildcard-capture machinery, not anyone's contract |
| `Reducer(int maxConsecutiveErrors)` | `Reducer(TerminationPolicy)` | the ceiling generalizes (§10.4) |

All renames are free now — zero releases, zero users — and breaking after 1.0.
That asymmetry is why convergence precedes the provider plans.

## 6. Architecture: the effectful reducer (carried from v1)

Unchanged in substance; restated for self-containment, with v2's state additions.

The loop performs no I/O. `reduce(SessionState, Event) → Step` is pure,
synchronous, and total; `Step` is the next state plus a list of `Effect`s. An
`ExecutionEngine` performs effects and feeds every result back as an `Event`.
Streaming tokens are ordinary events — that is why the loop streams natively.
Every seam is a plain blocking interface on virtual threads; an interactive
approval parks a thread, a durable one parks a *session* via `Awaited.Parked` and
a single-use `ParkToken`.

`SessionState` is a plain serializable record and the whole of the agent's memory:

```
SessionState
 ├── id                  SessionId
 ├── messages            settled conversation
 ├── pendingBlocks       assistant message being streamed in
 ├── pendingCalls        tool calls not yet resolved
 ├── pendingResults      results awaiting batch flush
 ├── consecutiveErrors   errored results in a row (any success resets)
 ├── turns               completed model turns                          [v2]
 ├── usage               cumulative token usage                         [v2]
 ├── failureReason       why status == FAILED, else null                [v2]
 └── status              IDLE | AWAITING_MODEL | AWAITING_APPROVAL |
                         EXECUTING_TOOL | COMPLETE | FAILED
```

Invariants the reducer maintains (each earned by a shipped fix and pinned by a
test): an assistant message carrying N `tool_use` blocks is always answered by a
user message carrying exactly N `tool_result` blocks — on the happy path, on the
error-ceiling path, and on the token-ceiling path; a denial is an errored result,
not a control branch; results batch into one message; new user input resets the
error streak; `MAX_TOKENS` fails loudly with pending calls abandoned-and-answered.

**Resume semantics for half-settled state** (unresolved in v1, resolved here):
engines persist progress mid-turn, so a store may hold state with non-empty
`pendingBlocks`/`pendingCalls`. The contract: `ExecutionEngine.run` on a session
whose status is not `IDLE`/`COMPLETE`/`FAILED` and which is not being resumed via
a `ParkToken` must refuse with an exception naming the status — a crashed turn is
completed by `resume`, inspected, or abandoned deliberately; it is never silently
overwritten by a fresh `run`. `DurableEngine` (a later plan) builds recovery on
this rule.

## 7. The grammar

The sealed hierarchies — `ContentBlock`, `Event`, `Effect`, `Decision`, `Awaited`,
`RunOutcome`, `ModelEvent`, `StopReason` — are Nessy's grammar. Sealing is a
promise with teeth in both directions:

- **We can't silently drop a case**: core switches are exhaustive with **no
  `default` arm**, so an unhandled variant is a compile error in our own build.
- **Adding a variant is breaking**: source-incompatible for every exhaustive
  switch downstream, `MatchException` at runtime for stale binaries. Therefore
  the grammar **freezes at 1.0**; additions are major-version events.

Etiquette split: core code MUST omit `default`; extender code is ADVISED to
include a `default` arm for forward tolerance across majors. Documented on every
sealed type.

**Pre-1.0 grammar completion** — variants known to be needed, added now while
free:

| Addition | Forces it |
|---|---|
| `ContentBlock.ThinkingBlock(text, signature)` | Anthropic extended thinking; the signature must round-trip for replay |
| `ContentBlock.RedactedThinkingBlock(data)` | Anthropic redacted thinking round-trip |
| `ContentBlock.ImageBlock(mediaType, base64Data)` | `Capability.IMAGE_INPUT` is already declared; the grammar must be able to say it |
| `ModelEvent.ThinkingChunk(text)` + `Event.ThinkingDelta(text)` | streamed thinking accumulates like text; the reducer merges deltas into a trailing `ThinkingBlock`. Signature delivery (Anthropic requires the signature to round-trip) is finalized in Plan 2 against the real wire — still pre-freeze |
| `Usage(inputTokens, outputTokens)` + `ModelEvent.TurnEnded(reason, usage)` | cost-budget termination and `gen_ai.usage.*` span attributes |
| `Event.UserSaid` canonicalizes to `List<ContentBlock>` with `UserSaid.of(String)` | multimodal input needs an entry path; one variant, not two |

`StopReason` gets a final audit against the real Anthropic and OpenAI wire formats
during the provider plans — the last gate before freeze.

## 8. The API surface

### 8.1 The front door

The first five minutes decide whether people love a framework. The event-level API
(`engine.run(id, event)` → pattern-match `RunOutcome` → spelunk content blocks) is
architecturally honest and ergonomically hostile as a first encounter. The facade
fixes that — sugar over the engine, zero new semantics:

```java
Agent agent = Nessy.agent()
    .provider(anthropic)                   // where tokens come from   (required)
    .model("claude-sonnet-4-5")            // which model              (required)
    .systemPrompt("You are a helpful assistant.")
    .tools(new ReadFileTool(), new GrepTool())
    .approver(Approver.denyAll("read-only demo"))
    .build();

Conversation chat = agent.converse();
Reply reply = chat.send("What does the build file declare?");
reply.text();                              // the assistant's prose, extracted
```

- `Agent` — a configured, reusable handle; `converse()` opens a fresh session,
  `resume(SessionId)` reopens a stored one, `engine()` and `events()` expose the
  full machinery. The escape hatch is one method away, so the facade never traps.
- `Conversation` — one session: `send(String) → Reply`, `sessionId()`.
- `Reply` — wraps the final `SessionState`: `text()` (concatenated text blocks of
  the final assistant message), `failed()`, `state()`.
- Every builder default works out of the box: in-memory store, in-process engine,
  allow-all approver (with the safety note that real tools deserve a real
  approver), synchronous hub, no-op observations, default termination (§10.4).
  The smallest useful agent is a provider and a model name.

`Agent` and friends are final classes, not seams: users who want a fake agent in
tests use a real `Agent` over `ScriptedModelProvider` — the classicist testing
stance, and the reason the no-mocking promise holds.

### 8.2 Tools

Unchanged in substance: `Tool<T>` with record-derived schemas, `requiresApproval()`
deliberately abstract so a new tool fails closed at compile time, `describe(T)`
for honest approval prompts. `ToolRegistry.of(tools…)` is the everyday
construction. `ToolContext` gains `events()` (§9) so long-running tools can report
progress.

### 8.3 Approval

`Approver` remains the blocking, harness-side interceptor. It is *not* an event
subscriber and never will be: approval is synchronous request/response with an
answer the loop waits on; the hub is one-way exhaust. Keeping those channels
separate is what keeps "the model cannot route around the gate" provable.

## 9. The event hub

Replaces per-object listeners. Anything may emit; subscribers declare interest by
type; no component maintains listener lists.

```java
public interface EventEmitter {
    void emit(Object event);
}

public interface EventHub extends EventEmitter {
    <E> Subscription subscribe(Class<E> type, Consumer<E> subscriber);
    static EventHub synchronous() { … }
}

public interface Subscription extends AutoCloseable {
    @Override void close();          // unsubscribe; idempotent
}
```

Three commitments, each load-bearing:

1. **Synchronous, in-order, same-thread by default.** Emission dispatches
   subscribers on the emitting thread, in subscription order, before returning —
   the guarantee live streaming and deterministic tests already rely on.
   Asynchronous delivery is an upgrade (a decorating hub or async subscriber
   wrapper), never the default; backpressure and slow consumers are explicitly the
   decorator's problem.
2. **Exhaust, never intake.** One-way, no return values, no vetoes. Input reaches
   the reducer only through `ExecutionEngine.run`; control lives only in blocking
   seams. The hub catches subscriber exceptions so no observer can alter or abort
   execution — the contract v1 documented but could not enforce. (Failures during
   failure reporting are dropped, not recursed.)
3. **Open vocabulary, typed subscription, no magic.** Hub events are plain
   records; dispatch is by class assignability; extension modules publish their
   own event types (`SessionPersisted`, `CallRetried`) without asking us. The
   *reducer's* sealed `Event` stays closed — the hub re-publishes loop activity
   wrapped in an envelope, it never feeds the loop.

Shipped event types: `SessionEvent(SessionId, Event, SessionState)` — every
reduced loop event, the migration of the old listener signature — and
`ToolProgress(SessionId, String toolCallId, String message)`, emitted by tools via
`ToolContext.events()` so a TUI can finally render progress from inside a
long-running tool. Infrastructure events (engine lifecycle, store activity, retry
attempts) arrive with the modules that emit them; the open hierarchy is what makes
that possible without grammar changes.

Wiring: the builder owns a `synchronous()` hub by default, `.events(EventHub)`
overrides it, `agent.events()` subscribes and unsubscribes at any time — including
mid-session, which the old design could not do. `nessy-testing` ships
`RecordingSubscriber`.

## 10. The SPI surface

### 10.1 ExecutionEngine

Unchanged: `run(SessionId, Event)` / `resume(SessionId, ParkToken, Event)` →
`RunOutcome`, two methods on purpose. The engine owns effect dispatch, parking,
persistence timing (including the mid-turn progress publication shipped in v1's
final fix wave), and instrumentation of the phases only it can see (§11).
`InProcessEngine` remains the default: virtual threads, blocks, never parks,
refuses `Awaited.Parked` loudly.

### 10.2 The model family

`ModelProvider` (`stream(ModelRequest)` returning an `AutoCloseable Iterable`,
plus `capabilities()`), `ModelRequest.unsupportedBy` as the anti-rot mechanism,
`ModelSettings` as the static half of a request (model, system prompt, max
tokens, requested capabilities). Wire-format details from v1 stand: providers
normalize the `Optional`-as-nullable-union schema shape for strict function
calling; providers with capability gaps degrade explicitly, never silently.

### 10.3 SessionStore

Unchanged: `load` / `save` / `consumeToken`, with single-use token consumption as
the at-least-once-delivery defense. In-memory default via `SessionStore.inMemory()`;
last-write-wins and non-evicting-token semantics documented on it.

### 10.4 TerminationPolicy (new, API-zone, consulted by the reducer)

v1's hard-coded consecutive-error ceiling was one termination rule wearing the
whole trenchcoat, and the unbounded-round-trip gap was flagged by the final review
and independently by an outside architecture review. Generalized:

```java
public interface TerminationPolicy {
    /** A reason to halt, or empty to continue. Pure; consulted by the reducer. */
    Optional<String> shouldHalt(SessionState state);

    static TerminationPolicy maxTurns(int max) { … }
    static TerminationPolicy maxConsecutiveErrors(int max) { … }
    static TerminationPolicy anyOf(TerminationPolicy... policies) { … }
    static TerminationPolicy never() { … }
}
```

The reducer consults the policy wherever it would emit `CallModel`; a halt settles
what is pending (answering every outstanding `tool_use`, per the transcript
invariant), sets `FAILED` with the policy's reason in `failureReason`, and emits
nothing. `Reducer(TerminationPolicy)` replaces `Reducer(int)`. Builder:
`.termination(policy)`, default
`anyOf(maxConsecutiveErrors(3), maxTurns(100))` — a wallet-guarding ceiling a
user can raise deliberately rather than discover involuntarily. Cost-budget
policies become possible the moment `usage` accumulates in state (§7), and ship
when a real provider reports real usage. The seam is earned on day one: two
genuinely different rules, previously one hard-coded.

### 10.5 Policy — specified now, built in its own plan

Contextual authorization layered over the static boolean:

```java
public interface Policy {
    PolicyDecision evaluate(ToolCall call, SessionState state);
    // sealed PolicyDecision: Allow, Deny(reason), RequireApproval
}
```

`Tool.requiresApproval()` survives as the fail-closed floor — the compile-time
"answer the question or it doesn't build" property is too valuable to trade — and
the default policy is derived from it. A contextual policy can then distinguish
`read_file("./README.md")` from `read_file("~/.ssh/id_rsa")`. `RequireApproval`
routes to the `Approver`; the deliberately omitted `MODIFY` verb stays omitted —
silently rewriting model-proposed arguments is an attribution nightmare. Lands
with its second real implementation (a path/allowlist policy), before 1.0.

### 10.6 ContextBuilder — deferred, by rule

Context-as-projection-of-state is right, and today's engine performs the identity
projection. The interface arrives in the same plan as the compactor — the second
genuinely different implementation — not before. Recorded so nobody mistakes the
deferral for an oversight.

## 11. Observability

Two channels with a clean division of labor, and no third:

| Concern | Channel |
|---|---|
| Narrative: UI rendering, audit, replay, progress, **counters** | event hub |
| Structure: spans, traces, timers, context propagation | Micrometer Observation |

**Micrometer Observation is adopted directly** — no bespoke facade. The reasoning,
recorded because it amends the plain-core dependency rule: `micrometer-observation`
is a standalone, near-zero-dependency artifact designed precisely for libraries to
embed; it no-ops when unconfigured; Spring Framework itself depends on it at
compile scope; one instrumentation point fans out to metrics *and* traces through
handlers we will never have to write or maintain; and `TestObservationRegistry`
gives span assertions in tests without an OTel SDK — the ship-the-double promise
fulfilled by an artifact that already exists. Building our own facade here would
be re-abstracting the converged thing.

The engine instruments the phases only it can see, and exploits Micrometer's
name/contextual-name split: the **observation name** is Nessy's stable,
low-cardinality metric identity, while the **contextual name** follows the
OpenTelemetry GenAI *agent* span conventions (span names
`invoke_agent` / `chat {model}` / `execute_tool {tool}`), so metrics stay stable
even as span conventions evolve:

| Observation (metric name) | Span (contextual name) | Key attributes |
|---|---|---|
| `nessy.run` | `invoke_agent` | `gen_ai.operation.name=invoke_agent`, `gen_ai.conversation.id` (session) |
| `nessy.turn` | `nessy.turn` | ours — semconv has no turn concept |
| `nessy.model.call` | `chat {model}` | `gen_ai.operation.name=chat`, `gen_ai.request.model`, `gen_ai.usage.*` |
| `nessy.tool.call` | `execute_tool {tool}` | `gen_ai.operation.name=execute_tool`, `gen_ai.tool.name`, `gen_ai.tool.call.id` |
| `nessy.approval.wait` | `nessy.approval.wait` | `gen_ai.tool.name` — ours; semconv has no human-approval concept |

The GenAI agent conventions are explicitly pre-1.0 (split into their own
`semantic-conventions-genai` repository mid-2026, still moving). The hedge is
already in the design: all names and attributes ship through overridable
`ObservationConvention`s, and Nessy commits to re-aligning with the conventions'
final form before its own 1.0. In a Spring Boot app with
Actuator, **observability lights up with no configuration at all** — Boot already
auto-configures the registry and handlers; the starter injects it.

Wiring: `.observations(ObservationRegistry)` on the builder, default `NOOP`. The
seam interfaces and sealed types reference Micrometer nowhere; SPI implementors
wanting deep instrumentation accept a registry in their own constructors, and
thread-local scoping — trivially correct under one-virtual-thread-per-run —
parents their observations automatically.

**Meters from events remain a supported pattern**: a hub subscriber incrementing
counters against a `MeterRegistry` is exactly what the hub is for. Durations and
anything needing propagated context belong to Observation — reconstructing spans
from fire-and-forget events is structurally impossible (no context during the
operation, no reliable parentage under concurrency), which is why there are two
channels and not one.

**Trace continuity across parking**: trace context is data, so it goes where data
goes — the W3C `traceparent` rides in `SessionState`, and resume opens a
continuation observation with a remote parent. Distributed trace continuity across
park/resume falls out of the explicit-state architecture; it is a story
listener-based frameworks cannot tell. Implemented alongside `DurableEngine`.

## 12. Testing doctrine

- **Purity first**: the reducer needs no doubles; drive a hundred steps in plain
  unit tests.
- **`ScriptedModelProvider`** makes the whole loop assertable with no key and no
  network; it records every request so tests assert on what the harness *sent*.
- **Test seams are internal** (§3): the retry decorator's `Sleeper`/clock, the
  durable engine's token source — single production implementations whose
  interfaces exist for test determinism, deliberately unadvertised.
- **Live tests** are tagged `live`, excluded by default via the
  `nessy.excludedGroups` property; `./mvnw verify` stays green keyless forever.
- **The promise**: no mocking library, ever, in ours or required of users.
  `TestObservationRegistry` (test scope) joins the doubles for span assertions.
- **Tests read as prose.** Method names are `snake_case` sentences; related
  scenarios group into `@Nested` classes named as capitalized phrases; the
  underscore→space display-name generator is configured module-wide via
  `junit-platform.properties`, so a failing report reads
  `TerminationPolicyTest ▸ Max turns ▸ halts at the ceiling and not below`.
  The discipline is diagnostic as well as cosmetic: a test whose name cannot be
  written as a sentence is usually testing more than one thing.

## 13. Modules and the defaults ladder

| Seam | In-core default | Upgrades Nessy provides | Extenders build |
|---|---|---|---|
| `ExecutionEngine` | `InProcessEngine` | `DurableEngine`; Temporal/Restate adapters | custom runtimes |
| `ModelProvider` | `ScriptedModelProvider` (testing) | `nessy-model-anthropic`, `nessy-model-openai`, retry decorator | any vendor |
| `SessionStore` | `SessionStore.inMemory()` | `nessy-store-jdbc` | Dynamo, Redis… |
| `Approver` | `allowAll()` / `denyAll()` | console; Slack/webhook | anything human-shaped |
| `TerminationPolicy` | error-ceiling + max-turns | cost budget (post-usage) | custom |
| `Policy` (pre-1.0) | derived from `requiresApproval()` | path/allowlist rules | OPA, corporate policy |
| `EventHub` | `synchronous()` | async decorator | bridges (SSE, message bus) |
| Observations | `ObservationRegistry.NOOP` | conventions + starter wiring | any Micrometer handler |
| `ContextBuilder` (deferred) | identity (unnamed) | compacting | RAG, redaction |

### 13.1 Classpath-upgradeable defaults (the Spring starter's defining feature)

In a Spring Boot application, dropping a Nessy module on the classpath makes its
implementation the default — consuming code knows nothing about what is
registered; it just asks for an `Agent`:

- The starter auto-configures every seam default with
  `@ConditionalOnMissingBean`; any contributed bean of a seam type displaces it
  (`nessy-store-jdbc` + a `DataSource` → the in-memory store stands down).
  Conflicts resolve with standard Spring means (`@Primary`, `nessy.store=jdbc`).
- **Tool beans are available, never ambient.** Declaring
  `@Component class LookupOrderTool implements Tool<…>` buys dependency
  injection and lifecycle — and grants the tool to no agent. Every agent's tool
  list is an explicit grant: tool beans injected into an `@Bean` method that
  calls `builder.tools(lookupOrder, escalate)`, or names listed in properties
  (`nessy.agents.support.tools: lookup_order, escalate`, matched by
  `Tool.name()`). An agent's tool list is its attack surface; least privilege
  is the grain of the design, not an opt-in.
- The primary pattern is hand-built, qualified `Agent` beans: consuming code
  injects a prototype-scoped `AgentBuilder` pre-wired with *infrastructure
  only* (provider, store, hub, observations, termination — never tools), sets
  what is the agent's own (`model`, `systemPrompt`, tools, approver), and calls
  `build()`. Declarative `nessy.agents.<name>.*` properties yield named `Agent`
  beans for the config-only path. The zero-configuration injectable `Agent`
  exists only in its safe form: no tools at all.

This requires no core changes: the starter pre-applies discovered infrastructure
to the builder, and explicit user calls still win. Core stays magic-free; the
magic lives entirely in the Spring layer, per §3.

**The grant principle — infrastructure is ambient; capability is granted;
authority is declared.** Stores, engines, hubs, providers, and observability
swap in by classpath. Tools are granted per agent, explicitly, in reviewable
code or config — a jar silently expanding an agent's capabilities is the same
supply-chain footgun as one changing its authority. And no library module may
auto-contribute an `Approver` or `Policy` bean: approval authority is always
the application's own explicit declaration. If none is declared, the starter's
`allowAll()` fallback announces itself with a prominent startup warning.

## 14. Sequencing

1. **Convergence** (next plan): zones, renames, hub, grammar completion,
   `TerminationPolicy`, Observation wiring, facade, JPMS, docs. The codebase must
   match this spec before anything new is built on it — the Anthropic provider
   should be *born* into `spi.model`, not migrated into it.
2. **Plan 2 — Anthropic provider**: native SDK, thinking/caching/usage for real,
   retry/backoff (with its internal `Sleeper` test seam), `StopReason` audit.
3. **Plan 2.5 — Policy**: contextual authorization, second implementation.
4. Then as previously mapped: OpenAI-wire provider, `DurableEngine` (+ trace
   continuity, + resume semantics of §6), compactor + `ContextBuilder`, Spring
   Boot starter, TUI.
5. **`nessy-tool-mcp` (unscheduled, acknowledged)**: an adapter exposing MCP
   server tools as `Tool<?>` instances. Deliberately unscheduled: it drags in
   authorization, elicitation, and remote-tool trust — interactions with the
   grant principle (§13.1) and the `Approver` that deserve their own design
   round, not a footnote. Recorded here so the seam review for `Tool`/`Policy`
   keeps remote tools in mind as a future implementor.
6. **1.0 gate**: grammar freeze after the `StopReason`/wire audit; JPMS decision
   finalized; artifact-reference design (outputs referenced from state, not
   embedded) resolved before any coding-agent toolset ships.

## 15. Risks

- **Micrometer coupling**: Micrometer's evolution is partially ours now. Accepted
  on its stability record and Spring Framework's identical exposure; the coupling
  surface is one builder method and internal calls, never seam signatures.
- **Grammar freeze pressure**: every sealed addition after 1.0 is a major. The
  §7 completion list plus the provider-plan wire audit is the mitigation; if the
  audit finds surprises, 1.0 waits.
- **Facade scope creep**: `Agent`/`Conversation` must stay sugar. The test: any
  facade behavior must be expressible in one sentence over the engine API. The
  moment it grows semantics of its own, it is rejected in review.
- **JPMS friction**: automatic-module dependencies may fight the module graph;
  the contingency in §4.4 keeps it from blocking convergence.
- **Hub misuse as a control plane**: mitigated structurally (no return values) and
  by documentation; the Approver-stays-blocking rule is the line that must hold.

## 16. Decisions resolved in this revision

| Decision | Resolution |
|---|---|
| Who is the primary user? | Application developers, mostly Spring shops |
| How is the extension surface identified? | A literal `spi` package zone; JPMS non-export of `internal` |
| Is `Tool` API or SPI? | API — tools are the everyday programming model |
| Own instrumentation facade or Micrometer? | Micrometer Observation, adopted directly |
| Events as the observability backbone? | No — hub for narrative and counters; Observation for spans, timers, context |
| Async or sync event delivery? | Synchronous default; async is a decorator |
| One front door or two? | One: `Nessy.agent()`; engine reached through `Agent` |
| Test-only interfaces: where? | Internal, unadvertised; promotion on evidence only |
| `MODIFY` policy verb? | Rejected — attribution nightmare |
| Grammar additions timing | Pre-1.0, per §7 list; frozen at 1.0 |
| Termination defaults | `anyOf(maxConsecutiveErrors(3), maxTurns(100))` |
