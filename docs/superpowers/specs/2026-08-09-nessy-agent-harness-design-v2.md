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
| 2 | Context fit | the conversation always fits the window; compaction and projection are not the agent's concern | `Compactor`, `Summarizer`, the context pipeline |
| 3 | A memory of record | everything durable: snapshots to resume, plus an opt-in journal of every message | `ConversationStore`, a declared `MessageAppended` listener (§17 — the journal is a listener, not a store) |
| 4 | Safe hands | tool calls bound, validated, contained; a throwing tool is a model-visible error, never a dead session | `ToolRegistry`, the invoker (Factor 9) |
| 5 | Guardrails | no capability exercised past the declared authority; the model has no say in whether it is asked | `ToolGrant`/`UsagePolicy`, `Approver`, the grant principle |
| 6 | A wallet guard | the loop is bounded — turns, errors, someday cost | `TerminationPolicy` |
| 7 | Witnesses | every run observable: spans for operators, declared listeners for UIs | Observations, declared listening (`listen`/`listenAsync`, `Conversation#events()`) — §9, §17 |
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
`Servlet` was — while `ConversationStore` is SPI, the way implementing a JDBC `Driver`
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
objects second* (`InMemoryConversationStore` needs no fake because the real thing is
zero-ceremony), *a dedicated internal test seam third*.

**No magic in core.** Explicit builder wiring only. Every object is traceable from
construction to use by cmd-click: no reflection-driven discovery, no classpath
scanning, no annotation processing in `nessy-core`. Discovery belongs to outer
layers — the Spring starter discovers via DI; a CLI may use `ServiceLoader` to
*list* providers. Core never does.

**Dogfood the SPI.** Every upgrade Nessy itself ships goes through the same public
seams with zero privileged hooks. Retry ships as a `ModelProvider` decorator.
Tracing of a specific store ships as a `ConversationStore` decorator. If we need a
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
principled), `java-uuid-generator` — conversation and park identifiers are
time-ordered UUIDv7, sortable by creation time and index-friendly in durable
stores — and `slf4j-api` for warnings (the unconfigured-approver and
unconfigured-compactor warnings, §13.1, §10.6, log through it rather than
`System.err`). Records, sealed interfaces, pattern matching, and virtual threads are
load-bearing. Every seam is a plain blocking interface — no `CompletableFuture`,
no `Flow.Publisher`, no reactive types anywhere.

**Logging provider (added 2026-08-10).** `logback-classic` joins as the
TEST-scope logging provider across the build — chosen over `slf4j-simple` so
build output shows the framework's own warnings clearly — and as a
COMPILE-scope dependency of `nessy-examples` specifically: examples are
runnable applications, and an application picks its own SLF4J provider rather
than inheriting the build's test-classpath default.

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
org.jwcarman.nessy               Nessy, Harness, HarnessBuilder, Agent, AgentBuilder, Conversation,
                                 Reply, AgentConfigurationException [§17]
org.jwcarman.nessy.api           ConversationEvent (sealed), Decision (sealed), Awaited (sealed), ParkToken,
                                 RunOutcome (sealed), StopReason — the sealed grammar only
org.jwcarman.nessy.api.message   Message, Role, Context [§10.8], ContentBlock (sealed: TextBlock,
                                 ThinkingBlock, RedactedThinkingBlock, ImageBlock, ToolUseBlock,
                                 ToolResultBlock), TokenEstimator [§10.8 — beside Context, which
                                 takes it directly; api may not depend on spi]
org.jwcarman.nessy.api.conversation   ConversationId, ConversationState, ConversationStatus, Usage, TerminationPolicy
org.jwcarman.nessy.api.tool      Tool, ToolContext, ToolRegistry, ToolSpec, ToolCall, ToolResult,
                                 ToolGrant, UsagePolicy, PolicyDecision (sealed)  [§10.5]
org.jwcarman.nessy.api.approval  Approver, ApprovalRequest
org.jwcarman.nessy.api.event     EventEmitter, ListenerRegistration, ListenerRegistry,
                                 ConversationEvents, ConversationScoped, Subscription,
                                 MessageAppended, ToolProgress, CompactionFailed, EnrichmentFailed
org.jwcarman.nessy.spi           ExecutionEngine, Reducer, Effect (sealed), Step, InProcessEngine
org.jwcarman.nessy.spi.model     ModelProvider, ModelRequest, ModelEvent (sealed), ModelStream,
                                 Capability, ModelSettings
org.jwcarman.nessy.spi.context   ContextPipeline, Projection, ContextEnricher  [§10.9]
org.jwcarman.nessy.spi.compaction Compactor, Compactors, Summarizer   [§10.6 consolidation]
org.jwcarman.nessy.spi.conversation   ConversationStore, MessageCodec  [§10.8; the journal is a
                                 listener now (§17), not a store — TranscriptStore/TranscriptEntry
                                 are gone]
org.jwcarman.nessy.internal      ToolInvoker, Schemas, observation conventions, engine machinery
```

Placement decisions worth their reasoning:

- **`Reducer`, `Effect`, `Step` are the SPI's centerpiece.** Users never touch
  them; engine implementors *must* — they are the semantics an engine executes.
  Neither user API nor internal: precisely SPI.
- **`Awaited` and `ParkToken` are API** (root, alongside the sealed grammar),
  because `Tool.execute` returns `Awaited<ToolResult>` and tools are everyday
  code; `RunOutcome.Parked` hands users a `ParkToken`. The SPI references them
  inward (`spi → api` is the allowed direction).
- **`TerminationPolicy` is API** (`api.conversation`, beside the rest of the session's
  lifecycle state): configuring budgets is everyday agent-writing, not hosting.
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
`nessy-engine-temporal`, `nessy-compactor-<implementation>` (algorithm packs
that bring dependencies; the summarizing default stays in core). `nessy-core` is complete for the single-node in-memory
posture; `nessy-testing` ships the doubles; `nessy-bom` pins versions;
`nessy-spring-boot-starter` wires the Spring world. All wiring converges on the
one builder.

## 5. Naming

### 5.0 Glossary (added 2026-08-09; made conversation-centric §17, 2026-08-10) — one word, one meaning

**Everything centers on a conversation** (§17): "session" has left the vocabulary
entirely — every type, package, and doc sentence that used to say it now says
"conversation." "The transcript" and "a run" below are deliberately *concepts*,
not types — nothing in the codebase is named `Transcript` or `Run`; they name
what a declared listener or a `RunOutcome` is *about*.

- **A conversation** — the continuing interaction, known by its
  `ConversationId`, persisting across runs, parks, and resumptions. Nessy's
  one organizing unit: every `ConversationEvent` names the conversation it
  belongs to (`conversationId()`), every listening level is scoped to it, and
  every store, status, and state type is named after it.
- **The transcript** (a concept, not a type) — a conversation's entire message
  history, forever, append-only. There is no dedicated store type for it: the
  journal is simply whatever a declared `MessageAppended` listener chooses to
  keep (§17; see §10.8 below, "the journal is a listener").
- **The ledger** — `ConversationState`: a value snapshot of everything true
  about a conversation right now — working messages, accounting, in-flight
  machinery.
- **The working set** — the ledger's message aspect: the compacted
  transcript (`[summary, …tail]` after compactions) that the reducer
  reasons over and `reply.state()` returns.
- **A `Context`** — a validated, pairing-legal message sequence bound for
  the wire: what one model call sees, minted per request by projection and
  recall.
- **A run** (a concept, not a type) — one drive of the loop: an entry fact in,
  effects performed to quiescence, a `RunOutcome` out.

The conventions, uniformly applied:

- **No `I`-prefixes, no `-Impl` suffixes, ever.**
- **Defaults are named by strategy, not by data structure**: `InMemoryConversationStore`,
  `InProcessEngine`, `ScriptedModelProvider`. (`MapToolRegistry` violated this and
  is renamed away — see ledger.)
- **The seam interface is the front door to its own defaults** via static
  factories: `ToolRegistry.of(tools…)`, `Approver.allowAll()`,
  `Approver.denyAll(reason)`, `ConversationStore.inMemory()`,
  `TerminationPolicy.maxTurns(n)`. One obvious place to look; core default classes
  may be package-private behind them. External modules ship public classes
  (`JdbcConversationStore`) — the asymmetry is deliberate: core defaults are reachable
  without knowing a class name, external implementations are named products.
- **Events are facts, named in the past tense or as observations** (`ToolFinished`,
  `AgentTold`, `TextDelta`); **effects are orders, named imperatively**
  (`CallModel`, `ExecuteTool`); **statuses are states** (`AWAITING_MODEL`).
- **One front door.** `Nessy.harness(provider)` is the only entry point
  (§17 supersedes the earlier `Nessy.agent()`, which is retired); the
  engine-level API is reached *through* the built `Agent`.

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

The loop performs no I/O. `reduce(ConversationState, ConversationEvent) → Step` is pure,
synchronous, and total; `Step` is the next state plus a list of `Effect`s. An
`ExecutionEngine` performs effects and feeds every result back as an `ConversationEvent`.
Streaming tokens are ordinary events — that is why the loop streams natively.
Every seam is a plain blocking interface on virtual threads; an interactive
approval parks a thread, a durable one parks a *session* via `Awaited.Parked` and
a single-use `ParkToken`.

`ConversationState` is a plain serializable record and the whole of the agent's memory:

```
ConversationState
 ├── id                  ConversationId
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

