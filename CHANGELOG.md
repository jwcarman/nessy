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
  Three ways to make one: a bare lambda for a single concern,
  `TurnObserver.builder()` composing per-variant consumers (repeat
  registrations chain in order), or extending `TurnObserverAdapter` and
  overriding only the hooks you watch — one dispatch switch serves all three.
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
  Consumer<T>[, Consumer<Throwable>])` — plus per-type sugar via the shared
  `ListenerDeclarations` interface (`onToolFinished`, `onModelRespondedAsync`,
  and kin: one `on*`/`on*Async` pair per conversation fact plus `ToolProgress`
  and `ApprovalRequested`) — frozen at `build()` — an agent-wide
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
- **The durable kernel: every entry appends, one verb drives.** A conversation
  now outlives the process that started it. `Conversation#tell` and the new
  `Harness#resume(ParkToken, ToolResolution[, TurnObserver])` are the same
  shape underneath — append to the conversation's durable agenda, then drive —
  because **appending always succeeds** (there is no "busy" answer; a tell
  mid-turn is never refused) and **driving is one re-entrant act**,
  `ConversationLoop#drive`, walking the status pointer from wherever it sits:
  idle with queued mail, a crashed in-flight turn, a parked wait past its
  resolution. What a queued tell becomes is a fold decision by status —
  quiescent opens a turn, mid-turn-with-debt rides the flush as an
  interjection, mid-turn-and-clean keeps the turn open instead of completing
  it (a clean `ModelResponded` folding against a non-empty agenda drains and
  continues — one fold rule replaces what a first-draft mailbox would have
  needed a whole subsystem for), and `PARKED` simply waits. `RunOutcome` is
  unchanged in shape and widened in meaning: a reading of the state either
  way, whether this call drove or another driver already holds the
  conversation.
- **Merge-at-drain, and the "cancel that" rule.** Every queued tell keeps its
  own `AgentTold` fact — one per arrival, in order — but drains into **one**
  user message, as distinct content blocks in UUIDv7 arrival order: the wire
  forbids consecutive user messages, and a driver that acted on tell 1 while
  durably holding tell 2 unread is the stale-instruction bug ("cancel that"
  must never sit unread behind the thing it cancels). Mid-turn tells do not
  reset the error streak and do not open a turn — streak reset is a property
  of the drain that opens one, not of being told something.
- **Two write disciplines, and nothing in between.** `ConversationStore#save
  (ConversationState, Collection<String> drainedAgendaIds)` is the fenced core:
  optimistic-locked on `state.version()`, throwing `StaleStateException` when
  another driver already moved the base — plain CAS, and the only thing that
  stops the zombie case (a stalled driver saving late after a re-drive already
  ran). `ConversationLoop#drive` retries up to `MAX_DRIVE_ATTEMPTS` (5) reloads
  on that exception before letting it surface. Beside it, `appendAgenda` is
  unconditional and never contended — an `AgendaItem.Told` or
  `AgendaItem.Resolved` row the fence doesn't know about, so a chatty world
  can never fence-fail a working driver. `ConversationState` carries the
  agenda as a loaded view (`ConversationStore.Loaded(state, agenda)`) rather
  than a state field — told ids drain transactionally with the very fold that
  consumes them (the note is never left on the agenda once its fold has
  landed); resolved ids drain only after the resumed fold *succeeds*, so a
  throwing `resume` leaves the resolution replayable rather than destroyed.
  An agenda with no state row behind it loads as `newConversation@v0` — a
  tell can arrive before a conversation has ever been driven.
