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
  rather than silently losing the call. Both `resume` and `progress` were
  single-agent this generation, and a second agent built from the same
  harness made either throw loudly, because a park token did not yet carry
  which agent's loop and registry it belonged to — a recorded design gap, not
  a silent one. **Superseded by the named-agent generation below**: every
  door moved off `Harness` onto `Agent`, parks now carry the minting agent's
  name, and a second agent built from the same harness just works.
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
  domain type disagree. Container-backed tests run against `postgres:17-alpine`
  via Testcontainers (pinned at the 1.21 line deliberately — 2.0 renames every
  module artifact) behind `@Tag("container")`, excluded from the default build
  the same way `live` is (`-Dnessy.excludedGroups=live,container` is the
  default; CI runs with `-Dnessy.excludedGroups=live` so containers execute
  there without needing a real model key).
- **`JdbcMemory`, the durable transcript.** `nessy-store-jdbc` also carries a
  `Memory` implementation now, not just a `ConversationStore`: `nessy_memory`
  (`conversation_id, seq, message`), bootstrapped idempotently by the same
  `create(DataSource, ObjectMapper)` discipline as `JdbcConversationStore`.
  `remember` holds `ListMemory`'s consecutive-duplicate idempotency rule for
  at-least-once tellings, enforced under a `SELECT ... FOR UPDATE` row lock
  instead of an in-process map; `recall` reads back in `seq` order into a
  `Context` — verbatim retention, the durable floor rather than a
  summarizing memory. Shared `Memory`-contract behavior it mirrors from
  `ListMemory`: `recall` trims a trailing unanswered tool-use message
  before building `Context` — the loop remembers a tool-use the moment its
  fold settles, before it knows whether the call will park, so a parked
  conversation's raw telling can legitimately end in an open tool-use that
  `Context`'s wire-safe invariant forbids; dropping that one still-open tail
  is what keeps a parked conversation's recall legal. `StateCodec` gained the
  message-codec surface both stores now share, rather than each registering
  its own Jackson mixins. Tests: codec round-trips offline; remember/recall
  order, cross-conversation isolation, duplicate tolerance, and empty recall
  against real Postgres in the container-tagged suite, mirroring
  `ListMemoryTest`'s own scenarios.
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
- **`nessy-examples` becomes a family.** The module is now a `pom`-packaging
  aggregator (`nessy-example-chat-cli` and `nessy-example-chat-web`, neither
  published — same `maven.deploy.skip` discipline as before); the reactor's
  own `<module>nessy-examples</module>` entry is unchanged. The old
  two-provider terminal demo (`AnthropicChat`, `OpenAiChat`, `DemoAgent`,
  `ConsoleApprover`) moved verbatim into `chat-cli`, history intact
  (`git mv`, not a rewrite). Spring Boot enters the reactor only inside the
  new `chat-web` module — `nessy-parent` never learns Spring exists.
- **`nessy-example-chat-web` — the first non-toy dogfood.** A Spring Boot
  4.1.0 chat app proving nessy's durable story end to end on one HTML page:
  a real browser UI (vanilla JS, `fetch` + `ReadableStream` SSE parsing, no
  framework, no build step), a real Postgres behind both `JdbcConversationStore`
  and `JdbcMemory`, and `IssueCouponTool` gated behind
  `UsagePolicy.requireApproval()` with an approver that always parks — the
  browser is the approver. `docker-compose.yml` (`postgres:17-alpine` plus
  `grafana/otel-lgtm`) starts and stops automatically under
  `spring-boot-docker-compose` around `mvn spring-boot:run`'s own lifecycle.
  The whole nessy wiring is five beans in one `@Configuration` class
  (`NessyConfig`: `store`, `memory`, `modelProvider`, `harness`, `agent`) —
  the simplicity test the design set out to pass. Ships
  full local observability, mirroring the mocapi-enterprise-demo recipe: one
  `grafana/otel-lgtm` compose service fans out OTel Collector, Tempo,
  Prometheus, Loki, and Grafana (`localhost:3000`, OTLP on `4318`/`4317`),
  wired via `spring-boot-starter-opentelemetry` (traces),
  `micrometer-registry-otlp` (metrics), and the OTel Logback appender
  (trace-correlated logs). The dogfood point: the harness bean takes Boot's
  own auto-configured `ObservationRegistry`, so nessy's model-call and tool
  observations land in the same Tempo trace as Boot's own HTTP and JDBC
  spans — one chat turn, one trace. Tested with one `@SpringBootTest` smoke
  (`@Tag("container")`, Testcontainers Postgres, a scripted `ModelProvider`
  swapped in for the real Anthropic bean via `@Profile("!test")` — no key,
  no network) that drives the whole wiring: post a message, watch SSE events
  arrive in order, park on the tool call, read the approval card back,
  resume, complete. o11y export is disabled under that same test profile —
  no collector in CI, and an exporter left pointed at nothing would retry-spam
  the log.
