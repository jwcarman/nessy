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

### 1.1 What a harness is (defined 2026-08-09)

The industry says "harness" constantly and defines it nowhere. Nessy plants the
flag: **a harness is the model-independent runtime an agent runs inside —
everything that stays the same when you swap the model or the prompt. An agent
is an identity — a model binding, a system prompt, granted tools, declared
authority — running inside a harness.**

A harness is best defined by its service contract. The eight services, each
mapped to the seams that provide it:

| # | Service | The guarantee | Provided by |
|---|---|---|---|
| 1 | Turn-taking | events in, decisions out, effects in order, one coherent transcript | `Reducer`, `ExecutionEngine`, `Context` |
| 2 | Context fit | the conversation always fits the window; compaction and projection are not the agent's concern | `CompactionPolicy`, `Summarizer`, `ContextBuilder`, `TokenEstimator`, `Memory` |
| 3 | A memory of record | everything durable: snapshots to resume, an append-only journal of every message | `SessionStore`, `TranscriptStore`, the engine's durability contract |
| 4 | Safe hands | tool calls bound, validated, contained; a throwing tool is a model-visible error, never a dead session | `ToolRegistry`, the invoker (Factor 9) |
| 5 | Guardrails | no capability exercised past the declared authority; the model has no say in whether it is asked | `ToolGrant`/`UsagePolicy`, `Approver`, the grant principle |
| 6 | A wallet guard | the loop is bounded — turns, errors, someday cost | `TerminationPolicy` |
| 7 | Witnesses | every run observable: spans for operators, live events for UIs | Observations, `EventHub` |
| 8 | A vendor-neutral model line | one grammar to any model; capabilities negotiated, degradation explicit | `ModelProvider` |

Each service is a testable claim, and most are already promises the test suite
enforces. The services framing, not the parts list, is what the README leads
with.

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
`micrometer-observation` (§11 records why that fourth dependency is
principled), and `java-uuid-generator` — session and park identifiers are
time-ordered UUIDv7, sortable by creation time and index-friendly in durable
stores. Records, sealed interfaces, pattern matching, and virtual threads are
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
org.jwcarman.nessy               Nessy, Harness [§8.4], Agent, AgentBuilder, Conversation, Reply
org.jwcarman.nessy.api           Message, Role, ContentBlock (sealed: TextBlock, ThinkingBlock,
                                 RedactedThinkingBlock, ImageBlock, ToolUseBlock, ToolResultBlock),
                                 ToolCall, ToolResult, Usage, StopReason,
                                 SessionId, SessionState, SessionStatus,
                                 Event (sealed), Decision (sealed), Awaited (sealed), ParkToken,
                                 RunOutcome (sealed), TerminationPolicy, Context [§10.8],
                                 CompactionStrategy, CompactionTrigger, CompactionPolicy [§10.6]
org.jwcarman.nessy.api.tool      Tool, ToolContext, ToolRegistry, ToolSpec,
                                 ToolGrant, UsagePolicy, PolicyDecision (sealed)  [§10.5]
org.jwcarman.nessy.api.approval  Approver, ApprovalRequest
org.jwcarman.nessy.api.event     EventEmitter, EventHub, Subscription, SessionEvent, ToolProgress
org.jwcarman.nessy.spi           ExecutionEngine, Reducer, Effect (sealed), Step, InProcessEngine
org.jwcarman.nessy.spi.model     ModelProvider, ModelRequest, ModelEvent (sealed), ModelStream,
                                 Capability, ModelSettings
org.jwcarman.nessy.spi.context   ContextBuilder, TokenEstimator          [amended, §10.8]
org.jwcarman.nessy.spi.compaction Summarizer                             [amended, §10.8]
org.jwcarman.nessy.spi.memory    Memory                                  [§10.9]
org.jwcarman.nessy.spi.session   SessionStore, TranscriptStore, TranscriptEntry, MessageCodec  [§10.8]
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

**Resolved 2026-08-09: the fallback exit was taken.** A full `module-info.java`
was built and green under Maven, but white-box tests (same-package, reflectively
instantiated by JUnit) fail on the module path in IDEs
(`InaccessibleObjectException … does not "opens" … to org.junit.platform.commons`),
and every remedy — per-developer IDE configuration, or test-only `opens` in the
production descriptor — costs contributors more than the descriptor buys. Both
jars ship `Automatic-Module-Name` (`org.jwcarman.nessy.core` / `.testing`); the
api/spi/internal boundary stands on package convention, with a lightweight
architecture test (asserting `api` never imports `spi`/`internal`) as the
enforcement candidate for a later plan. Revisit only if the JPMS testing story
materially improves.

### 4.5 Module ladder

Artifacts follow `nessy-<family>-<implementation>` so an upgrader can guess the
name: `nessy-model-anthropic`, `nessy-model-openai`, `nessy-store-jdbc`,
`nessy-engine-temporal`. `nessy-core` is complete for the single-node in-memory
posture; `nessy-testing` ships the doubles; `nessy-bom` pins versions;
`nessy-spring-boot-starter` wires the Spring world. All wiring converges on the
one builder.

## 5. Naming

### 5.0 Glossary (added 2026-08-09) — one word, one meaning

- **The transcript** — a session's entire message history, forever.
  Append-only; lives in the `TranscriptStore` journal.
