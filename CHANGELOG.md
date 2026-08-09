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

- **Time-ordered UUIDs (v7)** — Session and park identifiers are now time-ordered
  UUIDv7, generated via `com.fasterxml.uuid:java-uuid-generator`. The `Uuids`
  internal helper exposes `timeOrdered()` for sortable, index-friendly identifier
  generation. `SessionId.random()` and `ParkToken.random()` delegate to it while
  keeping signatures unchanged.
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