- **The DX generation.** The first real deployment (`chat-web`, above) left
  three apology comments in its own wiring — an API that makes its own
  example apologize has told you what to fix. This generation closes each
  one at the source, then rewrites `chat-web` on top of the result as its own
  acceptance test:
  - `ToolContext` now carries the authoritative `ToolCall` and a
    `progress(String)` method — a tool reports its own progress without ever
    constructing a `ToolProgress` or touching an id; the framework supplies
    both ids from the call it already gave the tool.
  - `Agent.snapshot(id)` is the page-rebuild read: a total
    `ConversationSnapshot(ConversationStatus status, List<ParkedCall>
    parkedCalls, Context context)` for any id, stored or not — `IDLE`,
    empty parks, `Context.empty()` for one never stored. `contextFor`
    deliberately stays loud (it throws on an unknown id) precisely so
    `snapshot` can be total; the division is stated in both javadocs so the
    two never drift back together by accident.
  - `TurnEvent.ToolCallParked(ToolCall call, ParkToken token)` narrates only
    after the park's save has committed — `applyParked` is the one choke
    point that emits it, so an observer never sees a token the store doesn't
    yet honor. `TurnEvent`'s type-level javadoc now carries two invariants
    directly: narration is at-least-once (a retried segment can emit
    `ToolCallParked` twice for one token — the loop's write discipline
    retries on stale saves, and re-narrating a park it already told you about
    is cheaper and safer than swallowing one it didn't), and turn observers
    are entry-scoped, so a future agent-wide standing observer must revisit
    that invariant loudly rather than silently becoming a capability
    broadcast.
  - `ListMemory` gained the recall open-tail trim `JdbcMemory` already had —
    now genuinely shared `Memory`-contract behavior, not a divergence one
    store carries alone. Both javadocs name the same remaining open case:
    halting mid-turn while a call is parked.
  - `UnknownParkTokenException` replaces a hand-rolled `IllegalArgumentException`
    for a token the store no longer recognizes at all (as opposed to one it
    recognizes but has already consumed) — a named rejection, not a wiring
    desync. `Harness.peek(ParkToken)` reads a park without consuming it, and
    `Harness.approve(ParkToken[, TurnObserver])` /
    `Harness.deny(ParkToken, String[, TurnObserver])` are sugar over
    `resume` for the two decisions every human-in-the-loop gate actually
    makes.
  - `Approver.parkAll()` — says park to everything, behind a fresh
    `ParkToken`, replacing the one-line lambda every durable-HITL app was
    writing by hand. `nessy-store-jdbc` gained
    `JdbcPersistence(JdbcConversationStore store, JdbcMemory memory)` — the
    concrete pair, not the `ConversationStore`/`Memory` interfaces — with
    `JdbcPersistence.create(DataSource, ObjectMapper)` building both halves at
    once — one bean where an app used to wire two.
  - **`chat-web` rewritten as its own acceptance test.** Every apology
    comment is gone: `IssueCouponTool` calls `context.progress(...)`
    directly: the chat GET endpoint rebuilds from `agent.snapshot(id)`
    instead of hand-stitching state; approval cards are event-borne —
    `SseEvents` reacts to `ToolCallParked` inline — with the snapshot-backed
    GET kept only as the race-path card source, not the primary one; the UI
    (`app.js`) dedupes approval cards by token, so the at-least-once
    narration contract above can never double-render one; `ApprovalController`
    catches the typed `UnknownParkTokenException` instead of a bare 409
    guess; and `NessyConfig` wires `Approver.parkAll()` and one
    `JdbcPersistence` bean where it used to wire an approver lambda and two
    stores.
  - **The `Awaited<T>` ruling.** Sonar S2326 ("T is not used in the
    interface") is a SonarCloud won't-fix, not a code change or a
    suppression: the type parameter is load-bearing grammar — it is what
    makes `Awaited<ToolResult>` and `Awaited<Decision>` distinct types even
    though only `Ready` carries a value — and no caller has ever needed an
    interface-level accessor; the ruling is applied in the SonarCloud UI with
    that justification, keeping the zero-suppression rule intact in code.
- **The Spring Boot starter — substrate arrives by classpath, identity stays
  yours.** Two new published artifacts, both in the BOM: `nessy-autoconfigure`
  (every `@AutoConfiguration` class and `@ConfigurationProperties` record;
  every feature dependency — `nessy-model-anthropic`, `nessy-model-openai`,
  `nessy-store-jdbc`, `spring-webmvc` — optional, each configuration gating
  itself with `@ConditionalOnClass`) and `nessy-spring-boot-starter` (a
  jar-packaged, src-less, dependency-only aggregator of `nessy-core` +
  `nessy-autoconfigure` — Boot's own convention for a starter pom, not a
  packaging choice of ours). `nessy-parent` still never learns Spring exists;
  Boot enters the reactor only inside these two modules.
  - **Providers by classpath.** `AnthropicModelProvider`/`OpenAiModelProvider`
    present on the classpath autoconfigures a `ModelProvider` bean from
    `nessy.{anthropic,openai}.{api-key,base-url}` — properties that are
    *overrides* layered on top of each SDK's own `fromEnv()` resolution, never
    replacements for it, so every ambient source the SDK already understands
    (auth tokens, base URLs, workload-identity federation) still works when a
    property is absent. When both provider jars are present, `nessy.provider`
    chooses, or — absent that — whichever side is the only one with a
    `nessy.*` key set; an explicit `nessy.*` property always outranks an
    ambient env var (a deliberate precedence ruling, not an oversight); truly
    ambiguous classpaths fail fast at startup, naming the property. Every
    provider bean backs off entirely the moment the application supplies its
    own `Harness` — a `Harness` cannot exist without a provider already in
    hand, so building (and possibly keylessly failing to build) a second,
    unused one would be pure waste.
  - **Persistence by classpath.** `nessy-store-jdbc` on the classpath plus a
    `DataSource` bean autoconfigures `JdbcPersistence`-backed
    `ConversationStore` and `Memory` beans — the app goes from JVM-lifetime
    memory to durable the moment the classpath says "I have a database."
    `nessy.jdbc.enabled` (default `true`) is the master switch;
    `nessy.jdbc.bootstrap-schema` (default `true`) picks between the
    bootstrapping `create` factories (idempotent `CREATE TABLE IF NOT EXISTS`
    at startup) and the bare constructors, for a datasource another process
    already schema-bootstrapped.
  - **Harness, autoconfigured; agents, never.** `NessyAutoConfiguration`
    builds a `Harness` from whatever `ModelProvider` (required),
    `ConversationStore`, `ObservationRegistry`, and `ObjectMapper` beans are
    in context, seeded from `nessy.default-model` — Boot's own
    auto-configured `ObservationRegistry` is what makes nessy's spans join
    Boot's HTTP/JDBC spans in one trace for free. **Agents are never
    autoconfigured, stated as the razor's own feature, not a gap**: identity
    (model, system prompt, tools, policies) is always the application's own
    `Harness#agent()` call — an `AgentBuilder` is never something this module
    builds on an app's behalf. Every bean here, like the provider beans above,
    yields outright to a hand-declared one.
  - **The web bridge**, active only when `spring-webmvc` resolves
    (`@ConditionalOnClass(SseEmitter)`): `TurnEventSse` maps `TurnEvent`
    narration onto a stable, versioned wire vocabulary — `delta`, `thinking`,
    `tool-requested`, `tool-progress`, `tool-decided`, `tool-completed`,
    `tool-parked` (`{token, tool, args}`) — via an exhaustive record-pattern
    switch with no `default` arm (this module compiles in the same reactor as
    `nessy-core`, so the sealed-grammar etiquette's fail-loud-at-compile-time
    rule beats an extender's forward-tolerant default here), plus a
    broken-pipe-tolerant `SseEmitter` send that completes a closed stream
    instead of failing the turn driving it. `TurnRunner#run(Function
    <SseEmitter, RunOutcome>, BiConsumer<SseEmitter, RunOutcome>)` drives that
    turn on a virtual thread, the emitter handed to the closure so the same
    instance always flows to both the turn and its outcome handler with no
    unsynchronized-publication window; it captures the request thread's
    Micrometer `ContextSnapshot` before starting the virtual thread and
    restores it there, so the turn's spans parent onto the request's trace
    instead of starting a new one — the trace-propagation bug `chat-web`
    shipped first-try becomes unmakeable. A `RuntimeException` escaping the
    turn ends the stream itself: one `done` event naming the failure
    (`{status: "ERROR", failureReason}`), then `completeWithError`.
  - **The property surface is deliberately this small, and stated as
    design:** `nessy.provider`, `nessy.{anthropic,openai}.{api-key,base-url}`,
    `nessy.default-model`, `nessy.jdbc.{enabled,bootstrap-schema}` — nothing
    else. Anything more exotic rides each SDK's own `fromEnv()` ambient
    resolution or the hand-declared-bean escape hatch, never a new `nessy.*`
    key. `@ConfigurationProperties` metadata is generated
    (`spring-boot-configuration-processor`), so IDEs complete every key.
  - **`chat-web`, round two.** The example's own acceptance test for the
    starter above: `NessyConfig` shrinks from five beans to one — the agent —
    with the `@Profile("!test")` split retired entirely (the smoke test's
    `@TestConfiguration` `Harness` bean now wins over the starter's own by the
    same `@ConditionalOnMissingBean` every autoconfigured bean here honors).
    The hand-rolled `SseEvents`/`ContextSnapshotFactory` bridge is deleted in
    favor of `TurnEventSse`/`TurnRunner`. `app.js`'s handler for a parked tool
    call is renamed `approval-needed` → `tool-parked`, conforming to the
    starter's published wire vocabulary rather than the reverse (dedupe-by-token
    logic unchanged) — an example-app wire rename, not a framework break, since
    `approval-needed` was never nessy's own name.
- **`nessy-example-night-watchman` — the clock is the caller.** The third
  example, and the leanest: no web, no database, no Docker.
  - **The pattern.** `@Scheduled(cron = "${watchman.cadence:0 * * * * *}")`
    initiates each turn of **one** continuous conversation — the same
    `Conversation<String>`, held for the app's lifetime, told "do your
    rounds" on every firing. Trend judgment (a vital that's merely drifting,
    not yet out of band) is conversation state at work, not a separate
    tracking mechanism the app writes: the model sees its own recent rounds
    and compares.
  - **`WindowedMemory`, the first custom-`Memory` dogfood.** Freedom of
    retention, rule of law at the border: `remember` delegates whole to
    `ListMemory`, nothing discarded from the underlying store; `recall` is
    where `Context#keepRecent(window)` keeps AT LEAST the last
    `watchman.window` messages (default `40`), cutting only at a pair-safe
    boundary — the tail can run one round longer when the boundary must walk
    past a tool exchange, and when no pair-safe boundary exists the context
    comes back whole. That one cut is the recall bound that lets a
    conversation run forever without growing the model call — the watchman's
    horizon is roughly its window, not its whole life.
  - **The leanest example.** `EngineRoom`'s seeded random walk (bilge biased
    `+3.5`/step) guarantees a run its arc — quiet, trend, alarm — inside
    roughly five to eight minutes at the default cadence; `check_vitals` and
    `raise_alarm` are both granted `UsagePolicy.allow()`, so nothing here
    ever parks. The in-memory substrate is the starter's own defaults with
    zero extra wiring — no `nessy-store-jdbc`, no compose file — and the
    whole suite runs offline, same as every other module.
  - The patient-researcher spec retired UNBUILT (branch archived at
    `patient-researcher-archive`); the examples matrix now reads `chat-cli` /
    `chat-web` / `night-watchman`.