- **A session** — the continuing interaction, known by its `SessionId`,
  persisting across runs, parks, and resumptions.
- **The ledger** — `SessionState`: a value snapshot of everything true
  about a session right now — working messages, accounting, in-flight
  machinery.
- **The working set** — the ledger's message aspect: the compacted
  transcript (`[summary, …tail]` after compactions) that the reducer
  reasons over and `reply.state()` returns.
- **A `Context`** — a validated, pairing-legal message sequence bound for
  the wire: what one model call sees, minted per request by projection and
  recall.
- **A run** — one drive of the loop: an entry fact in, effects performed
  to quiescence, a `RunOutcome` out.

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
| `Usage(inputTokens, outputTokens, cachedInputTokens)` + `ModelEvent.TurnEnded(reason, usage)` | cost-budget termination, `gen_ai.usage.*` span attributes, and the `PROMPT_CACHING` cache-hit split |
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

### 8.4 The Harness object and typed agents — settled 2026-08-09

**Reifying the harness.** §13.1's grant principle ("infrastructure is ambient;
capability is granted; authority is declared") has been structural doctrine
without a structural home: `AgentBuilder` conflates shared infrastructure with
per-agent identity, so every agent re-declares the store, hub, and observations.
Settled: the harness becomes a first-class object.

```java
Harness harness = Nessy.harness()          // configured once per application
    .provider(anthropic)                   // the DEFAULT provider, not a constraint
    .store(store).hub(hub).observations(registry).transcript(journal)
    .build();

Agent<SupportInput> support = harness.agent(SupportInput.class)
    .model("claude-sonnet-4-5").systemPrompt("…")
    .tools(grant(lookupOrder), grant(refund).with(approveOver(500)))
    .approver(slackApprover)
    .build();
```

- Infrastructure lives on the `Harness`; capability grants and authority
  declarations live on the `Agent`. The three-word principle becomes two
  builders whose method lists are the principle.
- **Provider is a harness default, an agent binding.** Agents on one harness may
  bind different providers (`.provider(ollama)` overrides); the cheap-model
  `Summarizer` already crosses vendors within a single agent, so provider mixing
  is a fact of the design, not an accommodation.
- The *engine instance* is per-agent either way (it binds provider + tools +
  approver + policies — all identity); the harness holds the shared substrate
  engines are built on, and the engine *kind* is a harness choice.
- `Nessy.agent()` survives as the one-liner over an implicit default harness;
  the front door does not get heavier for the simple case.
- The Spring story collapses to: the starter auto-configures a `Harness` bean;
  applications declare `Agent` beans from it. §13.1's "builder pre-wired with
  infrastructure only" was this concept without its noun.

**Typed agents.** All agents are typed: `Agent<I>` / `Conversation<I>` where `I`
is the agent's input vocabulary — typically a sealed interface of records the
application owns (the Akka Typed lesson, learned there the expensive way:
protocols retrofitted onto an untyped core cost a parallel API and a decade).
`Conversation.tell(I)` renders the typed input canonically into the outbound
user message; the sealed `Event` grammar is untouched — typing lives in the
facade's generics and ends at the wire. `Agent<String>` is the degenerate case
behind `Nessy.agent()`, and `send(String)` keeps working. Because retrofitting
generics onto a shipped non-generic front door is source-breaking, **the type
parameter must be born before 1.0** (gate table, §14). What this buys: triggers
become compile-checked per agent; non-user stimuli (cron ticks, external
notifications) arrive as honestly-typed records instead of fake user prose; and
a typed agent converges with `Tool<I>` — the same record-schema machinery — so
an `Agent<I>` is trivially adaptable into a `Tool<I>` granted to a parent
agent: the subagent story falls out of the type system. **The vocabulary binds
only the front door**: an agent's tools are not related to its input type —
each `Tool<T>` keeps its own independent input record, exactly as today. The
`I` in `Agent<I>` is what the *application* may tell the agent; what the
*model* may call remains the grant list. Rendering rules, schema publication
into the system prompt, and the `tell`/`send`/tap relationship get their own
design round before implementation.

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

**Incremental persistence (added 2026-08-09).** The snapshot-shaped contract does
not mandate full rewrites. `SessionState.messages` is **append-only** — the
reducer only ever appends, never edits or removes — and this is a documented
invariant durable stores may rely on: persist the un-persisted tail plus a small
mutable header (status, counters, usage, failureReason, pending state), making
save cost O(new messages) rather than O(history). Compaction is the one
licensed violation of append-only, shipped in Plan 4; it adds a generation
marker to `SessionState` so a store can distinguish "append the tail"
(generation unchanged) from "rewrite" (generation bumped). The load side is
addressed by compaction/`ContextBuilder` (summary + tail as the working set);
full event-sourced journaling remains a `DurableEngine`-plan question, not a
seam change.

**Amended 2026-08-09 (§10.8):** history's durable source of truth moves to
the append-only `TranscriptStore` journal, fed directly by the engine at
message birth; `SessionStore` keeps its snapshot role and the semantics
above, but compaction's rewrite is thereby demoted from information loss to
working-set trim.

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

### 10.5 Per-grant authority — `ToolGrant` and `UsagePolicy` (revised 2026-08-09)

