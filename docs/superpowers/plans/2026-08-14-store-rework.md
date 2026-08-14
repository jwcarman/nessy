# The Three Front Doors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the busy `ConversationStore` into three small front doors — the store (control block + inbox), the Parks registry (the callback door), and the Transcript (the versioned message log) — evict tokens from `ConversationState`, dissolve `consumeToken` into the fold, unify the two memories into `TranscriptMemory`, and ship `SummarizingMemory` as the tail API's dogfood.

**Architecture:** See the spec — it is unusually prescriptive (exact SPI shapes in §2, §5, §6) because the design was argued line-by-line. The tasks land in strict dependency order: additive Transcript first, then the memory unification, then the mechanical inbox rename, then the parking surgery (the high-risk heart), then the JDBC side, summarization, autoconfiguration, and finally examples + paperwork.

**Tech Stack:** Java 25, existing modules only (`nessy-core`, `nessy-store-jdbc`, `nessy-autoconfigure`, examples). No new dependencies anywhere.

**Spec:** `docs/superpowers/specs/2026-08-14-store-rework-design.md` — the binding authority. Every task brief cites its sections; read them before coding. The design-of-record's store chapter is amended by this spec.

## Global Constraints

- **The aesthetic bar (spec preamble, binding):** code reads like well-written prose — "baby code," no tricks. Each new interface's class javadoc opens with its one-sentence story.
- **TDD** where a test is prescribed; RED/GREEN evidence in reports.
- **Offline `./mvnw -q clean verify` stays green after EVERY task** — no Docker, no API key (container-tagged tests excluded by default). Container suite: `./mvnw -q verify -Dnessy.excludedGroups=live` (Docker required; run it in the tasks that touch JDBC or chat-web).
- **chat-web's smoke test assertions are the invariant** (spec §8): the park → approve → complete story must pass with wiring-only changes. Never weaken one of its assertions to make a task pass — that is a BLOCKED, not a fix.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. Never hand-write license headers. Never stage IDE metadata (`.classpath`, `.project`, `.settings/`, `.factorypath`).
- **No warning suppressions. No star imports. No mocking libraries. Prose snake_case test names. S5778/S5841 hygiene.** Sealed-grammar etiquette: core switches exhaustive with no `default` arm.
- **Renames are total:** after a rename task, `grep -rn "OldName" --include="*.java" .` (excluding target/) must return nothing. Javadoc and comments rename too.
- Commit messages in house style; trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: The Transcript — SPI, in-memory implementation, contract test

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/Transcript.java` (spec ruling 11.7: the Transcript lives in `spi.memory` — it is the memory jurisdiction's storage primitive)
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/InMemoryTranscript.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/memory/TranscriptContract.java` (abstract, the TCK shape `ConversationStoreContract` already uses)
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/spi/memory/InMemoryTranscriptTest.java` (extends the contract)

**Interfaces (spec §2 verbatim — these exact shapes):**
- `Transcript` with nested `record Entry(long version, Message message)`; methods `Entry append(ConversationId, Message)` (no-stutter: appending a message equal to the current last entry returns that existing entry unchanged), `List<Entry> all(ConversationId)`, `List<Entry> tail(ConversationId, long afterVersion)` (strictly greater), `List<Entry> page(ConversationId, long beforeVersion, int limit)` (strictly less, version order, at most limit — the newest `limit` entries below the bound, i.e. the page ends just under `beforeVersion`), `static Transcript inMemory()`.
- Versions are monotonic per conversation starting at 0, assigned by the implementation.
- `InMemoryTranscript`: `ConcurrentHashMap<ConversationId, List<Entry>>` with `compute`-under-the-key appends and immutable snapshot values — copy `ListMemory`'s concurrency discipline and its javadoc's reasoning (that class is your reference; it dies in Task 2).

- [ ] **Step 1:** Write `TranscriptContract` (abstract `protected abstract Transcript transcript();`) with prose-named tests: append assigns 0,1,2…; `all` returns version order; the no-stutter rule (append equal-to-last returns the existing entry, count unchanged; append equal-to-*earlier* still appends); `tail(after)` is strictly-greater and empty at the head version; `page(before, limit)` returns the newest `limit` entries strictly below the bound, in version order, and the full remainder when fewer exist; unknown conversation → empty lists; two conversations never see each other's entries. Then `InMemoryTranscriptTest extends TranscriptContract`. Watch it fail to compile.
- [ ] **Step 2:** Implement the SPI and `InMemoryTranscript`. GREEN.
- [ ] **Step 3:** `./mvnw -q clean verify` green. Format, commit: `feat: the transcript gets a name — versioned, no stutter, three reads`

---

### Task 2: TranscriptMemory — two memories become one (in-memory half)

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/TranscriptMemory.java`
- Delete: `nessy-core/src/main/java/org/jwcarman/nessy/spi/memory/ListMemory.java`
- Rename test: `ListMemoryTest.java` → `TranscriptMemoryTest.java` (same behavioral cases, re-targeted)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/AgentBuilder.java` (default memory), `nessy-core` test call sites (`ConversationLoopTest`, `ProviderModelCallExecutorTest`), `InMemoryConversationStore` (javadoc mention only), `nessy-examples/night-watchman/.../WindowedMemory.java` (delegate becomes `new TranscriptMemory(Transcript.inMemory())`; its javadoc's ListMemory reference updates)

**Semantics (spec §3):** `remember` = `transcript.append` (idempotency is the transcript's no-stutter rule — do not re-implement dedup here); `recall` = `Context.of(withoutOpenTail(all-messages))`. Move `withoutOpenTail` from `ListMemory` verbatim, javadoc included (keep the halt-while-parked caveat and the recorded-follow-up sentence; drop the "mirrors JdbcMemory" cross-reference once Task 5 deletes that class — write it as the single home now). Class javadoc opens: "The floor: remembers everything verbatim through a transcript, recalls it whole."

- [ ] **Step 1:** Port `ListMemoryTest`'s cases to `TranscriptMemoryTest` (constructor takes `Transcript.inMemory()`), plus one new case: two `TranscriptMemory` instances over the SAME transcript see each other's tellings (the seam is the storage, the memory is the policy). RED (class missing).
- [ ] **Step 2:** Implement; migrate every call site (`grep -rln "ListMemory"` must end empty outside target/); delete `ListMemory`. GREEN.
- [ ] **Step 3:** Offline verify green (night-watchman's suite covers the WindowedMemory delegate swap). Format, commit: `feat: TranscriptMemory — the two floors were always one policy`

---

### Task 3: The inbox rename — mechanical, total

**Files (rename/modify, no behavior change):**
- `AgendaItem.java` → `InboxEntry.java` (type, factories, javadoc: "One durable piece of mail in a conversation's inbox…"; keep `Told`/`Resolved` variant names and shapes UNCHANGED in this task — the `Resolved` re-key is Task 4's)
- `ConversationStore`: `appendAgenda` → `append`; `Loaded(state, agenda)` → `Loaded(state, inbox)`; class javadoc gains the sentence "Everything that enters a conversation lands in its inbox first; whoever drives next reads the mail." and `save`'s javadoc keeps its full current contract (park-sync clause included — it leaves in Task 4/5, not now).
- `InMemoryConversationStore`, `ConversationLoop` (call sites, comments, `drained` naming stays), `Harness` (append site), `JdbcConversationStore` + `StateCodec` (mixins/kind strings — keep the wire `kind` values `'told'`/`'resolved'` as they are), `schema.sql` (`nessy_agenda` → `nessy_inbox`, index renamed to `nessy_inbox_conversation`), tests (`AgendaItemTest` → `InboxEntryTest`, `ConversationStoreContract`, `HarnessTest`, `ZoneBoundariesTest`, `ConversationLoopTest`, `StateCodecTest`, JDBC tests).

**Requirements:** pure rename — `git diff` shows renames and identifier/doc churn only, zero logic edits. Exit criterion: `grep -rn "AgendaItem\|appendAgenda\|agenda" --include="*.java" --include="*.sql" nessy-core nessy-store-jdbc` (excluding target/) returns nothing (comments included; the word "agenda" leaves the codebase). Container suite for the JDBC store must be run (schema rename): `./mvnw -q verify -pl nessy-store-jdbc -am -Dnessy.excludedGroups=live`.

- [ ] **Step 1:** Perform the rename; run offline verify AND the nessy-store-jdbc container suite. Both green.
- [ ] **Step 2:** Run the grep exit criterion; paste its (empty) output in the report. Format, commit: `refactor: the agenda becomes the inbox — same mail, honest name`

---

### Task 4: Parking evicted — the heart (HIGH RISK: state semantics, loop routing)

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/Parks.java` (+ nested `record Park(ConversationId conversationId, ParkToken token, ToolCall call)`)
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/InMemoryParks.java`
- Modify: `ConversationState` (parkedCalls → `List<ToolCall>`; `parked(ToolCall)`; `withParkedCalls(List<ToolCall>)`; `toolFinished`'s parked-sibling handling re-keyed — `removeFirstMatchParked` collapses into `removeFirstMatch`), `ParkedCall` (KEEP the record `(token, call)` — it becomes the snapshot's card type only; javadoc re-aimed), `InboxEntry` (`Resolved(String id, String callId, ToolResolution resolution)`; factory `resolved(String callId, ToolResolution)`), `ConversationLoop` (resolution routing matches `state.parkedCalls()` by call id; `applyParked` registers `parks.park(new Park(id, token, call))` BEFORE `save`, then narrates after — spec §5's forced ordering, with a comment stating WHY the order is forced), `Harness` (holds `Parks`; `resume` = `parks.find(token)` → orElseThrow `UnknownParkTokenException` → `store.append(id, InboxEntry.resolved(park.call().id(), resolution))` → drive; `progress` = find + load + call-still-outstanding check → emit or false; `peek` returns `Optional<ParkedCall>` built from the Park — public signature unchanged), `HarnessBuilder` (`parks(Parks)`, default `Parks.inMemory()`), `AgentBuilder` (threads `Parks` to the loop and `Agent`), `Agent.snapshot` (cards = `parks.forConversation(id)` filtered to call ids in `state.parkedCalls()`, mapped to `ParkedCall(token, call)`), `ConversationStore` (DELETE `findPark`, `findParkConversation`, `consumeToken`; `save` javadoc loses the park-sync clause), `InMemoryConversationStore` (park index and consumed-set code deleted), `JdbcConversationStore` (the three deleted interface methods' `@Override` bodies removed so the reactor compiles — their SQL/table cleanup stays in Task 5), `StateCodec` (state shape: parkedCalls now plain ToolCall list; `Resolved` payload carries callId — wire `kind` values unchanged), `ConversationStoreContract` (park/consume cases move out; see tests below).
- Tests: `ParksContract` + `InMemoryParksTest` (register/find/forConversation/idempotent-re-register/entries-survive-resolution); `ConversationStateTest` park cases re-keyed; `ConversationLoopTest` — keep every existing park/resume scenario passing re-keyed, and ADD: a `Resolved` addressed to a settled call drains quietly (the dissolved `consumeToken`, fold-owned — spec §5); a redelivered resume re-drives and reads current truth; `HarnessTest` — resume/progress/peek over `Parks`; unknown token still throws `UnknownParkTokenException`; progress on a settled wait returns false.

**Binding semantics (spec §5, and the reviewer should hold the diff to them):** register-before-save ordering with the why-comment; narration after save only; orphan tolerance (a registered park whose save lost the fence resolves into stale mail — cover with a loop test if expressible, else state the gap in the report); `TurnEvent.ToolCallParked(call, token)` unchanged; single-agent restrictions unchanged.

- [ ] **Step 1:** Tests first where additive (ParksContract, the new loop cases as failing tests against the old shape won't compile — acceptable RED is the compile failure after the type changes land in stubs; narrate honestly in the report).
- [ ] **Step 2:** The surgery, in compile-lockstep. Offline verify green.
- [ ] **Step 3:** `grep -rn "consumeToken\|findParkConversation" --include="*.java" nessy-core nessy-store-jdbc` → only `JdbcConversationStore` remains (its cleanup is Task 5); paste output in report. Format, commit: `feat: the conversation forgets tokens — parks answer at their own door`

**Review note for the controller:** this task's review runs on Opus (reducer semantics + loop routing = the model policy's high-risk category).

---

### Task 5: The JDBC side — three doors over one database

**Files:**
- Create: `nessy-store-jdbc/.../JdbcTranscript.java` (implements `Transcript`; table `nessy_transcript (conversation_id, version, message)` PK `(conversation_id, version)`; append under the exact `SELECT … FOR UPDATE` last-row discipline `JdbcMemory.remember` uses today — lift that code; no-stutter enforced there)
- Create: `nessy-store-jdbc/.../JdbcParks.java` (table `nessy_parks (token text PK, conversation_id text NOT NULL, call jsonb NOT NULL)` + index on conversation_id; `park` idempotent via `ON CONFLICT (token) DO NOTHING`)
- Delete: `JdbcMemory.java` (+ its test; `TranscriptMemory` over `JdbcTranscript` replaces it)
- Modify: `schema.sql` (drop `nessy_park`/`nessy_token` tables; the store's schema is conversation + inbox only), `memory-schema.sql` → `transcript-schema.sql` (`nessy_memory` → `nessy_transcript`, `seq` → `version`) + new `parks-schema.sql`; `JdbcConversationStore` (delete park/token SQL and the three dead interface methods' bodies; `save` loses park-sync — its transaction is CAS + drain only), `JdbcPersistence` (bundles store + parks + transcript; `memory()` returns `new TranscriptMemory(transcript())`), `StateCodec` if any park serialization remains.
- Tests: JDBC runs of all three contracts (`JdbcConversationStoreTest`/`JdbcParksTest`/`JdbcTranscriptTest` extending the split TCKs, `@Tag("container")`); `ConversationStoreContract` in core now contains ONLY store semantics (fencing, drain atomicity, inbox append visibility).

**Verification:** `./mvnw -q verify -pl nessy-store-jdbc -am -Dnessy.excludedGroups=live` (Docker) AND offline reactor green. Fresh-bootstrap only; no data migration (spec §9.6).

- [ ] Steps: failing contract tests → implement → container GREEN → offline green → grep `nessy_park\b\|nessy_token\|nessy_memory\|JdbcMemory` empty → format, commit: `feat: three doors over one database — the store's save is one sentence again`