- **The three front doors — one database, three small contracts.**
  `ConversationStore` slims to a conversation's control block and its inbox
  alone: `load`, the fenced `save(state, drainedInboxIds)` (CAS the control
  block, drain the inbox, one atomic act, and nothing else), and the
  unconditional, never-contended `append`. `Parks` (`spi.conversation`) is
  the callback door's own registry — `park(Park)`, `find(ParkToken)`,
  `forConversation(ConversationId)` over a durable, keep-forever record of
  every wait this process has ever registered. `Transcript` (`spi.memory`)
  is the memory jurisdiction's own storage primitive — an append-only,
  versioned, per-conversation message log (`append`, `all`, `tail`, `page`),
  the read surface audit and chat history need and the primitive
  `TranscriptMemory`/`SummarizingMemory` build on. `HarnessBuilder#parks
  (Parks)` gives the harness the same substrate seam the store already had,
  defaulting to `Parks.inMemory()`.
  - **The inbox rename.** `AgendaItem` → `InboxEntry` (`Told`/`Resolved`
    variants keep their names, `Resolved` re-keyed `(callId, resolution)`),
    `ConversationStore#appendAgenda` → `#append`, `Loaded.agenda` →
    `Loaded.inbox` — the loop's internal vocabulary (`drained`, comments)
    follows throughout.
  - **`TranscriptMemory` — two memories become one.** `TranscriptMemory
    (Transcript)` replaces both `ListMemory` and `JdbcMemory`: `remember`
    appends to the transcript (idempotency is the transcript's own
    no-stutter rule), `recall` reads the whole log back and trims the loop's
    own open-tail bookkeeping before handing back a `Context`. Two
    `TranscriptMemory` instances over the same `Transcript` are two windows
    on one log, not two logs — the seam is the storage, the memory is the
    policy.
  - **`SummarizingMemory` — the tail API's dogfood, and the watermark
    story.** Keeps a bounded tail of the transcript verbatim, folding
    everything older into a running `SummaryStore` summary once that tail
    grows past a threshold — the fold boundary chosen the same pair-safe way
    `Context#pairSafeCut` chooses one, so a tool exchange straddling the
    threshold is always kept whole. The watermark *is* the bookkeeping:
    `SummaryStore#save` is deliberately unfenced, last-write-wins (design
    §10) — a crash between summarizing and saving just means the next
    `recall` re-summarizes the same tail and lands on the same watermark,
    cheap re-work, never a lost word, since the transcript is the truth a
    summary is only ever a cheaper way to re-read.
  - **Tokens evicted from `ConversationState`; `consumeToken` dissolves into
    the fold.** State no longer carries `ParkToken`s at all — the fold only
    ever matched a parked call by id. Replay protection (a redelivered
    resolution addressed to an already-settled call) is no longer a store
    method to call; it is the fold-owned is-this-call-still-outstanding
    check, run against `ConversationState.parkedCalls()` as the loop routes
    each inbox entry. That check is serial, not concurrent — it picks a
    winner among entries already appended, so two deliveries of the same
    token driven concurrently can both see the call as outstanding and both
    invoke the tool before the fence settles. `Tool` already documents the
    consequence: a tool that cannot be safely re-run makes itself
    idempotent, or parks and lets its remote side deduplicate by token.
  - **The `Parks` registry, and register-before-save orphan tolerance.** A
    tool that parks has already handed its token to the outside world before
    the loop can act, so the registry write is forced to precede the save,
    not chosen (design §5): a lost registry entry would strand a token the
    world holds — a wedged conversation with no way back in. The inverse
    failure — a registry entry whose save then loses the fence or never
    lands — is merely tolerated as an orphan: its eventual resolution
    translates fine, addresses a call the reloaded state no longer finds
    outstanding, and drains as stale.
  - **Wiring.** `nessy-autoconfigure` grows `Parks` and `Transcript` beans
    (`JdbcParks`, `JdbcTranscript`) under the same classpath-and-datasource
    rules as the store; the `Memory` bean becomes `TranscriptMemory` over the
    `Transcript` bean, retiring `JdbcMemory`; `NessyAutoConfiguration` passes
    the `Parks` bean into the harness. `ParkedCall` survives as the
    approval-card read shape — `Agent.snapshot`/`Harness.peek` still hand
    back `(token, call)` pairs — now sourced from `Parks.forConversation`
    filtered to calls `state.parkedCalls()` still names outstanding, rather
    than a park index the store used to sync on every save.