The earlier shape here — one agent-level `Policy` dispatching on tool names —
is superseded. Authority attaches to the *grant*: the binding between one agent
and one tool. The grant line becomes the complete security statement —
capability and authority, declared together, per agent, in one reviewable
place:

```java
Agent support = harness.agent()
    .tools(
        grant(add),                                   // floor applies: runs freely
        grant(refund).with(approveOver(500)),         // contextual: HITL past $500
        grant(deleteAccount).with(requireApproval())  // always a human
    )
    .approver(slackApprover).build();

Agent batch = harness.agent()
    .tools(grant(add), grant(refund).with(allow()))   // same tools, different authority
    .build();
```

- **`ToolGrant`** (api.tool): a `Tool` plus its `UsagePolicy` for this agent.
  `tools(…)` accepts grants; a bare `Tool` auto-wraps with the derived default.
- **`UsagePolicy`** (api.tool): per-grant, so it never dispatches on names —
  `PolicyDecision evaluate(ToolCall call, SessionState state)` with the
  decision grammar intact: sealed `Allow` / `Deny(reason)` / `RequireApproval`,
  and still no `MODIFY` — silently rewriting model-proposed arguments is an
  attribution nightmare. Factories `allow()`, `requireApproval()`,
  `deny(reason)`, plus the lambda form for contextual rules over arguments and
  session state.
- **`Tool.requiresApproval()` becomes exactly what it always wanted to be: the
  tool author's default.** No explicit policy on the grant → the derived policy
  (`true` → `requireApproval()`, `false` → `allow()`). An explicitly declared
  grant policy may override in either direction. **The loosening ruling**:
  authority is always the application's own explicit declaration (§13.1), so an
  application may waive a tool author's caution — but only at a grant site, in
  reviewable application code, next to the capability it loosens. Loosened by
  declaration: allowed, visible, attributable. Loosened by omission: never.
- The reducer/engine chokepoint is unchanged: `RequestApproval` consults the
  call's grant instead of a monolithic policy. Single enforcement point.
- Genuinely cross-cutting rules ("this agent never writes") are helpers that
  decorate a list of grants — keeping even the cross-cutting rule visible at
  the grant sites. The Spring config-only path gains per-tool authority:
  `nessy.agents.support.tools: add=allow, refund=approve`.

Lands before 1.0 (the `tools(…)` signature change is breaking after).

### 10.6 Context management — the settled design (2026-08-09, Plan 4)

Two mechanisms, two layers, per the original analysis — now fully specified:

**Layer 1 — `ContextBuilder` (spi): pure projection.** `List<Message>
project(SessionState state)`, consulted by engines wherever a `ModelRequest` is
assembled. State remains the full source of truth; the projection decides what
this request's model call sees. The seam is earned by two genuinely different
implementations: `ContextBuilder.identity()` (the default — today's behavior)
and `ContextBuilder.elidingToolResults(keepRecent)` — old tool-result contents
replaced with an elision marker while ids and pairing survive, so a 40KB file
read from twelve turns ago stops costing tokens. **Documented tradeoff**: a
sliding elision boundary rewrites one old message per turn, churning the prompt-
cache prefix; elision trades cache hits for context space, which is why identity
stays the default and the javadoc says so.

**Layer 2 — stateful compaction, through the reducer.** Triggered by *measured*
context size — no tokenizer: `TurnEnded.usage.inputTokens` is the provider's own
measurement of what the last call cost, captured into
`SessionState.lastInputTokens`. At the points where the reducer would emit
`CallModel`, if `lastInputTokens >= policy.triggerTokens()` it instead emits
`Effect.Compact(messagesToSummarize, instructions)` and enters
`SessionStatus.COMPACTING`. The engine performs it as an ordinary model call
(same provider, no tools, instrumented as `nessy.compaction`); the result
returns as `Event.Compacted(summary)`, and the reducer replaces the summarized
prefix with one summary message, keeps the recent tail verbatim, bumps
`SessionState.generation` (the store signal: unchanged generation → append the
tail; bumped → rewrite), and proceeds to `CallModel`.

Design rules:
- **Survivors**: summary + recent tail (`CompactionPolicy.keepRecentMessages`,
  pair-safe — the cut boundary only falls before a genuine user text turn, never
  between an assistant `tool_use` and its results, preserving the transcript
  invariant). The summary ships as a clearly-prefixed user message.
- **Failure is best-effort**: a failed summarization call emits a
  `CompactionFailed` event on the hub, feeds `Event.CompactionSkipped(reason)`,
  and the turn proceeds uncompacted — retried naturally at the next trigger. The
  session never dies because its summarizer hiccuped; if context truly
  overflows, the existing `MAX_TOKENS`/refusal machinery fails it loudly.
- **Configuration**: `CompactionPolicy(long triggerTokens, int
  keepRecentMessages, int summaryMaxTokens, String instructions)` in `api`,
  with `defaults()` (enabled, 100k trigger, 10 kept messages, 2,048-token
  summary cap) and `disabled()`; `AgentBuilder.compaction(policy)`.
  Compaction-by-default replaces v1's "fail loudly on overflow" — the loud
  failure remains the backstop, no longer the plan.

**Amended 2026-08-09 (§10.8):** the engine's private summarization method is
extracted into the `Summarizer` seam (`spi.compaction`), the pair-safe cut
relocates onto the `Context` type, and wire-bound message lists become
`Context`s. **Convergence rulings (2026-08-09, project owner) — compaction becomes one
strategy seam.** The final synthesis of the design session: compaction's
*decision* and *transformation* unify behind a single interface, while the
reducer keeps every scrap of bookkeeping authority:

```java
public interface CompactionStrategy {
    /** Pure — the reducer consults this at CallModel decision points. */
    boolean requiresCompaction(SessionState state);

    /** Effectful — the ENGINE performs this. May call models, may not.
     *  Returns a smaller working set and what producing it cost. */
    Result compact(List<Message> workingSet);

    record Result(List<Message> workingSet, Usage spend) { }
}
```

- **The choreography.** Reducer: `requiresCompaction` true at a decision
  point → emit `Effect.Compact(workingSet)` (the whole working set; the
  strategy owns where and how to shrink), enter `COMPACTING`. Engine:
  perform `compact(…)` under the `nessy.compaction` observation, validate
  the result (`Context.of(replacement)` — a pair-breaking strategy takes
  the existing best-effort failure path), feed
  `Event.Compacted(result.workingSet(), result.spend())`. Reducer: apply —
  replace messages wholesale, bump `generation`, accumulate the spend into
  `usage`, proceed to `CallModel`. **The strategy proposes; the reducer
  disposes.** A result that does not *shrink* the working set is applied
  as a skip (no bump — the reducer's belt to the engine's suspenders). A
  `Compacted` arriving while tool debt is outstanding applies as a skip too,
  regardless of shrink size — compaction only ever applies against a settled
  transcript (Controller ruling, fix round 1).
- **`Result.spend` is a bill, not a diff**: the tokens the compaction
  itself consumed (the summarizing call's own input + output), accumulated
  into the ledger like every other model call — the cost-accounting
  exclusion is repealed. Non-LLM strategies (truncation, tool-exchange
  dropping) spent nothing and return `Usage.zero()`.
- **Replay hardens for free.** `Event.Compacted` now carries the entire
  replacement working set — the *outcome*, not ingredients for re-deriving
  it. The recompute-the-cut hazard parked by Plan 4's review dissolves: a
  replayed `Compacted` reproduces state by construction.
- **The earlier pieces demote into the default strategy, not the trash.**
  `CompactionStrategy.summarizing(policy, summarizer)` is the default:
  `requiresCompaction` delegates to the policy's trigger, `compact` cuts
  at `Context.pairSafeCut(keepRecentMessages)` (no safe cut → unchanged
  result → skip) and stands in a `Summarizer` summary for the head.
  `CompactionPolicy` becomes its knob bundle — `(CompactionTrigger
  trigger, int keepRecentMessages, int summaryMaxTokens, String
  instructions)`, `defaults()` = `atTokens(100_000)`, `disabled()` =
  `never()` — and `CompactionTrigger` is the pluggable decision half:

  ```java
  public interface CompactionTrigger {
      boolean shouldCompact(SessionState state);
      static CompactionTrigger atTokens(long trigger) { … }
      static CompactionTrigger forWindow(long window, long maxTokens) { … } // ≈ 0.8 × (window − maxTokens)
      static CompactionTrigger never() { … }
  }
  ```

  Constants bake at construction; the builder wires `forWindow(…)`
  automatically when a `contextWindow` is declared on the model binding.
  `AgentBuilder.compaction(…)` overloads: pass a `CompactionPolicy` to
  tune the default strategy, or a `CompactionStrategy` to replace it
  wholesale. Alternative strategies (structured-facts digest, episodic
  cuts, rebuild-from-journal) implement the seam with no grammar change —
  the grammar freezes over outcomes, which are stable, not mechanisms,
  which are not.

Semantics above (measured trigger via the default, pair-safe cut inside
the default, best-effort failure, `COMPACTING`) are otherwise unchanged.

**The declared window (amended 2026-08-09).** The static `defaults()`
trigger is safe for 200k-class models and silently wrong for small-window
ones (a 32k local model sails past its real ceiling without ever
triggering; only the loud overflow backstop catches it). No fix can be
measured or queried: per-turn `usage` reports spend, never allowance, and
neither major vendor's models endpoint returns context length — the
ceiling exists only in documentation. So the three numbers are sourced
where each truthfully lives:

- **Declared**: `ModelSettings` gains an optional `contextWindow`, set
  where the model is set (`.model("llama-3").contextWindow(32_000)`) —
  a fact about the binding. Provider modules that genuinely can query it
  (OpenRouter's `context_length`, Ollama metadata) may pre-fill;
  application declaration always wins. No model→window table ships in
  core — hardcoded facts about other vendors' products rot on arrival.
- **Derived**: `CompactionTrigger.forWindow(window, maxTokens)` computes
  the threshold as roughly `0.8 × (window − maxTokens)` — reserving the
  reply's room, with the margin absorbing between-measurement growth
  (the new user turn, tool-result spikes, recall drift). The builder
  uses the derived trigger automatically when a window is declared;
  absolute `defaults()` remains the zero-config path.
- **Measured**: `lastInputTokens` stays the position gauge, unchanged —
  and because it measures the wire (post-projection, post-recall), memory
  enrichment is automatically inside the number.

### 10.7 Parallel tool execution — design note (resolved: NOT a freeze gate)

The design pass concluded parallelism requires **no sealed-grammar change**: a
`Step` already carries a `List<Effect>` — the batch container has existed since
v1. The future shape: the reducer approves calls serially as today (human
attention is serial; batch-approval UX is a separate question), then emits ALL
`ExecuteTool` effects in one `Step`; an engine MAY execute such a batch
concurrently on virtual threads but MUST feed the resulting `ToolFinished`
events back **in effect order** (parallel wall-clock, deterministic event order
— replayability survives). The reducer's id-keyed result handling already
tolerates this. Accordingly the freeze-gate table marks this item resolved:
purely a reducer/engine evolution, schedulable on demand post-1.0.

### 10.8 Context collaborators — the amendment (2026-08-09, post-Plan 4)

Plan 4 shipped compaction as a state rewrite and summarization as a private
engine method. A design review with the project owner immediately after
settled the follow-on architecture: the write path becomes sacred, the
domain collaborators become seams, and the transcript invariant gets exactly
one home. Four pieces, packaged by the domain they serve.

**The governing invariant: compaction never touches the write path.** The
engine appends every message to the transcript journal at the moment it is
born — unconditionally, directly, before anything read-shaped has an
opinion. Everything context-shaped (compaction, elision, windowing,
budgeting) lives on the read path. Append-only stops being a discipline
stores must trust the reducer to honor and becomes structural: nothing in
the system has an API to rewrite history.

**`Context` (api) — the pairing invariant's single home.** (Named
`Transcript` when first amended; renamed 2026-08-09 so each word means one
thing: *the transcript* is the journal's entire history, *a `Context`* is
a validated message sequence bound for the wire — and `ContextBuilder`
builds the thing it is named for.) The rule that an assistant `tool_use`
must never be separated from its results was enforced in two implicit
places (the reducer's private cut method; `elidingToolResults` being
careful) and in zero places for third-party projections — a pair-breaking
custom `ContextBuilder` would surface as a provider 400. `Context` is an
immutable value type over an ordered message list whose construction
validates the invariant: every `tool_use` id in an assistant message is
answered, completely and immediately, by the following results message,
and no orphan results exist. The boundary logic becomes its behavior — the
pair-safe cut (largest index at or below a limit that lands before a
genuine user text turn) moves out of the reducer's private method and onto
the type, with head/tail slicing beside it — so the reducer, the
summarizer's head selection, and any budget-aware projection all use one
implementation of "where may I cut?". Wire-bound seams speak `Context`:
`ContextBuilder.project` returns one, `ModelRequest` and `Effect.Compact`
carry one. `SessionState.messages` stays a plain list — a mid-turn state
legitimately ends with an open `tool_use` awaiting its results; the
reducer guarantees completeness at every `CallModel`, which is where
contexts are minted. An invalid projection now fails loudly at the seam,
in-process, with a message naming the orphaned id.

**`TranscriptStore` (spi.session) — the append-only journal.**

```java
public interface TranscriptStore {
    void append(SessionId id, TranscriptEntry entry);   // a pure sink — the ONLY method

    static TranscriptStore none() { … }                 // the default: auditability is opt-in
    static InMemoryTranscriptStore inMemory() { … }     // concrete type; exposes entries(id) for tests/hosts
}

public record TranscriptEntry(Message message, Usage turnUsage) { … }
```

**The seam is a pure sink (ruled 2026-08-09).** The framework NEVER reads
the journal — there is no `read` on the interface at all. Reading is the
backing store's native business (CQL, SQL, the concrete in-memory type's
own accessor); a combined implementation may physically rebuild
`SessionStore` snapshots from journal rows, but that is its private
affair — the seam contracts stay independent. **The default is `none()`**
— a noop sink, so the zero-config posture stays lean and compaction
genuinely bounds memory; retaining full history (in-memory for tests,
Cassandra for production) is a deliberate declaration, like everything
else with teeth.

The engine appends at message birth. The entry carries the one number that
is exact and non-derivable: `turnUsage` for assistant messages, whose
`outputTokens` genuinely are that message's cost — captured at the only
moment it exists (every other message appends `Usage.zero()`; its cost
surfaces as the next turn's input). Token *estimates* are deliberately NOT
journaled: an estimate is a cheap pure function of content the journal
already stores, so it is computed on demand by whatever `TokenEstimator`
is current — storing it would freeze one estimator's guess into the
permanent record and couple the write path to a read-path collaborator.
Store facts, compute derivations. Metadata rides on the entry rather than
on the `Message` grammar, which stays wire-pure.

**Append failure is strict (ruled 2026-08-09).** The journal is
audit-grade truth: a silent gap is worse than a failed turn, so a failed
append fails the run, loudly. The in-memory default cannot fail, so the
zero-config posture is untouched; strictness bites only where someone
deliberately wired a durable journal — which is exactly when they mean it.
(A best-effort mode, if ever demanded, would be a declared posture, never
a default.)

**At-rest encoding — `MessageCodec` (ruled 2026-08-09).** Durable stores
persist opaque bytes, never message structure: a `MessageCodec` owns the
`Message ↔ byte[]` translation. The default is `MessageCodec.json(mapper)`
— canonical JSON serialized as UTF-8 bytes — and encryption at rest is a
codec *decorator* —
`MessageCodec.encrypted(json, keyProvider)` — composing over any store
implementation rather than being rebuilt per vendor. The seam serves
`SessionStore` equally (encrypting the journal but not the snapshots would
be theater). Key management is the application's; `nessy-core` ships no
cryptography — the encrypting codec lives with the durable-store modules
that need it. The in-memory defaults hold live objects and use no codec at
all. The journal is never on the run
hot path: loads and resumes come from `SessionStore` snapshots exactly as
today. The journal exists for what snapshots cannot do — audit, debugging a
bad summary, re-summarizing with a better model later, and memory
extraction. With the journal as the durable source of truth for history,
compaction's state rewrite is demoted from information loss to working-set
trim. `SessionStore` is unchanged and `generation` survives (snapshot
stores still diff by it). The exemplary durable implementation is
**`nessy-store-cassandra`**: partition key per session, clustering by append
sequence — an append-heavy write path with rare sequential reads is
precisely the workload Cassandra's storage model is built for.

**`Summarizer` (spi.compaction) — the default strategy's sub-seam.**
(Ruled 2026-08-09: `CompactionStrategy` in §10.6 owns compaction wholesale;
`Summarizer` survives inside the default `summarizing(…)` strategy so "same
strategy, cheaper model" never requires reimplementing cut logic.)

```java
public interface Summarizer {
    Summary summarize(Context head, CompactionPolicy policy);

    record Summary(String text, Usage usage) { }   // non-LLM summarizers return Usage.zero()
}
```

(The `Summary` pair exists because of the usage ruling: the engine needs
the summarization call's spend to put on `Event.Compacted`.)

The head handed in may begin with the previous summary message, so
summaries fold forward across recompactions instead of nesting. It returns
prose plus the call's measured usage; the reducer keeps ownership of the summary message's format and
placement, so replay determinism stays in one place (`Event.Compacted`
carries the string, as shipped). Failure is a thrown `RuntimeException`,
and the engine's best-effort path is unchanged: `CompactionFailed` on the
hub, `CompactionSkipped` fed, turn proceeds. The default,
`Summarizer.usingProvider(provider)`, is exactly the shipped behavior
extracted from the engine — tool-free call, the policy's
`summaryMaxTokens` and `instructions`, blank result treated as failure —
and the engine keeps the `nessy.compaction` observation wrap. This is
deliberately a many-implementations seam: a cheap-model variant built over
a *different* `ModelProvider` (converse with the frontier model, summarize
with a small one), extractive or heuristic summarizers that never call a
model, remote summarization services. `AgentBuilder.summarizer(…)`, default
derived from the configured provider; the scripted double ships in
`nessy-testing` beside the other doubles.

**`TokenEstimator` (spi.context) — the message-level number that models
never report.**

```java
public interface TokenEstimator {
    long estimate(Message message);

    static TokenEstimator heuristic() { … }  // content characters / 4
}
```

Providers report usage per call; nothing reports it per message. This seam
manufactures that missing figure honestly, *on demand, on the read path
only*: budget-aware projections ("keep as many recent messages as fit in
20k tokens", an honest upgrade over counting messages), sizing the head
handed to a summarizer, and any offline analysis over journal content —
all recompute estimates from stored messages with whatever estimator is
current, rather than reading a frozen guess out of the record. It
complements the measured trigger and never replaces it: compaction keeps
triggering on `lastInputTokens`, the provider's own exact count. The
`heuristic()` default is good enough in a lot of cases and says so;
anything smarter — a tokenizer-library adapter, a provider's count-tokens
endpoint — drops in through the seam.

**Packaging is by domain, not by a catch-all.** `spi.context` holds
`ContextBuilder` (moved from `spi` root, a free rename pre-1.0) and
`TokenEstimator`; `spi.compaction` holds `Summarizer`; `spi.session` gains
`TranscriptStore` beside `SessionStore`. Collaborators live next to the
seam they serve, the way `spi.model` already works.

**What this amendment does not touch:** the measured trigger, the pair-safe
cut semantics (relocated, not changed), best-effort failure,
`CompactionPolicy`'s shape, and the engine's durability contract all stand
as shipped in Plan 4.

### 10.9 Memory — the recall seam (settled 2026-08-09)

Memory is the third read-path concern, and it needs its own seam for the same
reason summarization did: `ContextBuilder` is contractually pure, and recalling
facts from a graph or vector store is I/O. Memory is a sibling of projection,
not a subtype.

```java
public interface Memory {
    List<Message> recall(Context context);      // engine-performed; I/O sanctioned

    static Memory none() { … }                  // the default
}
```

- **Recall** (`spi.memory`): consulted by the engine at request assembly beside
  the projection; recalled facts are injected into the request. Best-effort by
  policy — a downed memory store costs *enrichment*, never the *turn* (failure
  emits on the hub and the call proceeds without memories; the compaction
  pattern). Being a separate seam is what makes that per-concern failure policy
  possible: buried inside a projection, "the graph is down" and "the projection
  is buggy" would share one fate.
- **Extraction needs no new seams.** The feedstock supply chain already exists:
  the `TranscriptStore` journal (offline pipelines — the token-annotated full
  history), hub subscribers (online extraction), and the compaction moment —
  the head handed to the `Summarizer` is exactly the material about to leave
  the context, so the engine's compact arm keeps a "last chance to remember"
  hook in mind for a memory extractor.
- The agentic mode (`search_memory`/`save_memory` as granted tools) already
  works through the tool seam, unchanged.
- **Documented tradeoff** (same genre as elision's): recalled content changes
  turn to turn, and front-of-prompt injection churns the prompt-cache prefix.
  A refresh-on-compaction strategy aligns the churn with the moment the prefix
  churns anyway; implementors get told this rather than rediscovering it.
- Why the token-usage claims around memory are credible: distilled facts are
  radically denser than the verbose history they replace, and recall composes
  with compaction — aggressive compaction is safe precisely when the facts
  worth keeping are already durable elsewhere.

The seam ships `none()`-defaulted before the first real implementation; the
graph-backed implementation arrives when a real backing store drives it.

### 10.10 The context assembler (settled 2026-08-09)

Three different message lists answer to one session id, and the distinction is
the architecture: the **journal** (`TranscriptStore.read` — everything that
ever happened), the **working set** (`SessionStore.load(id).messages` — what
the reducer reasons over, `[summary, …tail]` after compactions), and the
**assembled context** (working set → `ContextBuilder.project` →
`Memory.recall` — what one model call sees). With `identity()` and no memory,
the second and third are the same list; the gap opens only when read-path
shaping is opted into, and the record never lies to the application
(`reply.state()`) regardless of what any call was shown.

The assembly line exists but has no name; it gets one. A reified assembler —
harness-provided machinery binding agent-level choices (this agent's
projection, this agent's memory) — produces the third list on demand:

- **Engines consume it** for request assembly, so every engine (the in-process
  one, the durable one) builds requests identically — the §10.8 extraction of
  engine collaborators, arrived at by a better route.
- **`agent.contextFor(sessionId)`** exposes it as a debugging affordance:
  *show me exactly what the model would see right now* — answerable truthfully
  without a model call because assembly is deterministic over state.

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
| `ContextBuilder` | `ContextBuilder.identity()` | `elidingToolResults(keepRecent)` (shipped); stateful compaction (shipped, §10.6); token-budget windowing (§10.8) | RAG, redaction |
| `TranscriptStore` (§10.8) | `TranscriptStore.inMemory()` | `nessy-store-cassandra` | any append-only journal |
| `Summarizer` (§10.8) | `usingProvider(…)` — the session's own model | cheap-model variant; extractive | remote services, custom |
| `TokenEstimator` (§10.8) | `heuristic()` (chars / 4) | tokenizer-library adapter | provider count-tokens APIs |

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
   **Delivered as part of Plan 3** (`docs/superpowers/plans/2026-08-09-nessy-providers.md`),
   which also pulled forward the OpenAI-wire provider from item 4 below:
   `nessy-model-anthropic` and `nessy-model-openai` both shipped, live-validated,
   and the `StopReason`/wire audit performed — each SDK's stop/finish-reason
   enumeration verified complete against its own source, with an executable
   fail-loudly mapping (Tasks 5 and 8) rather than a silent default for
   anything the audit didn't account for.
3. **Plan 2.5 — Policy**: contextual authorization, second implementation.
4. Then as previously mapped: OpenAI-wire provider, `DurableEngine` (+ trace
   continuity, + resume semantics of §6), Spring Boot starter, TUI. Compaction
   and `ContextBuilder` (§10.6) shipped in Plan 4, ahead of this sequencing.
   The §10.8 context-collaborator amendment (`Context`, `TranscriptStore`,
   `Summarizer`, `TokenEstimator`, domain packaging) is **done** — shipped and
   tested end to end (the context-collaborators convergence plan) — it
   reshapes seams the durable engine will consume, so it landed before
   `DurableEngine` as planned. **Next: Plan 6**, the remainder of the
   2026-08-09 design session's queue — the `Harness` reification and typed
   front door (§8.4), per-grant authority (§10.5), `Memory` (§10.9), and the
   context assembler (§10.10) — with typed-input details (§8.4) getting their
   own brainstorm-to-spec round first. One standing DurableEngine note from
   the same session: pure replay is free, but replaying the imperative shell
   is not — a replayed reducer re-emits effects (the process-manager replay
   problem), so the journal must record which effects were performed and
   elide them on replay; the parked `Compacted` rulings from Plan 4's review
   are the same issue.
5. **`nessy-tool-mcp` (unscheduled, acknowledged)**: an adapter exposing MCP
   server tools as `Tool<?>` instances. Deliberately unscheduled: it drags in
   authorization, elicitation, and remote-tool trust — interactions with the
   grant principle (§13.1) and the `Approver` that deserve their own design
   round, not a footnote. Recorded here so the seam review for `Tool`/`Policy`
   keeps remote tools in mind as a future implementor.
6. **The freeze gates** — decisions that must clear before 1.0, because each
   becomes a breaking change afterward. A gate clears by shipping the change or
   by a recorded decision that deferral is safe:

   | Gate | Why it gates | Status |
   |---|---|---|
   | `StopReason` wire audit | sealed enum; new values break exhaustive switches | ✅ cleared — both SDKs' values enumerated; mapped or loudly rejected (Plan 3) |
   | JPMS decision | module descriptor cannot be added/removed compatibly | ✅ cleared — withdrawn with evidence (§4.4) |
   | Compaction grammar (`Effect.Compact`, `Event.Compacted`, `Event.CompactionSkipped`, `SessionStatus.COMPACTING`, `generation`/`lastInputTokens` on `SessionState`) | sealed additions + record components | ✅ cleared — shipped and tested end to end (§10.6) |
   | `Usage` cache-token component(s) (`cachedInputTokens`) | record component; `PROMPT_CACHING` cannot report the cache-hit split without it | ✅ cleared — `Usage` is now `(inputTokens, outputTokens, cachedInputTokens)` |
   | `ModelRequest.responseSchema` | record component; structured output (`reply.as(T)`) needs a schema slot to the provider | ✅ cleared — nullable slot shipped; providers wired today ignore it; the feature itself lands post-1.0 |
   | Artifact-reference design (outputs referenced from state, not embedded) | `ContentBlock`/state shape implications | open — resolve before any coding-agent toolset ships |
   | `Context` adoption (`ContextBuilder`/`ModelRequest`/`Effect.Compact` speak `Context`) | seam signature + record component types; breaking after 1.0 | ✅ cleared — shipped and tested end to end (this plan) |
   | Typed front door (`Agent<I>`/`Conversation<I>`, §8.4) | retrofitting generics onto a shipped non-generic facade is source-breaking | open — the type parameter must be born pre-1.0; `Agent<String>` is the degenerate case |
   | Entry-event vocabulary | sealed `Event`; every post-1.0 variant is a major | open — typed input (§8.4) is the settled direction for attribution; residue is cancellation (`RunCancelled`, a DurableEngine-plan question) and agent-to-agent delivery; audit before freeze |
   | Per-grant authority (`ToolGrant`/`UsagePolicy`, §10.5) | `tools(…)` signature change; breaking after 1.0 | open — ships pre-1.0 |
   | Parallel tool execution | — | ✅ resolved as NOT a gate — needs no sealed change (multi-effect Steps + ordered feed, §10.7) |
7. **Hardening (pre-1.0, non-blocking)**: Stream-translation tests should
   migrate to wire-JSON-driven fixtures (through each SDK's own
   deserialization) — builder-built fixtures validate a model of the wire, not
   the wire.

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
| Where does history live durably? (2026-08-09) | The append-only `TranscriptStore` journal, fed by the engine at message birth; state is the working set (§10.8) |
| Where is the tool-pairing invariant enforced? (2026-08-09) | Once, in the `Context` type, at construction (§10.8) |
| Is summarization pluggable? (2026-08-09) | Yes — `spi.compaction.Summarizer`, a many-implementations seam; the reducer keeps summary formatting (§10.8) |
| Per-message token accounting? (2026-08-09) | Models report per call only; `TokenEstimator` computes the message-level figure on demand, read-path only — the journal stores facts (`turnUsage`), never derivations (§10.8) |
| What is a harness? (2026-08-09) | The model-independent runtime an agent runs inside, defined by its eight-service contract (§1.1); reified as the `Harness` object (§8.4) |
| Where does authority attach? (2026-08-09) | To the grant — `ToolGrant` + `UsagePolicy` per agent-tool binding; `requiresApproval()` is the tool author's default; explicit grant policy may loosen or tighten (§10.5) |
| Is memory a `ContextBuilder`? (2026-08-09) | No — projection is pure, recall is I/O; `Memory` is a sibling seam with its own best-effort failure policy (§10.9) |
| Are agents typed? (2026-08-09) | Yes, all of them — `Agent<I>` over an application-owned sealed vocabulary; `Agent<String>` degenerate; born pre-1.0; tools keep their own input types (§8.4) |
| Does compaction's spend count? (2026-08-09) | Yes — `Event.Compacted(workingSet, spend)`; a bill, not a diff; non-LLM strategies bill `Usage.zero()`; the exclusion is repealed (§10.6) |
| Is compaction pluggable? (2026-08-09) | Wholesale — `CompactionStrategy.requiresCompaction(state)` + `compact(workingSet) → Result(workingSet, spend)`; the strategy proposes, the reducer disposes; `CompactionTrigger`/`Summarizer`/`CompactionPolicy` demote into the default `summarizing(…)` strategy (§10.6) |
| Journal append failure? (2026-08-09) | Strict — audit-grade truth; a failed append fails the run; in-memory default cannot fail (§10.8) |
| At-rest encoding? (2026-08-09) | `MessageCodec` (`Message ↔ byte[]`): JSON-as-UTF-8 default, encryption as codec decorator, serving both stores; core ships no cryptography (§10.8) |
| Is the journal readable through the seam? (2026-08-09) | No — `TranscriptStore` is a pure sink; the framework never reads it; default is `none()`, retention is opt-in (§10.8) |
| Transcript vs Context? (2026-08-09) | The transcript is the journal's full history; `Context` is the validated wire-bound sequence; glossary §5.0 |
| Test-only interfaces: where? | Internal, unadvertised; promotion on evidence only |
| `MODIFY` policy verb? | Rejected — attribution nightmare |
| Grammar additions timing | Pre-1.0, per §7 list; frozen at 1.0 |
| Termination defaults | `anyOf(maxConsecutiveErrors(3), maxTurns(100))` |
