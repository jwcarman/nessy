# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Nessy has not yet made a public release. The API is unstable and may change
without notice until the 1.0.0 release.

## [Unreleased]

Nothing has shipped, so nothing below is a breaking change against a version
anyone has. This section describes the framework's **current shape**, once,
in its current vocabulary — not the sequence of designs that produced it.

### The model

- **An agent is a recipe bound to an id.** The recipe is an `AgentType`
  compiled once into a `Harness` and kept for the process's life; the id is
  an `AgentId`. `harness.observe(agentId, observation)` is how work arrives.
  There is no per-agent handle: sharding already knows where an agent lives,
  and a handle is a thing that can go stale.
- **Exactly one actor per id**, cluster-wide, from Pekko cluster sharding —
  so two callers cannot corrupt one agent's state.
- **One actor works the whole turn.** It translates a message into an
  `Input`, calls a pure `decide(AgentState, Input) -> Decision`, persists
  what comes back, and runs the instructions. Every rule lives in a function
  with no way to *do* anything — no clock, no store, no actor, no Pekko
  import — and every effect lives in a shell that decides nothing. A
  three-day parked approval and a crash mid-model-call are ordinary unit
  tests rather than a cluster, a race and a fifteen-second timeout.
- **Phases are data**: `Idle`, `CallingModel`, `WorkingTools`. `Idle` is an
  arm rather than the absence of a turn, so going to sleep is a transition
  you can assert on.
- **The persisted document is ~260 bytes** — a turn id, a phase, two claim
  ids and a token count — measured against PostgreSQL while running real
  tools, and it does not grow with what the agent does.

### Durability

- **Recovery is not a mode.** The agent feeds itself `Recovered` on every
  activation, so the path that would otherwise run only after a crash runs
  constantly.
- **`CallState` has four arms** — `Approving`, `Running`, `Parked`,
  `Completed` — because recovery needs four answers. A parked call is left
  alone rather than re-asked: re-asking mints a second reply token and
  invalidates the one already sitting in somebody's inbox.
- **Deadlines are rows, not timers.** An in-memory timer dies with its
  actor, which meant an approval parked on a person for three days needed a
  process alive for three days. A sweep reads from the front of an index and
  stops at the first row not yet due, so its cost is the number of *expired*
  deadlines rather than the number outstanding.
- **Slow work answers to a logical address, never to `self`.** The executor
  outlives actors; a reference does not. An answer arriving is itself the
  knock that revives an unloaded agent.
- **Tool execution is at-least-once**, stated as a contract rather than
  hidden. The engine's mitigation is a stable key a tool can use to make
  itself idempotent.
- **`ReplyToken`** is how the outside world answers a parked call: sealed,
  authenticated, carrying agent type, agent id, turn and call. Keys rotate,
  so a token already in somebody's inbox survives one.

### Storage

- **A table per thing, shaped for how it is read** — `nessy_backlog`,
  `nessy_claim`, `nessy_reminder`, `nessy_transcript`, plus one per store
  module. There is no storage abstraction, deliberately: a general-purpose
  seam made its callers enforce its design rather than their own.
- **The backlog is a table**, ordered by the coalescer's own output. The row
  id **is** the turn id, because one observation is one turn — which is also
  what makes a take idempotent across a crash.
- **A take locks the agent's rows.** Two takes are routinely in flight, and
  without the lock both render the same row and both write its claim.
- **Claims** hold what a turn needs and no longer: the asking message and
  each tool's result. Deleted by *turn*, so a claim written just before a
  crash is swept even though no key list names it.
- **`Schemas.initialize(dataSource)`** applies every module's shipped
  `nessy-schema.sql`. The engine initializes only a database it created; one
  you supply is never touched uninvited, and the file's name is the opt-in
  because Boot looks for `schema.sql`.
- **Two portability rules**, enforced by a test rather than by memory: ANSI
  spellings only, and no reserved words as identifiers.