- **`nessy-example-order-desk` — the queue is the caller.** The fourth
  example, and the first whose trigger is a broker rather than a person or a
  clock: a message landing on RabbitMQ's `orders` queue initiates a turn,
  `tell`-ed to the conversation the order's own id mints, so external
  identity — not a session, not a browser tab — is what routes an event to
  its story. It is also the family's first typed-vocabulary agent —
  `harness.agent(OrderEvent.class)` over a sealed event grammar, every
  other example being `Agent<String>` — and the first to put the machine
  half of a turn on a real wire: `request_fulfillment` parks with its
  `ParkToken` riding as the AMQP correlation id on the outbound message, and
  an in-app "warehouse" listener plays the request back as a reply carrying
  that same correlation id, which a second listener translates straight
  into `agent.progress`/`agent.resume` — the kernel's "the token is the
  correlation contract" claim, made wire-visible, with no token field in
  either JSON payload. Acknowledgement is Boot's default AUTO, ruled rather
  than omitted: the container acks on successful listener return and
  requeues on failure or death, so killing the app mid-turn and restarting
  it demonstrates at-least-once redelivery against a real broker, absorbed
  by the fold's own is-this-call-still-outstanding replay protection with
  no manual channel plumbing anywhere in the module.
- **`nessy-example-dispatcher` — the two inbox doors over plain HTTP.** The
  fifth example: a Spring Boot app exhibiting both webhook trigger models at
  once. `POST /signals` is fire-and-forget — deposit, `202`, drive on a
  virtual thread — routed by external identity (the incident id mints the
  `ConversationId`). `POST /callbacks/{token}` and `.../progress` are the
  crew reporting back into a parked `request_field_crew` call; a duplicate
  completion callback re-drives idempotently rather than replaying the tool.
  The headline scene is restart-then-callback: signal, park, kill the app,
  restart it, `curl` the callback in a JVM that never saw the signal — the
  first example to make `JdbcParks` load-bearing rather than incidental.
  `curl` is the only client; `Agent<String>` is deliberately the right
  vocabulary here (the doors are the lesson, not typing).
