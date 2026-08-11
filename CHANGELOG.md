# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nessy has not yet made a public release. The API is unstable and may change
without notice until the 1.0.0 release.

## [Unreleased]

Nessy has never had a public release — nothing below is a breaking change
against any shipped version, because none exists. This section describes the
framework's current shape, once, in its final vocabulary, rather than the
sequence of renames and interim shapes that produced it.

### Added

- **The front door.** `Nessy.harness(ModelProvider)` builds a `Harness`: the
  infrastructure an application shares across every agent it builds — model
  provider, conversation store, observation registry, object mapper, declared
  listeners — assembled once. `Harness#agent()` (untyped, `Agent<String>`) /
  `Harness#agent(Class<I>)` (typed) then return an `AgentBuilder` seeded with
  that infrastructure, ready for one agent's identity: model, system prompt,
  tools, policies. The razor is structural: the provider, store, observation
  registry, and mapper are the harness's **alone**, never overridable per
  agent — an agent that needs different infrastructure is a second harness,
  never an `AgentBuilder` override. The model is the one **seeded** (not
  owned-outright) piece: `HarnessBuilder#defaultModel(String)` is the
  harness-wide fallback, and an agent's own `.model(...)` wins whenever both
  are set. Tools are **granted**, not owned or seeded — see `ToolGrant` below.
  `Agent.converse()` opens a `Conversation`; `.tell(input)` (or `.tell(input,
  TurnObserver)` to narrate the segment live) returns a `RunOutcome` —
  `Completed` or `Parked` — carrying the settled `ConversationState`. Five
  lines gets a working agent; there is no separate event-level engine handle
  to reach for — the loop and its `Memory` are the whole story.
- **The core loop: two effects, four facts, one fold.** The whole of an
  agent's semantics lives in one place now — `ConversationState.fold
  (ConversationEvent)` (`api.conversation`), a pure, parameter-free method on
  the state it folds, exhaustive over a **sealed four-fact grammar**
  (`ConversationEvent`, `api`): `AgentTold` (the entry fact — arbitrary
  content blocks, not presumed to come from a human, since triggers include
  webhooks and crons as well as `Conversation#tell`), `ModelResponded` (the
  model's whole settled contribution — message, stop reason, usage — one fact
  per call), `ModelCallFailed` (fate, not data: nothing is left in the
  dialogue to answer a call that failed outright), and `ToolFinished` (one
  piece of homework settled). Every variant self-attributes its
  `ConversationId` as its first component, which is what lets `fold` reject a
  fact addressed to one conversation but folded into another's state — the
  misdelivery guard (design §17) runs before the switch, and the switch
  itself carries no `default` arm anywhere in `nessy-core`. `fold` answers
  with a `Step` (state, the messages born this fold, effects to perform) —
  and there are only **two** effects now (`Effect`, sealed): `CallModel`
  (a singleton) and `ExecuteTool(ToolCall)`, no separate approval or
  compaction effects to sequence. `EffectExecutors` (`spi.execute`) is the
  two-slot record the loop performs against — `callModel(ModelCallExecutor)`,
  `toolCall(ToolCallExecutor)`, each `.execute(...)` returning
  `Awaited<ConversationEvent>` — implemented by `ProviderModelCallExecutor`
  (recalls from `Memory` and talks to the `ModelProvider`; the loop is the one
  that tells `Memory` the birth) and
  `GatedToolCallExecutor` (the one door into a tool call: policy, then the
  approval gate, then the invocation, folded into a single executor rather
  than three engine-sequenced steps). `TerminationPolicy` moved to the loop
  itself, consulted after every fold rather than owned by a retired engine
  type; a halt closes out any open homework with abandoned-error results
  through `ConversationState#halted(String)` before failing, the same closure
  every fatal-stop-reason path reuses. `ConversationState.modelCalls`
  (renamed from `turns`) is the field `TerminationPolicy` actually bounds —
  the count of model calls completed within one `tell`-to-clean-response
  episode.
