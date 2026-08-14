# The Agent Callback Doors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every token door (`resume`×2, `approve`×2, `deny`×2, `progress`, `peek`) from `Harness` to `Agent<I>`, make the harness immutable, require an agent name, stamp it into park records, and verify it at every door.

**Architecture:** Two kernel tasks (identity first, then the move — the stamp must exist before the doors can verify it), then the JDBC stamp, then the examples, then paperwork. The harness loses `loop`, `agentRegistry`, and `loopRegistrations`; `AgentBuilder.build()` stops writing back into it.

**Tech Stack:** Java 21+, Maven reactor, Testcontainers for store-jdbc/examples.

**Spec:** `docs/superpowers/specs/2026-08-14-agent-callback-doors-design.md` — binding. It amends the design of record: the harness-as-callback-front-door decision is superseded.

## Global Constraints

- TDD with RED/GREEN evidence; offline `./mvnw -q clean verify` green after EVERY task; container suites in tasks touching container-tested modules (`-Dnessy.excludedGroups=live`).
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata. No suppressions, no star imports, no mocking libraries, prose snake_case test names, S5778/S5841, Awaitility not sleep, sealed-grammar etiquette.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Standing invariant: **chat-web's smoke test assertions never change** (wiring-only edits allowed).

---

### Task 1: Identity — the required name and the stamped park