---

### Task 6: SummarizingMemory — the tail API earns its shape

**Files:**
- Create: `nessy-core/.../spi/memory/SummaryStore.java` (nested `record Summary(long watermark, String text)`; `Optional<Summary> find(ConversationId)`, `void save(ConversationId, Summary)`; `static SummaryStore inMemory()`), `InMemorySummaryStore.java`, `SummarizingMemory.java`
- Create: `nessy-store-jdbc/.../JdbcSummaryStore.java` (+ `summary-schema.sql`: `nessy_summary (conversation_id text PK, watermark bigint, summary text)`), wired into `JdbcPersistence`
- Tests: `SummarizingMemoryTest` (core, offline, scripted `ModelProvider` supplying the summary text) covering spec §4's list: watermark advance on threshold crossing; recall below threshold = summary head + tail with NO model call; crash-idempotency (discard the summary save, re-recall re-summarizes the same tail to the same watermark); pair-safe boundary (a tool exchange straddling the threshold is never split — the summarized prefix ends at a genuine user turn); the summary renders as one opening user message, skipped when empty; open-tail trim still applies to the tail. Plus `JdbcSummaryStoreTest` (container).

**Construction:** `SummarizingMemory(Transcript, SummaryStore, ModelProvider, String model, String prompt, int tailThreshold)` — javadoc states the jurisdiction rule (its call never touches `ConversationState.usage`) and that a lost summary write is re-done work, never lost words.