- **`Memory` — the content jurisdiction.** `Memory` (`spi.memory`) is the one
  seam that owns what a model call sees: told everything, in order — the
  user message when `AgentTold` folds, the assistant message when
  `ModelResponded` folds, and the batched tool-results message when the last
  pending call clears — a closed list of exactly three message-grade
  tellings (see `ConversationLoopTest`'s `Clean_response` and
  `Homework_round_trip` nested classes, which assert `memory.remembered()`
  directly against that shape). `Memory#recall(ConversationId)` answers with
  the finished `Context` the next model call will see; retention is the
  implementation's own business (transcribe, summarize, checkpoint, embed,
  discard) as long as `recall` returns a legal `Context` and the
  tool-exchange transaction is never split or reordered. `ListMemory` is the
  floor default — remembers everything verbatim, consecutive-duplicate
  idempotent for at-least-once redelivery — and `AgentBuilder#memory(Memory)`
  replaces it outright. This one seam absorbs what used to be three separate
  ones: compaction, the context pipeline, and (for token-aware retention)
  the declared `contextWindow` dial. `contextWindow` itself is unchanged and
  deliberately still just a declared, unconsumed setting on `ModelSettings`/
  `AgentBuilder` — the reservation for a future token-aware `Memory`
  implementation to read, not a promise this generation redeems.
- **`TurnEvent` + `TurnObserver` — live narration, not record.** `TurnEvent`
  (`api.turn`, sealed) is the roster a sitting consumer needs to tell one
  turn's story as it happens, without any of it ever folding into
  conversation state: `TextDelta`/`ThinkingDelta` (streamed chunks),
  `RedactedThinking` (an opaque signed-thinking block, complete), and the
  tool trio `ToolCallRequested`/`ToolCallDecided`/`ToolCallCompleted`. Core
  switches over `TurnEvent` are exhaustive with no `default` arm; extender
  code is advised to carry one for forward tolerance across majors.
  `TurnObserver` is the single-method sink (`void on(TurnEvent)`,
  `TurnObserver.noop()` the default) bound at `Conversation#tell(input,
  observer)` — the observer sees only that call's segment, in order,
  independent of whatever `Memory` and the fact log separately retain.
- **One path for tool authority.** `ToolGrant.grant(Tool<?>, UsagePolicy)`
  (`api.tool`) is the sole way to attach a tool to an `AgentBuilder`: capability
  and authority, declared together, so the grant line is the complete security
  statement structurally — no bare `grant(tool)`, no derived floor, nothing to
  route around it. `UsagePolicy` is the engine's one authority chokepoint,
  consulted before every tool call; a policy that throws or returns `null`
  fails closed. A policy that defers to a human raises `ApprovalRequested`
  (`api.event`) on the system channel before the approver is even asked,
  narrates the verdict as a `TurnEvent.ToolCallDecided` once it lands, and —
  parking aside — never leaves the tool-call executor that raised it.
- **Declared listening + `ListenerRegistry`.** `HarnessBuilder`/`AgentBuilder`
  both expose `listen(Class<T>, Consumer<T>)` and `listenAsync(Class<T>,
  Consumer<T>[, Consumer<Throwable>])`, frozen at `build()` — an agent-wide
  observer is a build-time declaration, never a runtime-attachable
  subscription. A harness's declarations seed every agent it builds, in
  order, before that agent's own. `Conversation#events()` is the one dynamic
  listening level: a `ConversationEvents` already scoped to that one
  conversation, so nothing subscribed through it ever sees another
  conversation's traffic. Delivery order per event: this conversation's
  dynamic subscribers, then the frozen chain (harness-then-agent). A throw
  from a synchronous listener, at either tier, propagates out and aborts the
  call that emitted — the veto is the throw; an async listener runs off the
  emitting thread and never gets that power. The four `ConversationEvent`
  facts and `ApprovalRequested` both ride this same system channel, so
  `.listen(ConversationEvent.class, ...)` is the declaration point for a
  fact-grade journal now that there is no dedicated `MessageAppended`
  broadcast to subscribe to instead.
- **The typed front door.** Every agent is `Agent<I>` over an
  application-owned input vocabulary, typically a sealed interface of
  records; `Harness#agent()` is the degenerate `Agent<String>` case.
  `Conversation<I>.tell(I)` (plus the tap overload) is the only way to advance
  a conversation. `InputRenderer<I>` (`api.message`) does the rendering:
  `InputRenderer.text()` is the pass-through `String` default, and
  `InputRenderer.json(ObjectMapper)` — the default for any richer vocabulary —
  renders a `[snake_case_simple_name]` tag line plus canonical JSON.
  `AgentBuilder#renderer(InputRenderer<I>)` overrides either; a
  sealed-switch renderer is the recommended idiom for anything past tagged
  JSON. A renderer that returns a null/empty block list, or throws, fails
  `tell()` outright, before the engine ever sees the call.