**Files:** `nessy-core`: `AgentBuilder.java` (+`name(String)` setter, non-blank check at setter, required check in `build()`), `Agent.java` (+`name()` accessor, final field), `spi/conversation/Parks.java` (`record Park` gains `String agentName` — read the `register` seam first; if it takes a `Park`, the method signature is untouched), the in-memory `Parks` implementation, `internal/ConversationLoop.java` (park registration carries the loop's agent name — the loop gains the name at construction from `AgentBuilder.build()`). Tests: `AgentBuilderTest` (name required/blank), every reactor build-site gains `.name(...)`.

**Interfaces:**
- Produces: `AgentBuilder.name(String)` (throws `IllegalArgumentException` on null/blank at the setter; `build()` throws `IllegalStateException` when never called, message explaining the covenant: the name is how parked work finds its way home across restarts); `Agent.name()`; `Parks.Park(ConversationId, ParkToken, ToolCall, String agentName)`.
- Consumed by: Task 2 (verification), Task 3 (JDBC column), Task 4 (example names).

- [ ] RED: `AgentBuilderTest` — `build_without_a_name_refuses_with_the_covenant`, `a_blank_name_is_rejected_at_the_setter` (one throwing invocation per lambda).
- [ ] Implement: setter + field + build() check + `Agent.name()`; thread the name into `ConversationLoop` and the `Parks.Park` it registers.
- [ ] Mechanical sweep: add `.name(...)` to EVERY agent build site in the reactor — nessy-core tests, nessy-testing (source + tests), nessy-autoconfigure tests, all six example configs/mains. Example names: `"hello"`, `"chat-cli"`, `"chat-web"`, `"night-watchman"`, `"order-desk"`, `"dispatcher"`. Test fixtures: short prose names (`"scribe"`, `"clerk"` — whatever the fixture's existing voice suggests).
- [ ] Stamp assertion: an existing park-path test extends to assert the registered `Park` carries the agent's name.
- [ ] GREEN: full offline verify. Harness doors still exist this task (they ignore the stamp; Task 2 both moves and verifies — sequencing keeps every commit green).
- [ ] Commit: `feat: the agent gets a name — and the park remembers who left it`

### Task 2: The move — doors to the agent, the harness forgets (HIGH RISK — Opus review)

**Files:** `nessy-core`: `Agent.java` (gains all seven doors: `resume`×2, `approve`×2, `deny`×2, `progress`, `peek` — bodies moved from `Harness`, plus ownership verification), new `api/WrongAgentException.java` (extends the house's runtime-exception family; message names BOTH sides: `park was minted by agent '<stamp>'; this agent is '<name>'`), `Harness.java` (the seven methods DELETED; fields `loop`, `agentRegistry`, `loopRegistrations` DELETED; `AgentBuilder.build()` stops writing back; class javadoc rewritten: inert substrate, immutable after build), `AgentBuilder.java` (build wires the loop/registry into the `Agent` only). Tests: harness token-door tests move to an `Agent`-scoped test class, re-targeted verbatim where semantics are unchanged.

**Interfaces:**
- Consumes: Task 1's `Agent.name()` and `Park.agentName`.
- Produces: `Agent.resume/approve/deny/progress/peek` (same signatures as the old harness methods), `WrongAgentException`.

**Binding semantics (spec §2, §3, §5 — the reviewer holds the diff to these):**
- Every door verifies BEFORE acting: `parks.find(token)` → absent ⇒ `UnknownParkTokenException` (unchanged); present with `agentName` mismatch ⇒ `WrongAgentException`, and NOTHING is appended or driven — A's conversation is untouched.
- `progress` emits on the agent's own registry (the harness-registry-plus-agent-declarations composition already built at `build()`); the "two lanes, one audience" property holds without the AtomicReference.
- Post-save discipline, quiet-drain replay protection, and the at-least-once exposure documentation move with the methods, unchanged.
- `Harness` has no non-final fields afterward — pin with a reflection test in the style of the existing annotation-pin tests.
- [ ] RED: two-agent green path — two named agents on one harness, each parks, each resumes its own token, each drive runs under its own grants and narrates on its own observer (the test the old design refused at runtime).
- [ ] RED: cross-agent refusal — `agentB.resume(A's token)` throws `WrongAgentException` naming both names; assert A's conversation state unchanged after. Same shape for `progress` and `peek`.
- [ ] Implement the move; delete the harness state and the two `IllegalStateException` guards ("single-agent this generation" leaves the codebase).
- [ ] Move/re-target the harness door tests; GREEN; full offline verify.
- [ ] Commit: `feat: the harness forgets — callbacks come home to the agent`

### Task 3: The JDBC stamp

**Files:** `nessy-store-jdbc`: `parks-schema.sql` (+`agent_name` NOT NULL), `JdbcParks` (write + read the column), its container test (stamp roundtrip: a park written by a named agent comes back carrying the name; a `WrongAgentException` scenario through the JDBC registry).

- [ ] RED (container): roundtrip + cross-agent refusal against real Postgres.
- [ ] Implement; GREEN: `./mvnw -q verify -pl nessy-store-jdbc -am -Dnessy.excludedGroups=live` and full offline verify.
- [ ] Commit: `feat: the stamp survives the restart — agent_name lands in nessy_parks`

### Task 4: The examples walk through the new doors

**Files:** `chat-web/ApprovalController` (`harness.peek/approve/deny` → `agent.peek/approve/deny`; inject the `Agent` bean), `dispatcher/CallbackController` (`harness.resume/progress` → `agent.resume/progress`), `order-desk/FulfillmentReplies` (same swap). Names already landed in Task 1 — this task is the door swaps only.

- [ ] Swap all three; container suites for chat-web, dispatcher, order-desk (`-Dnessy.excludedGroups=live`) — chat-web smoke assertions UNTOUCHED.
- [ ] Full offline verify.
- [ ] Commit: `refactor: three controllers change doors — the token comes home to the agent`

### Task 5: Paperwork

Root README: durable section rewritten (verbs on the agent; `peek`/`approve`/`deny` finally documented — DX tax #10; the single-agent caveat DELETED; one sentence on the naming covenant beside the queue-name/correlation-id covenants). Both callback examples' READMEs re-verbed. CHANGELOG: `### Added` (named agents, agent doors, `WrongAgentException`, the stamp) + `### Breaking (pre-1.0)` per spec §7 (harness doors removed; `name(...)` required; `Park`/schema gain `agent_name`). Full offline + container sweep end to end.

- [ ] Commit: `docs: the doors sign off — the name is the covenant`

---

## Self-Review Notes (already applied)

- Task 1 lands the reactor-wide `.name(...)` sweep so every later task inherits a green reactor — the requiredness would otherwise break ~95 build sites mid-pipeline.
- Task 2 sequences AFTER the stamp exists so the doors verify from their first commit; the harness doors stay alive through Task 1 so no intermediate commit is red.
- The cross-agent refusal tests assert state-untouched, not just the throw — verification-before-append is the load-bearing ordering.
- Task 4 is swap-only by construction; if a swap changes any smoke assertion, that is a finding, not a test edit (standing invariant).