- **The narration finishes its sentences.** `TurnEvent` gains
  `AssistantSaid(Message)` — the settled assistant-role message behind the
  `TextDelta`/`ThinkingDelta` preview, emitted once per model response the
  fold absorbs, including tool-use-only responses — and `TurnEnded(status,
  failureReason)`, the segment's closing line, emitted at every exit:
  quiescent completion, `FAILED` (with its reason), and `PARKED`. Like
  `AssistantSaid` and `ToolCallParked`, narration is at-least-once per drive
  attempt, not exactly-once: a fence-lost retry may re-narrate the ending;
  consumers dedupe. `TurnObserverAdapter` and `TurnObserverBuilder`
  (built via `TurnObserver.builder()`) grow matching
  `onAssistantSaid`/`onTurnEnded` hooks, and `TurnObserver` gains a
  `logging(Logger, String prefix)` factory — a ready-made observer that
  narrates settled assistant messages (`AssistantSaid`, not the streaming
  deltas), tool requested/completed/parked, and the segment's ending (with
  the failure reason repeated at `WARN`) to a `Logger` at one call site,
  prefixed per caller. `TurnEventSse`
  takes over emitting the wire's `done` event itself (the hand-synthesized
  `done` chat-web's controllers used to build by hand is gone) and gains a
  new `message` wire event carrying `AssistantSaid`'s non-blank text.
- **Two guards where silence used to cost a demo its lesson.**
  `AgentBuilder` (in `nessy-core`) now logs a WARN when `Memory` is left at
  its in-memory default while the harness's `ConversationStore` was
  explicitly configured — a configuration that quietly discards
  conversation history across restarts was previously silent. `UsagePolicy.allow()` now returns a canonical
  singleton (`AgentBuilder` uses identity, not equality, to recognize an
  all-allow grant) so the existing "no approver configured" WARN can be
  skipped precisely when every granted tool is already all-allow — the
  warning no longer fires for a harness that was never going to ask
  anyone anything.
- **`Context` learns to read itself aloud.** `Context.lines()` returns
  `List<Context.Line>` (`record Line(String role, String text)`) — the
  transcript rendered as a flat, role-tagged line list for callers that
  want to print or log a conversation's shape without walking `Message`
  content blocks themselves. Both example copies of a hand-rolled
  `TranscriptView` are deleted in its favor.
- **`nessy-examples/hello`** — the root README's five-minute example as a
  runnable module: `nessy-core` plus `nessy-testing`'s
  `ScriptedModelProvider`, no key, no network, no Docker —
  `./mvnw -q -pl nessy-examples/hello -am compile exec:java`. The README's
  snippet is corrected to match the real `nessy-testing` API wherever prose
  had drifted, and the run command sits directly beneath it — `nessy-testing`
  gets its first dogfood, and the headline promise becomes something you can
  actually run.