### 6.1 The lifecycle (settled 2026-08-10)

The phases of a run, named — the framework's official map, born from a
Maven-lifecycle design conversation with the project owner. Maven's insight
is that phases define *when* and participants define *what*; Nessy's
refinement is that each phase also declares **how much a participant may
decide**, because an agent harness has determinism and authority stakes a
build tool does not. The phases are descriptive of what is already built;
the table is normative about openness:

| Phase | Contract | How you participate | Openness |
|---|---|---|---|
| Load | snapshot → ledger | `ConversationStore` | seam |
| Prepare | wiring | build time, on purpose — harness + grants, reviewable | closed at runtime (the grant principle) |
| Contextualize | ledger → `Context` | the context pipeline: `project`/`enrich` bindings (§10.9) | fully open |
| Invoke | `Context` → stream | provider decorators (retry, routing); observations | decorate |
| Interpret | wire → facts | sealed grammar + reducer | **closed — determinism is the product** |
| Execute | wishes → outcomes | tools, through the grant chokepoint | open through the chokepoint only |
| Integrate | facts → ledger | the reducer, sole author of succession | **closed — the ledger never lies** |
| Checkpoint | ledger → durable | `ConversationStore` + `MessageAppended` subscribers | seam + spine |
| Evaluate | continue? | `TerminationPolicy`, `Compactor` | strategy objects |
| Complete | → `Reply` / `RunOutcome` | facade | fixed |