- [ ] Steps: failing tests → implement → GREEN → offline verify + store-jdbc container suite → format, commit: `feat: SummarizingMemory — the watermark is the bookkeeping`

---

### Task 7: The starter learns the doors

**Files:**
- Modify: `nessy-autoconfigure` — `JdbcPersistenceAutoConfiguration` grows `Parks` and `Transcript` beans (same `@ConditionalOnMissingBean` + datasource + classpath rules; `Memory` bean becomes `TranscriptMemory` over the transcript bean); `NessyAutoConfiguration.harness(...)` resolves an `ObjectProvider<Parks>` and calls `builder.parks(...)` when available; property surface unchanged.
- Tests: extend `JdbcPersistenceAutoConfigurationTest`/`NessyAutoConfigurationTest` — parks/transcript beans appear with a datasource and back off to user-declared beans; harness receives the parks bean; the ordering pin (`after = DataSourceAutoConfiguration.class`) still asserted.
- Verify chat-web: `./mvnw -q verify -pl nessy-examples/chat-web -am -Dnessy.excludedGroups=live` — the smoke test passes with its assertions UNTOUCHED (wiring-only changes to the example, e.g. renamed types). If an assertion fails, STOP and report BLOCKED — do not adapt the assertion.

- [ ] Steps: failing autoconfigure tests → implement → GREEN → chat-web container suite green → offline green → format, commit: `feat: the starter opens three doors — beans by classpath, as before`