- **`PARKED`, the parked lane, and real `resume`/`progress`.** `PARKED` joins
  `ConversationStatus`: a parked conversation self-describes to any ops
  surface — no driver, no lease, durable patience. State gained
  `parkedCalls: List<ParkedCall(token, call)>` beside `pendingCalls`; parking
  is a loop-applied closure transition (`state.parked(call, token)`), the same
  shape as `halted`. `Harness#resume(token, resolution[, observer])` consumes
  the token (`ConversationStore#consumeToken`, at-least-once-safe — a redelivered
  resolution is read, not replayed), appends an `AgendaItem.Resolved`, and
  drives; `Harness#progress(token, message) -> boolean` is `resume`'s
  non-terminal sibling — it only ever *peeks* the token via `findPark`, never
  consumes it, and emits `ToolProgress` on the built agent's own registry, the
  same audience the in-process tee reaches. Re-parking an already-resumed call
  is unsupported this generation and fails loud (`IllegalStateException`)
  rather than silently losing the call. Both `resume` and `progress` are
  single-agent this generation too: a second agent built from the same
  harness makes either throw loudly, because a park token does not yet carry
  which agent's loop and registry it belongs to — a recorded design gap, not
  a silent one.
- **The tee, and `ToolCallProgressed`.** `TurnEvent.ToolCallProgressed(call,
  message)` narrates a running tool's progress to whoever is watching the live
  segment. `GatedToolCallExecutor` wraps the `ToolContext` emitter so every
  `ToolProgress` a tool emits is teed to the segment's `TurnObserver` as a
  `ToolCallProgressed` carrying the *executor's own authoritative* `ToolCall`
  — a tool's self-reported id is never trusted for narration. Only
  `ToolProgress` is teed; every other system-channel event passes through
  untouched. The tee catches and logs an observer's throw rather than letting
  it propagate — texture must never alter the record, so a UI bug narrating
  progress cannot become a model-visible tool failure — the opposite ruling
  from the model path's own propagate-on-throw semantics, and both are
  documented side by side on `TurnObserver`.
- **`nessy-store-jdbc`.** A `ConversationStore` for one Postgres, no cluster
  membership: `nessy_conversation` (fenced state), `nessy_agenda` (told/resolved
  entries), `nessy_park`, and `nessy_token` (consumed-token markers), created
  idempotently by `JdbcConversationStore.create(DataSource, ObjectMapper)`.
  Saves run at `READ_COMMITTED` (the version check is the only isolation this
  discipline needs); loads run at `REPEATABLE READ` so a reader never sees the
  state, the agenda, and the park index from mixed generations. `StateCodec`
  carries the Jackson mixins state's records need to round-trip through
  `jsonb`, with drift guards that fail the build if the mapped shape and the
  domain type disagree. Container-backed tests run against `postgres:16-alpine`
  via Testcontainers (pinned at the 1.21 line deliberately — 2.0 renames every
  module artifact) behind `@Tag("container")`, excluded from the default build
  the same way `live` is (`-Dnessy.excludedGroups=live,container` is the
  default; CI runs with `-Dnessy.excludedGroups=live` so containers execute
  there without needing a real model key).
- **At-least-once, on the record.** Re-driving a stalled `EXECUTING_TOOL`
  conversation re-performs its pending calls — the fence keeps concurrent
  duplicates from corrupting state, but it does not stop a tool from running
  twice. `Tool`'s javadoc now says so directly: a tool that cannot be safely
  re-run makes itself idempotent, or parks and lets its remote side dedup by
  token. One paragraph, no machinery — the litmus that shaped the whole design
  (does the world already provide it?) answered this one too.
- **What this generation deliberately did not build.** The mailbox-as-API
  (`Mail`, `MailReceipt`, `post`) that the durable spec's first draft carried
  — review killed it against the litmus (*does the world already provide
  it?*); its one irreplaceable service, accepting input for a busy
  conversation, survives as the agenda the fold itself drains, no
  broker required. Also unbuilt, deliberately: claims/leases (fencing already
  makes concurrent drivers *safe*; a claim would only save wasted *spend*, and
  can arrive later as one column with no semantic change), sweepers (every
  agenda entry is followed by a driving entry, or the conversation is wedged
  regardless of any sweep), park timeouts (`ParkPolicy` — real, deferred until
  a deployment demands wall-clock eviction), cross-node event fan-out (the
  application's own bus sits behind a declared listener, unchanged from v2),
  and model-call parking (the contract shape permits it; nothing exercises it
  yet).
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
