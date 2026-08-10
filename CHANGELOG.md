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

- **The event delivery machinery is named `ListenerRegistry`/`ListenerRegistration`**
  (pre-1.0, never released under any interim name) — `ListenerDeclaration` is
  `ListenerRegistration`; `EventSpine`/`EventSpines`/`SynchronousEventSpine`
  collapse into one final `ListenerRegistry`, whose `extendedWith(...)` builds
  an agent's registry from its harness's (`harnessRegistry.extendedWith(agentRegistrations)`).
- **The razor-bound harness — `Nessy.harness(ModelProvider)` (design §17,
  2026-08-10 evening ruling, pre-1.0 breaking)** — `Nessy.harness(provider)`
  is now THE front door; the provider is the harness's one required thing,
  enforced by constructor signature rather than discovered later at
  `build()`. `Nessy.agent()` and the no-arg `Nessy.harness()` are removed
  outright. The two builders are disjoint: `AgentBuilder` loses every infra
  setter (`.provider`/`.store`/`.observations`/`.mapper`, and the `.events`
  override) — `HarnessBuilder` owns all four exclusively, with the same
  defaults as before (`ConversationStore.inMemory()`, `ObservationRegistry.NOOP`,
  a fresh `ObjectMapper`). The one seeded (not owned-outright) piece is the
  model: `HarnessBuilder#defaultModel(String)` is the harness-wide fallback,
  and agent `.model(...)` always wins when both are set. An agent that needs
  a different provider or store is a **second harness** — one harness per
  infrastructure profile — never an `AgentBuilder` override.
