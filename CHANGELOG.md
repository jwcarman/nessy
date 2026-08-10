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

- **The typed front door** — every agent is `Agent<I>` over an
  application-owned input vocabulary `I`, typically a sealed interface of
  records. `Harness#agent()` / `Nessy.agent()` return `AgentBuilder<String>`,
  the degenerate case; the new `Harness#agent(Class<I>)` returns
  `AgentBuilder<I>` for anything richer. `Conversation<I>.tell(I)` (plus the
  tap overload `tell(I, Consumer<Event>)`) is now the **only** verb —
  `send(String)` is removed outright, not kept beside it. `InputRenderer<I>`
  (`api.message`, `List<ContentBlock> render(I input)`) does the rendering:
  `InputRenderer.text()` is the pass-through `String` default (raw text → one
  text block, byte-for-byte what `send` always produced);
  `InputRenderer.json(ObjectMapper)` is the default for any other vocabulary
  — a `[snake_case_simple_name]` tag line plus canonical JSON of the input,
  over the harness's own mapper. `AgentBuilder#renderer(InputRenderer<I>)`
  overrides either default; the sealed-switch renderer — one arm per variant
  of the application's own sealed vocabulary — is the recommended idiom for
  anything past tagged JSON. A renderer that returns a null or empty block
  list, or throws, fails `tell()` outright — before the engine ever sees the
  call — rather than degrading silently: the caller is present on its own
  thread, so there is no best-effort path here the way there is for
  compaction or recall. Typing lives entirely in the facade's generics and
  dissolves at the wire; the sealed `Event` grammar, the reducer, and the
  engine are all unchanged. See `AgentFacadeTest`'s `Typed_front_door` nested
  class and the README's "Typed agents" section.
- **`Harness`** (root) — infrastructure reified. `Nessy.harness()` assembles
  the substrate an application shares across every agent it builds — provider
  default, session store, event hub, observation registry, object mapper —
  once; `Harness#agent()` then returns an `AgentBuilder` seeded with those
  pieces, ready to be given one agent's identity: model, system prompt,
  tools, policies. Two agents built from the same harness share its session
  store and event hub by construction. `HarnessBuilder#transcript(store)` is
  sugar, not a stored piece: it registers the journaling subscriber directly
  on the hub at `build()` time, once per harness. `Nessy.agent()` survives
  unchanged as sugar over an implicit default
  harness — the front door does not get heavier for the single-agent case.
- **`ToolGrant`/`UsagePolicy`** (`api.tool`) — capability and authority,
  declared together, per tool, per agent. A `ToolGrant(tool, policy)` pairs a
  granted `Tool` with the `UsagePolicy` the engine's one authority chokepoint
  consults before it runs; `ToolGrant.grant(tool)` derives the same default
  `Tool#requiresApproval()` always drew, so `tools(Tool...)` behaves exactly
  as before. `tools(ToolGrant...)` supersedes it when a grant's policy needs
  to loosen or tighten past that default — `ToolGrant#with(UsagePolicy)`
  reuses the tool with a different policy. A policy that throws or returns
  `null` fails closed (`PolicyDecision.Deny`), never an accidental allow.
- **`Memory`** (`spi.memory`) — the recall seam: `Memory.recall(Context)`
  fetches messages from outside a session's own transcript — a graph, a
  vector store, whatever a caller wires up — and the engine prepends whatever
  comes back ahead of the projected request. Sibling to `ContextBuilder`, not
  a subtype: projection stays pure and total, recall is I/O and best-effort
  — a downed store or a pairing-invariant-breaking result costs the request
  its enrichment, never the turn, and emits `RecallFailed` on the hub.
  Default is `Memory.none()`, recognized by identity so the default path
  allocates and observes nothing. Wired via `.memory(...)` on `AgentBuilder`.
- **`RecallFailed(SessionId, String)`** (`api.event`) — the hub event a
  failed recall emits, mirroring `CompactionFailed` exactly: the reason a
  turn's memory enrichment was skipped, for observability and alerting.
- **`Agent.contextFor(SessionId)`** and **`ContextAssembler`** (spi) — the
  debugging affordance that answers *what would a call made against this
  session see right now*, truthfully and without spending a model call:
  `contextFor` loads the session's stored state and runs it through the same
  `ContextAssembler` instance — one implementation of "project, then recall"
  — that `InProcessEngine.requestFor` consults on every conversational send,
  so the preview and the real thing can never drift apart. Still performs
  recall's I/O to answer, so a configured `Memory` is genuinely consulted.
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
  method — the framework never reads its own audit log). **The journal rides
  the hub**: the engine holds no `TranscriptStore` at all — it emits the new
  `MessageAppended(sessionId, message, turnUsage)` (`api.event`) at its
  newborn choke point, and a journal is simply a subscriber.
  `TranscriptStore.feedFrom(EventHub)` is that subscription — an inline
  default method that turns each `MessageAppended` into one `append` call on
  the emitting thread, so a failing append propagates and fails the run
  exactly as a direct engine dependency once did (the synchronous spine's
  veto-by-throw, see below). `.transcript(store)` on `HarnessBuilder` /
  `AgentBuilder` is sugar over exactly this call, registered once per harness
  hub rather than once per agent. `TranscriptStore.none()` is **retired** —
  there is no sentinel any more; the absence of a `.transcript(...)` call is
  simply the absence of a subscriber. An application that prefers
  best-effort journaling wraps the same subscription in `EventHub.async(...)`.
  `TranscriptStore.inMemory()` ships an `InMemoryTranscriptStore` with a
  test/host-facing `entries(id)` reader. `TranscriptEntry(message, turnUsage)`
  carries each message's exact cost — an assistant turn's own usage, a
  compaction summary's spend, or `Usage.zero()` for everything else.
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
  dispatch is synchronous, in subscription order, on the emitting thread.
  Ships `SessionEvent` (every reduced loop event), `ToolProgress`
  (long-running tools reporting through `ToolContext.events()`), and the new
  `MessageAppended` (every message, at birth, with its turn usage — the
  subscription point for journaling, memory extraction, and anything else
  that follows the transcript). `nessy-testing` ships `RecordingSubscriber`.
