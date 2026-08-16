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
  `effect(I input)` statement of what the call will do, and an
  `execute(I, ToolContext)`. `ToolGrant.grant(...)` is the one way a tool
  reaches an agent, so a tool's authority is stated at the grant line, not
  buried in the tool's own code.
- **Authorization: a ladder from a static verdict to a typed, enriched
  decision.** `UsagePolicy<E>.evaluate(AuthzContext, E)` is the tool call
  executor's one authority chokepoint, consulted before the tool runs and
  before the approver is ever asked; the decision vocabulary is always the
  same sealed three, `Allow`, `Deny(reason)`, `RequireApproval`. Rigor rises
  in rungs, and a grant that never climbs past one costs nothing for the
  rungs above it: rung 0 is `UsagePolicy.allow()`/`.deny(reason)` —
  canonical statics that skip effect rendering, context assembly, and every
  enricher entirely; rung 1 is a lambda reading `AuthzContext.call()`/
  `.state()`; rung 2 welds a tool's typed `EffectfulTool<I, E>` effect to
  its policy at compile time via `ToolGrant.grant(tool, List.of(), policy)`;
  rung 3 runs an ordered `Enricher<? super E>` list — `(context, effect) ->
  context`, the same shape as a policy's own `(context, effect) ->
  decision`, said twice — depositing assessments (a principal exchange, a
  risk score, a quota read; I/O welcome) before the policy judges. A
  throwing effect, enricher, policy, or principal resolver each denies that
  one call closed, naming the stage that broke, never an escaped exception
  and never an allow. `spi.intent`'s `declare_intent`/`clear_intent` tools
  and `AgentConfig.intent(Class<?>)` let a model state an untrusted claim of
  what it's about to do, read back via `AuthzContext.declaredIntent()`; the
  vocabulary is an ordinary tool input type nessy validates no further than
  null and a repeat-call guard, catching a bad fit only at the same
  fail-closed call time every tool call already gets.
  `AgentConfig.principal(Function<ConversationId, ?>)` feeds
  `AuthzContext.principal()`, an agent-level resolver seam over any
  principal shape — nessy defines the slot, never the type. `Agent#
  authorizationReport()` renders every grant's own story — effect type,
  enricher names in order, policy identity — read straight from the wiring,
  so it can never drift from what actually runs.
- **Approval and the `Approver` seam.** `Approver.allowAll()`,
  `.denyAll(reason)`, and `.parkAll()` cover the common cases; a custom
  `Approver` decides per `ApprovalRequest` and may itself park, deferring
  the decision to a human or another system before the gated tool ever
  runs.
- **`Memory` and the context pipeline.** `Memory` (`spi.memory`) owns what a
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
- **Time-ordered identifiers.** Conversation and park identifiers are
  UUIDv7, generated via java-uuid-generator — sortable by creation time,
  index-friendly for a durable store.
- **Observability.** Micrometer `Observation` instrumentation covers model
  calls, tool executions, and the loop's own turns; `nessy-core` logs
  through `org.slf4j:slf4j-api`, leaving the binding to the application.
- **Termination and retry seams.** `TerminationPolicy` is a per-agent
  cost/call budget guarding against a runaway loop; `RetryingModelProvider`
  decorates any `ModelProvider` with retry policy;
  `AgentConfig#contextWindow(long)` is a declared token-budget dial
  reserved for a future token-aware `Memory`.
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