- **`AgentConfigurationException` (new public type, front-door package)** —
  every agent build-time *configuration* failure (currently: no model
  resolves from either the agent or the harness's `defaultModel`) now throws
  this instead of a bespoke `IllegalStateException`, with a message naming
  exactly what is missing. Wiring-desync failures (a hand-rolled grant map
  that disagrees with its tool registry) are unchanged `IllegalArgumentException`
  — those are a caller's programming error at the call site, not an
  incomplete declaration.
- **Declared listening (design §17) replaces the runtime-subscribable hub** —
  both `HarnessBuilder` and `AgentBuilder` gain `listen(Class<T>, Consumer<T>)`
  and `listenAsync(Class<T>, Consumer<T>[, Consumer<Throwable>])`, frozen at
  `build()` (no mutation path exists afterward — Prepare is a build-time
  phase). A harness's declarations seed every agent it builds — before that
  agent's own, in declaration order — reproducing the old "one hub
  subscriber sees every agent's traffic" contract without a shared, mutable
  hub instance: the seeded `Consumer` fires independently for each agent's
  own conversations. Delivery order per emitted event: this conversation's
  dynamic subscribers (see next bullet) first, then the frozen chain,
  harness-then-agent, in declaration order. A throw from a sync declaration,
  in either tier, propagates out and stops delivery to everything after it,
  aborting the operation that emitted — the veto is the throw, unchanged; an
  async declaration never gets that power, since its listener already runs
  off the emitting thread by the time delivery reaches it.
- **`Conversation#events()` — the one dynamic listening level** — returns a
  `ConversationEvents` (new public interface, `api.event`) already scoped to
  that one conversation: `.subscribe(Class<T>, Consumer<T>)` only ever
  delivers events self-attributed (the new `ConversationScoped` marker
  interface, implemented by `ConversationEvent` and every open notice) to
  that conversation's id, so nothing subscribed through it ever sees another
  conversation's traffic — no manual id filtering required any more.
  `Conversation#tell(input, tap)` is now sugar over exactly this: a
  subscription wired for the call's duration and closed when it returns,
  which is why its existing tests — including the foreign-event isolation
  proof — pin unchanged.
- **The `Context` edit algebra (design §10.8, thumbs-upped 2026-08-10)** —
  `Context` (`api.message`) now owns the safe edits over the pairing
  invariant it already enforced, so raw list surgery never has to happen in
  user code. Tier 1, the trusted kernel — the only code that touches the
  message list directly: `drop(Predicate<Message>)` (pair-atomic: matching
  either half of a tool exchange removes the whole exchange; a plain message
  drops on its own), `map(Function<Message, Message>)` (revalidating; a
  pairing-breaking rewrite propagates the constructor's
  `IllegalArgumentException`, naming the orphaned id), and
  `enrich(ContentBlock...)`/`enrich(List<ContentBlock>)` (appends exactly one
  user-role message; null/empty rejected). Tier 2, structural verbs built on
  that kernel: `elideToolResults(int keepRecentMessages)` (absorbs the former
  `Projection.elidingToolResults` — see "Changed" below), `keepRecent(int n)`
  (slides to the nearest pair-safe boundary that keeps at least `n` recent
  messages; unchanged when no boundary exists), and `limitTokens(long budget,
  TokenEstimator estimator)` (drops pair-safe boundaries from the head while
  over budget and a safe cut remains; returns honestly over budget when
  boundaries run out). `tokens(TokenEstimator estimator)` sums the per-message
  estimate across the context. Every verb returns a new validated `Context`
  and uses a bare verb name (JDK-immutable style — `String.strip`,
  `Stream.filter` — never a `with`-prefix). The admission rule: a verb joins
  `Context` only if its correctness depends on the context's own structure —
  pairing, position, size — never anything semantic; redaction, summarization,
  and reordering are deliberately not verbs here.
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
  compaction or enrichment. Typing lives entirely in the facade's generics and
  dissolves at the wire; the sealed `Event` grammar, the reducer, and the
  engine are all unchanged. See `AgentFacadeTest`'s `Typed_front_door` nested
  class and the README's "Typed agents" section.
- **`Harness`** (root) — infrastructure reified. `Nessy.harness()` assembles
  the substrate an application shares across every agent it builds — provider
  default, session store, event hub, observation registry, object mapper —
  once; `Harness#agent()` then returns an `AgentBuilder` seeded with those
  pieces, ready to be given one agent's identity: model, system prompt,
  tools, policies. Two agents built from the same harness share its session
  store and event hub by construction. `Nessy.agent()` survives
  unchanged as sugar over an implicit default
  harness — the front door does not get heavier for the single-agent case.
- **`ToolGrant`/`UsagePolicy`** (`api.tool`) — capability and authority,
  declared together, per tool, per agent. A `ToolGrant(tool, policy)` pairs a
  granted `Tool` with the `UsagePolicy` the engine's one authority chokepoint
  consults before it runs. `tools(ToolGrant...)` is the intended way to attach
  tools to an `AgentBuilder` — see the one-path ruling below, and the later
  deletion of `tools(ToolRegistry)` that finally made it the *only* way. A
  policy that throws or returns `null` fails closed (`PolicyDecision.Deny`),
  never an accidental allow.
- **One path for tool authority (ruled 2026-08-10, pre-1.0 breaking; design
  §17's final addendum)** — `Tool#requiresApproval()` is deleted from the
  interface: a tool is pure capability (name, schema, execution) and carries
  zero authority content. `ToolGrant.grant(Tool<?>, UsagePolicy)` is now the
  sole construction path — no bare `grant(tool)`, no derived floor, no
  `ToolGrant#with(UsagePolicy)` re-dressing. `AgentBuilder.tools(Tool...)` is
  removed outright, since no derivable policy exists any more; every tool
  attachment through `tools(ToolGrant...)` states its policy or fails to
  compile. `tools(ToolRegistry)` was left standing at this point, though —
  runtime-checked, not compile-checked, and only closed later by the
  vestigial-trap deletion below. The grant line is the complete security
  statement, structurally, once that gap is closed too.
- **`ContextEnricher`** (`spi.context`) — the enrichment seam:
  `ContextEnricher.enrich(SessionState)` fetches messages from outside a
  session's own transcript — a graph, a vector store, whatever a caller wires
  up. Memory is just a `ContextEnricher`. Sibling to `Projection`, not a
  subtype: projection stays pure and total, enrichment is I/O and best-effort
  — a downed store or a pairing-invariant-breaking result costs that one
  contributor's enrichment, never the turn, and emits `EnrichmentFailed` on
  the hub. Enrichers key on `SessionState`, not the projected `Context`: the
  context is the thing that will *include* the enrichment, so keying on it
  would be circular, and projection is a wire concern — an elided tool
  result reads `"[elided]"` in the projected context but full text in the
  working set. There is no `ContextEnricher.none()` sentinel; an empty
  enrichment list on `ContextPipeline.Builder` is the degenerate,
  zero-allocation, zero-observation case. Wired via
  `.context(pipeline -> pipeline.enrich(...))` on `AgentBuilder`.
- **`EnrichmentFailed(SessionId, String)`** (`api.event`) — the hub event a
  failed enrichment contributor emits, mirroring `CompactionFailed` exactly:
  the reason one contributor's context enrichment was skipped, for
  observability and alerting.
- **`Agent.contextFor(SessionId)`** and **`ContextPipeline`** (`spi.context`) —
  the debugging affordance that answers *what would a call made against this
  session see right now*, truthfully and without spending a model call:
  `contextFor` loads the session's stored state and runs it through the same
  `ContextPipeline` instance — one implementation of "project, then enrich,
  then compose per placement" — that `InProcessEngine.requestFor` consults on
  every conversational send, so the preview and the real thing can never
  drift apart. Still performs enrichment's I/O to answer, so configured
  `ContextEnricher` contributors are genuinely consulted.
- **`Context`** (`api`) — the pairing invariant's single home: an immutable,
  validated message sequence bound for the wire, whose construction rejects an
  orphan `tool_use`/results pair. `ModelRequest` and `ContextBuilder.project`
  speak `Context` now instead of a plain `List<Message>`; `Effect.Compact` is
  a bare marker (the engine hands the compactor the state it already holds),
  and `Compactor.compact`'s returned working set is validated as a `Context`
  at the engine's compact-result check.
  Pair-safe cutting and head/tail slicing (`Context.pairSafeCut(int)`,
  `Context.head(int)`) live on the type, so the reducer, the default
  summarizer, and any custom compactor share one implementation of "where may
  I cut?".
- **`Compactor`** (`spi.compaction`) — the one compaction seam, replacing the
  earlier `CompactionStrategy`/`CompactionPolicy`/`CompactionTrigger` split
  (owner ruling 2026-08-10: the consolidation). `requiresCompaction(SessionState)`
  (pure, consulted by the reducer at every `CallModel` decision point) and
  `compact(SessionState)` (effectful, performed by the engine only, seeing the
  whole ledger rather than a bare message list) are the whole interface. The
  compactor proposes a replacement working set; the reducer disposes —
  applying the result, bumping `generation`, and treating a non-shrinking
  result as a skip. `Compactor.disabled()` never compacts.
  `AgentBuilder.compaction(Compactor)` is the single overload — no more
  policy-versus-strategy ambiguity.
- **`Compactors`** (`spi.compaction`) — the summarizing default's factory:
  `Compactors.summarizing(summarizer)` returns a builder whose knobs each
  belong to their owner — `.triggerTokens(long)` (fires once measured input
  tokens cross it; default 100k), `.window(window, maxTokens)` (derives the
  trigger at ≈ 0.8 × (window − maxTokens), reserving room for the reply; wired
  automatically when `AgentBuilder.contextWindow(...)` is declared and no
  explicit compactor is set), and `.keepRecent(int)` (how many trailing
  messages survive verbatim; default 10). `AgentBuilder` assembles this
  default automatically from the harness's provider unless
  `.compaction(Compactor)` replaces it outright.
- **`Compactors.window(int keepRecent)`** (`spi.compaction`) — a zero-spend,
  lossy alternative to the summarizing default: once triggered, it drops the
  working set's head at the nearest pair-safe boundary via
  `Context.of(state.messages()).keepRecent(keepRecent)` and hands that back as
  the `Result` — no model call, no summary, no spend. Its builder shares the
  exact same trigger knobs as `Compactors.summarizing` — `.triggerTokens(long)`
  (default 100k) and `.window(window, maxTokens)` (the same ≈ 0.8 ×
  (window − maxTokens) derivation) — so switching between the two is a
  one-line change. History earlier than the boundary is simply gone, not
  condensed; reach for `Compactors.summarizing` when losing the earliest
  turns outright is not an acceptable trade.
- **Build-time defaults that announce themselves** — `HarnessBuilder` and
  `AgentBuilder` now resolve every default at `build()` instead of in field
  initializers: each knob's field starts `null`, and `build()` resolves it
  via `Optional.ofNullable(x).orElseGet(this::defaultX)` — one named,
  documented `defaultX()` method per knob (`defaultStore`,
  `defaultObservations`, `defaultMapper` on `HarnessBuilder`;
  `defaultSystemPrompt`, `defaultMaxTokens`, `defaultCapabilities`,
  `defaultTools`, `defaultGrants`, `defaultApprover`, `defaultTermination`,
  `defaultContextCustomizer`, `defaultCompactor` on `AgentBuilder`).
  Behavior is unchanged for every knob whose default was already quiet;
  `defaultApprover()` and `defaultCompactor()` are the two knobs design
  §13.1 requires to announce themselves, so they now each log an SLF4J
  warning, once per agent `build()`, naming what defaulted and how to
  configure it instead:
  - `defaultApprover()` — falling back to `Approver.allowAll()` (core's
    approver default is allow-all today, not deny) logs a warning naming the
    fallback and pointing at `.approver(...)`, exactly as §13.1 requires of
    the classpath-upgradeable default.
  - `defaultCompactor()` — falling back to the summarizing compactor logs a
    warning naming the algorithm (`summarizing`), the trigger (the literal
    100k default, or the `contextWindow`/`maxTokens` it was derived from),
    `keepRecent` (10), the summarizing model, and `.compaction(...)` as the
    way to configure a different one.
- **SLF4J as `nessy-core`'s own logging façade** — `org.slf4j:slf4j-api` is
  now a direct `nessy-core` dependency (previously only a transitive one,
  pulled in by victools; confirmed via `dependency:tree` before adding it,
  and version-aligned with the same `slf4j.version` property the test-scoped
  `slf4j-simple` provider already used). `HarnessBuilder`/`AgentBuilder`'s
  `listenAsync(Class, Consumer)` convenience — the one that reports a failed
  async listener for you — now logs through an SLF4J `Logger` instead of a
  JDK `System.Logger`, the same channel the new default-announcing warnings
  above use. Logging policy: warnings for surprising defaults only, never
  hot-path chatter.
- **The jurisdiction rule (ruled 2026-08-10) — the ledger bills the loop,
  telemetry bills the rest.** `SessionState.usage()` accumulates only what
  `ModelTurnEnded` reports for the loop's own conversational turns.
  `Compactor.Result` and `Event.Compacted` carry no `Usage` component; the
  reducer's `compacted` handler never touches `usage()`, shrink or skip.
  Whatever a compactor's own call costs — the summarizing default's
  input/output tokens today, a tool's internal model calls tomorrow — is
  auxiliary spend and stays out of the ledger entirely: the summarizing
  compactor instruments its own model call as a `nessy.model.call`
  Micrometer observation, nested under `nessy.compaction`, using the exact
  span-name/attribute-key conventions the engine's own conversational calls
  use. (This ruling supersedes an earlier same-day decision — briefly
  implemented — to bill a compactor's spend into `SessionState.usage()`
  alongside every conversational turn; that plumbing is gone.)
- **`Summarizer`** (`spi.compaction`) — the summarizing default's sub-seam:
  `summarize(Context head) -> String`, with instructions and the summary's
  own token ceiling baked in at construction rather than threaded per call.
  Lets "same compactor, cheaper model" swap in without reimplementing cut
  logic. `Summarizer.usingProvider(provider, config, summaryMaxTokens,
  instructions, observations)` is the tool-free summarization call the
  engine always performed before this seam existed, instrumented as its own
  `nessy.model.call` observation on the supplied `ObservationRegistry` per
  the jurisdiction rule above; the 3-arg `usingProvider(provider, config,
  observations)` convenience defaults to a 2,048-token ceiling and
  `Summarizer.DEFAULT_INSTRUCTIONS`. `AgentBuilder.summarizer(...)` overrides
  what the assembled default calls, ignored once `.compaction(Compactor)`
  replaces the mechanism outright. `ScriptedSummarizer` ships in
  `nessy-testing` beside the other test doubles, scripting plain `String`
  summaries (plus a throwing mode).
- **The journal is a listener, finally and fully (design §17, pre-1.0
  breaking; nothing released) — `TranscriptStore`, `TranscriptEntry`,
  `InMemoryTranscriptStore`, and the `.transcript(...)` builder knob are
  retired outright, never having shipped.** The journal was never anything
  but a subscriber on `MessageAppended(conversationId, message, turnUsage)`
  (`api.event`) — this history briefly gave that subscription its own store
  interface and builder sugar (`feedFrom`/`declareListener`,
  `.transcript(store)`), then found that vehicle added a type and a knob for
  a use no listener declaration couldn't already express. A journal today is
  simply `.listen(MessageAppended.class, journal::add)` on either builder —
  sync (the audit-grade default: a throwing listener fails the run, the
  synchronous spine's veto-by-throw, but the conversation's snapshot already
  reached the `ConversationStore` still saves) or `.listenAsync(...)` for a
  best-effort posture — with no sentinel for "no journal": the absence of a
  declaration already says that. `MessageAppended`'s `Usage.zero()` for every
  non-assistant newborn, including a compaction summary, is unchanged — the
  jurisdiction rule above means the journal never sees a compactor's spend
  either.
- **`MessageCodec`** (`spi.conversation`) — the `Message ↔ byte[]` translation
  a durable store needs to persist opaque bytes rather than message
  structure. Default is `MessageCodec.json(mapper)`; encryption at rest is
  meant to compose as a codec *decorator* over any store, not a per-vendor
  reimplementation.
- **`spi.context` and `spi.compaction` packaging** — collaborators now live
  next to the seam they serve: `spi.context` holds `Projection` and
  `ContextPipeline` (`ContextBuilder`, briefly moved here from `spi` root,
  has since dissolved into `Projection` — see "The context pipeline" below)
  and the new `TokenEstimator`; `spi.compaction` holds the whole compaction
  seam — `Compactor`, `Compactors`, `Summarizer`.
  `TokenEstimator.estimate(Message)` (default `heuristic()`,
  content characters / 4) manufactures the per-message token figure no
  provider reports, computed on demand on the read path only — never
  journaled, so a frozen estimate can't rot the permanent record.
- **`ModelRequest` carries `Context`** — `ModelRequest.context()` replaces the
  plain message list it used to carry, so every provider now receives the
  same validated, pairing-legal sequence the rest of the read path already
  guarantees.
- **Compaction stays best-effort** — a failed summarization call skips
  compaction for that turn rather than failing it, and emits
  `CompactionFailed` on the hub; instrumented via the `nessy.compaction`
  observation alongside the engine's other spans.
- **The context pipeline: `ContextPipeline`, `Projection`, `Placement`**
  (`spi.context`; supersedes `ContextBuilder` and `ContextAssembler`, both
  dissolved — see "Removed" below) — the Contextualize phase (design §6.1,
  §10.9), the one lifecycle phase with fully open, Maven-style binding:
  `.context(pipeline -> pipeline.project(...).enrich(...).placement(...))` on
  `AgentBuilder` replaces the old `.contextBuilder(...)`/`.memory(...)` pair
  outright. `Projection` (`Context apply(Context context)`) is pure and
  total, applied in declaration order to the `Context` minted from the
  session's messages; the standard elision idiom is a lambda over `Context`'s
  own edit algebra (see "The `Context` edit algebra" above) —
  `ctx -> ctx.elideToolResults(keepRecentMessages)` (formerly
  `ContextBuilder.elidingToolResults`) — replacing the content of older tool
  results with a placeholder while keeping the recent window verbatim,
  trading prompt-cache hits for context space. The empty projection list is
  identity — there is no dedicated `Projection.identity()` factory, the
  empty list already says it.
  `ContextEnricher` contributors run after projection, each independently
  best-effort under its own `nessy.context.enrich` observation, concatenating
  in declaration order; `ContextPipeline.Placement` (`ENRICHMENTS_FIRST`, the
  default, and `ENRICHMENTS_LAST`) decides where the enriched block lands
  relative to the projected transcript. Project runs before enrich by
  jurisdiction, not sequence: enrichers key on the ledger, so ordering costs
  them nothing, while projections govern the transcript's wire form — enriched
  material must stay outside their reach. `ContextPipeline` is constructed
  once per agent at `AgentBuilder.build()` time and shared by
  `InProcessEngine.requestFor` and `Agent.contextFor`, exactly as
  `ContextAssembler` was.
- **Context pipeline vocabulary settled (design §10.9, 2026-08-10)** — this
  pipeline's working names before its first release — `Shape`, `Memory`
  (`spi.memory`), `.recall(...)`/`.shape(...)`, `RecallFailed`, and
  `MEMORIES_FIRST`/`MEMORIES_LAST` — are renamed throughout to their settled
  form: `Projection`, `ContextEnricher` (`spi.context`; `spi.memory`
  dissolves), `.project(...)`/`.enrich(...)`, `EnrichmentFailed`, and
  `ENRICHMENTS_FIRST`/`ENRICHMENTS_LAST`. A pre-release rename, not a breaking
  change — none of the old names ever shipped.
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

- **The compactor is built, not configured through the agent (owner ruling,
  2026-08-10, pre-1.0 breaking)** — `AgentBuilder` loses `.summarizer(Summarizer)`,
  `.summaryMaxTokens(int)`, and `.summaryInstructions(String)` outright;
  `.compaction(Compactor)` is the only compaction-related method left on it.
  Tuning the default summarizing compactor's own knobs — the summary reply's
  token cap and its instructions, alongside the trigger and keep-recent knobs
  those knobs' siblings already lived on — now means building a `Summarizer`
  and a `Compactor` explicitly via `Compactors.summarizing(...)` and handing
  the result to `.compaction(...)`; there is no longer a one-path-plus-knobs
  hybrid. `Summarizer.usingProvider` is re-signatured to match: it takes a
  bare `(ModelProvider, String model, int summaryMaxTokens, String
  instructions, ObservationRegistry)` instead of a full `ModelSettings`, with
  a `(ModelProvider, String model, ObservationRegistry)` convenience
  defaulting the ceiling and instructions. **Behavior change:** because the
  production summarizer no longer takes a `ModelSettings`, it no longer has
  an agent's `systemPrompt()` to forward — every summarization request now
  carries no system prompt at all, so an agent's persona no longer steers how
  its own history gets summarized. The default, uncustomized compactor
  `AgentBuilder.build()` assembles when `.compaction(...)` is never called is
  unaffected in its own effective defaults (a 2,048-token summary ceiling,
  `Summarizer.DEFAULT_INSTRUCTIONS`, a 100k trigger or a window-derived one,
  `keepRecent` 10) — only where those defaults live moved, from builder
  fields to `AgentBuilder`'s own internal assembly.
- **The hub is demoted: `EventHub`/`SynchronousEventHub` leave the public
  surface entirely (design §17, pre-1.0 breaking; supersedes the "`EventHub`
  subscribers choose sync or async" entry directly below, whose subscribe/
  subscribeAsync surface no longer exists)** — `EventHub.synchronous()`,
  `.subscribe(Class, Consumer)`, and `.subscribeAsync(...)` are gone.
  `HarnessBuilder#hub(EventHub)` dies with them. The replacement is entirely
  in `api.event`: `EventEmitter` survives, public, unchanged in shape (plus a
  new `EventEmitter.noop()` convenience) — it is what `ToolContext#events()`
  still exposes to tools. `EventSpine` (new, public, `extends EventEmitter`)
  is the narrower per-agent delivery apparatus a `HarnessBuilder`/
  `AgentBuilder` assembles at `build()` via the new `EventSpines.of(List<ListenerDeclaration>)`
  factory from the harness's seeded declarations plus the agent's own; its
  only capability beyond emitting is `forConversation(ConversationId)`,
  returning the scoped `ConversationEvents` view `Conversation#events()`
  hands out. `ListenerDeclaration` (new, public) is the frozen unit `listen`/
  `listenAsync` capture. There is no general, agent-wide, runtime-attachable
  subscription left anywhere — an agent-wide observer is declared once, at
  build time; see "Declared listening" above.
- **`EventHub` subscribers choose sync or async at subscription time
  (pre-1.0 breaking)** — the static `EventHub.async(listener, onError)` /
  `EventHub.async(listener)` wrapper helpers are removed outright; their
  implementation folds into two new default methods,
  `subscribeAsync(Class<E>, Consumer<E>, Consumer<Throwable>)` and the
  `System.Logger`-backed convenience `subscribeAsync(Class<E>, Consumer<E>)`.
  The hub itself is always the synchronous spine — `subscribe` still delivers
  in subscription order, on the emitting thread, veto-by-throw — but a
  subscriber now declares sync or async once, at the call that registers it,
  rather than wrapping its own listener before handing it to `subscribe`.
  Both overloads are default methods on `EventHub` delegating to `subscribe`
  with a wrapped consumer, so `SynchronousEventHub` (and any other
  implementation) gets async-for-free without knowing about virtual threads.
  The returned `Subscription` is the same type regardless. Async delivery
  still starts one fresh virtual thread per event, now named
  `nessy-delivery-*`; the subscribeAsync javadoc carries the ordering caveat
  that follows from that (an async subscriber may observe events out of
  order under load — order-sensitive subscribers stay sync). Mechanical fix:
  `hub.subscribe(type, EventHub.async(listener, onError))` →
  `hub.subscribeAsync(type, listener, onError)`.
- **`Projection.elidingToolResults(keepRecentMessages)` dissolves into
  `Context.elideToolResults(keepRecentMessages)` (pre-1.0 breaking)** — the
  standard elision projection is now a verb on `Context`'s own edit algebra
  (see "Added" above) instead of a factory returning an opaque `Projection`
  implementation. The pipeline idiom becomes
  `.project(ctx -> ctx.elideToolResults(2))`; the `elidingToolResults` factory
  method and the package-private `ElidingToolResults` class are both removed
  outright — there is no deprecation window pre-1.0. Every call site (README,
  `AgentBuilder`/`ContextPipeline` javadoc, `EndToEndTest`, `AgentFacadeTest`)
  moves to the lambda form; behavior is pinned end-to-end by
  `ContextPipelineTest` and `ContextTest`'s `Eliding_tool_results` tests, which
  port the old `ProjectionTest` scenarios verbatim.
- **`Conversation.send(String)` → `Conversation<I>.tell(I)` (pre-1.0 breaking)** —
  `send` and its tap overload are removed outright; `tell` (and
  `tell(I, Consumer<Event>)`) are the only way to advance a conversation now.
  `Agent`, `Conversation`, and `AgentBuilder` all pick up the `<I>` input-
  vocabulary type parameter; `Agent<String>`/`Conversation<String>` is the
  drop-in replacement for every existing `Nessy.agent()` call site — the
  mechanical fix is `.send(x)` → `.tell(x)` plus spelling out `Agent<String>`
  wherever the raw type was written. See "The typed front door" above.
- **`ContextEnricher.enrich` cues on `SessionState`, not `Context`** —
  enrichment cues on the ledger, not the projected `Context`: the context is
  the thing that will *include* the enriched messages, and projection is a
  wire concern (an elided tool result reads `"[elided]"` in the projected
  context but full text in the working set), so enrichment relevance should
  key on the conversation's truth. `enrich(SessionState)`'s argument mirrors
  what a `Projection` sees before projecting, and `ContextPipeline`
  concatenates every contributor's output.
- **`ContextBuilder` and `ContextAssembler` dissolved into the context
  pipeline (pre-1.0 breaking)** — `spi.context.ContextBuilder` and
  `spi.ContextAssembler` are deleted outright, not deprecated.
  `ContextBuilder.identity()` is now simply the empty projection list on a
  `ContextPipeline.Builder`; `ContextBuilder.elidingToolResults(n)` moved to
  `Projection.elidingToolResults(n)`, taking and returning `Context` instead
  of projecting from `SessionState`. `AgentBuilder.contextBuilder(...)` and
  `AgentBuilder.memory(...)` are both replaced by the single
  `AgentBuilder.context(Consumer<ContextPipeline.Builder>)`. See "The context
  pipeline" above for the full shape.
  the root `api` package now holds only the sealed grammar (`Event`,
  `Decision`, `Awaited`, `RunOutcome`, `ParkToken`, `StopReason`); everything
  else moved into a named subpackage: `Message`, `Role`, `Context`,
  `ContentBlock` and its variants moved to `api.message`; `SessionId`,
  `SessionState`, `SessionStatus`, `Usage`, `TerminationPolicy` moved to
  `api.session`; `ToolCall`, `ToolResult` moved to `api.tool`
  alongside `Tool`. No type was renamed and no signature changed — this is a
  pure package move; source using the old `org.jwcarman.nessy.api.*` imports
  for these types must update the import statement only. Compaction's own
  types took a further step: `api.compaction` (`CompactionStrategy`,
  `CompactionPolicy`, `CompactionTrigger`) is dissolved outright rather than
  kept as a resting place — see the `Compactor` consolidation above.
- **`AgentBuilder.tools(...)` source-compat note (pre-1.0 breaking, superseded
  twice over — first by the one-path ruling above, now by the
  `tools(ToolRegistry)` deletion below)** — the `Tool...` overload this note
  originally described no longer exists at all, and neither does
  `tools(ToolRegistry)`: `tools(ToolGrant...)` is the only overload left. A
  bare `.tools()` call (zero arguments) resolves unambiguously to it; `tools`
  still defaults to an empty `ToolRegistry.of()` with no grants when never
  called.
- **`AgentBuilder.tools(ToolRegistry)` deleted (pre-1.0 breaking; review
  finding I1)** — a vestigial trap: a non-empty registry attached this way
  carried no grants, so the engine's construction-time check ("no grant for
  tool: …") threw for every tool in it, always. It had zero call sites
  anywhere in this codebase. `tools(ToolGrant...)` — see the one-path ruling
  above — is now the only way to attach tools to an `AgentBuilder`, and the
  "states its policy or does not compile" promise made above finally holds
  with nothing left to route around it.
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
