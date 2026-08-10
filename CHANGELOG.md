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
  `Agent.converse()` opens a `Conversation`; `.tell(input)` returns a `Reply`
  whose `.text()` extracts the assistant's prose. Five lines gets a working
  agent; the event-level `ExecutionEngine` stays one call away via
  `Agent.engine()` for anything the facade doesn't cover.
- **The Conversation-centric grammar.** `ConversationEvent` (`api`) is the
  sealed, self-attributing fact vocabulary the reducer folds: every variant
  carries its own `ConversationId` as its first component. `AgentTold` is the
  entry fact — the agent was told something, as arbitrary content blocks
  rather than plain text; the name matches the verb (you `tell` the agent),
  and deliberately doesn't presume the teller is human, since triggers
  include webhooks and crons as well as `Conversation#tell`. The rest of the
  grammar covers a model turn end to end: `TextDelta`/`ThinkingDelta` (streamed
  chunks), `ThinkingSigned`/`RedactedThinkingArrived` (thinking-block
  round-trips), `ToolCallRequested`/`ApprovalDecided`/`ToolFinished` (the tool
  lifecycle through the one authority chokepoint), `ModelTurnEnded`, and
  `Compacted`/`CompactionSkipped`. The misdelivery guard (design §17) is
  `Reducer.reduce`'s first check: a fact addressed to one conversation can
  never fold into another's state, so a misrouted event fails loudly at the
  reducer rather than corrupting state silently.
- **One path for tool authority.** `ToolGrant.grant(Tool<?>, UsagePolicy)`
  (`api.tool`) is the sole way to attach a tool to an `AgentBuilder`: capability
  and authority, declared together, so the grant line is the complete security
  statement structurally — no bare `grant(tool)`, no derived floor, nothing to
  route around it. `UsagePolicy` is the engine's one authority chokepoint,
  consulted before every tool call; a policy that throws or returns `null`
  fails closed.
- **Declared listening + `ListenerRegistry`.** `HarnessBuilder`/`AgentBuilder`
  both expose `listen(Class<T>, Consumer<T>)` and `listenAsync(Class<T>,
  Consumer<T>[, Consumer<Throwable>])`, frozen at `build()` — an agent-wide
  observer is a build-time declaration, never a runtime-attachable
  subscription. A harness's declarations seed every agent it builds, in
  order, before that agent's own. `Conversation#events()` is the one dynamic
  listening level: a `ConversationEvents` already scoped to that one
  conversation, so nothing subscribed through it ever sees another
  conversation's traffic; `Conversation#tell(input, tap)` is sugar over a
  subscription wired for the call's duration. Delivery order per event: this
  conversation's dynamic subscribers, then the frozen chain
  (harness-then-agent). A throw from a synchronous listener, at either tier,
  propagates out and aborts the call that emitted — the veto is the throw; an
  async listener runs off the emitting thread and never gets that power.
- **The context pipeline + the `Context` edit algebra.** `.context(pipeline ->
  pipeline.project(...).enrich(...).placement(...))` on `AgentBuilder` wires
  the Contextualize phase (design §10.9): `Projection` (pure, total,
  `Context apply(Context)`) runs in declaration order over the `Context`
  minted from the conversation's messages, then `ContextEnricher` contributors
  (I/O, independently best-effort, emitting `EnrichmentFailed` on failure)
  concatenate in, placed by `ContextPipeline.Placement` relative to the
  projected transcript. `Context` (`api.message`) owns the pairing
  invariant's safe edits so raw list surgery never happens in application
  code: the trusted kernel is `drop(Predicate<Message>)` (pair-atomic),
  `map(Function<Message, Message>)` (revalidating), and
  `enrich(ContentBlock...)`; built on that kernel are `elideToolResults(int)`,
  `keepRecent(int)`, and `limitTokens(long, TokenEstimator)`. `Agent.contextFor
  (ConversationId)` answers "what would a call against this conversation see right now"
  through the exact same pipeline instance the engine consults, so the
  preview and the real thing can never drift apart.
- **`Compactor`/`Compactors`.** `Compactor` (`spi.compaction`) is the one
  compaction seam: `requiresCompaction(ConversationState)` (pure) and
  `compact(ConversationState)` (effectful, engine-only) — the compactor proposes a
  replacement working set, the reducer disposes. `Compactors.summarizing(...)`
  is the default, assembled automatically from the harness's own provider
  unless `.compaction(Compactor)` replaces it outright: triggers once measured
  input tokens cross a threshold (100k by default, or derived from a declared
  `contextWindow`), summarizes the pair-safe head through a `Summarizer`, and
  keeps the trailing `Compactors.SummarizingBuilder.DEFAULT_KEEP_RECENT`
  messages (10) verbatim. `Compactors.window(int keepRecent)` is the
  zero-spend, lossy alternative — same trigger knobs, no model call, no
  summary. An unconfigured compactor logs a warning naming exactly what
  defaulted, once per agent `build()` (design §13.1). Compaction stays
  best-effort: a failed summarization call skips that turn's compaction and
  emits `CompactionFailed` rather than failing the turn. The jurisdiction
  rule keeps a compactor's own spend out of `ConversationState.usage()` — the
  ledger bills only the loop's own conversational turns; a compactor's cost
  is telemetry's business, instrumented as its own `nessy.model.call`
  observation nested under `nessy.compaction`.
- **The journal is a listener.** There is no dedicated journal type: a
  transcript is `.listen(MessageAppended.class, journal::add)` on either
  builder — sync for audit-grade (a throwing listener fails the run, veto-by-
  throw) or `.listenAsync(...)` for best-effort — with no sentinel for "no
  journal," since the absence of a declaration already says that.
  `MessageAppended(conversationId, message, turnUsage)` fires for every
  message at birth, including compaction summaries (at `Usage.zero()`, since
  the jurisdiction rule keeps a compactor's spend off the ledger there too).
- **`MessageCodec`** (`spi.conversation`) — the `Message ↔ byte[]` translation
  a durable `ConversationStore` needs to persist opaque bytes rather than
  message structure. Default is `MessageCodec.json(mapper)`; encryption at
  rest composes as a codec decorator over any store.
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
  approver or compactor falling back to a default, an async listener's
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
  phase the engine can see — `nessy.run`, `nessy.turn`, `nessy.model.call`,
  `nessy.tool.call`, `nessy.approval.wait`, `nessy.compaction`,
  `nessy.context.enrich` — as stable metric names, with span names following
  the OpenTelemetry GenAI *agent* conventions. Wired via `.observations(...)`;
  default is `ObservationRegistry.NOOP`.
- **`nessy-examples`** — a runnable two-provider demo: `DemoAgent` wires an
  ungated `AddTool` and an approval-gated `ClockTool` behind a
  `ConsoleApprover`, with `AnthropicChat` and `OpenAiChat` mains demonstrating
  the raw-event and per-tell-tap streaming patterns respectively.
- **Time-ordered UUIDs (v7)** — conversation and park identifiers are time-ordered
  UUIDv7, sortable by creation time and index-friendly in durable stores.
- Tests read as prose: method names are `snake_case` sentences, related
  scenarios group into `@Nested` classes, and the underscore-to-space
  display-name generator is configured module-wide, so a failing report reads
  `TerminationPolicyTest ▸ Max turns ▸ halts at the ceiling and not below`.
- Coverage reporting via JaCoCo (report-only; no gate yet).