- **Named agents, and the callback doors move to `Agent`.** `AgentBuilder
  #name(String)` is now required at `build()` — a durable wire contract
  exactly like a queue name or a callback URL (design §3), not a cosmetic
  label: every `Parks.Park` an agent registers is stamped with it, and every
  callback door verifies a resolution's stamp against the agent handling it
  before acting. `Parks.Park` gains `agentName`, and `nessy_parks` gains
  `agent_name NOT NULL` to carry it durably. All five callback doors —
  `resume`, `progress`, `approve`, `deny`, `peek` — move off `Harness` onto
  `Agent`, the identity that actually owns the loop, the grants, and the
  registry a callback needs to act (design of record amendment, 2026-08-14:
  "the callbacks should not be coming to the harness. They should always go
  through the agent"). Each door verifies the park's stamp *before* appending
  or driving anything; a mismatch throws the new `WrongAgentException`,
  naming both the agent that minted the park and the one the callback landed
  on, self-diagnosing a rename-without-redeploy on the first callback rather
  than misrouting a resolution through the wrong agent's grants and
  listeners. The payoff: two agents built from the same harness can now each
  receive callbacks correctly — the single-agent restriction the earlier
  parked-lane entry above recorded as a design gap is closed.
- **Install docs.** The root README gains an Install section, directly after
  the five-minute example: the `nessy-bom` `<dependencyManagement>` import
  and the artifacts an application actually depends on — `nessy-core`,
  `nessy-spring-boot-starter`, a `nessy-model-*` provider, `nessy-testing`
  for scripted tests, `nessy-store-jdbc` for durability — every coordinate
  read straight off the reactor's own poms, plus the honest sentence: no
  public release yet, `./mvnw install` and `0.1.0-SNAPSHOT` until there is
  one.
- **`Memory.windowed(Memory delegate, int n)`.** A decorator that clips
  `recall` to the last `n` messages of whatever the delegate returns,
  without changing what the delegate itself retains — a small, composable
  answer to "make the model see less" that sits beside `TranscriptMemory`
  and `SummarizingMemory` rather than replacing either.
- **`TurnObserver.logging(Logger, Supplier<String>)`.** A sibling of the
  existing `logging(Logger, String prefix)` factory for callers whose
  prefix isn't known until the moment a `tell` actually happens (a
  conversation id minted per call, say) — the supplier resolves once per
  event, not once per registration.
- **`Tool#execute`'s javadoc teaches the parking recipe.** Three steps, in
  order: mint a token via `ParkToken.generate()`; return
  `Awaited.parked(token)` with it; get that token to whatever will resume
  the call later (a webhook, a queue, an approval UI) before the method
  returns. The recipe lives at the one call site every tool author actually
  reads.
- **The parks-in-memory guard is now conditional, like its `Memory`
  sibling.** `HarnessBuilder` warns at `build()` when parks are left at
  their in-memory default while the harness's `ConversationStore` was
  explicitly configured (`storeSet`) — a durable store paired with
  in-memory parks is the mismatch worth shouting about; an all-in-memory
  harness is a coherent choice and stays quiet. `hello`'s first line of
  output is no longer a warning.