- **SLF4J + Logback.** `nessy-core` logs through `org.slf4j:slf4j-api`
  directly (not merely a transitive dependency); every module's test
  classpath carries `ch.qos.logback:logback-classic` as its SLF4J provider,
  managed once at the parent, so a build's own warnings — an unconfigured
  approver falling back to a default, an async listener's
  failure — actually render during `mvn verify` instead of vanishing into an
  unconfigured binding. `nessy-examples` carries `logback-classic`
  compile-scope instead of test-scope, and ships its own `logback.xml`: an
  app picks its own logging provider rather than inheriting the build's.
- **Real, live-validated model providers.** `nessy-model-anthropic` and
  `nessy-model-openai` wrap each vendor's own Java SDK: native request
  assembly, streaming translation, thinking/caching/usage accounting, and an
  executable `StopReason` mapping that fails loudly on any wire value the
  audit didn't enumerate. `Builder.fromEnv()` on both delegates to the
  underlying SDK's own environment support; both take an explicit
  `baseUrl(...)` for OpenAI-compatible endpoints (OpenRouter, Ollama, …).
  `RetryingModelProvider` decorates either with exponential-backoff retry of
  the stream's opening only, over a provider-specific retryable-failure
  predicate.
- **Observability.** Micrometer `Observation` instrumentation covers every
  phase the loop can see — `nessy.run`, `nessy.model.call`, `nessy.tool.call`,
  `nessy.approval.wait` — as stable metric names, with span names following
  the OpenTelemetry GenAI *agent* conventions. `nessy.turn` never existed as
  its own span (a turn is narrated via `TurnEvent`, not observed as a
  phase), and `nessy.compaction`/`nessy.context.enrich` retired along with
  the seams they measured — compaction and context assembly are `Memory`'s
  internal business now, off the harness's own observation surface. Wired
  via `.observations(...)`; default is `ObservationRegistry.NOOP`.
- **`nessy-examples`** — a runnable two-provider demo: `DemoAgent` wires an
  ungated `AddTool` and an approval-gated `ClockTool` behind a
  `ConsoleApprover`. `AnthropicChat` and `OpenAiChat` both narrate a turn live
  via a `TurnObserver` handed to `Conversation#tell(input, observer)` —
  assistant prose as it streams, homework as it's requested; `AnthropicChat`
  additionally taps `Conversation#events()` for the fact-log side of the
  story, subscribing `ConversationEvent.ToolFinished` to print which tool
  just settled, so the two mains demonstrate the observer and the
  fact-tapping paths rather than the same pattern twice.
- **What this generation retired.** The two-effect/four-fact core loop above
  replaced a wider grammar and a multi-object engine split: the standalone
  `Reducer` and `ExecutionEngine` types dissolved into `ConversationState
  .fold` plus the loop and its `EffectExecutors`; the compaction and
  context-pipeline seams (`Compactor`, `Compactors`, `Summarizer`,
  `ContextPipeline`, `Projection`, `ContextEnricher`) absorbed into `Memory`;
  the front-door `Reply` type retired in favor of `RunOutcome`, with no
  `.text()` extraction method surviving it (narrate text via `TurnObserver`,
  or read `Memory`/the fact log instead); and the `MessageAppended` broadcast
  and its `MessageCodec`/`JsonMessageCodec` at-rest encoding retired with the
  dedicated transcript-store family, superseded by `ConversationEvent`
  arriving on the same declared-listening channel every other fact does.
  Nothing here was ever public, so this is not a deprecation — it is the
  shape settling before the first release.
- **Time-ordered UUIDs (v7)** — conversation and park identifiers are time-ordered
  UUIDv7, sortable by creation time and index-friendly in durable stores.
- Tests read as prose: method names are `snake_case` sentences, related
  scenarios group into `@Nested` classes, and the underscore-to-space
  display-name generator is configured module-wide, so a failing report reads
  `TerminationPolicyTest ▸ Max turns ▸ halts at the ceiling and not below`.
- Coverage reporting via JaCoCo (report-only; no gate yet).
