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

> **Note.** Much of the narrative below predates the agent-as-scope rebuild
> and was superseded on 2026-08-20 — `Nessy.harness`/`Agent#converse().tell()`,
> `ParkToken`, planning, the notebook, and reflection among them. The design
> of record is now the agent-as-scope, durable-computation, and
> action-and-tool-vocabulary specs; the entries added for the 2026-08-20
> waves (the doors, the action wave) describe the current shape and are
> called out as such below.

### Added

- **Agentic observability: one fact stream, `HarnessConfig.observationRegistry(ObservationRegistry)`,
  and a roster of OTel GenAI spans and counters.** `nessy-agent` now folds every
  event through one harness-level stream — `DefaultAgent`'s synchronous shell
  and `DeliveryWorker`'s durable one both publish `(agentId, event, transition)`
  to it — and a package-private `Observations` object subscribes to turn it
  into Micrometer `Observation`s: `invoke_agent {agentType}` per *segment*
  (from a resuming delivery to the next idle or park — never spanning a park
  itself, since an in-process span cannot survive a restart), `chat {model}`
  per model call (carrying the vendor's own `gen_ai.usage.input_tokens`/
  `output_tokens`, discarded until now), `execute_tool {tool}` per tool run,
  and Nessy's own `nessy.approval.wait`/`nessy.tool.wait` dwell spans plus
  three engine counters (`nessy.delivery.dropped`, `nessy.state.stale_retries`,
  `nessy.effects.refired`). Pinned against the OpenTelemetry GenAI semantic
  conventions' **2025 attribute revision** (`gen_ai.provider.name`, renamed
  from the older `gen_ai.system`) — semconv is still *development* status
  upstream, so this is the version implemented against, not a promise it
  won't move again. `HarnessConfig.observationRegistry(ObservationRegistry)`
  is the one seam, default `ObservationRegistry.NOOP`: absent an
  application-supplied registry, the whole roster is inert and free.
  `nessy-agent` depends on `micrometer-observation` alone — exporters, the
  OTel tracing bridge, and OTLP live in the application; `nessy-examples/observed`
  is the runnable reference, exporting traces (OTLP/gRPC), metrics
  (OTLP/HTTP), and logs (the OTel logback appender) to Grafana's
  `otel-lgtm` image, with no exporter ever throwing through a turn even when
  no collector is listening. See the [observability guide](docs/guides/observability.md)
  for the full roster.
  - **`HarnessObserver` (`org.jwcarman.nessy.agent.spi`), renamed from
    `AgentObserver` — breaking for any out-of-tree implementation.** Every
    method gains a leading `AgentId` parameter: it observes the harness's one
    fact stream now, not a single scope stamped fresh per id by a factory.
    `HarnessConfig.agentObserver(...)` is renamed `harnessObserver(...)`
    to match. `Harness.subscribe(HarnessObserver)` is the stream's
    package-private door beside the existing `subscribe(AgentId, TurnObserver)`;
    the application-facing door stays `HarnessConfig.harnessObserver(...)`.
  - **`Model.provider()` (`nessy-spi`) — new method, no default, breaking for
    any out-of-tree `Model` implementation.** Returns the semconv
    `gen_ai.provider.name` value the bound handle answers for at its vendor
    (`anthropic`, `openai`, `x_ai`, `gcp.gemini`, `aws.bedrock`, or a test
    double's own name) — asked of the `Model` rather than its `ModelProvider`
    gateway on purpose: the executor that opens `chat` holds a bound `Model`
    and never sees the gateway, and one gateway class can serve several
    vendors (the OpenAI-compatible gateway answers `openai` for an OpenAI key
    and `x_ai` for an xAI one). No default, so a new vendor cannot silently
    report someone else's name. `ModelProvider.name()` is untouched — it
    remains the human-readable banner string.
- **`HarnessConfig.continuum(Continuum)`: the harness accepts its computation
  store.** Omitted, the harness mints a private in-memory Continuum as before;
  supplied, it uses yours — a `continuum-jdbc`-backed one for parked calls that
  survive the process, or one instance shared by several harnesses so any of
  them delivers what another parked. The durability-mismatch warning now fires
  only when it knows the tiers differ (a durable substrate with no Continuum
  supplied) rather than comparing against a hard-coded `true`. Proven twice:
  `SharedContinuumTest` (two harnesses, one in-memory Continuum, no database)
  and `DurableResumeTest` (`JdbcSubstrate` + `continuum-jdbc` over one
  PostgreSQL container: park on harness A, shut it down, approve on a fresh
  harness B, watch the turn complete) — the first test in the tree to exercise
  a durable computation store. Continuum is now 0.4.0.
- **Model provider discovery: `nessy-model-env` becomes `nessy-model-discovery`,
  and depends on no provider module.** Providers register a new SPI type,
  `ModelProviderBootstrap`, through `ServiceLoader`; `ModelDiscovery` loads
  whatever is on the classpath and bootstraps it from the environment. A
  hello-world agent's compile classpath drops from 99 jars to under 40.
  Removed with it: `NESSY_PROVIDER=bedrock` (Bedrock registers nothing —
  construct it directly), the `grok` alias for `xai`, and the
  warn-and-default on ambiguous keys, which now fails fast naming every
  candidate.
- **Codec adoption: the homegrown `Codec`/`CodecFactory` retire in favor of
  `org.jwcarman.codec` 0.2.0.** `org.jwcarman.nessy.spi.substrate.Codec` and
  its `CodecFactory` are deleted; every reference moves to
  `org.jwcarman.codec.spi.Codec`/`.CodecFactory` (`codec-core`, Central,
  0.2.0 — no snapshots), backed by `org.jwcarman.codec.jackson2.Jackson2CodecFactory`
  (`codec-jackson2`) as the Jackson binding. `then` is renamed `andThen`
  throughout; `CodecFactory#codec(Class)` is renamed `create(Class)`.
  `SubstrateSupport` mints one `Jackson2CodecFactory` over its
  copy-and-pinned mapper — the codec extension point and the pin logic are
  unchanged, only the factory implementation moves. `SubstrateBacklog`'s
  outer `String[]` envelope now mints from the substrate's own codec
  factory (`store.document(kind, String[].class)`) rather than constructing
  a second, redundant `Jackson2CodecFactory` per instance. Exception
  contract: the external codec throws `UncheckedIOException`; the typed
  views (`SubstrateDocumentStore`/`SubstrateJournalStore`) translate that
  into the same teaching `IllegalArgumentException` every malformed-payload
  test has always seen, naming the `kind`. Wire formats are byte-identical
  — the same mapper, the same `writeValueAsBytes`/`readValue` path — proven
  by the raw-bytes-pinning tests (`SubstrateBacklog`'s base64 envelope,
  `OutcomeCodec`'s wire shape, `SubstrateMemory`'s legacy-transcript
  fallback), all passing unchanged.

- **`tell`/`ask`/`subscribe`, and the console.** `Agent#observe` is renamed
  `Agent#tell` — the caller-perspective, fire-and-forget verb; `drive()` is
  unchanged, the manual pump. `Agent#subscribe(TurnObserver)` returns a
  `Subscription` (`nessy-api`, `AutoCloseable`, idempotent close, never
  throws) routing into a per-agent-id fanout the harness now carries
  internally (`TurnFanout`) — a subscriber sees `TextDelta`, `ThinkingDelta`,
  `RedactedThinking`, `ToolCallRequested`/`Completed`/`Progressed`,
  `AssistantSaid`, and `TurnEnded` for its id, whether the turn settles
  synchronously or a worker-driven delivery folds it days later; the
  harness's own configured `turnObserver` rides the same fanout as one more
  subscriber, running last and unguarded, so a throwing global observer
  keeps its long-standing abort-the-call meaning without starving any
  `subscribe`d observer of the event first. `Agent#ask(O)` is the pattern
  built on top — subscribe a private capture, `tell`, block for the turn's
  own outcome, close — resolving a sealed `TurnOutcome`
  (`Replied(String)`/`Parked(ApprovalRequest)`/`Failed(String)`, in
  `nessy-agent`) read entirely off that same event grammar: `Replied` and
  `Failed` from `AssistantSaid`/`TurnEnded`, `Parked` off-channel through the
  harness's existing §5a approval notifier, since a parked call is never
  narrated at all. Zero new event types.

  `Console` (`nessy-agent`'s host package) is the CLI front end: `approver()`
  renders a flattened `ApprovalRequest` (`id`, `call`, `agentType`,
  `agentId`) and reads y/n(+reason), answering through
  `Harness#approvals()` by `request.id()`; `run()` is the read-`ask`-print
  loop — `Replied` prints, `Parked` hands off to the approver face and
  waits for the same turn to settle, `Failed` says so honestly. `Nessy.cli()`
  keeps its builder shape and now composes a real kept `Harness` (via
  `Nessy.harness`, not a bespoke wiring of its own) with a fresh `Console`;
  `.build()` returns `Console`, not the retired `CliAgent`. This closes a
  real bug in the old wiring: `CliAgent`'s narrator pointed straight at its
  `RelayTurnObserver` instead of the per-id fanout the generic door already
  routes through, so `ask` hung forever on a cli-built agent whenever a tool
  call needed approval — delegating to the shared door fixes it by
  construction, and gives the cli door the same `ComputationApprover`-backed
  §5a park it always should have had. `CliBuilder` gains `.grants(ToolGrant...)`
  (sharing one slot with `.tools(Tool...)`, matching `HarnessConfig`) and
  `.in(InputStream)`/`.out(PrintStream)` overrides for embedding or
  scripted-IO testing; its default conversation store moves from a bespoke
  `VerbatimMemory` to the same in-memory-substrate-backed `Memory` every
  other harness door defaults to.

- **Typed stores: `DocumentStore<T>`/`JournalStore<T>` over the substrate,
  a substrate-level codec factory, op minting.** `Substrate` gains
  `document(kind, Class<T>|Codec<T>)` and `journal(kind, Class<T>|Codec<T>)`
  default methods minting typed views implemented once, as library code: the
  codec dance, the version plumbing, and the CAS-retry read-modify-write
  loop (`DocumentStore#update`) live in the view, not in every feature.
  `Substrate#codecs()` exposes one `CodecFactory`, backed by one pinned
  `ObjectMapper` per substrate instance (statics-die law) via the new
  `SubstrateSupport` base class every concrete substrate extends;
  overriding the mapper at construction (`InMemorySubstrate(ObjectMapper)`,
  `InMemorySubstrate(Clock, ObjectMapper)`) is the codec extension point.
  Both views mint batch-composable ops (`writeOp`/`deleteOp`/`appendOp`)
  lowering to the existing `Substrate.Op` primitives, so a multi-store
  atomic commit (fold-advance, completion) composes typed writes from
  several stores into one `Substrate#batch` call. `Versioned<T>` is the
  small value+version carrier `DocumentStore#read` returns.
  `SubstrateBacklog`, `SubstrateMemory`, `SubstrateAgentStateStore`,
  `SubstrateIntentStore`, and `SubstrateComputations` all rebase onto the
  typed views; every hand-rolled CAS-retry loop and manual
  `writeValueAsBytes`/`readValue` call site in these recipes retires. The
  parked `HarnessConfig.backlogCodec` fallback derivation (a hand-rolled
  `Codec.json(pinned, observationType)` for the typed door) retires in
  favor of the default substrate's own codec factory, constructed over the
  same pinned mapper every other recipe threads through — wire-format
  identical for the default (no explicit `.substrate(...)`) case. Wire
  formats and CAS/batch semantics are unchanged throughout.

- **Opaque computation identity, kind-scoped keyspaces, replayable delivery.**
  `CallAddress.approval()`/`.execution()` derive a `ComputationId` by
  digesting — SHA-256 over a length-prefixed encoding of `(purpose,
  agentType, agentId, responseId, callId)`, lowercase hex — rather than
  concatenating a colon-delimited string; the id carries no extractable
  structure and nothing anywhere parses one back apart. Execution
  computations, approval computations, and the outbox each get a
  per-agent-type kind (`computation/<agentType>`, `approval/<agentType>`,
  `outbox/<agentType>`) instead of one shared kind distinguished by a key
  prefix, so isolation between agent types is by construction — the
  type-filtered runtime sweep (`isForeignTypeComputation`, the `approval:`
  key-prefix skip, the outbox minimal-peek foreign-type filter) is retired
  along with it. A completion's delivery is now keyed by the completed
  computation's own id, not a fresh random key; a replayed creation under
  that key converges instead of duplicating, which closes the
  grant-delivery-pending window a staleness redrive used to fall through
  (`ComputationDeferredToolCallPolicy#pendingComputation` now also checks
  for a pending delivery at that deterministic key). `ApprovalRequest`
  (`nessy-spi`) carries the approval `ComputationId` directly (`id`) plus
  plain `agentType`/`agentId` strings for display, rather than the full
  `CallAddress` — a computation-backed approver reads the committed
  `responseId` it needs for continuation-building from the agent's own
  state at ask time instead (identity spec §6); `ToolContext`
  (`nessy-api`) exposes a tool's stable idempotency key as a single opaque
  `invocation` (`ComputationId`) rather than a structured address pair. The
  `api.computation` package is retired: `ComputationId` moved beside
  `CallAddress` (now in `nessy-agent`, alongside `ToolInvocationId`); the
  rest of its residents (`Continuation`, `Outcome`, `PendingComputation`,
  `CreateResult`, `CompletionResult`) sank into `nessy-agent`, package-private
  except where a desk's signature forces otherwise. `Outcome.Success`
  carries its payload data-born (an already-encoded `JsonNode`) rather than
  a raw `Object`, since a `ToolResult` and a `Decision` both flow through it
  and neither alone is the whole vocabulary.
- **The front door.** `Nessy.harness(HarnessCustomizer)` builds a `Harness` —
  the infrastructure an application shares across every agent it builds:
  model provider, conversation store family, observation registry, object
  mapper, declared listeners. A customizer lambda fills in a `HarnessConfig`
  — fluent setters, no public `build()` — and the factory validates it the
  instant the lambda returns. `Harness#agent(AgentCustomizer<String>)` and
  `Harness#agent(Class<I>, AgentCustomizer<I>)` (typed) hand the same
  customizer idiom an `AgentConfig` seeded with that infrastructure.
  `Agent#converse()` opens a `Conversation`; `.tell(input)` (or
  `.tell(input, TurnObserver)` to narrate the turn live) returns a
  `RunOutcome` — `Completed` or `Parked` — carrying the settled
  `ConversationState`.
- **The durable loop: two effects, four facts, one fold.** An agent's whole
  semantics lives in `ConversationState.fold(ConversationEvent)`, a pure,
  parameter-free method exhaustive over a sealed four-fact grammar —
  `AgentTold`, `ModelResponded`, `ModelCallFailed`, `ToolFinished` — folding
  to a `Step` (state, new messages, effects). Only two effects exist,
  `CallModel` and `ExecuteTool(ToolCall)`; every conversation is a durable,
  at-least-once inbox, so a redelivered turn folds without duplicating
  effects.
- **`PARKED` conversations and durable callbacks.** A tool or approver that
  must outlive the process returns `Awaited.parked(token)` instead of a
  ready result; the conversation persists as `PARKED` and a later
  `Agent#resume`/`approve`/`deny` — against a `Subagent` handle or the top
  level `Agent` — drives it to completion, in this process or a fresh one.
  Parking is repeatable: a resumed call may itself park again (an approval
  wait followed by the tool's own execution wait), each on a fresh token, at
  most one of each outstanding at a time.
- **Tools and grants.** `Tool<I>` is a name, a description, an `inputType()`
  whose JSON Schema the model sees without being hand-written, an
  `execute(I, ToolContext)`, and a `requiredCompletion()` declaring the
  strongest completion semantics the tool needs. `ToolGrant.grant(...)` is
  the one way a tool reaches an agent, so a tool's authority is stated at
  the grant line, not buried in the tool's own code. `Tool.of(Class<T>,
  ToolCustomizer<T>)` composes a first-party tool from a customizer —
  `executes(Function<T,?>)`, `executes(BiFunction<T,ToolContext,?>)`, or
  `defers(BiConsumer<T,ToolContext>)` (which sets `DURABLE` automatically)
  — so a three-line tool needs no class.
- **Authorization: a ladder from a static verdict to a typed, enriched
  decision — action, not effect.** `UsagePolicy<A>.evaluate(AuthzContext, A)`
  is the tool call executor's one authority chokepoint, consulted before the
  tool runs and before the approver is ever asked; the decision vocabulary is
  always the same sealed three, `Allow`, `Deny(reason)`, `RequireApproval`.
  Authorization begins with the **grant's** statement of the action, not the
  tool's: in every mainstream authorization model (XACML, AWS IAM, Cedar)
  "effect" already names the verdict, so nessy's own vocabulary renamed its
  "effect" to "action" and moved the speaker off the tool — `EffectfulTool`
  and `Tool.effect(T)` are deleted, and a third-party tool is governable with
  no wrapping. Rigor rises in rungs, and a grant that never climbs past one
  costs nothing for the rungs above it: rung 0 is `UsagePolicy.allow()`/
  `.deny(reason)` — canonical statics that skip action rendering, context
  assembly, and every enricher entirely; rung 1 is a lambda reading
  `AuthzContext.call()`; rung 2 welds an `ActionContributor<I, A>` (`A
  actionOf(I input)`) to the policy at compile time via
  `ToolGrant.grant(tool, contributor, policy)`; rung 3 adds an ordered
  `Enricher<? super A>` list — `(context, action) -> context`, the same
  shape as a policy's own `(context, action) -> decision`, said twice — via
  `ToolGrant.grant(tool, contributor, enrichers, policy)`, depositing
  assessments (a principal exchange, a risk score, a quota read; I/O
  welcome) before the policy judges. The rendered action is deposited under
  the well-known `AuthzContext.ACTION_KEY` before any enricher runs. A
  throwing action contributor, enricher, policy, or principal resolver each
  denies that one call closed, naming the stage that broke, never an
  escaped exception and never an allow.
- **The standard risk shape and the principal kit.** `RiskLevel`
  (`VERY_LOW`…`VERY_HIGH`, NIST SP 800-30's five qualitative levels),
  `RiskAssessment(likelihood, impact, factors)` with a computed
  `severity()`, `RiskFactors`' open string vocabulary (`destructive`,
  `irreversible`, `external-world`, `read-only`, `spends-money`,
  `touches-pii`), and `AuthzContext.RISK_KEY` back the canonical
  `RiskPolicies.threshold(approveAt, denyAt)` policy — severity below
  `approveAt` allows, up to `denyAt` requires approval, at or above denies;
  an absent assessment fails closed. `Enrichers.principal(Supplier<?>
  resolver)` is a named enricher depositing a resolved identity under
  `AuthzContext.PRINCIPAL_KEY` — nessy still never imposes an identity
  shape.
- **Intent, reborn.** Only `AuthzContext.DECLARED_INTENT_KEY` survived an
  earlier distillation; the declaration tool is rebuilt against the current
  machine in `org.jwcarman.nessy.agent.intent`: `Intent(String
  declaration)`, the `IntentStore` SPI (`record(Intent)`/`latest()`, with an
  `InMemoryIntentStore` reference implementation), `IntentTool` (the model's
  claim channel, always allowed by design), and `IntentEnricher` (deposits
  the latest declaration under `DECLARED_INTENT_KEY`). The claim stays
  untrusted by definition; policies weigh it accordingly.
- **The sealed tool-event channel.** `sealed interface ToolEvent` (today
  just `Progress(String message)`) delivered through `ToolEventListener`
  replaces `EventEmitter.emit(Object)` and the untyped `ToolProgress` wire
  record — an open-ended, `String.valueOf`-falling-back channel in an
  otherwise sealed-grammar codebase. `ToolContext.progress(String)` is
  unchanged as the speaker's own door; `ToolProgress.toolCallId` dissolves,
  since the executor already holds the call.
- **The doors: authorization moves to the executor, approval becomes a fact
  on a durable slot.** The chokepoint now sits in the tool call executor
  itself, ahead of both the durable backend and the approver, so a call is
  judged once regardless of which node picks it up. Adjudication is no
  longer an in-memory wait: `SlotApprover` opens a durable computation slot
  per call via `CallAddress.approval()`/`.execution()` (`CallAddress
  (agentType, agentId, callId)`), registers a `REDRIVE_SCOPE` continuation
  (`ScopeRedrive`) to resume the owning scope, and returns
  `Adjudication.Suspended` until a human answers — the approval itself
  becomes a fact recorded on that slot, not a callback held open in a
  process. `SlotDeferredToolCallPolicy` gives a tool's own `Awaited.Deferred`
  the same treatment for execution. Two desks — an `ApprovalDesk` and a
  `CompletionDesk` — front these slots. `CompletionPolicy` (`IMMEDIATE`,
  `AWAITABLE`, `DURABLE`) declares, per tool, the strongest completion
  semantics it needs, and a wiring that cannot suspend filters a `DURABLE`
  tool out of what the model even sees rather than failing loud later.
  `BoundedBacklog<O>` caps how many pending observations an agent scope
  will queue before rejecting more. `Nessy.autonomous()` opens the builder
  for a host that drives agent scopes without a human at a keyboard driving
  each `tell` — observations arrive, `Agent#drive()` makes progress, and the
  approval/completion desks are how a human re-enters the loop.
- **Approval and the `Approver` seam.** `Approver.allowAll()`,
  `.denyAll(reason)`, and `.parkAll()` cover the common cases; a custom
  `Approver` decides per `ApprovalRequest` and may itself park, deferring
  the decision to a human or another system before the gated tool ever
  runs.
- **`AgentMemory` and the context pipeline.** `AgentMemory` (`spi.memory`) owns what a
  model call actually sees. `Memory.pipeline(Transcript)` composes a
  `ContextHydrator` (full or summarizing recall) with an ordered chain of
  `.transform(...)` steps over a fixed floor — the transcript is always the
  base every pipeline hydrates from.
- **Planning.** `spi.plan`, the `update_plan` tool, and `PlanTools` give the
  model a task list it creates and maintains itself, appearing in context on
  every model call for as long as it has tasks — a defense against
  long-horizon drift on multi-step work.
- **The Notebook.** `spi.notebook` and `NotebookTools` give the model a
  place to write durable notes keyed to a `SubjectId` — a compact index
  rides every recall, and the model reads a note's full body only when it
  judges it relevant. Two agents sharing a `SubjectId` share the same notes.
  Every entry carries a `source` — the identity that wrote it — and
  `NotebookTools` enforces it: `remember` and `forget` may only mutate an
  entry whose stored source already matches the calling identity, so one
  author can't silently overwrite or erase another's note; the rendered
  index annotates any heading sourced from elsewhere as `(from <source>)`.
- **Reflection.** `spi.reflection` and `Reflection.critic(ReflectionCustomizer)`
  build a listener for `ConversationSettled` that reviews a settled
  transcript with a side model call and writes 0..n distilled lessons into
  the subject's notebook, sourced `"reflection"`. A `FAILED` settlement
  always reflects; a `COMPLETE` one only when `reflectOnSuccess(true)` is
  set. Lesson names derive deterministically from the conversation id, so a
  redelivered settlement overwrites its own earlier lesson through the
  notebook's ordinary last-write-wins upsert rather than duplicating it.
  Injection needs no new machinery — a lesson is recalled exactly like any
  other notebook entry. Reflection failures are logged and dropped rather
  than thrown, the deliberate opposite of the subagent completion
  listener's throw-for-retry: a lost lesson is a shame, a conversation
  failed over its own homework is worse.
- **Subagents.** `AgentConfig#subagent(SubagentCustomizer<String>)` and
  `#subagent(Class<T>, SubagentCustomizer<T>)` define a child agent right
  inside its parent's own config — a `SubagentConfig`, not a builder, with
  no public `build()` of its own. Building the parent grants the delegation tool,
  wires the links store, and registers the wake-up listener internally. The
  delegation tree is a lexical nesting of these declarations, so a cycle is
  unrepresentable. The degenerate door wraps the call in a one-field
  `Delegation(String task)`; the typed door makes the declared record the
  delegation tool's own wire schema directly, via a required
  `InputRenderer<T>`. `Agent#subagent(String)` returns a narrow `Subagent`
  handle — `approve`/`deny`/`resume`/`snapshot`/`subagent` for tree
  traversal, deliberately no `converse()`/`tell()`. A gated delegation whose
  child itself parks is fully supported: an approval wait, then the child's
  own execution wait, two waits rather than a wedge.
- **Storage: eight SPIs, one JDBC implementation.** `ConversationStore`,
  `Parks`, `Transcript`, `SummaryStore`, `PlanStore`, `Notebook`,
  `SubagentLinks`, and `IntentStore` each ship a zero-configuration in-memory
  default. `nessy-jdbc` implements all eight over a plain
  `javax.sql.DataSource`, speaking five dialects behind one code path —
  Postgres, MySQL, MariaDB, SQL Server, and Oracle — detected from the
  connection metadata, never assumed. `nessy-tck` publishes the contract test
  for every SPI seam as an abstract JUnit 5 class, run once in-memory and once
  per vendor against `nessy-jdbc`.
- **Core dissolves — `nessy-api` and `nessy-spi` become modules.** The
  distillation left `nessy-core` containing only `api.**` and `spi.**` —
  nothing remained to be "core," so the module dissolves into what it
  already was: no rename, an evaporation. `nessy-api` is the vocabulary
  everyone shares — messages, the tool and authorization/risk grammar, turn
  events, `Awaited`, `CallAddress`, `CompletionPolicy`, `ToolEvent`,
  `Intent` — and depends on `nessy-durable` and Jackson alone. `nessy-spi`
  is the seams an outsider implements without ever knowing the machine —
  the model provider SPI, `Memory`, `IntentStore`, and the approver trio
  (`Approver`/`Adjudication`/`ApprovalRequest`) — and depends on `nessy-api`.
  Tool, policy, and enricher authors compile against `nessy-api` alone;
  adapter authors against `nessy-spi`; application builders against
  `nessy-agent`, which carries both transitively along with the machine
  itself.
- **Time-ordered identifiers.** Conversation and park identifiers are
  UUIDv7, generated via java-uuid-generator — sortable by creation time,
  index-friendly for a durable store.
- **Observability.** Micrometer `Observation` instrumentation covers model
  calls, tool executions, and the loop's own turns; `nessy-api` logs
  through `org.slf4j:slf4j-api`, leaving the binding to the application.
- **Termination and retry seams.** `TerminationPolicy` is a per-agent
  cost/call budget guarding against a runaway loop; `RetryingModelProvider`
  decorates any `ModelProvider` with retry policy;
  `AgentConfig#contextWindow(long)` is a declared token-budget dial
  reserved for a future token-aware `AgentMemory`.
- **Native model providers.** `nessy-model-anthropic`, `nessy-model-openai`,
  `nessy-model-gemini`, and `nessy-model-bedrock` each implement
  `ModelProvider` against their own SDK; `nessy-model-discovery` resolves
  whichever of them is on the classpath from the environment
  (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`) — Bedrock is
  never discovered, constructed directly instead — so an application
  switches providers by swapping a dependency and an environment variable,
  not its code.
- **`nessy-console`.** A terminal front door for any `Agent<String>` — read
  a line, tell the agent, render deltas, prompt again — one line to run,
  `ConsoleApprover` included.
- **`nessy-tool-mcp`.** `McpToolbox` opens an MCP server and hands back its
  tools as plain `Tool<JsonNode>` instances, granted individually with their
  own `UsagePolicy` exactly like a hand-written tool.
- **`nessy-spring-boot-starter` and `nessy-autoconfigure`.** Substrate
  arrives by classpath — add the starter jar, get a `Harness` bean wired to
  whatever durable stores and providers are on the classpath — while agent
  identity (name, prompt, tools) stays application code the starter never
  touches.
- **`nessy-testing`.** `ScriptedModelProvider` plays back a scripted model
  turn deterministically, no key and no network call required — the offline
  test double every test in this repository not marked live uses in place
  of a real provider.
- **Five trigger shapes, one entry point.** `agent.converse().tell(...)`
  looks the same whether the caller is a person at a keyboard, a browser
  request, a cron firing, a message landing on a queue, or a webhook — the
  durable inbox absorbs the telling the same way regardless of origin.
- **`nessy-examples`.** A family of eight runnable applications: `hello`
  (the README's five-minute example as a module), `chat-cli` (a two-provider
  terminal chat), `scout` (an MCP-toolbox-backed agent that reads code),
  `chat-web` (a Spring Boot chat app with human-in-the-loop approval over
  SSE), `night-watchman` (a clock-triggered agent), `order-desk` (a
  queue-triggered agent), `dispatcher` (durable parks over plain HTTP), and
  `newsroom` (the subagent delegation demo: a `writer` delegates research to
  a `researcher` it defines inline, with a real Postgres-backed restart
  scene).
- **The four tiers: substrate, host, harness, binding (§10.11).** `Harness<O>`
  (id-free, immortal, one per `AgentType`) and `Binding<O>` (a thin, id-specific
  handle stamped fresh per delivery) replace `AgentWiring`, deleted — a
  ten-component positional record hand-built in demos. `DefaultAgent` now takes
  `(Harness<O>, Binding<O>)` and builds its per-scope model/tool executors from
  the harness's factories; `Harness.of(...)` is the construction door, since a
  package-private canonical constructor can't cross the `agent.host` package the
  builders live in. `AutonomousHost` drops its `ConcurrentMap<AgentId,
  AgentWiring>` cache entirely: `agentFor(id)` binds a fresh handle on every
  call, correct only because the state behind it is shared, not per-binding.
  `InMemoryStateSubstrate`, `InMemoryMemorySubstrate`, and
  `InMemoryBacklogSubstrate` are that shared underlay — one thread-safe object
  per substrate, with `forScope(id)` handing back a thin view rather than a
  copy, so losing a handle loses nothing. `Nessy.autonomous()`'s
  `memoryFactory`/`storeFactory` default to a shared substrate's `forScope` and
  are invoked once per delivery; a caller-supplied factory carries the same
  obligation — return a view over shared state, never freshly-created state.
- **`StalenessPolicy` names the §6.1 judgment.** `StalenessPolicy.isStale(Phase,
  Instant lastSaved)` replaces `AgentWiring`'s `staleThreshold`/`Clock` pair;
  the clock leaves the machine and the policy owns time, via canonical
  `after(Duration)`, `after(Duration, Clock)`, and `never()`. `Nessy.cli()`
  wires `never()`, dropping an arbitrary 5-minute ceiling that made no sense
  for a foreground REPL; `Nessy.autonomous()` gets `.staleness(StalenessPolicy)`
  in place of the old `staleThreshold(Duration)`/`clock(Clock)` pair, defaulting
  to `after(Duration.ofMinutes(5))`.
- **Typed intent: an organization's own sealed vocabulary rides the same kit
  (vocabulary amendment §3).** `Schemas` shapes a sealed interface's wire
  schema as a `oneOf` over its permitted records, each carrying a required
  const `"type"` discriminator; `SealedInputs` reads `"type"`, matches it
  against `getPermittedSubclasses()` by simple name, and binds the remainder
  into that record — a missing or unknown `"type"` fails in-band naming every
  legal type, before the tool that owns the input ever runs.
  `RegistryToolCallExecutor` routes a sealed `inputType()` through
  `SealedInputs.bind`, else falls through to the existing mapper path.
  `IntentStore<T>`/`InMemoryIntentStore<T>`/`IntentTool<T>` carry both tiers
  of the vocabulary on one generic kit: `IntentStore#declare(T)` (renamed
  from `record(T)`) and `IntentTool#inputType()` returning the vocabulary
  itself, so a sealed vocabulary rides the `oneOf` schema and discriminator
  binder with zero extra code — `IntentTool.freeform(store)` keeps the
  pre-built `T = Intent` tier. `IntentPolicies.requireDeclared(Class<?>)`
  denies unless a same-typed declaration is on the context, teaching the
  model to declare before it acts; `UsagePolicy.allOf(List<UsagePolicy<Object>>)`
  composes policies deny-biased, in order — first `Deny` wins, otherwise any
  `RequireApproval` wins, otherwise allow. `TypedIntentDemo` plays all three
  arcs end to end against an in-fixture `OpsIntent` vocabulary: an undeclared
  restart is denied in-band, teaching the model to declare, and the retried,
  now-risky restart parks for a human and completes once approved; a
  declared target that disagrees with the attempted one is denied by an
  in-fixture consistency policy naming both; and a declaration shaped outside
  the vocabulary is rejected by the discriminator binder itself, before the
  intent tool ever runs, with nothing stored.
- **The docs site rebirth.** Every published page — concepts and guides
  alike — now describes the agent-as-scope shape rather than the harness it
  replaced: `Nessy.cli()`/`Nessy.autonomous()`, `Harness`/`Binding`, the
  `ToolGrant` authorization ladder, `TurnObserver`/`AgentObserver`, and the
  four native model providers plus `nessy-model-discovery`, each claim checked
  against source rather than carried forward from the old narrative. The
  closing wave rewrites Providers, MCP Clients, and Observability against
  the current API — including two MCP examples that had drifted onto a
  builder shape (`harness.agent(...)`, a bare `.approver(...)`) no longer in
  the tree, now rebuilt on `Nessy.autonomous()` and `ToolGrant.grant` — and
  sweeps the site for dead vocabulary and broken `Where next` links.
- **`nessy-examples`: three runnable modules, consumer code against the
  current API.** A non-published aggregator (`maven.deploy.skip`, excluded
  from Sonar) in the root reactor with `hello`, `approvals`, and `governed`,
  each a standalone `main` using only `Nessy.cli()`/`Nessy.autonomous()`,
  `Tool.of`, and `ToolGrant.grant` — no internals. `hello` wires one
  calculator tool through `Nessy.cli()` for one turn; `--scripted` swaps in
  a `ScriptedModelProvider` so it needs no key and no network, printing "The
  answer is 4. (COMPLETE)". `approvals` mirrors `ApprovalPlayground` as
  consumer code: one DURABLE `restart` tool behind
  `UsagePolicy.requireApproval()`, a console loop (post / approve / deny /
  quit) against a real provider, or `--scripted` for a deterministic
  post-park-approve-complete arc printing "APPROVED AND COMPLETE".
  `governed` plays the full gate — a typed `OpsIntent` vocabulary, a
  risk-assessing enricher, and `UsagePolicy.allOf(requireDeclared,
  threshold)` — narrating one scripted run end to end (bounce, declare,
  park, approve, complete) to "GOVERNED TURN COMPLETE". CI restores the
  consumer smoke the build had lost: a step runs `hello` scripted and greps
  its sentinel, so the README's five-minute promise is checked against a
  real run on every build, not just against source.
- **The autonomous host narrates the turn by default.**
  `Nessy.AutonomousBuilder#build()` now defaults `agentObserver` to a
  `TurnNarrationAdapter` over the turn observer when the caller never sets
  one, matching the posture `CliBuilder` has always had: `AssistantSaid` and
  `TurnEnded` narrate on the turn observer without extra wiring. A
  caller-supplied `.agentObserver(...)` still replaces the wiring wholesale.
- **The substrate: `Substrate` (substrate design).** Every store
  in Nessy collapses onto one interface, `org.jwcarman.nessy.spi.substrate`
  (`nessy-spi`): documents (mutable current-truth, `read`/`write`/`delete`/
  `keys`, CAS-addressed by `(kind, key)`), a journal (immutable history,
  `append`/`entries`, addressed by `(kind, key, seq)`), and one atomic
  `batch` across both. `ConflictException` is the single conflict signal;
  `InMemorySubstrate` is the reference substrate, shipped in `nessy-spi`
  alongside the contract. Four substrate recipes replace the old per-concern
  substrates and SPIs: `SubstrateAgentStateStore` (`kind=state`, the document
  version *is* the scope version), `SubstrateMemory` (`kind=memory`, a journal,
  one entry per message, never rewritten), `SubstrateBacklog<O>`
  (`kind=backlog`, a JSON-array document, read-mutate-CAS), and
  `SubstrateComputations`
  (`kind=computation`, one document per computation holding
  `{status, outcome?, continuations[]}` — `DurableComputationBackend` is no
  longer an adapter SPI, just the vocabulary the two desks speak).
  `InMemoryStateSubstrate`, `InMemoryMemorySubstrate`,
  `InMemoryBacklogSubstrate`, `InMemoryAgentStateStore`,
  `InMemoryIntentStore`, `InMemoryDurableComputationBackend`, and the
  builder's `storeFactory` seam are all deleted. `Nessy.autonomous()` gets
  one new storage seam, `.substrate(Substrate)` (default a fresh
  `InMemorySubstrate`); `.memoryFactory(...)` and `.backend(...)` survive
  as override seams over it. A JDBC adapter is two tables
  (`nessy_document`, `nessy_journal`) and is not part of this change — the
  outbox (`kind=outbox`) and a summarization sidecar (`kind=summary`) are
  ratified in the design as future work, not built.
- **Intent moves out: `nessy-intent`.** The declared-intent claim channel —
  `IntentTool`, `IntentStore`, `IntentEnricher`, `IntentPolicies`, `Intent`,
  and `SubstrateIntentStore` (the `kind=intent` recipe, last-write-wins via
  read-then-CAS) — leaves `nessy-agent` for its own artifact,
  `org.jwcarman.nessy:nessy-intent` (package `org.jwcarman.nessy.intent`),
  depending on `nessy-api` and `nessy-spi`. An application that never
  declares intent now carries none of this code, and none of its storage
  footprint.
- **Bytes below, one mapper throughout.** `Substrate` payloads are `byte[]`,
  not `String` — the substrate never assumed UTF-8 JSON, it just hadn't
  said so in the type. `Codec<T>` (`org.jwcarman.nessy.spi.substrate`) is
  the typed seam above the bytes: `Codec.json(mapper, type)` binds
  unannotated user shapes (sealed vocabularies included, via the
  `SealedInputs` discriminator convention), and `Codec<T>#then(Codec<byte[]>)`
  chains a byte-to-byte transform — compression, encryption — onto any
  codec, left-to-right on encode. Nessy-owned sealed types
  (`ContentBlock`, `Phase`) now carry `@JsonTypeInfo`/`@JsonSubTypes`
  directly instead of a hand-rolled tree-walking codec; the pinned
  `ObjectMapper` binds them. Every substrate recipe takes an optional
  `Codec<T>` for its stored shape. Both host builders gain
  `.objectMapper(ObjectMapper)`: `build()` copies it and pins the
  format-critical settings (lower-camel naming, tolerant reads, `ALWAYS`
  inclusion, no root wrapping, no default typing) onto the copy, threading
  that one pinned mapper through every recipe that binds JSON —
  user-registered modules survive the copy; the wire format does not float
  on a caller's naming or inclusion preference. **Observations are typed:**
  `Nessy.autonomous(Class<O>)` opens a door where `AutonomousHost<O>` and
  `SubstrateBacklog<O>` carry any `O`, not just `String`; the caller
  supplies `.renderer(ObservationRenderer<O>)` (no default, unlike the
  `String` door's preset), and the backlog codec is always derived as
  `Codec.json(pinned, observationType)` — there is no override seam for it
  yet. `Nessy.autonomous()` (no argument) keeps the `String` text door,
  unchanged in behavior. The JDBC reference schema's payload column is
  `BYTEA`/`BLOB`, never a JSON-typed column, so a wrapping transform's
  ciphertext is always representable.
- **The json repeal: annotate your own vocabulary, standard Jackson.**
  Nessy binds nothing bespoke and polices nothing (2026-08-22). `SealedInputs`
  is deleted; a sealed tool input binds through Jackson's own polymorphic
  machinery in `RegistryToolCallExecutor` — the caller's sealed interface
  carries `@JsonTypeInfo(use = Id.NAME, property = "type")` and
  `@JsonSubTypes` directly, the same two standard annotations for every
  vocabulary, first-party or user-authored. `Schemas` derives its `oneOf`
  discriminated schema from those same annotations via victools' Jackson
  module, so the schema shown to the model and the bound shape agree by
  construction via the annotations — `Schemas` builds its own generator, so
  a mapper-level customization (a registered module, a mix-in, a custom
  `AnnotationIntrospector`) is visible to binding but invisible to the
  schema; a sealed interface missing the annotations themselves is rejected
  up front — `Schemas`' own requirement, since it cannot generate a
  discriminated schema without the information, not Nessy babysitting a
  caller's Jackson setup. `SealedJsonCodec` is deleted along with it:
  `Codec.json(mapper, type)` is now a plain `writeValueAsBytes`/`readValue`
  pair through `mapper`, exactly as configured — no construction-time
  annotation check, no type-component collision guard, no double-discrimination
  special case for a sealed `type`. Misconfiguration (a missing annotation, a
  colliding component name) surfaces exactly as it would in any Jackson
  application, translated at the codec boundary into an
  `IllegalArgumentException` naming the offense the same as any other
  malformed input. `SubstrateBacklog`'s hand-rolled envelope parser/writer is
  gone too; the `List<String>` envelope binds through the same injected,
  pinned `ObjectMapper` every other recipe uses. Test your vocabulary over
  `InMemorySubstrate`: storage there is real encoded bytes, so a Jackson
  misconfiguration fails in your own unit tests, not in production.
- **Durable deliveries: nothing waits.** A durable computation is no longer
  a promise a live process `await`s — it's a chain of atomic ownership
  transfers over the substrate: pending computation → pending delivery (the
  outbox, now built) → advanced fold. Presence means pending throughout;
  there is no status field and no terminal record anywhere in the pipeline.
  `DurableComputationBackend` shrinks to three operations —
  `create`/`complete`/`find` — and `complete` performs the whole transfer in
  one substrate `batch`: delete the computation, create its outbox
  delivery. `DeliveryWorker` (`nessy-agent`) is the one consumer: a
  heartbeat thread per host drains pending deliveries and reconciles each
  through the pure reducer, journal appends, state CAS, and the delivery's
  own removal in one atomic batch; `nudge()` runs an immediate synchronous
  drain right after any completion commits, so the heartbeat is the
  recovery net, never the happy-path latency. Two new identities carry the
  pipeline: `ModelResponseId` (minted in the model-call executor, never the
  reducer) and `ToolInvocationId` (= `ModelResponseId` + the provider's call
  id), the latter handed to every tool invocation through
  `ToolContext.invocationId()` as a natural idempotency key. The approval
  gate now runs inline, exactly once: a parked `RequireApproval` creates a
  computation whose continuation carries the tool call itself, so a grant's
  delivery dispatches the call directly — no re-derivation, no re-run of
  the policy or the approver. Durable tools declare `RetrySemantics`
  (`RETRYABLE`/`NON_RETRYABLE`, default `NON_RETRYABLE`) and an optional
  `timeout` at registration; a reaper sweep on the same heartbeat bumps and
  redispatches an overdue `RETRYABLE` computation, or manufactures a
  `TIMEOUT_NON_RETRYABLE` failure for an overdue `NON_RETRYABLE` one, riding
  the normal delivery pipeline into the fold either way — a computation with
  no declared timeout waits indefinitely, which is what an approval needs.
  This is a from-scratch reform of the durable-computation design, not a
  deprecation cycle: `await()`, `AwaitResult`, `ComputationStatus`,
  `ALREADY_TERMINAL`, terminal computation records, the `List<Continuation>`
  multicast surface and its set-dedup, and the live
  `ContinuationDispatcher`/`ContinuationHandler` fire path are all deleted,
  not superseded in place. Two edges are open by design rather than
  papered over: a single-winner delivery claim is per-host only until an
  outbox lease lands with the first durable substrate adapter, so a grant
  delivery can be drained more than once across hosts in the meantime; and
  the instant between a grant's own completion and its delivery being
  drained is not yet closed, so a redrive landing exactly there still
  re-asks the approver — both parked, not fixed, and named as such in
  [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/).
- **Harness first: one door, kept forever.** `Nessy.harness(HarnessCustomizer<String>)`
  and `Nessy.harness(Class<O>, HarnessCustomizer<O>)` replace `Nessy.autonomous()`
  as the application front door — the `String` and typed-observation doors
  respectively, both reached through the same customizer grammar
  `Tool.of(type, customizer)` already teaches: the lambda fills in a
  `HarnessConfig` (renamed from the old `AutonomousBuilder`, identical
  setters, no public `build()`), and Nessy alone turns it into the finished
  `Harness` the instant the lambda returns. The harness is immortal, not
  closeable: no example anywhere on the site opens one in a
  `try`-with-resources any more. Its life-support — the delivery worker,
  the approval and completion desks, the reaper sweep — moved in from the
  now-deleted `AutonomousHost`: `harness.approvals()` and
  `harness.completions()` are the harness's own desks, and `shutdown()` is
  the one undecorated lifecycle method, documented as infrastructure-only
  (a container's destroy callback, a test's teardown) rather than
  application hygiene. `harness.bind(AgentId)` returns a plain `Agent<O>`
  directly — `Binding` leaves the public surface entirely, demoted to
  internal wiring — and `.observe(observation)` replaces `post(id,
  observation)` as the tell verb; `bind(id).observe(...)` is now the whole
  story. Grant ceremony is no longer required on the five-minute path:
  `.tools(Tool<?>...)` grants allow-by-default sugar the same as it always
  did, just one call away from `harness.bind(id).observe(...)` rather than
  behind a builder's `build()`. **One harness per agent type per
  substrate** is now a stated contract: two harnesses sharing both a type
  and a substrate would double-drain each other's deliveries, since each
  harness's worker and reaper sweep every record carrying that type
  regardless of which harness produced it — a new type-filtered-sweep law
  keeps different types on one shared substrate from ever touching each
  other's records. "Host" retires to meaning your process — the JVM that
  keeps a harness reference alive, nothing more; the four-tier vocabulary
  (substrate, host, harness, binding) becomes three (substrate, harness,
  binding). See [Getting Started](https://jwcarman.github.io/nessy/guides/getting-started/),
  [the harness guide](https://jwcarman.github.io/nessy/guides/harness/)
  (renamed from the old Autonomous Agents guide), and
  [The Tiers](https://jwcarman.github.io/nessy/concepts/the-four-tiers/).
- **The model split: a provider provides models.** `ModelProvider` — one
  transport that used to answer `capabilities()` for a whole vendor lineup
  while accepting any model string per request — splits into a vendor
  gateway and a bound handle. `ModelProvider` is now the application
  singleton holding the SDK client and credentials; its one job is `Model
  model(String id)`, a cheap, immutable handle bound to one model id,
  sharing the gateway's client. `Model` is the thing that actually runs
  requests — `stream(ModelRequest)`, `capabilities()` (now honestly
  per-model, not a vendor-wide guess), and `id()` — and it is what
  `HarnessConfig#model(Model)` and `Nessy.cli()`'s `CliBuilder#model(Model)`
  consume; the harness never sees the gateway itself. Two agents on two
  models is two handles drawn from one gateway
  (`anthropic.model("claude-opus-5")`, `anthropic.model("claude-haiku-4-5")`)
  feeding two harnesses — no model string threads through a `ModelRequest`
  any more, since the handle it is sent to already knows which model runs
  it. `.systemPrompt(String)` moves off `ModelSettings` onto the harness
  config directly, required with no settings fallback; `ModelSettings`
  keeps only the optional tuning bag (`maxTokens`, requested
  `capabilities`, `contextWindow`) and loses both `model` and
  `systemPrompt`. Wrappers rebase one level down: `RetryingModelProvider`
  becomes `RetryingModel`, a decorator over the thing that runs requests
  rather than the gateway, with each vendor's `RETRYABLE` predicate feeding
  it unchanged. `ModelDiscovery.fromEnv()` returns a bound `Model`
  directly, and `ModelDiscovery.select()`'s `Selection` carries a
  `Model` alongside the chosen provider's name rather than a
  `ModelProvider`. See [Providers](https://jwcarman.github.io/nessy/guides/providers/).
- **Durable dissolves: the spine stops pretending to be a tier.** The
  `nessy-durable` module and the `org.jwcarman.nessy.agent.durable` package
  are gone — a separate durability tier no longer names anything real once
  the deliveries reform made the computation pipeline the only execution
  path, in-memory and JDBC alike. `DurableComputationBackend` is deleted
  outright: there was exactly one implementation and no swap story, so
  `Harness`, `HarnessConfig`, `DeliveryWorker`, and both desks now hold
  `SubstrateComputations` concretely — the `Substrate` beneath it is the one
  seam a host swaps. Its vocabulary records relocate by consumer need,
  smallest surface wins: `ComputationId`, `ToolInvocationId`, `Continuation`,
  `Outcome`, `PendingComputation`, `CreateResult`, and `CompletionResult`
  move to the new `org.jwcarman.nessy.api.computation` package in
  `nessy-api`, public because `SubstrateComputations`'s own public
  `create`/`complete`/`find` methods expose all seven. `OutcomeCodec` and
  `ScopeRouting` return to package-private, reversing the harness-first fix
  round's disclosed widening now that their one caller, `DeliveryWorker`,
  sits back in the same package. Behavior is unchanged — this is relocation
  and dead-abstraction deletion, not a semantics change. See
  [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/).
- **Remembrance: memory leaves the fold-advance batch.** `Memory` is not
  atomically consistent with a fold, by design — a genuinely foreign store
  (a vector DB, Redis, a bespoke schema) can never join a substrate batch,
  and interrogating the old atomicity requirement showed it was never
  load-bearing. `Memory#remember(Message)` is replaced by
  `Memory#remember(Remembrance)`: `Remembrance` (new, `nessy-spi`, beside
  `Memory`) is a sealed vocabulary of three members — `UserMessage`,
  `AssistantMessage`, `ToolExchange` — each carrying its own opaque turn
  key, mapping one-to-one onto the three fold moments (an observation, a
  tool-call-free model turn, and a completed tool call). Three laws govern
  the SPI: append-before-commit (the caller's law — a throwing `remember`
  aborts the attempt before anything commits, leaving the caller's work
  pending for natural redrive); idempotent-by-key convergence and
  recall-order (the implementor's law); memory-ahead-is-benign (documented,
  tolerated). `DeliveryWorker`'s commit batch shrinks to `[state CAS,
  delivery delete]` — memory ops have left it entirely, along with the
  `requirePlainSubstrateMemory` guard, `SubstrateMemory#writesPlainlyTo`,
  and `currentMemoryHead`, none of which name anything real once any
  `Memory` implementation is first-class. `SubstrateMemory` rebases onto
  the new SPI: idempotence is a small per-scope marker document
  (`kind=memory-keys`), CAS-written in the same batch as the journal append
  it guards; `recall()` reassembles paired messages from whatever order its
  `Remembrance`s arrive in, and still reads transcripts written before this
  reform (a bare `Message` per entry, no `"type"` discriminator) unchanged.
  `nessy-testing` ships `MemoryContractTest`, a runnable conformance suite
  any `Memory` — including a third party's — extends to prove it honors the
  three laws; `SubstrateMemory` and `VerbatimMemory` both pass it. See
  [Memory](https://jwcarman.github.io/nessy/concepts/memory/).
- **The tell rename: `Agent#observe` becomes `Agent#tell`.** One
  pre-1.0 sweep, no alias, no deprecation limbo — `agent.tell(observation)`
  is now the caller-perspective verb for enqueueing one ambient world fact
  (Akka prior art); `drive()` is unchanged. The fold-internal "observation"
  vocabulary — the `O` type parameter, `ObservationRenderer`,
  `AgentObserver`, backlog naming, the `Observed` event — is untouched;
  only the public `Agent` method that took an observation renames. See
  [the harness guide](https://jwcarman.github.io/nessy/guides/harness/)
  (design of record:
  `docs/superpowers/specs/2026-08-23-front-ends-design.md` §1/§5).
- **Every call is approved: the approval lifecycle folds into the scope
  (breaking).** Three sealed types that each said "yes/no" a different
  way — `PolicyDecision {Allow, Deny, RequireApproval}`, `Adjudication
  {Granted, Refused, Suspended}`, `Decision {Allow, Deny}` — collapse into
  one: `Approval {Approved(reference), Denied(reason, reference)}`;
  `DecisionCodec` retires with them. A grant now takes an `Approver`
  (`ToolGrant.grant(tool, ..., approver)` — the approver is always last)
  instead of a `UsagePolicy`; `Approvers.allow()`/`.deny(reason)`/`.defer()`
  are the three built-ins, `Approvers.rules(...)` is a ladder (first answer
  wins, `Rule.Verdict.Defer` parks), and `Approvers.allOf(...)` is a gate
  (every member must approve). `UsagePolicy`, `RiskPolicies`, and
  `IntentPolicies` retire in favor of
  `Approver`/`Approvers`/`Rule`/`RiskRules`/`IntentRules`. `AuthzContext`
  retires; its typed-fact mechanism survives as `Facts`, and its role — the
  enriched question — is `ApprovalRequest`, a JSON document by contract:
  every field renders through the harness's pinned mapper, once, at
  enrichment, and the rendered document is the record of what was decided
  on. The two-step `grant.assemble` then `grant.decide` retires with it —
  the harness builds the request once, and the approver reads it.
  `Approver.approve(ApprovalContext) -> ApprovalOutcome` is the facade (one
  method, a world behind it, like `Memory`); `ApprovalContext.defer()` does
  the plumbing — it parks the question, folds `ApprovalDeferred` into the
  scope, waits for that fold to commit, and only then hands back the id, so
  nobody can be told about a question the scope has not yet recorded.
- **A call's lifecycle is in the phase, not a side index.** `AwaitingTools`
  replaces its `pending` set and `gathered` list with one `calls` map,
  callId to `CallStatus` — `Pending`, `AwaitingApproval(ComputationId
  approval, ApprovalRequest request)`, `Running`, `AwaitingResult
  (ComputationId)`, `Finished(ToolResultBlock)` — so the persisted
  `awaiting-tools` phase's own wire format changes: a call waiting on a
  parked computation now names that computation's id in its own status, in
  the scope's own state document, rather than in a separate substrate kind.
  The frozen `ApprovalRequest` itself lives in `AwaitingApproval` too — that
  is how `ApprovalDesk.request(agentId, callId)` finds the same document a
  human or an approver saw, with no separate read door on Continuum.
  `DispatchIndex`, `CallAddress.indexKey()`, the gate's index-read
  absorption, and `DeliveryWorker.isCurrentDispatch` retire outright —
  nothing outside the phase remembers "this call is already in flight"
  anymore. Three events join the
  grammar (`ApprovalDeferred`, `ApprovalAnswered`, `ToolDeferred`) and
  `ToolFinished` gains an `Optional<ComputationId>`; `ExecuteTool` splits
  into two effects, `SeekApproval` and `RunTool`, each producing exactly one
  kind of result. `ToolCallExecutor.executeTool`/`executeGrantedToolNow`
  retire in favor of `seekApproval`/`runTool`, neither with a conditional
  inside: `seekApproval` never runs a tool, `runTool` never consults an
  approver — the answer is already a fact in the phase by the time it runs.
- **The lease pays for a message, never for the work.** Both
  `DeliveryWorker` consumers — approval and tool — now only fold a result
  into the scope and return; neither ever runs a tool inline. An `Approved`
  answer's fold is what emits `RunTool`, dispatched afterward on the
  harness's own executor, outside any Continuum lease — closing the
  double-run and pump-starvation hazards a slow granted tool used to open
  under the old lease-runs-the-tool design. The approval kind's own lease
  drops to 30 seconds accordingly.
- **A mismatched delivery is dropped with a `WARN`, never redelivered.**
  With both windows that could once race closed by construction (`defer()`
  folds and commits before it ever hands back an id; a `Running` call names
  no computation until `ToolDeferred` folds), a delivery whose scope is not
  in the status that awaits it can only be an orphan or a duplicate — never
  a race worth retrying. `DeliveryWorker` logs it at `WARN`, naming the
  agent, the call, the computation, and the status the phase actually
  found, and consumes it; nothing is released for redelivery, and there is
  no backoff-and-retry path for a permanent failure. `EarlyDeliveryException`
  retires — releasing the delivery for a later retry was never the right
  response once the race it was released for no longer exists. A denial
  that finishes a call is committed to the transcript exactly like any
  other outcome, on both paths: the in-band path (an approver that answers
  on the spot, inside `seekApproval`) additionally narrates both
  `ToolCallDecided` and `ToolCallCompleted` through the turn's
  `TurnObserver`; a desk-delivered answer — folded by `DeliveryWorker`,
  days later, on no thread anyone is listening from — narrates neither.
  `Memory` records it either way; live turn narration of a desk-delivered
  denial is a known gap, not yet closed.
- **The desk gains doors, and a principal.** `harness.approvals()` now
  answers `approve`/`deny` two ways — by the computation's own opaque id,
  for whoever was handed one, and by `(agentId, callId)`, for whoever has
  only the question, resolved through the scope's own phase (`AwaitingApproval`
  names the id; a caller who answers before the park has folded is refused
  with a loud "not awaiting approval" rather than losing the answer). Both
  doors take a `principal` and a `note`/`reason`, folded into the answer's
  `reference` — the desk is the one door with no subsystem behind it, so it
  refuses to let a yes in anonymously. `withdraw(id, reason)` folds a
  parked ask as a denial; `request(agentId, callId)` returns the same
  frozen `ApprovalRequest` document the approver was handed. `HarnessConfig
  .approvalNotifier` retires outright — telling people is the approver's
  own job now, not a harness-level, one-recipient callback; see
  [Writing an approver](https://jwcarman.github.io/nessy/guides/harness/#writing-an-approver).
  `ComputationApprover` retires along with it. See
  [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/)
  and [the harness guide](https://jwcarman.github.io/nessy/guides/harness/)
  (design of record: `docs/superpowers/specs/2026-08-25-approval-lifecycle-design.md`).
- **The tool hands out its own id: `ToolContext.defer()` (breaking).**
  `ToolContext` was a record; it is now an interface, the mirror of
  `ApprovalContext` — `call()`, `invocation()`, `progress(message)`, and
  the new `defer()`, which creates this call's durable computation, folds
  `ToolDeferred`, waits for that fold to commit, and only then returns the
  id. `Awaited.deferred()` is legal only after this call's own `defer()`:
  returning it without deferring fails in-band with `"deferring tool never
  called context.defer()"`, and returning `Awaited.ready(...)` after
  deferring fails in-band with `"tool answered after deferring"`. The
  executor never creates a computation itself anymore — `ToolExecution`,
  `DeferredToolCallPolicy`/`ComputationDeferredToolCallPolicy`, and
  `ApprovalContexts` all retire outright, along with
  `RegistryToolCallExecutor`'s five-arg, no-Continuum test constructor and
  its `PARKING_UNAVAILABLE` failure — the executor's two `ContinuumClient`s
  are required, never null, and there is no factory or policy seam that
  hands it a narrower view. `Sink#deliver` (`DefaultAgent.deliver`) now
  narrates `AgentObserver.applyFailed` and **rethrows** on a fold that
  cannot commit, closing a hole where `defer()` could hand back an id for a
  park that was never actually recorded. Callers that hand their sink to a
  pooled executor see no change — the narration was already the only trace,
  and the task ends either way. A wiring with an INLINE executor
  (`executor(Runnable::run)`) does see the throw: a dispatched effect folds
  on the delivering thread, so a failure in that nested fold now propagates
  out through `ask()`/`tell()`. It is narrated exactly once, by the frame
  that failed. `ApprovalRequest.draft(...)` gains a
  fifth argument, the bound tool input, which an enricher reads back typed
  through the new `Draft#input(Class<T>)` — transient, never serialized
  into the frozen document, since `call().arguments()` is already that
  evidence. See [Tools](https://jwcarman.github.io/nessy/concepts/tools/#deferring-the-door)
  and
  [Durable Computation](https://jwcarman.github.io/nessy/concepts/durable-computation/)
  (design of record: `docs/superpowers/specs/2026-08-25-tool-context-defer-design.md`).

### Changed

- **`DeliveryWorker` now publishes every fold to the fact stream, not just
  `DefaultAgent`'s synchronous shell.** Before the agentic-o11y reform, a
  worker-driven completion (a desk's approval answer, a durable tool's
  result) folded silently — nothing narrated it. Both fold sites publish
  through one door now, so the configured `HarnessObserver` and the
  observability roster alike see a durable delivery exactly like a
  synchronous one, with no separate "resumed from durable storage" event
  of its own.
- **Metric names are the span's own name, not the semconv name
  `gen_ai.client.operation.duration`.** Micrometer requires every
  observation sharing one name to carry the same low-cardinality key set;
  `invoke_agent`, `chat`, and `execute_tool` carry deliberately different
  attributes, so sharing the one semconv metric name is a meter with
  unstable tags — Micrometer's own strict test registry rejects it outright
  and a real backend corrupts it. Each operation is therefore timed under
  its own name (also its semconv *span* name); an application that wants
  the exact semconv metric maps the three onto it in its own
  `ObservationHandler`.
- **Every outcome-bearing key value is declared at start as the placeholder
  `"none"`, overwritten once the outcome is known** —
  `nessy.turn.outcome`, `nessy.approval.answer`, `nessy.tool.outcome`,
  `nessy.tool.deferred`, `gen_ai.response.finish_reasons`, and
  `error.type`. Same rule as above, applied a second time: Micrometer
  compares an observation's key set against others already recorded under
  that name, so a `chat` that only sometimes carried `error.type` would
  itself fail. A reader of these spans must treat `"none"` as "not yet
  known / not applicable" — including `error.type=none` on a span that
  finished successfully, which is the documented shape, not a bug.
- **The semconv `gen_ai.client.token.usage` metric is the application's to
  record, not `nessy-agent`'s.** An `ObservationRegistry` times
  observations; it cannot record a value histogram. The `chat` span carries
  the vendor's own token counts as key-values instead
  (`gen_ai.usage.input_tokens`/`output_tokens`), and a ten-line
  `ObservationHandler` — shipped in `nessy-examples/observed` — reads them
  on `onStop` and records the metric to its own `MeterRegistry`.
  `nessy-agent` never depends on a `MeterRegistry`.
