# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nessy has not yet made a public release. The API is unstable and may change
without notice until the 1.0.0 release.

## [Unreleased]

This release converges the codebase on the v2 design: the engine is reorganized
around who reads which package, the public vocabulary is renamed to its final
form, listeners give way to a typed event hub, the sealed grammar picks up the
variants providers will need, termination becomes a policy instead of a
hard-coded number, spans and metrics are wired through Micrometer, and a small
facade puts the whole thing behind five lines. Every change below is a rename,
an addition, or new instrumentation — no existing behavior of the reducer
changed.

### Added

- **`Context`** (`api`) — the pairing invariant's single home: an immutable,
  validated message sequence bound for the wire, whose construction rejects an
  orphan `tool_use`/results pair. `ModelRequest` and `ContextBuilder.project`
  speak `Context` now instead of a plain `List<Message>`; `Effect.Compact` and
  `CompactionStrategy.compact` carry the working set as `List<Message>` (a
  pure reducer must not mint a throwing type), validated as a `Context` at the
  engine's compact-result check.
  Pair-safe cutting and head/tail slicing (`Context.pairSafeCut(int)`,
  `Context.head(int)`) live on the type, so the reducer, the default
  summarizer, and any custom projection share one implementation of "where may
  I cut?".
- **`CompactionStrategy`** (`api`) — compaction's decision and transformation
  unify behind one seam: `requiresCompaction(SessionState)` (pure, consulted
  by the reducer at every `CallModel` decision point) and
  `compact(List<Message>)` (effectful, performed by the engine only). The
  strategy proposes a replacement working set and what producing it cost; the
  reducer disposes — applying the result, bumping `generation`, and treating a
  non-shrinking result as a skip. The built-in `summarizing(policy,
  summarizer)` strategy (`spi.compaction.CompactionStrategies`) is the
  `CompactionPolicy`-tuned default `AgentBuilder` assembles automatically;
  `AgentBuilder.compaction(...)` now overloads on `CompactionPolicy` (tune the
  default) versus `CompactionStrategy` (replace the mechanism wholesale, wins
  outright even over an earlier policy call).
- **`CompactionTrigger`** (`api`) and declared context windows —
  `CompactionTrigger` is the pluggable decision half of `CompactionPolicy`:
  `atTokens(trigger)`, `forWindow(window, maxTokens)` (≈ 0.8 × (window −
  maxTokens), reserving room for the reply), and `never()`. `ModelSettings`
  gains an optional `contextWindow`, set via `.model(name).contextWindow(n)`
  on the builder; when declared and no explicit `CompactionPolicy` is set,
  `AgentBuilder.build()` derives the trigger from it automatically, so a
  small-window model no longer relies solely on the loud-overflow backstop.
- **Complete usage accounting for compaction** — `CompactionStrategy.Result.spend`
  is a bill, not an excluded side channel: whatever a strategy's own call
  costs (a summarizer's input/output tokens; `Usage.zero()` for a
  non-LLM strategy) is accumulated into `SessionState.usage()` alongside every
  conversational turn, via `Event.Compacted(workingSet, spend)`. This repeals
  the earlier cost-accounting exclusion, under which the compaction call's
  tokens never reached the ledger.
- **`Summarizer`** (`spi.compaction`) — the default strategy's sub-seam:
  `summarize(Context head, CompactionPolicy policy) -> Summary(text, usage)`.
  Lets "same strategy, cheaper model" swap in without reimplementing cut
  logic. The default, `Summarizer.usingProvider(provider, config)`, is the
  tool-free, policy-bound summarization call the engine always performed
  before this seam existed; `AgentBuilder.summarizer(...)` overrides it,
  ignored once `.compaction(CompactionStrategy)` replaces the mechanism
  outright. `ScriptedSummarizer` ships in `nessy-testing` beside the other
  test doubles.
- **`TranscriptStore` and `TranscriptEntry`** (`spi.session`) — an append-only
  journal of a session's entire message history, independent of what
  compaction keeps in the working set. A pure sink (`append` is the only
  method — the framework never reads its own audit log); default is
  `TranscriptStore.none()`, so retention stays opt-in and zero-config memory
  bounds are untouched. Once wired via `.transcript(...)` on the builder, the
  journal is strict: an append that throws fails the run outright, the same
  as a failing model call. `TranscriptStore.inMemory()` ships an
  `InMemoryTranscriptStore` with a test/host-facing `entries(id)` reader.
  `TranscriptEntry(message, turnUsage)` carries each message's exact cost —
  an assistant turn's own usage, a compaction summary's spend, or
  `Usage.zero()` for everything else.
- **`MessageCodec`** (`spi.session`) — the `Message ↔ byte[]` translation a
  durable `TranscriptStore` needs to persist opaque bytes rather than message
  structure. Default is `MessageCodec.json(mapper)`; encryption at rest is
  meant to compose as a codec *decorator* over any store, not a per-vendor
  reimplementation.