- Certified against PostgreSQL 17 in `nessy-store-tests`.

### Tools and authorization

- **`Tool<I>`** — a name, a description, an input type, and a method. The
  input type becomes the schema the model is shown.
- **`Awaited`** has two arms: `ready` answers now, `deferred` parks the call
  and lets the world answer later without holding a thread, an actor or a
  process.
- **`Approver`** is asked per call and may answer or defer. Every call goes
  through one, ungated included — one path through the code is worth more
  than the message it saves, and it is the path recovery has to work on.
- **`ToolCallRequest`** names one call, and a tool and an approver are handed
  the same one. It replaced two context objects that carried overlapping
  views of the same call, so an approver could not see what the tool would
  get. `callKey()` is the turn and the call together — a model's call id is
  unique within one response only — and it is what a tool deduplicates on
  under at-least-once execution.
- **A denial is an answer**, with its reason, and the model responds to it.
  It is not a failed turn and must not look like a broken tool.
- **`ToolDescriber`** writes the sentence a person consents to. Consenting to
  a message you have not read is not consent.
- **Intent** (`nessy-intent`) is a claim channel: the model declares what it
  is about to do in your vocabulary, and `IntentPolicy.requireDeclared` fails
  closed for a call with nothing behind it.

### Memory

- **`Memory`** is two methods, `recall` and `remember`. The memory owns
  history; the engine asks and sends what comes back.
- **An exchange is written whole** — the asking message and the results
  answering it, in one write — which is what makes re-driving after a crash
  always safe.
- **`TranscriptMemory`** is one row per message; `recent` is a newest-first
  cursor that stops at a character budget rather than reading a fixed tail.
- **`nessy-memory-pipeline`** shapes the context with ordered stages;
  **`nessy-memory-notebook`** gives an agent notes recalled by heading, whose
  bodies cannot reach the model by accident; **`nessy-memory-plan`** gives it
  a plan resent wholesale, which is idempotent under at-least-once replay by
  construction.

### Observability

- **Narration** as `AgentEvent`s, each with a time-ordered id, so a listener
  that drops off resumes from the last one it saw — `Last-Event-ID` over SSE
  costs one line.
- **Narration has its own lifecycle**: it lingers briefly after its last
  subscriber rather than being unloaded on an idle timer, because its whole
  state is live subscribers and unloading destroys it. An agent is allowed to
  think for longer than its audience takes to type.
- **Traces form one tree per turn** — verified at 23 spans under a single
  root, including model calls, approvals and tools. Context travels in
  headers rather than thread-locals, because a captured scope does not
  survive the hop to a worker.
- **Timers per message type and per unit of work**, with OpenTelemetry GenAI
  attributes pinned against the 2025 `gen_ai.*` set.

### Doors

- **`PekkoHarnessFactory`** with `EngineConfig` (per process) and
  `HarnessConfig` (per agent type).
- **`Repl.run(customizer)`** builds a whole terminal application — actor
  system, cluster, harness, loop — in one call.
- **`nessy-spring-boot-starter`** assembles a harness from `nessy.*`
  properties and beans, every bean `@ConditionalOnMissingBean`, plus a
  pending-approvals projection. The starter carries no code — the beans live
  in `nessy-spring-boot-autoconfigure`, which is the convention every Boot
  starter follows and what lets an application take the beans without the
  starter's transitive opinions.
- **Model providers** for Anthropic, OpenAI (and any OpenAI-compatible
  endpoint), Gemini and Bedrock, with `nessy-model-discovery` resolving from
  the environment.
- **`nessy-tool-mcp`** imports a remote MCP server's tools as ordinary tools.

### Not yet

- Nothing published to Maven Central.
- No migration story between schema versions; the project is pre-1.0 and
  says so.
- Risk-based gating — assessments, thresholds, and rule ladders — is
  designed but not currently in the codebase.
