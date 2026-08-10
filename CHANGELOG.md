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

- **Stateful compaction** — `CompactionPolicy` (`triggerTokens`,
  `keepRecentMessages`, `summaryMaxTokens`, `instructions`) is wired into
  `Reducer` and `InProcessEngine`: once `SessionState.lastInputTokens()` (the
  model's own measured input-token count) reaches the trigger, the reducer
  summarizes everything but the most recent messages, cuts only on a
  message-pair boundary, and bumps `SessionState.generation()`. Default is
  `CompactionPolicy.defaults()` (100,000 trigger, keep 10 messages, 2,048-token
  summary); `CompactionPolicy.disabled()` turns it off. Compaction is
  best-effort — a failed summarization call skips compaction for that turn
  rather than failing it, and emits `CompactionFailed` on the hub — and
  instrumented via the `nessy.compaction` observation alongside the engine's
  other spans.
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