- **`spi.context` and `spi.compaction` packaging** — collaborators now live
  next to the seam they serve: `spi.context` holds `ContextBuilder` (moved
  from `spi` root) and the new `TokenEstimator`; `spi.compaction` holds
  `Summarizer`. `TokenEstimator.estimate(Message)` (default `heuristic()`,
  content characters / 4) manufactures the per-message token figure no
  provider reports, computed on demand on the read path only — never
  journaled, so a frozen estimate can't rot the permanent record.
- **`ModelRequest` carries `Context`** — `ModelRequest.context()` replaces the
  plain message list it used to carry, so every provider now receives the
  same validated, pairing-legal sequence the rest of the read path already
  guarantees.
- **`CompactionPolicy`** (`api`) — the default strategy's knob bundle:
  `CompactionTrigger trigger`, `keepRecentMessages`, `summaryMaxTokens`,
  `instructions`. `CompactionPolicy.defaults()` (`CompactionTrigger.atTokens(100_000)`,
  keep 10 messages, 2,048-token summary cap) is what
  `CompactionStrategies.summarizing(policy, summarizer)` runs unless
  overridden; `CompactionPolicy.disabled()` (`CompactionTrigger.never()`)
  turns compaction off. Compaction stays best-effort — a failed
  summarization call skips compaction for that turn rather than failing it,
  and emits `CompactionFailed` on the hub — and is instrumented via the
  `nessy.compaction` observation alongside the engine's other spans.
- **`ContextBuilder`** — a seam that projects `SessionState` into the messages
  one model call actually sees, independent of what compaction stores.
  `ContextBuilder.identity()` (the builder default) hands over the transcript
  unchanged; `ContextBuilder.elidingToolResults(keepRecentMessages)` replaces
  the content of older tool results with a placeholder while keeping the
  recent window verbatim, trading prompt-cache hits for context space. Wired
  via `.contextBuilder(...)` on the `Agent` builder.
- **`Usage.cachedInputTokens`** — the third component of `Usage`, reporting the
  cache-hit split of a turn's input tokens now that both live providers can
  report it.
- **`ModelRequest.responseSchema`** — a nullable JSON-Schema slot on
  `ModelRequest` reserved for structured output (`reply.as(T)`); the slot
  ships now, the feature lands post-1.0, and providers wired today ignore it
  entirely.
- **`nessy-model-anthropic` and `nessy-model-openai`** — real, live-validated
  model providers wrapping each vendor's own Java SDK: native request assembly,
  streaming translation, thinking/caching/usage accounting, and an executable
  `StopReason` mapping that fails loudly on any wire value the audit didn't
  enumerate rather than guessing at it. Both `Builder.fromEnv()` delegate to the
  underlying SDK's own environment support (`ANTHROPIC_BASE_URL`,
  `OPENAI_BASE_URL`, auth tokens, etc.), not a hand-rolled subset, and both
  builders take an explicit `baseUrl(...)` for OpenAI-compatible endpoints
  (OpenRouter, Ollama, …). OpenAI's live suite is fully green against a real
  key; Anthropic's is live-validated too, including the empty-system fix — one
  real bug the live run surfaced (an empty system block rejected by the API),
  now fixed and pinned by regression tests.
- **`RetryingModelProvider`** — a decorator that retries only the opening of a
  model stream, with exponential backoff (`RetryPolicy`); each provider module
  publishes its own retryable-failure predicate
  (`AnthropicModelProvider.RETRYABLE`, `OpenAiModelProvider.RETRYABLE`), since
  which failures are safe to retry is provider-specific.
- **`nessy-examples`** — a runnable two-provider demo: `DemoAgent` wires an
  ungated `AddTool` and an approval-gated `ClockTool` behind a
  `ConsoleApprover`, with `AnthropicChat` and `OpenAiChat` mains demonstrating
  the raw-event-hub and per-send-tap streaming patterns respectively.
- **`Conversation.send(String, Consumer<Event>)`** — a per-send tap alongside
  the existing `send(String)`: scoped to this conversation's events only,
  delivered synchronously in order, and closed automatically when `send`
  returns. The SSE-friendly path for pushing a single reply's events without
  subscribing to the raw hub.
- **Pre-1.0 grammar completion II**: `StopReason.REFUSAL`, thinking-block
  signatures (`Event.ThinkingSigned`, `ThinkingBlock.signature`), and redacted
  thinking (`RedactedThinkingBlock`) round out the vocabulary both live
  providers need.
- **Coverage reporting via JaCoCo** (report-only; no gate yet).
- **Time-ordered UUIDs (v7)** — Session and park identifiers are now time-ordered
  UUIDv7 (sortable by creation time, index-friendly in durable stores).
- **The `Agent` facade** — `Nessy.agent().provider(...).model(...).tools(...).build()`
  is now the framework's one front door. `Agent.converse()` opens a `Conversation`;
  `Conversation.send(String)` returns a `Reply` whose `text()` extracts the
  assistant's prose. The event-level `ExecutionEngine` API remains one method
  away via `Agent.engine()` and `Agent.events()` — the facade adds no new
  semantics, only sugar over it.