---

### Task 8: Paperwork — README seams, CHANGELOG's loud breaking list

**Files:**
- Modify: root `README.md` — every mention of `ListMemory`, the store's shape, agenda vocabulary, or park methods updated to the three-doors story (grep for `ListMemory`, `agenda`, `consumeToken`, `findPark`); the durable section's description of `save` matches the slimmed contract.
- Modify: `CHANGELOG.md` — one entry for the generation under `### Added` (the three doors, TranscriptMemory, SummarizingMemory, the dissolved consumeToken as a design point) and the spec §9 breaking list reproduced under `### Breaking (pre-1.0)` **including the durable-state incompatibility note (old parked states do not deserialize; no migration ships)**.
- Verify: full offline reactor + `-Dnessy.excludedGroups=live` container sweep once, end to end.

- [ ] Steps: write → both verifies green → format, commit: `docs: three front doors sign the paperwork — the breaking list says it loud`

---

## Self-Review Notes (already applied)

- Task ordering is compile-safe: Transcript (additive) → TranscriptMemory (kills ListMemory while JdbcMemory still exists — they never referenced each other in code, only javadoc) → inbox rename (no shape change) → parking surgery (Task 4 deletes the three interface methods, so its file list includes `JdbcConversationStore` for the `@Override` body removals that keep the reactor compiling; the remaining park SQL and tables leave in Task 5 with the schema work and its container tests).
- chat-web compiles between Tasks 4 and 7 because its own code never called the three deleted store methods (it uses Harness/Agent surfaces, which keep their signatures).
- The `ConversationStoreContract` test-jar ships to store-jdbc (existing mechanism); the split contracts ride the same jar.
- Spec §2's `page` semantics ("newest limit entries below the bound, version order") are restated identically in Task 1's contract-test list — one definition, two citations.