- **The synchronous spine — the veto is the throw** (design §9.1, pre-1.0
  breaking): `EventHub`'s delivery contract no longer catches a subscriber's
  `RuntimeException`. A throwing subscriber now propagates straight out of
  `emit`, stopping delivery to every subscriber after it and whatever called
  `emit` in the first place — a subscriber that must stand in the way of
  something (an audit write, an invariant check) writes inline and lets its
  exception propagate; a subscriber with no business stopping anything opts
  out per-subscriber via the new `EventHub.async(listener, onError)` (plus a
  `System.Logger`-backed convenience overload), which runs the listener on a
  fresh virtual thread so nothing it throws ever reaches the emitting thread.
  `Conversation.tell(I, Consumer<Event>)`'s tap is just another hub
  subscriber and picks up the same behavior: a throwing tap now aborts the
  call rather than being silently contained.
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

- **`Conversation.send(String)` → `Conversation<I>.tell(I)` (pre-1.0 breaking)** —
  `send` and its tap overload are removed outright; `tell` (and
  `tell(I, Consumer<Event>)`) are the only way to advance a conversation now.
  `Agent`, `Conversation`, and `AgentBuilder` all pick up the `<I>` input-
  vocabulary type parameter; `Agent<String>`/`Conversation<String>` is the
  drop-in replacement for every existing `Nessy.agent()` call site — the
  mechanical fix is `.send(x)` → `.tell(x)` plus spelling out `Agent<String>`
  wherever the raw type was written. See "The typed front door" above.
- **`Memory.recall(Context)` → `Memory.recall(SessionState)` (pre-1.0 breaking)** —
  recall now cues on the ledger, not the projected `Context`: the context is
  the thing that will *include* the recalled messages, and projection is a
  wire concern (an elided tool result reads `"[elided]"` in the projection but
  full text in the working set), so recall relevance should key on the
  conversation's truth. `recall(SessionState)` mirrors
  `ContextBuilder.project(SessionState)`, and `ContextAssembler` concatenates
  their outputs. Custom `Memory` implementations must update their lambda
  parameter's type.
- **`api` reorganized into domain families (pre-1.0 breaking, imports-only)** —
  the root `api` package now holds only the sealed grammar (`Event`,
  `Decision`, `Awaited`, `RunOutcome`, `ParkToken`, `StopReason`); everything
  else moved into a named subpackage: `Message`, `Role`, `Context`,
  `ContentBlock` and its variants moved to `api.message`; `SessionId`,
  `SessionState`, `SessionStatus`, `Usage`, `TerminationPolicy` moved to
  `api.session`; `CompactionStrategy`, `CompactionPolicy`, `CompactionTrigger`
  moved to `api.compaction`; `ToolCall`, `ToolResult` moved to `api.tool`
  alongside `Tool`. No type was renamed and no signature changed — this is a
  pure package move; source using the old `org.jwcarman.nessy.api.*` imports
  for these types must update the import statement only.
- **`AgentBuilder.compaction(...)` source-compat note (pre-1.0 breaking)** —
  adding the `CompactionStrategy` overload alongside the existing
  `CompactionPolicy` one means `.compaction(null)` no longer resolves: the
  call is now ambiguous between the two overloads and requires an explicit
  cast, e.g. `.compaction((CompactionPolicy) null)`. Source using the
  single-overload form to explicitly pass a null policy must add the cast.
- **`AgentBuilder.tools(...)` source-compat note (pre-1.0 breaking)** — adding
  the `ToolGrant...` overload alongside the existing `Tool...` one means a
  bare `.tools()` call (zero arguments) no longer resolves: it is now
  ambiguous between the two varargs overloads, since an empty array satisfies
  either equally well. Source relying on the zero-arg form must either drop
  the call entirely (`tools` already defaults to an empty `ToolRegistry.of()`)
  or pass an explicit empty array, e.g. `.tools(new Tool<?>[0])` — not
  `.tools((Tool<?>[]) null)`, which NPEs inside `DefaultToolRegistry.of`.
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