The repeating agent cycle is Contextualize → Invoke → Interpret → Execute →
Integrate → Checkpoint → Evaluate; Load precedes it, Complete follows. A
durable engine checkpoints per iteration (per fold, in practice — the
in-process engine's progress holder is the degenerate form), which is what
makes parking and resumption free: succession is a pure fold, the
outstanding work is recorded *in* the ledger (`status`, the pending lane),
and the fold cannot perceive time between facts.

Interpret and Integrate are closed **on purpose and permanently**: opening
them would hand out authorship of state succession, and every guarantee in
this document — replayability, testing without mocks, the authority
chokepoint, wire legality — is downstream of there being exactly one
author. The next person who arrives with a middleware proposal for these
phases should find this paragraph.

## 7. The grammar

The sealed hierarchies — `ContentBlock`, `ConversationEvent`, `Effect`, `Decision`, `Awaited`,
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
| `ModelEvent.ThinkingChunk(text)` + `ConversationEvent.ThinkingDelta(text)` | streamed thinking accumulates like text; the reducer merges deltas into a trailing `ThinkingBlock`. Signature delivery (Anthropic requires the signature to round-trip) is finalized in Plan 2 against the real wire — still pre-freeze |
| `Usage(inputTokens, outputTokens, cachedInputTokens)` + `ModelEvent.TurnEnded(reason, usage)` | cost-budget termination, `gen_ai.usage.*` span attributes, and the `PROMPT_CACHING` cache-hit split |
| `ConversationEvent.AgentTold` canonicalizes to `List<ContentBlock>` with `AgentTold.of(ConversationId, String)` | multimodal input needs an entry path; one variant, not two |

`StopReason` gets a final audit against the real Anthropic and OpenAI wire formats
during the provider plans — the last gate before freeze.

## 8. The API surface

### 8.1 The front door (superseded by §17 — `Nessy.harness(provider)` is the door now)

The first five minutes decide whether people love a framework. The event-level API
(`engine.run(id, event)` → pattern-match `RunOutcome` → spelunk content blocks) is
architecturally honest and ergonomically hostile as a first encounter. The facade
fixes that — sugar over the engine, zero new semantics. The shape below is the
one shipped and tested (§17 supersedes `Nessy.agent()`, shown historically in the
rest of this section, with `Nessy.harness(provider)` as the sole front door):

```java
Agent<String> agent =
    Nessy.harness(anthropic)               // where tokens come from   (required, by signature)
        .build()
        .agent()
        .model("claude-sonnet-4-5")        // which model              (required)
        .systemPrompt("You are a helpful assistant.")
        .tools(ToolGrant.grant(new ReadFileTool(), UsagePolicy.allow()),
               ToolGrant.grant(new GrepTool(), UsagePolicy.allow()))
        .approver(Approver.denyAll("read-only demo"))
        .build();

Conversation<String> chat = agent.converse();
Reply reply = chat.tell("What does the build file declare?");
reply.text();                              // the assistant's prose, extracted
```

- `Agent<I>` — a configured, reusable handle; `converse()` opens a fresh
  conversation, `resume(ConversationId)` reopens a stored one, `engine()` expose
  the full machinery; `Conversation#events()` is the one dynamic listening level
  (§17). The escape hatch is one method away, so the facade never traps.
- `Conversation<I>` — one conversation: `tell(I) → Reply`, `conversationId()`.
- `Reply` — wraps the final `ConversationState`: `text()` (concatenated text blocks of
  the final assistant message), `failed()`, `state()`.
- Every builder default works out of the box: in-memory store, in-process engine,
  allow-all approver (with the safety note that real tools deserve a real
  approver), no-op observations, default termination (§10.4).
  The smallest useful agent is a provider and a model name.

`Agent` and friends are final classes, not seams: users who want a fake agent in
tests use a real `Agent` over `ScriptedModelProvider` — the classicist testing
stance, and the reason the no-mocking promise holds.

### 8.2 Tools (superseded by §17's tool-authority addendum)

`Tool<T>` keeps record-derived schemas and `describe(T)` for honest approval
prompts, but `requiresApproval()` is DELETED (§17 addendum, "one path for tool
authority"): a tool is pure capability — name, schema, execution — and carries
zero authority content. `ToolRegistry.of(tools…)` remains the everyday
construction of a registry from bare tools; attaching a tool to an *agent*,
however, always goes through a grant (§10.5). `ToolContext` gains `events()`
(§9) so long-running tools can report progress.

### 8.3 Approval

`Approver` remains the blocking, harness-side interceptor. It is *not* a
declared listener and never will be: approval is synchronous request/response
with an answer the loop waits on; declared listening (§9) is one-way exhaust.
Keeping those channels separate is what keeps "the model cannot route around
the gate" provable.

### 8.4 The Harness object and typed agents — settled 2026-08-09 (harness shape superseded by §17)

**Superseded by §17**: the harness/agent split proposed and reified in this
section is real and shipped, but its exact builder shape — the `.hub(…)`/
`.transcript(…)` wiring, `Nessy.agent()` as a no-harness one-liner — is
superseded by §17's razor-bound harness: `Nessy.harness(provider)` is now the
sole front door, `Nessy.agent()` is retired, `.hub(...)` and `.transcript(...)`
are both gone (declared listening replaces the hub; the journal is a listener,
not a knob). The typed-agent material below (`Agent<I>`, `InputRenderer<I>`,
`tell`) stands as shipped. This section is retained for the reasoning history;
§17 governs the current shape.

**Shipped status:** the `Harness` reification below shipped un-generic —
`Agent`, not `Agent<I>` — ahead of the typed-front-door decision. That is
deliberate, not a partial implementation of this section: the harness/agent
split (infrastructure vs. identity) and the type parameter are separable
decisions, and only the latter is source-breaking to retrofit later. The type
parameter itself remains **open**, gated pre-1.0 (§14), and arrives with its
own brainstorm-to-spec round covering the input vocabulary, rendering rules,
and the `tell`/`send`/tap relationship.

**Reifying the harness.** §13.1's grant principle ("infrastructure is ambient;
capability is granted; authority is declared") has been structural doctrine
without a structural home: `AgentBuilder` conflates shared infrastructure with
per-agent identity, so every agent re-declares the store, hub, and observations.
Settled: the harness becomes a first-class object.

Illustrative only — see §17 for the shipped shape (`Nessy.harness(provider)` is
required by signature, `.hub(...)`/`.transcript(...)` don't exist, and every
grant states its policy explicitly, per the tool-authority addendum):

```java
Harness harness = Nessy.harness()          // superseded: provider is required by signature (§17)
    .provider(anthropic)                   // the DEFAULT provider, not a constraint
    .store(store).hub(hub).observations(registry).transcript(journal)  // hub/transcript retired (§17)
    .build();

Agent<SupportInput> support = harness.agent(SupportInput.class)
    .model("claude-sonnet-4-5").systemPrompt("…")
    .tools(grant(lookupOrder, allow()), grant(refund, approveOver(500)))  // every grant states its policy (§17 addendum)
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
user message; the sealed `ConversationEvent` grammar is untouched — typing lives in the
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
*model* may call remains the grant list.**Typed details (settled 2026-08-10, landed):** `tell(I)` is the
only verb — `send(String)` is removed, not kept beside it (a typed front
door with an untyped side entrance isn't typed). `Conversation<I>.tell(I)`
plus the tap variant `tell(I, Consumer<ConversationEvent>)`. `InputRenderer<I>` lives
in `api.message` (`List<ContentBlock> render(I input)`); builder defaults:
a `String` vocabulary installs a pass-through renderer (raw text → one
text block), a typed vocabulary defaults the tagged-JSON renderer
(`[order_escalation]` snake_case tag + canonical JSON over the harness
mapper), both overridable via `.renderer(...)`. The sealed-switch renderer
is the documented recommended idiom. Shipped and tested end to end:
`Agent`/`Conversation`/`AgentBuilder` all carry the `<I>` parameter,
`Harness#agent(Class<I>)` joins `Harness#agent()` (`AgentBuilder<String>`,
reached via `Nessy.harness(provider).build().agent()` — §17 supersedes
`Nessy.agent()`), and every call site across the codebase converted
from `send` to `tell` — see `InputRendererTest` and `AgentFacadeTest`'s
`Typed_front_door` nested class. Deferred with intent: schema
publication into the system prompt (opt-in, later) and the agent-as-a-tool
adapter (its own plan — its shape precisely: wrapping an `Agent<I>` as ONE
tool for a parent makes that wrapper's input record `I`, because calling
the agent means telling it something from its vocabulary; it implies
nothing about any other tool's type).

## 9. Declared listening (rewritten 2026-08-10, §17 — the hub is retired)

**Superseded, in full, by §17.** The general-purpose, runtime-subscribable
`EventHub` this section originally specified shipped, then was demoted and
finally retired outright: its pluggability threatened the load-bearing
semantics (seeding order, freeze-at-build, veto-by-throw) that declared
listening now guarantees structurally instead of by convention. `EventHub`
and `HarnessBuilder.hub(...)` no longer exist in any form, public or internal.
What ships today:

```java
public interface EventEmitter {
    void emit(Object event);
    static EventEmitter noop() { … }
}

public interface ConversationEvents {
    <T> Subscription subscribe(Class<T> type, Consumer<T> listener);
}

public interface Subscription extends AutoCloseable {
    @Override void close();          // unsubscribe; idempotent
}
```

Listening now has exactly two tiers, both described fully in §17:

1. **Declared, frozen, seeded (the default tier).** `listen(Class<T>,
   Consumer<T>)` and `listenAsync(Class<T>, Consumer<T>[, Consumer<Throwable>])`
   are builder verbs on both `HarnessBuilder` and `AgentBuilder` — Prepare is a
   build-time phase, so there is no runtime `subscribe` at this level at all. A
   harness's declarations seed into every agent it builds, ahead of that
   agent's own, in declaration order; frozen at `build()`, with no mutation
   path afterward. `ListenerRegistry` (`api.event`) is the frozen chain plus
   the one dynamic view described next; `ListenerRegistration` is one
   declaration (sync or async).
2. **Conversation-local subscription (the one dynamic level).**
   `conversation.events().subscribe(type, listener) → Subscription` —
   in-memory, per-handle, non-durable, already scoped to that one
   conversation. This is the sole place runtime attach/detach still happens —
   the UI/SSE-attachment case.

Delivery order per emitted event: this conversation's dynamic subscribers
first, then the frozen chain (harness declarations, then the agent's own, in
declaration order). Three commitments carried forward from the old hub
design, now enforced by this shape rather than merely documented by it:

1. **Synchronous, in-order, same-thread by default.** Delivery dispatches
   listeners on the emitting thread, in the order above, before returning —
   the guarantee live streaming and deterministic tests rely on.
2. **A throw is a veto, and only a throw.** A synchronous listener that
   throws propagates straight out of `EventEmitter.emit` and stops delivery
   to everything after it, aborting whatever operation emitted — never a
   returned decision; approval authority still belongs exclusively to the
   `Approver`/grant chokepoint. An async declaration never gets that power:
   its listener already runs on its own virtual thread by the time delivery
   reaches it, and whatever it throws reaches its own `onError` handler
   instead (a `Logger`-backed overload needs no explicit handler).
3. **Open vocabulary, typed dispatch, no magic.** Declared events are plain
   records; dispatch is by class assignability, including registering
   directly at `ConversationEvent.class` and switching internally. The
   *reducer's* sealed `ConversationEvent` stays closed and is now emitted
   directly — no envelope (§17: `SessionEvent`/`ConversationAdvanced` is
   DELETED) — alongside the open notices `MessageAppended`, `CompactionFailed`,
   `EnrichmentFailed`, `ToolProgress` (all `ConversationScoped`, each carrying
   its own `ConversationId`).

Shipped event types: every `ConversationEvent` variant, self-attributing
(`conversationId()` on the sealed interface itself); `MessageAppended`
(`ConversationId, Message, Usage turnUsage`), emitted at the engine's newborn
choke point — the declaration point for journaling (§10.8); `ToolProgress`
(`ConversationId, String toolCallId, String message)`, emitted by tools via
`ToolContext.events()`; `CompactionFailed`/`EnrichmentFailed`
(`ConversationId, String reason`).

`nessy-testing` ships recording listeners (plain `List`-collecting
`Consumer`s) rather than a dedicated subscriber type — declared listening has
no special subscriber contract left to double for.

## 10. The SPI surface

### 10.1 ExecutionEngine

Unchanged: `run(ConversationId, ConversationEvent)` / `resume(ConversationId, ParkToken, ConversationEvent)` →
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

### 10.3 ConversationStore

Unchanged: `load` / `save` / `consumeToken`, with single-use token consumption as
the at-least-once-delivery defense. In-memory default via `ConversationStore.inMemory()`;
last-write-wins and non-evicting-token semantics documented on it.

**Incremental persistence (added 2026-08-09).** The snapshot-shaped contract does
not mandate full rewrites. `ConversationState.messages` is **append-only** — the
reducer only ever appends, never edits or removes — and this is a documented
invariant durable stores may rely on: persist the un-persisted tail plus a small
mutable header (status, counters, usage, failureReason, pending state), making
save cost O(new messages) rather than O(history). Compaction is the one
licensed violation of append-only, shipped in Plan 4; it adds a generation
marker to `ConversationState` so a store can distinguish "append the tail"
(generation unchanged) from "rewrite" (generation bumped). The load side is
addressed by compaction/`ContextBuilder` (summary + tail as the working set);
full event-sourced journaling remains a `DurableEngine`-plan question, not a
seam change.

**Amended 2026-08-09 (§10.8), superseded 2026-08-10 (§17):** history's durable
source of truth moved to an append-only journal, first specified as a
dedicated `TranscriptStore` seam; §17 deletes that type entirely — the
journal is finally and fully a declared `MessageAppended` listener, no store
type, no builder knob (§10.8's journal subsection below is superseded
accordingly). `ConversationStore` keeps its snapshot role and the semantics
above regardless; compaction's rewrite remains a working-set trim, never
information loss, because whatever journal a declared listener keeps is
unaffected by it.

### 10.4 TerminationPolicy (new, API-zone, consulted by the reducer)

v1's hard-coded consecutive-error ceiling was one termination rule wearing the
whole trenchcoat, and the unbounded-round-trip gap was flagged by the final review
and independently by an outside architecture review. Generalized:

```java
public interface TerminationPolicy {
    /** A reason to halt, or empty to continue. Pure; consulted by the reducer. */
    Optional<String> shouldHalt(ConversationState state);

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

### 10.5 Per-grant authority — `ToolGrant` and `UsagePolicy` (rewritten 2026-08-10, §17 addendum — one path for tool authority)

**This section is fully rewritten to the shipped, final shape.** Two earlier
revisions are superseded in sequence: a v1 agent-level `Policy` dispatching on
tool names, then a v2 per-grant `ToolGrant` that still allowed a *bare* grant
deriving its policy from `Tool.requiresApproval()`. The 2026-08-10 addendum
("one path for tool authority") deletes that derivation entirely:
`Tool.requiresApproval()` is DELETED — a tool is pure capability (name,
schema, execution) and carries zero authority content; whether an application
wants a human in the loop is a deployment decision no tool author can make.
The policy is now MANDATORY on every grant, with exactly one construction
path — `ToolGrant.grant(tool, policy)` — no bare grants, no derived floors, no
`.with(...)` re-dressing of an existing grant, no defaults:

```java
Agent<String> support =
    harness.agent()
        .tools(
            ToolGrant.grant(add, UsagePolicy.allow()),               // runs freely
            ToolGrant.grant(refund, approveOver(500)),                // contextual: HITL past $500
            ToolGrant.grant(deleteAccount, UsagePolicy.requireApproval())) // always a human
        .approver(slackApprover)
        .build();

Agent<String> batch =
    harness.agent()
        .tools(ToolGrant.grant(add, UsagePolicy.allow()), ToolGrant.grant(refund, UsagePolicy.allow()))
        .build();
```

- **`ToolGrant`** (`api.tool`): a `Tool` plus the `UsagePolicy` this agent
  consults for it, constructed only via the static `grant(Tool, UsagePolicy)`
  factory — the record's canonical constructor is not itself the public
  surface a caller reaches for; `grant(...)` is. `AgentBuilder.tools(...)`
  takes `ToolGrant...` — `tools(Tool...)` and `tools(ToolRegistry)` are GONE;
  every tool attachment is a grant that states its policy, or it does not
  compile.
- **`UsagePolicy`** (`api.tool`): per-grant, so it never dispatches on names —
  `PolicyDecision evaluate(ToolCall call, ConversationState state)` with the
  decision grammar intact: sealed `Allow` / `Deny(reason)` / `RequireApproval`,
  and still no `MODIFY` — silently rewriting model-proposed arguments is an
  attribution nightmare. Factories `allow()`, `requireApproval()`,
  `deny(reason)`, plus the lambda form for contextual rules over arguments and
  conversation state.
- **The compile-time fail-closed property now lives at the grant, not at the
  tool.** A grant does not exist until its authority is answered — there is no
  way to write `grant(deleteAccount)` and have anything silently derive a
  policy for it, the way `requiresApproval()` used to. The grant line is the
  complete security statement, structurally: capability and authority,
  declared together, per agent, per tool, in one reviewable place, with no
  path around it.
- The reducer/engine chokepoint is unchanged: `RequestApproval` consults the
  call's grant. Single enforcement point.
- Genuinely cross-cutting rules ("this agent never writes") are helpers that
  build a list of grants, each still stating its own policy explicitly —
  keeping even the cross-cutting rule visible at the grant sites. The Spring
  config-only path gains per-tool authority:
  `nessy.agents.support.tools: add=allow, refund=approve`.

Lands before 1.0 (the `tools(…)` signature change is breaking after).

### 10.6 Context management — the settled design (2026-08-09, Plan 4)

Two mechanisms, two layers, per the original analysis — now fully specified:

**Layer 1 — `ContextBuilder` (spi): pure projection.** `List<Message>
project(ConversationState state)`, consulted by engines wherever a `ModelRequest` is
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
`ConversationState.lastInputTokens`. At the points where the reducer would emit
`CallModel`, if `lastInputTokens >= policy.triggerTokens()` it instead emits
`Effect.Compact(messagesToSummarize, instructions)` and enters
`ConversationStatus.COMPACTING`. The engine performs it as an ordinary model call
(same provider, no tools, instrumented as `nessy.compaction`); the result
returns as `ConversationEvent.Compacted(summary)`, and the reducer replaces the summarized
prefix with one summary message, keeps the recent tail verbatim, bumps
`ConversationState.generation` (the store signal: unchanged generation → append the
tail; bumped → rewrite), and proceeds to `CallModel`.

Design rules:
- **Survivors**: summary + recent tail (the summarizing builder's `keepRecent`,
  pair-safe — the cut boundary only falls before a genuine user text turn, never
  between an assistant `tool_use` and its results, preserving the transcript
  invariant). The summary ships as a clearly-prefixed user message.
- **Failure is best-effort**: a failed summarization call emits a
  `CompactionFailed` event on the hub, feeds `ConversationEvent.CompactionSkipped(reason)`,
  and the turn proceeds uncompacted — retried naturally at the next trigger. The
  session never dies because its summarizer hiccuped; if context truly
  overflows, the existing `MAX_TOKENS`/refusal machinery fails it loudly.
- **Configuration (superseded in detail by the 2026-08-10 unbleed below;
  shape stands)**: `Compactors.summarizing(summarizer)` with builder
  knobs `triggerTokens` (default 100k), `keepRecent` (default 10),
  `.window(w, maxTokens)`; the summarizer bakes `summaryMaxTokens` (default
  2,048) and `instructions`; `Compactor.disabled()`;
  `AgentBuilder.compaction(compactor)`.
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
public interface Compactor {                       // spi.compaction — renamed & consolidated 2026-08-10
    /** Pure — the reducer consults this at CallModel decision points. */
    boolean requiresCompaction(ConversationState state);

    /** Effectful — the ENGINE performs this. May call models, may not.
     *  Decides on the ledger, transforms with the ledger in view. */
    Result compact(ConversationState state);

    record Result(List<Message> workingSet) { }   // spend stripped 2026-08-10 — see the jurisdiction rule

    static Compactor disabled() { … }
}
```

**The consolidation (ruled 2026-08-10):** the reducer talks to a
`Compactor`, full stop — everything else is construction detail of
particular compactors. `CompactionTrigger` and `CompactionPolicy` are
DELETED: the trigger was the seam's pure half wearing its own name, and
the policy bundled knobs belonging to three different owners. The knobs
move to the default's own builder —
`Compactors.summarizing(summarizer).triggerTokens(50_000).keepRecent(5).build()`
— with `.window(window, maxTokens)` carrying the 0.8 derivation, and the
declared-`contextWindow` auto-wiring unchanged. `Summarizer.summarize(Context
head)` bakes its config (instructions, summaryMaxTokens) at construction.
`AgentBuilder.compaction(Compactor)` is the single overload (the old
two-overload null-ambiguity dies with it). `Effect.Compact` becomes a bare
marker like `CallModel` — the engine hands the compactor the state it
holds; replay folds the outcome-carrying `Compacted`, so the effect needs
no payload. Compaction is SPI, not API: `api.compaction` dissolves;
grammar events stay `api`. The summarizing default STAYS IN CORE — it is
dependency-free and compaction-by-default stands on it; the module ladder
reserves `nessy-compactor-<implementation>` for algorithms that bring
dependencies (extractive/NLP, embeddings, remote services).

- **The choreography.** Reducer: `requiresCompaction` true at a decision
  point → emit the bare `Effect.Compact` marker (the engine hands the compactor
  the ledger it holds; the compactor owns where and how to shrink), enter `COMPACTING`. Engine:
  perform `compact(…)` under the `nessy.compaction` observation, validate
  the result (`Context.of(replacement)` — a pair-breaking strategy takes
  the existing best-effort failure path), feed
  `ConversationEvent.Compacted(result.workingSet())`. Reducer: apply — replace
  messages wholesale, bump `generation`, proceed to `CallModel`. **The strategy proposes; the reducer
  disposes.** A result that does not *shrink* the working set is applied
  as a skip (no bump — the reducer's belt to the engine's suspenders). A
  `Compacted` arriving while tool debt is outstanding applies as a skip too,
  regardless of shrink size — compaction only ever applies against a settled
  transcript (Controller ruling, fix round 1).
- **The jurisdiction rule (ruled 2026-08-10, superseding the morning's
  spend-is-a-bill ruling — made before the seam existed):** the ledger
  bills the LOOP's own spend — what `TurnEnded` reports for conversational
  turns. Auxiliary spend — compaction, tool-internal calls, anything a
  performer does privately — is telemetry's jurisdiction: the summarizing
  compactor instruments its own model call (usage on its span, nested
  under `nessy.compaction`), and subagent tools will get the same
  treatment. A `Usage` component on the seam's `Result` was the
  summarizing implementation bleeding through the abstraction — every
  `Usage.zero()` a truncating compactor wrote was the abstraction
  apologizing. Wallet-guard integrity survives: compaction frequency is
  coupled to loop progress, which is exactly what the guard bounds.
- **Replay hardens for free.** `ConversationEvent.Compacted` now carries the entire
  replacement working set — the *outcome*, not ingredients for re-deriving
  it. The recompute-the-cut hazard parked by Plan 4's review dissolves: a
  replayed `Compacted` reproduces state by construction.
- **The earlier pieces demote into the default strategy, not the trash.**

  Constants bake at construction; `AgentBuilder.compaction(Compactor)` is
  the single knob; the default summarizing compactor is tuned through
  `Compactors.summarizing`'s own builder — see the unbleed below for exactly
  where each knob now lives. Alternative strategies (structured-facts digest, episodic
  cuts, rebuild-from-journal) implement the seam with no grammar change —
  the grammar freezes over outcomes, which are stable, not mechanisms,
  which are not.

**The unbleed (ruled 2026-08-10): no agent-level summary knobs, ever.** An
earlier draft let `AgentBuilder` itself expose summary-shaped setters
(`summaryMaxTokens(...)`, `summaryInstructions(...)`) as sugar over the
default compactor — the agent-level surface bleeding into what belongs to
the compactor's own builder. That sugar is REJECTED: `.compaction(Compactor)`
is the ONE compaction-related method `AgentBuilder` exposes, full stop. Every
knob the default summarizing compactor has — the summary reply's token cap,
its instructions, the trigger, how many recent messages survive verbatim —
belongs to a `Summarizer` and a `Compactors.summarizing(...)` builder,
assembled explicitly and handed to `.compaction(...)` when an application
wants anything other than the build-time default:

```java
Summarizer summarizer =
    Summarizer.usingProvider(
        provider, "fake-model", 1_024, "Summarize the conversation so far, focusing on open TODOs.",
        observations);
Compactor compactor =
    Compactors.summarizing(summarizer).triggerTokens(50_000).keepRecent(20).build();

Agent<String> agent = harness.agent().model("fake-model").compaction(compactor).build();
```

`Summarizer.usingProvider(ModelProvider, String model, int summaryMaxTokens,
String instructions, ObservationRegistry)` is the production summarizer's one
construction path; a four-argument overload defaults `summaryMaxTokens` to
2,048 and `instructions` to `Summarizer.DEFAULT_INSTRUCTIONS`. **No persona
in summaries**: every request `usingProvider` builds carries an empty system
prompt — an agent's own `.systemPrompt(...)` is never forwarded to a
summarization call, even though the summarizer shares the agent's provider
and model; a persona quietly steering how its own history gets summarized was
exactly the bleed this ruling closes.

**`Compactors.window(int keepRecent)` joins `Compactors.summarizing(...)` as
a second named strategy** — a zero-spend, lossy alternative: once triggered,
it drops the working set's head at the nearest pair-safe boundary that still
leaves `keepRecent` messages verbatim, no model call, no summary. Same
trigger knobs (`triggerTokens`, `.window(window, maxTokens)`) as the
summarizing builder, so switching between them is a one-line change. Use it
when a compaction call's cost is unacceptable and losing the earliest turns
outright (rather than condensing them) is an acceptable trade.

**Build-time defaults + the warning doctrine.** An agent that never calls
`.compaction(...)` still gets a working, summarizing compactor — assembled
entirely internally by `AgentBuilder.build()` from the harness's own
provider and the agent's resolved model, plus a declared `contextWindow`
when there is one to derive the trigger from. That default is never silent:
`AgentBuilder.build()` logs a warning once per agent naming the algorithm,
the trigger, `keepRecent`, and the summarizing model, and pointing at
`.compaction(...)` to configure something else — the same doctrine
`Approver.allowAll()`'s unconfigured-approver default follows (§13.1): a
zero-config posture is fine, a *silent* one is not, because trading tokens
for fidelity is a real cost an application should choose, not inherit
without being told.

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
- **Derived**: the summarizing builder's `.window(window, maxTokens)` computes
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
`ModelRequest` and `ContextBuilder.project` speak `Context`; `Effect.Compact`/
`Compactor.compact(ConversationState)` receives the ledger; `Effect.Compact` is a bare marker (a pure
reducer must not mint a throwing type), validated as a `Context` at the
engine's compact-result check. `ConversationState.messages` stays a plain list — a mid-turn state
legitimately ends with an open `tool_use` awaiting its results; the
reducer guarantees completeness at every `CallModel`, which is where
contexts are minted. An invalid projection now fails loudly at the seam,
in-process, with a message naming the orphaned id.

**The edit algebra (thumbs-upped 2026-08-10).** `Context` owns not just the
pairing invariant but the safe edits over it — raw list surgery is where
pairing bugs breed, so user code never does any. Every verb returns a new
validated `Context`; bare verb names (JDK-immutable style: `String.strip`,
`Stream.filter`), never `with`-prefixes — withers are for record slots,
verbs are for derivations.

- Tier 1, the trusted kernel: `drop(Predicate<Message>)` — pair-atomic
  (matching either half of a tool exchange removes the exchange; invalid
  results unconstructible); `map(Function<Message, Message>)` —
  revalidating (a pairing-breaking rewrite throws naming the orphaned id);
  `enrich(ContentBlock...)` / `enrich(List<ContentBlock>)` — appends ONE
  user-role message (the carrier for non-human content, as with tool
  results).
- Tier 2, structural verbs built on the kernel: `elideToolResults(int
  keepRecentMessages)` (absorbs the former standalone projection — the
  pipeline idiom becomes `.project(ctx -> ctx.elideToolResults(2))` and
  the factory dies); `keepRecent(int n)` (sliding window at the nearest
  pair-safe boundary; unchanged when none exists); `limitTokens(long
  budget, TokenEstimator estimator)` (drops pair-safe boundaries from the
  front until the estimate fits; honestly over budget when no safe cut
  remains — `TokenEstimator`'s marquee consumer).
- Queries: `pairSafeCut`, `head`, `messages`, `tokens(estimator)`.
- **The admission rule**: a verb joins `Context` only if its correctness
  depends on the context's own structure — pairing, position, size —
  never for anything semantic.
- **Failure policy is fixed by kind, never configured**: projections are
  LOUD (pure; failure is your bug; the un-thrown redactor is a security
  hole), enrichers are SOFT (I/O; failure costs enrichment, never the
  turn). There is deliberately no loud/soft knob — the verb you bind to
  IS the declaration. Mandatory content is input, not enrichment: fetch
  it before `tell`.
- **Not verbs, on record**: redaction/masking (semantic; compose from
  `map`/`drop` — and redaction is jurisdictional: from the *model* → a
  projection; from *storage* → the codec/at-rest layer; from the *record*
  → the front door, before `tell`. A redaction feature that does not ask
  "from whom?" is theater); summarization (I/O and money —
  `Compactor`'s job on the ledger); reordering (order is meaning;
  inexpressible on purpose); raw positional insert (pairing's graveyard).

**Superseded in full by §17: `TranscriptStore` is DELETED, along with
`TranscriptEntry`, `InMemoryTranscriptStore`, `NoOpTranscriptStore`, and the
`.transcript(...)` builder knob.** The dedicated-seam design below was a real,
shipped intermediate step — first a direct engine dependency, then (the "rides
the hub" ruling just below) a hub subscriber — but §17 goes one step further
and finishes the thought: there is no store type for the journal at all.
`MessageAppended(ConversationId, Message, Usage turnUsage)` is the only
first-class thing; journaling is simply a `.listen(MessageAppended.class,
...)` (or `.listenAsync`) declaration like any other, with sync giving the
strict/veto posture this section argues for and async giving the best-effort
one. A future `nessy-store-cassandra` ships a listener class, not a store.
`MessageCodec` survives, unchanged in role, in `spi.conversation`. Retained
below for the reasoning history that led here.

> **AMENDED 2026-08-15:** superseded by the store rework's `Transcript`
> primitive and the shipped **`nessy-transcript-cassandra`** module
> (spec: `2026-08-15-cassandra-transcript-design.md`) — a `Transcript`
> implementation, not a listener; the journal-as-listener posture this
> section argued was retired when `Transcript` became first-class. The
> `nessy-store-cassandra` mentions in this document are that retired
> hypothesis, kept as reasoning history.

**`TranscriptStore` (spi.conversation) — the append-only journal (historical; deleted by §17).**

```java
public interface TranscriptStore {
    void append(ConversationId id, TranscriptEntry entry);   // a pure sink — the ONLY method

    static TranscriptStore none() { … }                 // the default: auditability is opt-in
    static InMemoryTranscriptStore inMemory() { … }     // concrete type; exposes entries(id) for tests/hosts
}

public record TranscriptEntry(Message message, Usage turnUsage) { … }
```

**The seam is a pure sink (ruled 2026-08-09).** The framework NEVER reads
the journal — there is no `read` on the interface at all. Reading is the
backing store's native business (CQL, SQL, the concrete in-memory type's
own accessor); a combined implementation may physically rebuild
`ConversationStore` snapshots from journal rows, but that is its private
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

**The journal rides the hub (re-ruled 2026-08-10, superseding the direct
feed).** The engine no longer holds a `TranscriptStore`; it emits
`MessageAppended` on the hub at the newborn choke point (§9.1), and the
journal is a *subscriber*. Wiring `.transcript(store)` on the harness is
sugar: it registers the inline journaling subscriber. **Strictness
survives, relocated**: the inline subscriber writes on the emitting thread
and lets a failed append propagate — the run fails, loudly, exactly as
before; the audit guarantee now lives in the subscriber's inline-ness
rather than in an engine dependency. An application that prefers
best-effort journaling wraps the same subscriber in the async helper —
a declared posture, per subscriber, never a default. `TranscriptStore.none()`
is retired: absence of a journal is simply the absence of a subscriber.

**At-rest encoding — `MessageCodec` (ruled 2026-08-09).** Durable stores
persist opaque bytes, never message structure: a `MessageCodec` owns the
`Message ↔ byte[]` translation. The default is `MessageCodec.json(mapper)`
— canonical JSON serialized as UTF-8 bytes — and encryption at rest is a
codec *decorator* —
`MessageCodec.encrypted(json, keyProvider)` — composing over any store
implementation rather than being rebuilt per vendor. The seam serves
`ConversationStore` equally (encrypting the journal but not the snapshots would
be theater). Key management is the application's; `nessy-core` ships no
cryptography — the encrypting codec lives with the durable-store modules
that need it. The in-memory defaults hold live objects and use no codec at
all. The journal is never on the run
hot path: loads and resumes come from `ConversationStore` snapshots exactly as
today. The journal exists for what snapshots cannot do — audit, debugging a
bad summary, re-summarizing with a better model later, and memory
extraction. With the journal as the durable source of truth for history,
compaction's state rewrite is demoted from information loss to working-set
trim. `ConversationStore` is unchanged and `generation` survives (snapshot
stores still diff by it). The exemplary durable implementation is
**`nessy-store-cassandra`**: partition key per session, clustering by append
sequence — an append-heavy write path with rare sequential reads is
precisely the workload Cassandra's storage model is built for.

**`Summarizer` (spi.compaction) — the default strategy's sub-seam.**
(Ruled 2026-08-09, names as consolidated 2026-08-10: `Compactor` in §10.6 owns compaction wholesale;
`Summarizer` survives inside the default `summarizing(…)` strategy so "same
strategy, cheaper model" never requires reimplementing cut logic.)

```java
public interface Summarizer {
    String summarize(Context head);    // config baked at construction; spend is its own span's business (2026-08-10)


}
```

(The `Summary` pair exists because of the usage ruling: the engine needs
the summarization call's spend to put on `ConversationEvent.Compacted`.)

The head handed in may begin with the previous summary message, so
summaries fold forward across recompactions instead of nesting. It returns
prose plus the call's measured usage; the summary message's format and
placement (the `SUMMARY_PREFIX` marker) live in `SummarizingCompaction`, not
the reducer, so replay determinism stays in one place (`ConversationEvent.Compacted`
carries the replacement working set and the spend, as shipped — never a raw
string). Failure is a thrown `RuntimeException`,
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

**`TokenEstimator` (api.message, moved from spi.context by the edit-algebra
amendment below — see "Packaging is by domain") — the message-level number
that models never report.**

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
`ContextBuilder` (moved from `spi` root, a free rename pre-1.0);
`spi.compaction` holds `Summarizer`; `spi.conversation` holds `ConversationStore`
and `MessageCodec` (originally alongside `TranscriptStore` too, before §17
deleted that type — the journal is a listener now, not a seam member here).
Collaborators live next to the seam they serve, the way
`spi.model` already works — with one exception: `TokenEstimator` lives in
`api.message`, beside `Context`, not in `spi.context` — the edit algebra's
`Context.tokens`/`Context.limitTokens` (§10.8) take it directly in `Context`'s
own public signature, and `api` may not depend on `spi`.

**What this amendment does not touch:** the measured trigger, the pair-safe
cut semantics (relocated, not changed), best-effort failure,
the compactor knobs, and the engine's durability contract all stand
as shipped in Plan 4.

### 10.9 The context pipeline — the Contextualize phase (vocabulary settled 2026-08-10)

The Contextualize phase is the one lifecycle phase with fully open,
Maven-style binding. Its vocabulary, in the owner's words: **compact**
happens conditionally and actually succeeds the `ConversationState` (an
Evaluate decision, not part of this pipeline); **project** creates a
projection of the working set (dropping, eliding, modifying); **enrich**
adds new messages to the projection. Memory is just a `ContextEnricher`.

```java
harness.agent(SupportInput.class)
    .context(pipeline -> pipeline
        .project(ctx -> ctx.elideToolResults(2))   // PROJECT: 0..n, pure, declaration order
        .project(redactingSecrets())
        .enrich(graphMemory)                 // ENRICH: 0..n contributors, each best-effort
        .enrich(userProfile)
        .placement(ENRICHMENTS_FIRST))       // where enrichments land
```

- **`Projection`** (`spi.context`): `Context apply(Context context)` —
  pure, total, applied in declaration order to the working set's minted
  `Context`. A projection's failure is the application's own bug and fails
  loud. Standard projections are written as lambdas over `Context`'s edit algebra (§10.8) — `ctx -> ctx.elideToolResults(2)` — proving the algebra sufficient; there are no opaque projection classes to import.
- **`ContextEnricher`** (`spi.context`): `List<Message> enrich(ConversationState
  state)` — I/O sanctioned, each contributor individually best-effort (its
  own `nessy.context.enrich` observation and `EnrichmentFailed` hub event;
  a failed enricher costs its contribution, never the turn). Contributions
  concatenate in declaration order. Memory implementations are enrichers;
  the former `spi.memory` package dissolves here — so do RAG, user
  profiles, ambient facts, anything additive.
- **Why project before enrich — jurisdiction, not sequence.** Enrichers
  key on the ledger, not the projection, so ordering costs them nothing.
  Projections govern the *transcript's* wire form; enriched material must
  be outside their reach — otherwise every projection carries a
  "don't touch the memories" clause. Project-then-enrich means projections
  see transcript only, enrichments arrive verbatim, and `placement`
  (ENRICHMENTS_FIRST default; the cache tradeoff documented) decides where
  they land.
- **Determinism by construction**: bindings are declared at build time in
  reviewable code — the POM analog — never registered at runtime through
  the hub. Declaration order is execution order; same ledger, same
  bindings, same `Context`; `agent.contextFor(id)` runs the same pipeline.
  The hub carries facts and vetoes (§9.1); the pipeline carries
  participation. They do not mix.
- The pipeline executor is engine machinery (`spi.context`), one instance
  per agent, consumed by Invoke preparation and `contextFor`. The compact
  path remains unpiped: a strategy's working set is its own business.

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
| `nessy.compaction` | `compact` | ours; semconv has no compaction concept |
| `nessy.memory.recall` | `recall` | ours; semconv has no memory-recall concept |

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
goes — the W3C `traceparent` rides in `ConversationState`, and resume opens a
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

Rows below reflect §17's final shapes: the `EventHub` row is removed (the seam
is retired, not upgraded — see §9); the journal row is now a declared
listener, not a store.

| Seam | In-core default | Upgrades Nessy provides | Extenders build |
|---|---|---|---|
| `ExecutionEngine` | `InProcessEngine` | `DurableEngine`; Temporal/Restate adapters | custom runtimes |
| `ModelProvider` | `ScriptedModelProvider` (testing) | `nessy-model-anthropic`, `nessy-model-openai`, retry decorator | any vendor |
| `ConversationStore` | `ConversationStore.inMemory()` | `nessy-store-jdbc` | Dynamo, Redis… |
| `Approver` | `allowAll()` / `denyAll()` | console; Slack/webhook | anything human-shaped |
| `TerminationPolicy` | error-ceiling + max-turns | cost budget (post-usage) | custom |
| `UsagePolicy` (per grant) | none — mandatory on every grant, stated explicitly via `ToolGrant.grant(tool, policy)` (§17 addendum; no derivation from the tool) | path/allowlist rules; upgrades are contextual lambdas | OPA, corporate policy |
| Declared listening (§9, §17) | `listen(type, listener)` sync, build-time, frozen | `listenAsync(type, listener[, onError])` per listener | bridges (SSE, message bus) via `Conversation#events()` |
| Observations | `ObservationRegistry.NOOP` | conventions + starter wiring | any Micrometer handler |
| `Context` edit algebra (§10.8) / `ContextPipeline` (§10.9) | no projections, no enrichers — the working set unchanged | `ctx -> ctx.elideToolResults(keepRecent)`, `ctx -> ctx.limitTokens(budget, estimator)` as lambda projections (shipped); stateful compaction (shipped, §10.6) | RAG, redaction, custom `Projection`/`ContextEnricher` lambdas |
| The journal (§10.8, §17) | absent — a declared `MessageAppended` listener is opt-in | `nessy-store-cassandra` ships a `MessageAppended` listener class, not a store | any listener that follows the transcript |
| `Summarizer` (§10.8) | `usingProvider(…)` — the agent's own model, no persona forwarded | cheap-model variant; extractive | remote services, custom |
| `TokenEstimator` (§10.8) | `heuristic()` (chars / 4) | tokenizer-library adapter | provider count-tokens APIs |
| `Memory` (§10.9) | `Memory.none()` | graph-backed recall | vector stores, custom retrieval |

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
   **Superseded** by §10.5 per-grant authority (`ToolGrant`/`UsagePolicy`),
   delivered in the harness plan (item 4 below) rather than as its own plan.
4. Then as previously mapped: OpenAI-wire provider, `DurableEngine` (+ trace
   continuity, + resume semantics of §6), Spring Boot starter, TUI. Compaction
   and `ContextBuilder` (§10.6) shipped in Plan 4, ahead of this sequencing.
   The §10.8 context-collaborator amendment (`Context`, `TranscriptStore`,
   `Summarizer`, `TokenEstimator`, domain packaging) is **done** — shipped and
   tested end to end (the context-collaborators convergence plan) — it
   reshapes seams the durable engine will consume, so it landed before
   `DurableEngine` as planned. **Plan 6 delivered**, the remainder of the
   2026-08-09 design session's queue except its typed front door: the
   `Harness` reification (§8.4, minus the type parameter), per-grant
   authority (§10.5), `Memory` (§10.9), and the context assembler plus
   `Agent.contextFor` (§10.10) all shipped and are tested end to end. **The
   typed front door has since landed too** (`Agent<I>`/`Conversation<I>`,
   §8.4) — its own brainstorm-to-spec round settled the vocabulary/rendering
   questions (§8.4 "Typed details"), and the build converted every call site
   from `send` to `tell` across the codebase (gate table below, now cleared).
   One standing DurableEngine note
   from the same session: pure replay is free, but replaying the imperative
   shell is not — a replayed reducer re-emits effects (the process-manager
   replay problem), so the journal must record which effects were performed
   and elide them on replay; the parked `Compacted` rulings from Plan 4's
   review are the same issue.
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
   | Compaction grammar (`Effect.Compact`, `ConversationEvent.Compacted`, `ConversationEvent.CompactionSkipped`, `ConversationStatus.COMPACTING`, `generation`/`lastInputTokens` on `ConversationState`) | sealed additions + record components | ✅ cleared — shipped and tested end to end (§10.6) |
   | `Usage` cache-token component(s) (`cachedInputTokens`) | record component; `PROMPT_CACHING` cannot report the cache-hit split without it | ✅ cleared — `Usage` is now `(inputTokens, outputTokens, cachedInputTokens)` |
   | `ModelRequest.responseSchema` | record component; structured output (`reply.as(T)`) needs a schema slot to the provider | ✅ cleared — nullable slot shipped; providers wired today ignore it; the feature itself lands post-1.0 |
   | Artifact-reference design (outputs referenced from state, not embedded) | `ContentBlock`/state shape implications | open — resolve before any coding-agent toolset ships |
   | `Context` adoption (`ModelRequest` and the pipeline speak `Context`; `Compactor.compact(ConversationState)` receives the ledger, its result validated as a `Context` at the engine) | seam signature + record component types; breaking after 1.0 | ✅ cleared — landed and tested end to end |
   | Typed front door (`Agent<I>`/`Conversation<I>`, §8.4) | retrofitting generics onto a shipped non-generic facade is source-breaking | ✅ cleared — landed; `Agent<String>` is the degenerate case behind `Nessy.agent()` |
   | Entry-event vocabulary | sealed `ConversationEvent`; every post-1.0 variant is a major | open — typed input (§8.4) is the settled direction for attribution; residue is cancellation (`RunCancelled`, a DurableEngine-plan question) and agent-to-agent delivery; audit before freeze |
   | Per-grant authority (`ToolGrant`/`UsagePolicy`, §10.5) | `tools(…)` signature change; breaking after 1.0 | ✅ cleared — shipped and tested end to end (this plan) |
   | Parallel tool execution | — | ✅ resolved as NOT a gate — needs no sealed change (multi-effect Steps + ordered feed, §10.7) |
   | `Context.systemPrompt` (system-channel dynamics) | whether the system prompt gets its own place in the `Context` edit algebra, distinct from message content, is a shape decision breaking after 1.0 | open — interpolation itself is declined (§16); this gate is specifically about a future dynamic system-channel seam, not reopening interpolation |
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
- **Declared listening misuse as a control plane**: re-scoped 2026-08-10 (§9,
  §17) — the synchronous spine *sanctions* veto-by-exception, so the
  remaining line to hold is: no return values, and approval authority never
  moves off the `Approver`/grant chokepoint. A listener may stop the world;
  it may not decide anything.

## 16. Decisions resolved in this revision

| Decision | Resolution |
|---|---|
| Who is the primary user? | Application developers, mostly Spring shops |
| How is the extension surface identified? | A literal `spi` package zone; JPMS non-export of `internal` |
| Is `Tool` API or SPI? | API — tools are the everyday programming model |
| Own instrumentation facade or Micrometer? | Micrometer Observation, adopted directly |
| Events as the observability backbone? | No — declared listening for narrative and counters (§9); Observation for spans, timers, context |
| Async or sync event delivery? | Synchronous default per listener; async is a per-listener declaration (`listenAsync`), not a hub flavor (§9, §17) |
| One front door or two? | One: `Nessy.harness(provider)` (§17 supersedes the earlier `Nessy.agent()`); engine reached through `Agent` |
| Where does history live durably? (2026-08-09; superseded 2026-08-10) | State is the working set (`ConversationStore`); the transcript itself is whatever a declared `MessageAppended` listener chooses to keep — no dedicated journal store exists (§10.8, §17) |
| Where is the tool-pairing invariant enforced? (2026-08-09) | Once, in the `Context` type, at construction (§10.8) |
| Is summarization pluggable? (2026-08-09) | Yes — `spi.compaction.Summarizer`, a many-implementations seam; summary formatting (the `SUMMARY_PREFIX` marker) lives in `SummarizingCompaction`, not the reducer (§10.8) |
| Per-message token accounting? (2026-08-09) | Models report per call only; `TokenEstimator` computes the message-level figure on demand, read-path only — the journal stores facts (`turnUsage`), never derivations (§10.8) |
| What is a harness? (2026-08-09) | The model-independent runtime an agent runs inside, defined by its eight-service contract (§1.1); reified as the `Harness` object (§8.4) |
| Where does authority attach? (2026-08-09; tightened 2026-08-10) | To the grant, exclusively — `ToolGrant.grant(tool, policy)` per agent-tool binding, policy mandatory; `Tool.requiresApproval()` is DELETED, so there is no tool-author default left to loosen or tighten (§10.5, §17 addendum) |
| Is memory a `ContextBuilder`? (2026-08-09) | No — projection is pure, recall is I/O; `Memory` is a sibling seam with its own best-effort failure policy (§10.9) |
| Are agents typed? (2026-08-09) | Yes, all of them — `Agent<I>` over an application-owned sealed vocabulary; `Agent<String>` degenerate; born pre-1.0; tools keep their own input types (§8.4) |
| Whose spend does the ledger bill? (2026-08-10, supersedes 2026-08-09) | The loop's own — `TurnEnded` for conversational turns; auxiliary spend (compaction, tool-internal) is telemetry's jurisdiction; `Compacted` carries only the working set (§10.6) |
| Is compaction pluggable? (2026-08-09; consolidated 2026-08-10) | Wholesale — `Compactor.requiresCompaction(state)` + `compact(state) → Result(workingSet)`; the compactor proposes, the reducer disposes; trigger/policy dissolved into `Compactors.summarizing`'s builder; `Summarizer` is its sub-seam (§10.6) |
| Journal append failure? (2026-08-09; relocated 2026-08-10) | Strict by default — a synchronous journal listener's throw fails the run, same as any other sync listener's veto; an application that prefers best-effort journaling declares `listenAsync` instead; there is no engine dependency left to be strict or lenient on the journal's behalf (§10.8, §17) |
| At-rest encoding? (2026-08-09) | `MessageCodec` (`Message ↔ byte[]`): JSON-as-UTF-8 default, encryption as codec decorator, serving both stores; core ships no cryptography (§10.8) |
| Is the journal readable through the seam? (2026-08-09; superseded 2026-08-10) | There is no journal seam any more to be readable or not — a declared `MessageAppended` listener is opt-in application code, not a framework-owned store (§10.8, §17) |
| Transcript vs Context? (2026-08-09; glossary re-cast 2026-08-10) | The transcript is a *concept*, not a type — whatever a declared `MessageAppended` listener keeps; `Context` is the validated wire-bound sequence; glossary §5.0 |
| Test-only interfaces: where? | Internal, unadvertised; promotion on evidence only |
| `MODIFY` policy verb? | Rejected — attribution nightmare |
| Grammar additions timing | Pre-1.0, per §7 list; frozen at 1.0 |
| Termination defaults | `anyOf(maxConsecutiveErrors(3), maxTurns(100))` |
| System-prompt interpolation? (2026-08-10) | Declined — static composes in userland (string concatenation ahead of `.systemPrompt(...)` needs no framework feature); dynamic is enrichment, and enrichment already has a seam (`ContextEnricher`, §10.9); system-channel dynamics (a system prompt that itself varies per request, not just per agent) await the `Context.systemPrompt` gate (§14) rather than being decided by default |


## 17. The conversation convergence (ruled 2026-08-10, evening)

One authoritative record of the day's final design session with the project
owner. Where any earlier section conflicts with this one, THIS section
governs; the convergence plan sweeps the body text.

**Everything centers on a Conversation.**
- The B-sweep: `SessionState` → `ConversationState`, `SessionId` →
  `ConversationId`, `SessionStore` → `ConversationStore`, `SessionStatus` →
  `ConversationStatus`; the sealed grammar `Event` → **`ConversationEvent`**
  ("anything that changes the `ConversationState`" — the owner's
  definition). The glossary updates accordingly; "session" leaves the
  vocabulary.
- **Every `ConversationEvent` declares `ConversationId conversationId()`**
  on the sealed interface, carried as a component on every variant, stamped
  at the two birthplaces (the engine's `translate()`; `tell()`).
- **The misdelivery guard**: the reducer asserts
  `event.conversationId().equals(state.id())` at the top of the fold and
  fails loudly on mismatch. Defense in depth: a fact addressed to
  conversation A can never fold into conversation B — corruption that
  would be near-impossible to diagnose at runtime is made impossible
  instead.
- **The envelope (`SessionEvent`/`ConversationAdvanced`) is DELETED.** The
  spine emits the self-attributing grammar events directly, alongside the
  open notices (`MessageAppended`, `CompactionFailed`, `EnrichmentFailed`,
  `ToolProgress` — each already carrying its `ConversationId`). Listeners
  subscribe by type — including `ConversationEvent.class` itself and
  switching internally. State-needing renderers are conversation-scoped
  and hold the handle.

**Listening is declared, scoped, and frozen.**
- Harness- and agent-level listeners are declared on the BUILDERS
  (`listen(type, listener)` / `listenAsync(type, listener[, onError])`) and
  frozen at `build()` — Prepare is a build-time phase. No runtime
  subscription at those scopes; runtime monitors delegate through a
  declared listener in userland.
- The harness SEEDS its listener declarations into every agent built from
  it (the provider-default pattern applied to listeners); delivery order:
  conversation-local, then the agent's list (harness seeds first, then the
  agent's own declarations), declaration order within each. A throw
  anywhere stops the chain — the veto is the throw, unchanged.
- **Conversation-local subscription is the one dynamic level**:
  `conversation.events().subscribe(...)` returning `Subscription` —
  in-memory, per-handle, non-durable (UI/SSE attachment). The per-`tell`
  tap's fate (sugar over this, or deleted) is the implementer's proposal,
  reviewed.
- **`EventHub` is demoted to internal delivery machinery**: the seam
  leaves the public surface and the defaults ladder (its pluggability
  threatened its own load-bearing semantics); `EventEmitter` survives for
  emitters (`ToolContext.events()`); `HarnessBuilder.hub(…)` dies.

**The harness is the idiom, reified — and razor-bound.**
- **The razor**: if a proposed harness feature could not be expressed as
  "pre-configuration of an agent builder," it does not belong on the
  harness.
- `Nessy.harness(provider)` is THE front door — the provider is the
  harness's one required thing, enforced by signature. `Nessy.agent()` is
  RETIRED.
- **Owned** (harness-only, no agent override, disjoint builders):
  provider, `ConversationStore` (default `inMemory()`), observations
  (default NOOP), `ObjectMapper` (default fresh). **Seeded** (agents may
  override/extend): `defaultModel(String)`, listener declarations.
  **Granted** (agent-only, never harness-touched): tools — no harness
  toolkit API; shared grant lists are a userland constant handed to
  chosen agents (`tools(...)` accepts collections). Capability crossing
  into an agent happens only via an explicit grant line in that agent's
  own declaration.
- **Model resolution**: agent `.model(...)`, else harness
  `defaultModel`, else **`AgentConfigurationException`** at build — a
  real, named exception type adopted for every agent build-time
  configuration failure.
- The odd-one-out agent (different store, different vendor) is a SECOND
  harness — one harness per infrastructure profile; harnesses may share
  store instances.

**The journal is a listener, finally and fully.**
- `TranscriptStore`, `TranscriptEntry`, `InMemoryTranscriptStore`,
  `NoOpTranscriptStore`, and the `.transcript(…)` knob are DELETED.
  `MessageAppended(conversationId, message, turnUsage)` is the only
  first-class thing; journaling is a listener somebody declares (sync =
  strict/veto; `listenAsync` = best-effort). A future
  `nessy-store-cassandra` ships a listener class, not a store.
  `MessageCodec` survives in `spi.session` for store modules. Tests use
  recording listeners.

**Deliberately OUT of this wave** (recorded, not smuggled): agent
`.name(…)` attribution for multi-agent observability — its vehicle (the
envelope) was deleted and the owner never ruled; open question, revisit
with the multi-agent work.

**Addendum (ruled 2026-08-10, late): one path for tool authority.**
`Tool.requiresApproval()` is DELETED — a tool is pure capability (name,
schema, execution) and carries zero authority content; whether an
application wants a human in the loop is a deployment decision no tool
author can make. The policy is MANDATORY on every grant and there is
exactly one construction path: `grant(tool, policy)` — no bare grants, no
derived floors, no `.with(...)` re-dressing, no defaults. The compile-time
fail-closed property relocates to the grant: a grant does not exist until
its authority is answered. `AgentBuilder.tools(Tool...)` dies with the
derivation — every tool attachment is a grant that states its policy. The
grant line is the complete security statement, structurally.

**Addendum (ruled 2026-08-10): the entry fact matches the verb.**
`UserSaid` → **`AgentTold`** — you `tell` the agent; the fact is that the
agent was told. Kills the false presumption that the teller is a human
(triggers include webhooks and crons). The wire's `Role.USER` is vendor
protocol and unaffected. Also ruled: logback-classic joins as the
TEST-scope logging provider (build output shows our warnings) and a
COMPILE-scope dependency of nessy-examples; the CHANGELOG's unreleased
archaeology is pruned to the final shapes only.