- **Two exception messages stop asking the reader to guess.**
  `WrongAgentException` now names the token, the agent that minted the
  park, and the fix in one sentence ("an agent's name is a durable wire
  contract; redeploy under '&lt;stamp&gt;' to drain its parks").
  `UnknownParkTokenException` stops saying a token was "settled" — registry
  entries survive resolution, and a settled token drains quietly rather
  than throwing — and its message prints `token.value()` rather than the
  record's own `toString()`.
- **`nessy-transcript-cassandra` — a second durable `Transcript`, and the
  polyglot proof.** `CassandraTranscript` (`CqlSession` in place of
  `DataSource`, `nessy-store-jdbc`'s sibling): one row per message in
  `nessy_transcript`, partitioned by conversation and clustered by an
  append-only `version`, bootstrapped idempotently by
  `CassandraTranscript.create(CqlSession, ObjectMapper)`. Cassandra has no
  row lock and no sequence, so `append` compare-and-inserts in a loop
  instead of `JdbcTranscript`'s `SELECT ... FOR UPDATE`: read the
  partition's last row at `SERIAL`, absorb an at-least-once re-telling that
  repeats the same message (the no-stutter rule, held exactly as every
  other `Transcript`), else `INSERT ... IF NOT EXISTS` at the next
  zero-based version; a lost race re-reads at `SERIAL` and re-evaluates the
  stutter rule against the winner, the same serialization the row lock
  gives the JDBC sibling for free. A writer that keeps losing gives up
  after a bounded number of attempts with an `IllegalStateException`
  naming the contention. `StateCodec`'s message-codec surface is
  duplicated from `nessy-store-jdbc` rather than depended on — two stores
  agreeing on a wire format by specification, not by shared runtime
  dependency. Proven by `nessy-core`'s `TranscriptContract` test-jar suite
  and a concurrency test (parallel appenders, Awaitility, no sleeps —
  strictly monotonic versions, no duplicates, no lost messages, the
  no-stutter rule held under contention) against real Cassandra via
  Testcontainers, tagged `container` like the JDBC module's own.
- **`CassandraTranscriptAutoConfiguration` — arbitration by the rule
  already in place.** New in `nessy-autoconfigure`, gated on
  `CassandraTranscript`/`CqlSession` on the classpath, a `CqlSession` bean
  present, and `nessy.cassandra.enabled` (default `true`); ordered `after
  = CassandraAutoConfiguration.class` (so its `@ConditionalOnBean
  (CqlSession.class)` check runs only once Boot's own Cassandra
  auto-configuration has had a chance to publish one) and `before =
  JdbcPersistenceAutoConfiguration.class`, so its `Transcript` bean always
  lands first and the JDBC auto-configuration's own
  `@ConditionalOnMissingBean` `Transcript` bean method backs off by the
  rule it already lives by — no edit to the JDBC auto-configuration was
  needed. The JDBC `Memory` bean composes over whichever `Transcript` won,
  so `ConversationStore` and `Parks` stay on Postgres unchanged; only the
  transcript itself moves. `CqlSession` arrives entirely from Boot's own
  Cassandra auto-configuration — compose-detected `cassandra` image,
  Testcontainers `@ServiceConnection`, or plain `spring.cassandra.*`
  properties — this configuration adds no session configuration of its
  own, mirroring `JdbcPersistenceAutoConfiguration`'s relationship to
  `DataSource`.
- **The polyglot claim, proven.** The store rework's three front doors
  (`ConversationStore`, `Parks`, `Transcript`) are genuinely separable
  stores, not a package deal — an autoconfigure context test now proves it
  directly: a `DataSource` and a `CqlSession` both present yields the JDBC
  `ConversationStore` and `Parks`, a `CassandraTranscript`, and one `Memory`
  composed over it, all in a single Spring context. No new example module
  — the proof lives in the module's own container tests and this
  autoconfigure test; see `nessy-transcript-cassandra`'s README for the
  wiring an application adds.
- **`nessy-tool-mcp` — the world's MCP toolboxes open, and the zero-kernel
  claim proven end to end.** `McpToolbox.connect(McpClientTransport,
  ObjectMapper)` performs the MCP `initialize`/`tools/list` handshake once
  and hands back every server tool as a plain nessy `Tool<JsonNode>`;
  `tools()` lists them all, `tool(name)` fails noisy — a
  `NoSuchElementException` naming every tool actually on offer. The kernel
  needed no change at all to carry an MCP-backed tool: `ToolSpec`'s
  wire-neutral `ObjectNode` schema, `Tool#spec()`'s default-method override
  point, and `ToolInvoker`'s identity-hop deserialization for
  `inputType() = JsonNode.class` were already enough, proven not merely
  asserted — an end-to-end test grants an `McpTool` through a real
  `AgentBuilder` and drives it through the actual
  `ToolInvoker`/`GatedToolCallExecutor` path against a real in-process MCP
  server (the SDK's own server side, the first true end-to-end MCP
  exchange in this repo, no Docker/key/network, default build). `McpTool`
  maps text content blocks (newline-joined) to a success `ToolResult`,
  `isError` to the error shape, and degrades non-text content (images,
  embedded resources) by JSON-encoding it into the text output rather than
  dropping it — a documented v1 limitation, tools-only and text-first;
  elicitation, sampling, resources, prompts, and roots are deliberately
  banked for a later generation, and MCP progress notifications are not
  forwarded to `ToolContext.progress` in v1 because the SDK's sync client
  offers only a session-global progress consumer, not one scoped to a
  single call. Transports arrive entirely from the SDK (`StdioClientTransport`,
  `HttpClientStreamableHttpTransport`, both `mcp-core`) — nessy adds none
  of its own — and the module depends on `mcp-core` plus
  `mcp-json-jackson2` explicitly rather than the `mcp` facade, keeping
  Jackson 3 off the classpath entirely (the rest of this repo, including
  `ToolSpec`'s `ObjectNode`, is built on Jackson 2). The import posture is
  the grant principle applied to a whole server at once: opening a
  toolbox authorizes nothing by itself, and every tool it yields still
  needs its own `ToolGrant`/`UsagePolicy`, one at a time — see
  `nessy-tool-mcp`'s README for the connect/grant idiom.
- **`nessy-example-scout` — the agent that reads other people's code.** The
  sixth example, and `nessy-tool-mcp`'s security story made runnable: a
  terminal REPL, chat-cli's exact posture, granted a toolbox imported from
  DeepWiki's public, no-auth MCP server — `read_wiki_structure` and
  `read_wiki_contents` allowed outright, `ask_question` behind
  `UsagePolicy.requireApproval()`, so a human approves a *remote* server's
  tool call at the console before it runs. The tool names are verified
  against the live server rather than guessed (`initialize` against
  `https://mcp.deepwiki.com/mcp`, 2026-08-15); a drifted name fails loud at
  `McpToolbox#tool(String)`, before the REPL ever opens. `Scout#main` and the
  fully offline `ScoutTest` share one package-private construction seam,
  `Scout#scout(Harness, McpToolbox, String, Approver)`, so the test exercises
  the demo's own grant table — over an in-process MCP server reproducing
  `nessy-tool-mcp`'s own test-tree pairing (`InMemoryMcpTransport`/
  `PipedClientTransport`, copied locally with attribution, since this module
  cannot depend on another module's `src/test`) — rather than a parallel
  copy of it. See `nessy-examples/scout/README.md` for the grants, the
  approval-prompt transcript, and the DeepWiki covenant.

### Breaking (pre-1.0)

All of the following are deliberate, in-development shape changes — nothing
below breaks a shipped version, because none exists yet — but they are loud
because every signature named was public as of the previous entries above:

- **`RunOutcome.Parked` slims to `Parked(ConversationState state)`.** The
  token it used to carry travels a different way now: on the narrated
  `TurnEvent.ToolCallParked(call, token)` event, and in `state.parkedCalls()`
  for anyone reading state after the fact rather than watching the turn live.
- **`Agent.resume(id)` is renamed `Agent.conversation(id)`.** "Resume" now
  means exactly one thing across the whole API: answering a park
  (`Harness.resume(ParkToken, ToolResolution, ...)`). Reopening a stored
  conversation to keep talking to it was never actually resuming anything —
  it was just naming the conversation you already had — so it gets the name
  that says that.
- **`ToolContext(ConversationId, ToolCall, EventEmitter)` gains a component.**
  The DX generation's `ToolContext.progress(String)` needs the authoritative
  call and conversation id in hand, so the record grew from whatever it
  carried before to this three-component shape — a third public break this
  generation. Any existing tool test that constructs a `ToolContext` directly
  breaks at compile time; direct construction is the canonical offline way to
  test a `Tool`, so this is expected to touch every tool's own test suite, not
  a corner case.
- **The store rework, stated loud (design §9): all deliberate,
  in-development shape changes; nothing below breaks a shipped version,
  because none exists.**
  - `ConversationStore` loses `findPark`, `findParkConversation`,
    `consumeToken`; `appendAgenda` → `append`; `Loaded.agenda` →
    `Loaded.inbox`.
  - `AgendaItem` → `InboxEntry`; `Resolved` re-keyed `(callId, resolution)`.
  - `ConversationState.parkedCalls` becomes `List<ToolCall>`; `parked(call,
    token)` → `parked(call)`. **Durable states serialized under the old
    shape do not deserialize under the new one; no migration code ships
    (pre-1.0).**
  - `ParkedCall` is replaced by `Parks.Park` (registry) and the snapshot's
    `(token, call)` card shape (which keeps the `ParkedCall` name and record
    definition, now sourced from the registry).
  - `ListMemory` and `JdbcMemory` are deleted in favor of `TranscriptMemory`
    over a `Transcript`.
  - `nessy_memory` renames to `nessy_transcript` (`seq` → `version`);
    `nessy_agenda` → `nessy_inbox`; `nessy_park`/`nessy_token` dropped;
    `nessy_parks` and `nessy_summary` added. Fresh bootstrap only; no data
    migration.
  - `HarnessBuilder.parks(...)` now defaults to `Parks.inMemory()` separately
    from the store: a hand-wired durable deployment that only called
    `.store(...)` must now also add `.parks(JdbcParks.create(...))` (or use
    the starter, which wires both), or every parked token dies with the JVM.
- **`TurnEvent` gains two variants — `AssistantSaid` and `TurnEnded`.** Core
  switches over the sealed grammar (the SSE bridge among them) update at
  compile time, per the sealed-grammar etiquette (no `default` arm in
  `nessy-core`); extender switches that already carry a `default` arm are
  untouched.
- **`UsagePolicy.allow()` returns a canonical singleton.** Behavior is
  identical for every existing caller; identity is newly meaningful only to
  `AgentBuilder`'s all-allow-grant WARN skip above — no other code should
  come to depend on it.
- **The SSE wire vocabulary gains `message`; the framework now emits `done`
  itself.** Additive and shape-compatible — no existing event is renamed —
  but chat-web's controllers no longer hand-synthesize `done`, so a consumer
  that depended on the framework staying silent about the turn's end will
  now see one more event on the wire.
- **The callback doors move off `Harness`, stated loud (design §7).**
  `Harness.resume`/`approve`/`deny`/`progress`/`peek` are **removed**, not
  deprecated — pre-1.0, and a deprecation shim would keep the stateful
  fields alive on `Harness`, defeating the point. Every caller now holds
  the `Agent` it wants a callback answered on and calls the door there
  instead; `Harness` is no longer a place to receive callbacks, only a
  front door for *building* agents.
- **`AgentBuilder.name(String)` is required.** Every existing `build()`
  call site without a declared name now throws `AgentConfigurationException`
  at `build()` time (`.name(...)` itself throws the same
  `AgentConfigurationException` if called with a null or blank name — both
  branches carry the same durable-wire-contract sentence) — every example,
  every test that builds an agent, and every application build site needs
  a name added.
- **`Parks.Park` and the `nessy_parks` schema gain `agent_name`.** `Park`
  grows an `agentName` component; `nessy_parks` grows `agent_name NOT
  NULL`. Pre-1.0: schema recreate, no migration script — a durable
  deployment upgrading across this change starts its parks table fresh.
- **`Harness` is no longer a callback receiver.** Its javadoc and this
  README say what it is instead: substrate, immutable, a front door for
  *building* agents only — every field on `Harness` is final, and no
  method on the class ever writes to one.
