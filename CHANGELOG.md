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
  `ModelProvider` against their own SDK; `nessy-model-env` picks between
  installed providers from the environment (`ANTHROPIC_API_KEY`,
  `OPENAI_API_KEY`, `GEMINI_API_KEY`, or an explicit `NESSY_PROVIDER=bedrock`
  for the one provider with no key of its own), so an application switches
  providers by switching an environment variable, not its code.
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