- **The event hub**, replacing per-object listeners. `EventHub`/`EventEmitter`
  let any component emit and any subscriber declare interest by type;
  dispatch is synchronous, in-order, and same-thread by default, and a
  subscriber's exception can never affect execution. Ships `SessionEvent`
  (every reduced loop event) and `ToolProgress` (long-running tools reporting
  through `ToolContext.events()`). `nessy-testing` ships `RecordingSubscriber`.
- **`TerminationPolicy`**, replacing the hard-coded consecutive-error ceiling.
  A pure `shouldHalt(SessionState)` the reducer consults before every model
  call, with `maxTurns`, `maxConsecutiveErrors`, `anyOf`, and `never` factories.
  Default is `anyOf(maxConsecutiveErrors(3), maxTurns(100))` — a
  wallet-guarding ceiling raised deliberately, not discovered involuntarily.
- **Micrometer Observation instrumentation** of the phases the engine can see:
  `nessy.run`, `nessy.turn`, `nessy.model.call`, `nessy.tool.call`, and
  `nessy.approval.wait` as stable metric names, with contextual (span) names
  following the OpenTelemetry GenAI *agent* conventions (`invoke_agent`,
  `chat {model}`, `execute_tool {tool}`). Wired via `.observations(...)` on the
  builder; default is `ObservationRegistry.NOOP`.
- **Pre-1.0 grammar completion**: `ContentBlock.ThinkingBlock` and
  `RedactedThinkingBlock` for extended-thinking round-trips, `ContentBlock.ImageBlock`
  for `Capability.IMAGE_INPUT`, streamed thinking deltas, and
  `Usage`/`ModelEvent.TurnEnded` for real token accounting and future
  cost-budget termination policies.

### Changed

- **`AgentBuilder.compaction(...)` source-compat note (pre-1.0 breaking)** —
  adding the `CompactionStrategy` overload alongside the existing
  `CompactionPolicy` one means `.compaction(null)` no longer resolves: the
  call is now ambiguous between the two overloads and requires an explicit
  cast, e.g. `.compaction((CompactionPolicy) null)`. Source using the
  single-overload form to explicitly pass a null policy must add the cast.
- **Zones**: the codebase is reorganized from `org.jwcarman.nessy.core.*` into
  `org.jwcarman.nessy` (front door), `.api` (application developers: `Tool`,
  `Approver`, the message/event grammar), `.spi` (infrastructure extenders:
  `ExecutionEngine`, `ModelProvider`, `SessionStore`), and `.internal`
  (unadvertised machinery). The rule: if writing an agent requires it, it's
  API; if hosting agents requires it, it's SPI.
- **Renamed for their final form** (v1 → v2):

  | v1 | v2 |
  |---|---|
  | `org.jwcarman.nessy.core.*` | dissolved into `api` / `spi` |
  | `Nessy` in `.engine` | `Nessy` at root |
  | `Builder.model(ModelProvider)` + `.modelName(String)` | `.provider(ModelProvider)` + `.model(String)` |
  | `MapToolRegistry` | package-private behind `ToolRegistry.of(...)` |
  | `ApproveEverything` / `DenyEverything` | package-private behind `Approver.allowAll()` / `denyAll(reason)` |
  | `AgentConfig` | `ModelSettings` in `spi.model` |
  | `AgentEventListener` | deleted — replaced by the event hub |
  | `RecordingEventListener` | `RecordingSubscriber` (nessy-testing) |
  | `ToolInvoker`, `Schemas` | moved to `internal` |
  | `Reducer(int maxConsecutiveErrors)` | `Reducer(TerminationPolicy)` |
  | `Reducer.withDefaults()` | `Reducer.defaults()` |
  | `SessionId.random()` | `SessionId.generate()` |
  | `ParkToken.random()` | `ParkToken.generate()` |

- **`Reducer.defaults()`, `SessionId.generate()`, `ParkToken.generate()`** — renamed
  from `withDefaults()`/`random()`. The identifiers mint time-ordered UUIDv7
  values now, not random ones; the old names claimed a property that no
  longer holds, and `defaults()` matches the factory idiom already used by
  `TerminationPolicy.defaults()`.

- **Tests read as prose**: method names are `snake_case` sentences, related
  scenarios group into `@Nested` classes, and the underscore-to-space
  display-name generator is configured module-wide, so a failing report reads
  `TerminationPolicyTest ▸ Max turns ▸ halts at the ceiling and not below`.
- JPMS module descriptor withdrawn: white-box tests (same-package,
  reflectively instantiated by JUnit) fail on the module path in IDEs
  (`InaccessibleObjectException ... does not "opens" ... to
  org.junit.platform.commons`), and the fixes — per-developer IDE config or
  test-only `opens` in the production descriptor — cost more than the
  descriptor buys. Both jars carry `Automatic-Module-Name`
  (`org.jwcarman.nessy.core` / `org.jwcarman.nessy.testing`); the
  api/spi/internal boundary stands on package convention until revisited
  pre-1.0.
- `SessionState.pendingResults()` narrowed from `List<ContentBlock>` to
  `List<ToolResultBlock>`, the only variant it ever holds.
