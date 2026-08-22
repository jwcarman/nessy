# Bytes and Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute the byte-and-codec amendment — `byte[]` payloads, `Codec<T>`, one injected `ObjectMapper`, direct annotations on nessy-owned types, `Substrate*` recipe renames, and generic observations through `SubstrateBacklog<O>` and `AutonomousHost<O>`.

**Architecture:** The substrate stores bytes and nothing else; `Codec<T>` (nessy-spi) is the typed seam with `Codec.json(mapper, type)` for user shapes; one `ObjectMapper` enters at the builder, is copy-and-pinned, and threads everywhere a mapper is used (statics forbidden); nessy-owned sealed hierarchies carry `@JsonTypeInfo`/`@JsonSubTypes` directly and the tree-walking codecs die with golden tests pinning the wire format; observations are typed end to end with the `String` text door as the default instantiation.

**Spec:** `docs/superpowers/specs/2026-08-21-scoped-store-design.md` — the header's byte-and-codec ruling plus §3, §4.5, §6.4, §7, §8 as amended 2026-08-21 (commit 7ebbb98c). Binding.

## Global Constraints

- Every task ends with a green scoped build; full `./mvnw -q clean verify` is the ONCE-per-task final gate (build economics per CLAUDE.md). Never two Mavens in one worktree.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- House law: no mocking libraries; no `@SuppressWarnings`; no star imports; prose camelCase test names; S5778; S5841; release-profile javadoc (`mvn -P release -DskipTests -Dgpg.skip=true verify` before the final commit of any task touching published javadoc).
- **Wire-format invariance is the law of this branch**: every stored-format change must be invisible — discriminator values, field names, and JSON shapes are pinned by the existing golden/round-trip tests, which may be RELOCATED but never weakened. A red golden test means the implementation is wrong, not the test.
- Array-bearing records: content equality (`Arrays.equals`), defensive copies in compact constructor and accessor (spec §3).
- Malformed payloads surface as `IllegalArgumentException` naming the offense; Jackson exceptions never leak (spec §7).
- The annotations law at its true scope (spec §7): user types never require annotations; nessy-owned types carry them openly.
- Design-authority rule: any concept not named in this plan or the spec stops the task.

## Tasks

