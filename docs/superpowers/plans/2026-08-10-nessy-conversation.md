# The Conversation Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute spec §17 (commit aa4c0a3) in full: everything centers on a Conversation — the B-sweep with self-attributing events and the misdelivery guard, declared/frozen/scoped listening with the hub demoted, the razor-bound harness with disjoint builders and `Nessy.harness(provider)`, the journal as a listener, and the death of the envelope and the transcript-store family.

**Architecture:** Spec §17 governs wherever older sections conflict; Task 4 sweeps the body text. All work in the existing modules.

**Tech Stack:** unchanged.

## Global Constraints

As every prior plan: keyless green on `./mvnw -q clean verify` AND `-Prelease -Dgpg.skip=true`, FOREGROUND builds, license/spotless before commit, no suppressions/star imports/auxiliary ctors, exhaustive core switches no default, ZoneBoundariesTest green (genuinely enforcing), mocapi prose tests, surefire XML truth. Plus:
- §17 is the contract. Read it FIRST, in full, every task.
- Where a task finds §17 ambiguous, report the ambiguity — do not invent a ruling.

---

### Task 1: The B-sweep — `Conversation*` everywhere, self-attributing events, the misdelivery guard (HIGH-RISK: grammar + reducer — Opus review)

**Files:** every module. Renames: `SessionState`→`ConversationState`, `SessionId`→`ConversationId`, `SessionStore`→`ConversationStore` (+`InMemorySessionStore`→`InMemoryConversationStore`), `SessionStatus`→`ConversationStatus`, sealed `Event`→`ConversationEvent` (file + every `Event.X` reference; `EventTest`→`ConversationEventTest`). Package homes unchanged (`api` root for the grammar; `api.session`→ **rename package to `api.conversation`** — the family's name changed; sweep `spi.session`→`spi.conversation` likewise, MessageCodec riding along). `ToolContext`, notices, observations keep their shapes but their `SessionId` fields become `ConversationId`.

**Self-attribution:** `ConversationEvent` declares `ConversationId conversationId();` — every variant gains the component (FIRST position, uniformly). Stamping: `InProcessEngine.translate(...)` gains the id (from the state it holds); `tell()` builds `UserSaid(conversationId, blocks)`; `Compacted`/`CompactionSkipped` built by the engine likewise; reducer-internal? The reducer constructs NO events — verify by grep and state it in the report.

**The misdelivery guard:** first statement of `Reducer.reduce`: `if (!event.conversationId().equals(state.id())) throw new IllegalArgumentException("misdelivered fact: event for " + … + " folded into " + …)`. Every existing reducer test fixture stamps the matching id (mechanical); ONE new test: `a_misdelivered_fact_is_rejected_loudly` (event for id B folded into state A → thrown, message names both ids).

**The envelope dies:** `api/event/SessionEvent.java` DELETED. `reduceAndNotify` emits the grammar event ITSELF (`hub.emit(event)` post-fold — order: after adopting the new state, before effects, exactly where the envelope emission sat). Subscribers in tests/examples that consumed the envelope re-home onto raw events (they now carry their own id) or onto notices. `RecordingSubscriber` (nessy-testing) adapts.

- [ ] Steps: red (rename compile errors drive) → implement → both profiles green → commit `refactor!: everything centers on a Conversation`.
- The jurisdiction-review javadoc fix rides along: `Reducer` skip-paragraph javadoc no longer claims spend accounting (delete the stale clause); plus its two minors if trivial (ProviderSummarizer blank-throw comment; absent-TurnEnded span note).

---

### Task 2: The razor-bound harness — front door, disjoint builders, declared listening, hub demotion

**Files:** `Nessy`, `Harness`, `HarnessBuilder`, `AgentBuilder`, `Agent`, `Conversation`, `api/event/*`, engine wiring.

- `Nessy.harness(ModelProvider provider)` — THE entry (provider by signature); `Nessy.harness()` no-arg DELETED if present; **`Nessy.agent()` RETIRED** (delete; sweep every usage — tests, examples, README — onto `Nessy.harness(provider).agent(...)`).
- **Disjoint builders**: `AgentBuilder` loses `.provider/.store/.observations/.mapper/.events` (any infra setter); `HarnessBuilder` owns them exclusively (store default `inMemory()`, observations NOOP, mapper fresh). `HarnessBuilder.defaultModel(String)` seed; agent `.model(...)` wins; neither → **`AgentConfigurationException`** (new public type, front-door package; message names the missing thing; adopted for ALL agent build-time config failures — sweep the existing `IllegalStateException` config throws in `build()`).
- **Declared listening**: `listen(Class<T>, Consumer<T>)` + `listenAsync(Class<T>, Consumer<T>[, Consumer<Throwable>])` on BOTH builders; frozen at build; harness declarations seed every agent (seeds first, then the agent's own, declaration order). Delivery chain per §17: conversation-local → agent list. Veto = throw, anywhere, stops chain + operation.
- **`conversation.events().subscribe(type, listener)` → `AgentSubscription`** — the one dynamic level, per-handle, non-durable. Propose the per-`tell` tap's fate (sugar over this vs deletion) in the report; implement your proposal.
- **Hub demotion**: `EventHub`/`SynchronousEventHub` leave the public surface (internal delivery machinery — move to `internal` or package-private; `EventEmitter` SURVIVES public for `ToolContext.events()`); `HarnessBuilder.hub(...)` dies; `subscribe/subscribeAsync` public API dies with it (replaced by the builder verbs + conversation subscribe). ZoneBoundariesTest adjusted.
- Tests: freezing pinned (no mutation path exists post-build — compile-level, note in report); seeding order pinned (harness seed fires before agent declaration, declaration order within); veto-from-agent-listener stops delivery to later listeners; async declaration never vetoes; conversation-local attach/detach; `AgentConfigurationException` for missing model (chain: agent model > defaultModel > throw) pinned exactly per the owner's pseudocode semantics.

- [ ] red → implement → green both profiles → commit `feat!: the razor-bound harness — Nessy.harness(provider), declared listening`.

---

### Task 3: The journal is a listener — transcript family deleted

**Files:** `spi/conversation/` (post-rename), engine, harness, tests.

- DELETE: `TranscriptStore`, `TranscriptEntry`, `InMemoryTranscriptStore`, `NoOpTranscriptStore`, the `.transcript(...)` knob, and their tests. `MessageCodec` SURVIVES (`spi.conversation`).
- `MessageAppended(ConversationId conversationId, Message message, Usage turnUsage)` — already the emit at the newborn choke point; verify unchanged through T1's rename.
- Journaling in tests/E2E: recording listeners (a `List`-collecting `listen(MessageAppended.class, …)` declared on the harness/agent builder). The E2E journal proof (`the_journal_keeps_what_compaction_removes`) re-homes onto a recording listener, assertions preserved (order, usage attribution, compaction summary newborn with `Usage.zero()`). Strictness proof: a THROWING sync listener on `MessageAppended` fails the run; snapshot still saved.
- README/CHANGELOG: journal = a listener you declare; the Cassandra module sketch becomes "ships a `MessageAppended` listener"; module ladder row adjusted (`nessy-store-cassandra` → a listener + a `ConversationStore`).

- [ ] red → implement → green → commit `refactor!: the journal is a listener — the transcript store family retires`.

---

### Task 4: Docs convergence + the spec body sweep + E2E coherence

- Sweep the spec BODY to §17 (every `SessionState`/`SessionId`/`Event `-as-grammar/envelope/transcript-store/knob mention updated or marked superseded; §5.0 glossary rewritten conversation-centric; §9 rewritten to declared listening; defaults ladder rows: EventHub row removed, ConversationStore renamed, journal row → listener; package map; gate table `Context.systemPrompt` row survives; sequencing).
- README top-to-bottom conversation-centric pass (front door examples all `Nessy.harness(provider)`; mirrors verbatim in AgentFacadeTest; the harness razor stated; owned/seeded/granted table).
- CHANGELOG: collapse Unreleased coherently to the final shapes (nothing released) with a "conversation convergence" heading; keep the superseded-rulings honesty lines.
- E2E: one new facade proof `everything_centers_on_a_conversation` — harness with a seeded listener + agent with its own + a conversation-local subscription; one tell; assert all three heard, in the pinned order, each event self-attributing (`conversationId` present and equal).

- [ ] red where testable → implement → green both profiles → commit `docs+test: the conversation convergence, end to end`.

---

## Self-Review

**§17 coverage:** B-sweep+attribution+guard → T1; envelope death → T1; declared/frozen/seeded listening + hub demotion + conversation-dynamic + front door + disjoint + defaultModel/AgentConfigurationException + razor → T2; journal-as-listener + deletions → T3; body sweep + docs + E2E → T4. OUT (per §17): agent `.name(…)`.

**Type consistency:** `ConversationEvent.conversationId()` (T1) is what T2's raw-event listeners and T4's E2E assert; `MessageAppended(ConversationId, …)` (T3) matches T1's rename; `AgentConfigurationException` (T2) is what T4's README documents.

**Ordering:** T1 first (everything else speaks the new names); T2 before T3 (journal-as-listener needs the builder verbs); T4 last.

**Placeholder scan:** none — semantics, names, and test scenarios stated throughout; the two implementer-proposal points (tap's fate; hub's internal placement) are explicitly delegated-with-review, not placeholders.