### Task 1: Bytes below — `Substrate` goes `byte[]`, `Codec<T>` arrives
**Files:** nessy-spi `substrate/Substrate.java` (String payload → `byte[]` in write/append/Document/Entry/Op.WriteDocument/Op.AppendEntry; add content-equality + defensive-copy overrides to the four array-bearing records per spec §3), `substrate/InMemorySubstrate.java` (copy on write, copy on read — no aliasing), NEW `substrate/Codec.java` — TWO-PARAMETER chainable form per amended spec §3: `Codec<I,O>` with `encode(I)/decode(O)`, default `<T> Codec<I,T> chain(Codec<O,T> next)` (decode composes backwards), `static <T> Codec<T,String> json(ObjectMapper mapper, Class<T> type)` (tolerant binding via the given mapper; sealed interfaces bind the `SealedInputs` way: discriminator from permitted subclasses, unannotated), `static Codec<String,byte[]> utf8()`. Mechanical compile-shims at every substrate call site across the reactor (recipes, tests, fixtures): `payload.getBytes(UTF_8)` / `new String(payload, UTF_8)` at the boundary — NO renames, NO codec params yet (Task 4's job); the shims keep every existing test's semantics identical.
**Tests:** contract suite updated to bytes; NEW: array-record equality (two Documents with equal-content distinct arrays are equal; mutation of a caller's array after write does not alter a later read; mutation of a returned array does not alter the store); `Codec.json(...).chain(Codec.utf8())` round-trips a plain record and a sealed vocabulary member via class token; a marker `Codec<byte[],byte[]>` link chained on proves order (encode passes through it last, decode first); malformed bytes → IAE naming the offense.
**Commit:** `feat: bytes below — the substrate stores byte arrays and Codec is the typed seam`

### Task 2: The annotations land — tree-walkers die, format survives
**Files:** `@JsonTypeInfo(use = Id.NAME, property = "type")` + `@JsonSubTypes` with the EXACT existing discriminators on `ContentBlock` (nessy-api: text/image/thinking/redacted-thinking/tool-use/tool-result) and `Phase` (nessy-agent: idle/awaiting-model/awaiting-tools). Rewrite `MessageCodec`/`StateCodec`/`OutcomeCodec` internals to mapper-based binding (same public String-signature methods for now — Task 4 re-seats them): delete the hand-built `ObjectNode` write paths and discriminator-switch read paths; keep the boundary translation (Jackson exception → IAE naming the offense), keep tolerant-read mapper settings, keep `OutcomeCodec`'s closed-vocabulary door check as code, keep `StateCodec`'s `AwaitingTools` reconstruction through the canonical constructor (annotations bind via canonical ctor — verify the compact-constructor invariants still fire, there is a test). `Codecs` shrinks to what still earns its place (mapper construction + shared helpers still referenced).
**Tests:** the existing 40+ golden/round-trip tests are the gate and MUST pass unmodified in their assertions (relocation allowed): every block type, both roles, each phase, unknown-field tolerance, unknown-discriminator rejection naming the offense, non-array rejection, `ThinkingBlock` absent-signature default (`""` — mirror with `@JsonSetter`/default handling as needed to preserve it), `ToolResultBlock.isError` boolean strictness if pinned. Add one test proving a Jackson exception cannot leak (malformed JSON → IAE).
**Commit:** `feat: the vocabulary annotates itself — five hundred lines of tree-walking retire`

### Task 3: One mapper throughout — injection replaces ambience
**Files:** `Nessy.java` both builders gain `.objectMapper(ObjectMapper)` (default: fresh mapper); a package-private copy-and-pin helper in nessy-agent codec package (pin: `PropertyNamingStrategies.LOWER_CAMEL_CASE`, `FAIL_ON_UNKNOWN_PROPERTIES` off, no default typing — spec §7); the pinned copy threads into: `MessageCodec`/`StateCodec`/`OutcomeCodec` (instance-or-parameter, your call mechanically — no static mapper survives), recipe default codecs (Task 4 consumes this), `SealedInputs`/`Schemas` call sites (they already take mapper parameters — thread the pinned one), `ConfiguredTool` result rendering, `SubstrateIntentStore` (its private static mapper dies; constructor takes the mapper or a `Codec`). DELETE `Codecs.MAPPER` public static. Grep the reactor for `new ObjectMapper()` — after this task the only constructions are the builder default, the copy-and-pin helper, and model-provider wire mappers (vendor contracts, exempt per spec §7).
**Tests:** a user-registered module flows: hand the builder a mapper with a custom serializer for a user type, prove an intent declaration (or observation, once Task 5 lands — use intent here) round-trips through it; a SNAKE_CASE-configured user mapper does NOT change stored field names (the pin holds — golden test reads back camelCase); builder rejects null mapper.
**Commit:** `feat: one mapper in, pinned copy throughout — statics die`

### Task 4: Recipes take their true names and their codecs
**Files:** rename `StoredAgentStateStore`→`SubstrateAgentStateStore`, `StoredMemory`→`SubstrateMemory`, `StoredBacklog`→`SubstrateBacklog`, `StoredComputations`→`SubstrateComputations` (nessy-agent) and `StoredIntentStore`→`SubstrateIntentStore` (nessy-intent), including test classes. Each gains the optional codec constructor parameter typed `Codec<T, byte[]>` (spec §3): state — `Codec<Phase, byte[]>` defaulting to the StateCodec binding chained with `utf8()`; memory — `Codec<Message, byte[]>` likewise; computations — internal document codec stays internal (closed payload vocabulary; no public codec param — record WHY in javadoc); intent — defaulting to `Codec.json(mapper, vocabulary).chain(Codec.utf8())`; backlog — see Task 5 (generic). Task 1's `getBytes` shims dissolve into the codecs. Remove remaining String-signature indirection so recipes speak `Codec<T>` → `byte[]` → substrate.
**Tests:** existing recipe suites keep passing under new names; NEW per recipe: a custom codec is honored (hand a marker codec that prefixes bytes, prove the substrate sees prefixed bytes and reads route back through it).
**Commit:** `feat: the recipes take their true names — Substrate* with a codec in hand`

### Task 5: Typed observations — `SubstrateBacklog<O>` and `AutonomousHost<O>`
**Files:** `SubstrateBacklog<O>` implements `Backlog<O>`: constructor `(Substrate, String agentId, int capacity, Codec<O, byte[]> codec)`; document layout per spec §6.4 — JSON array whose elements are base64 of `codec.encode(o)`. `AutonomousHost<O>` with `post(String agentId, O observation)`; `AutonomousBuilder<O>`; `Nessy.autonomous()` returns the `String`-typed builder with today's renderer and ergonomics EXACTLY preserved (existing examples/tests compile unchanged); a typed entry point (e.g. `Nessy.autonomous(Class<O>)`) requiring the observation renderer the harness already needs (read `Harness`'s renderer seam first and mirror its existing contract) plus defaulting the backlog codec to `Codec.json(pinnedMapper, observationType).chain(Codec.utf8())`. CliBuilder stays String.
**Tests:** typed end-to-end: a record observation posts through a typed host, survives the backlog (kill the host, build a second host over the same substrate, the pending observation drains and drives a scripted turn — proving codec round-trip through the queue); String door unchanged (existing AutonomousHostTest suite green unmodified); capacity/FIFO/retry suites re-run generic.
**Commit:** `feat: observations are typed — the backlog and the host learn O`

### Task 6: Paper trail
**Files:** docs/concepts/storage.md (bytes, Codec, one-mapper, transforms-as-patterns — outbox/summary stay specified-not-built), docs/concepts/authorization+intent+memory+durable-computation pages (renames, codec mentions), docs/guides/autonomous-agents.md (`.objectMapper(...)`, typed host, `.substrate(...)`), README/CHANGELOG, mkdocs strict green. Truth discipline: claim nothing unshipped.
**Commit:** `docs: bytes below, one mapper throughout — the site learns the codec seam`

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 bytes+codec | Sonnet | **Opus** (SPI contract + aliasing integrity) |
| 2 annotations | Sonnet | **Opus** (wire-format invariance = transcript invariants) |
| 3 one mapper | Sonnet | Sonnet |
| 4 renames+codecs | Sonnet | Sonnet |
| 5 typed observations | Sonnet | **Opus** (generics across host/harness/backlog + durability arc) |
| 6 docs | docs-writer (Sonnet) | Haiku scoped |
| Final | — | **Opus** |
