# Context Collaborators Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Converge the codebase on the 2026-08-09 design session: the `Context` value type, the `CompactionStrategy` seam (propose/dispose choreography, complete usage accounting), declared context windows, domain-packaged collaborators (`spi.context`, `spi.compaction`), and the append-only `TranscriptStore` journal with `MessageCodec`.

**Architecture:** Spec of record is `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md` — §5.0 (glossary), §10.6 (CompactionStrategy rulings), §10.8 (context collaborators, strict journal, MessageCodec). Everything lands in `nessy-core` (+ provider modules for the `ModelRequest` sweep, + `nessy-testing` doubles). No new Maven modules.

**Tech Stack:** Java 25, Maven, JUnit 5 + AssertJ, Jackson (already an api dependency), Micrometer Observation.

## Global Constraints

- `./mvnw -q clean verify` must pass with no API key and no network, always; release check is `./mvnw -q clean verify -Prelease -Dgpg.skip=true` (gpg hangs in sandboxed shells). Builds FOREGROUND only.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No `@SuppressWarnings`, no star imports, no auxiliary constructors with silent defaults, house validation style (`Objects.requireNonNull(x, "x must not be null")`, `"x must be at least N"`).
- Core switches over sealed types stay exhaustive with NO `default` arm.
- Zone rule: `spi → api` only; `ZoneBoundariesTest` must stay green (extend it for new packages).
- Tests: mocapi-style prose (snake_case names, `@Nested` groups), AssertJ, driven fixtures over hand-rolled state, no mocking libraries. Test-count truth is surefire XML.
- Nomenclature per spec §5.0: transcript = journal's full history; working set = the ledger's message aspect; `Context` = validated wire-bound sequence.

---

### Task 1: The `Context` value type

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/Context.java`
- Test: `nessy-core/src/test/java/org/jwcarman/nessy/api/ContextTest.java`; nullity pins in `api/ValidationTest.java`

**Interfaces:**
- Consumes: `Message`, `Role`, `ContentBlock` (check the real sealed variant names in `api/ContentBlock.java` before coding — the tool-use and tool-result blocks carry ids).
- Produces (later tasks rely on these exact signatures):

```java
public record Context(List<Message> messages) {
    // compact ctor: List.copyOf; validates the pairing invariant (below);
    // throws IllegalArgumentException naming the first offending id.

    public static Context of(List<Message> messages) { return new Context(messages); }

    /** Largest cut <= messages.size() - keepRecentMessages (clamped to size - 1)
     *  where messages.get(cut) is a USER message whose blocks are ALL text —
     *  a genuine user turn. Walks downward; 0 if none qualifies. */
    public int pairSafeCut(int keepRecentMessages) { … }

    /** The prefix [0, cut) as a new Context. cut must come from pairSafeCut. */
    public Context head(int cut) { … }
}
```

**The pairing invariant (validated in the compact constructor):** for every ASSISTANT message containing tool-use blocks, the immediately following message must be a USER message whose tool-result blocks answer exactly that set of ids — complete (every id answered), exclusive (no unknown ids), immediate (nothing in between). A tool-result block may appear only in such an answering message. A trailing ASSISTANT message with unanswered tool-use ids is REJECTED — `Context` is wire-bound; open tails live in `SessionState`, never in a `Context`. An empty message list is a valid `Context`.

`pairSafeCut` and its genuine-user-turn helper move VERBATIM in behavior from `Reducer`'s private methods (`pairSafeCut`/`isGenuineUserTurn` in `spi/Reducer.java`); Task 4 deletes them there. Do not change the arithmetic: `limit = min(size - keepRecent, size - 1)`, walk `cut = limit; cut > 0; cut--`.

- [ ] **Step 1: failing tests.** `ContextTest`, `@Nested` groups `Validity` and `The_pair_safe_cut`:
  - `a_plain_conversation_is_valid`; `a_completed_tool_exchange_is_valid` (assistant tool_use ×2 → user results ×2, ids matching); `an_unanswered_tool_use_is_rejected` (trailing; message names the id); `a_partial_results_message_is_rejected` (2 calls, 1 result); `a_result_for_an_unknown_id_is_rejected`; `a_result_outside_an_answering_message_is_rejected`; `an_interleaved_message_breaks_the_pair` (tool_use, plain user text, then results → rejected); `an_empty_context_is_valid`.
  - Cut group: port the boundary scenarios from `ReducerCompactionTest` against `Context.pairSafeCut` directly — cut lands exactly at `size − keepRecent`; walks down past a tool exchange; 0 when nothing qualifies; `keepRecentMessages` 0 clamps to `size − 1`.
- [ ] **Step 2: red** (class absent). **Step 3: implement.** **Step 4: both profiles green.** **Step 5: commit** `feat(api): the Context value type — the pairing invariant's single home`.

---

### Task 2: `CompactionTrigger` and the reshaped `CompactionPolicy`, declared windows

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/CompactionTrigger.java`
- Modify: `api/CompactionPolicy.java`, `spi/model/ModelSettings.java` (+`contextWindow`), `spi/Reducer.java` (consult becomes `compaction.trigger().shouldCompact(state)` — one line), `AgentBuilder.java` (`.contextWindow(long)`, window-derived trigger)
- Test: `api/CompactionTriggerTest.java` (new); mechanical sweeps of every `new CompactionPolicy(` site

**Interfaces — Produces:**

```java
public interface CompactionTrigger {
    /** Pure; consulted at CallModel decision points. */
    boolean shouldCompact(SessionState state);

    static CompactionTrigger atTokens(long trigger) { … }   // lastInputTokens() >= trigger; trigger >= 1 validated
    static CompactionTrigger forWindow(long window, long maxTokens) { … }
        // atTokens(max(1, (long) (0.8 * (window - maxTokens)))); window > maxTokens validated
    static CompactionTrigger never() { … }                  // always false
}
```

- `CompactionPolicy` becomes `record CompactionPolicy(CompactionTrigger trigger, int keepRecentMessages, int summaryMaxTokens, String instructions)` — retained validations unchanged, trigger non-null. `defaults()` = `(atTokens(100_000), 10, 2_048, DEFAULT_INSTRUCTIONS)`; `disabled()` = defaults with `never()` (javadoc updated — no more MAX_VALUE talk).
- `ModelSettings` gains `Long contextWindow` — the THIRD sanctioned nullable; javadoc against the `responseSchema` precedent (null = undeclared). When non-null: must be `> maxTokens`.
- `AgentBuilder.contextWindow(long)` convenience → its `ModelSettings`. Wiring in `build()`: window declared AND `.compaction(...)` never called → defaults with trigger replaced by `forWindow(window, maxTokens)`. Explicit `.compaction(...)` always wins.
- The reducer keeps ALL current semantics — the trigger consult just reads `compaction.trigger().shouldCompact(state)` instead of comparing `lastInputTokens` inline. (Task 4 replaces this again with `strategy.requiresCompaction`; this task keeps the diff minimal and green.)

- [ ] **Step 1: failing tests.** `CompactionTriggerTest`: `at_tokens_fires_at_and_above_the_threshold` (99_999 no / 100_000 yes / 100_001 yes via `withLastInputTokens`); `for_window_reserves_the_reply_room` (window 200_000, maxTokens 8_192 → fires at exactly `(long) (0.8 * 191_808)` and not one below — compute the literal in the test); `for_window_rejects_a_window_smaller_than_max_tokens`; `never_never_fires` (even at `Long.MAX_VALUE`). `AgentFacadeTest`: `a_declared_window_derives_the_trigger` (scripted usage above the derived threshold compacts; explicit `.compaction(CompactionPolicy.disabled())` beats the declared window).
- [ ] **Step 2: red.** **Step 3: implement + sweep** (compiler finds every `CompactionPolicy` construction; `policy(100_000, 4)`-style test helpers convert to `atTokens`; semantics preserved). **Step 4: both profiles green.** **Step 5: commit** `feat(api): CompactionTrigger and declared context windows`.

---

### Task 3: Domain packaging — `spi.context`, `Context` on `ModelRequest`, `TokenEstimator`

**Files:**
- Move: `spi/ContextBuilder.java` → `spi/context/ContextBuilder.java`; `spi/ElidingToolResults.java` → `spi/context/ElidingToolResults.java` (tests move alongside)
- Create: `spi/context/TokenEstimator.java` + `spi/context/TokenEstimatorTest.java`
- Modify: `spi/model/ModelRequest.java` (`messages` → `Context context`), `spi/InProcessEngine.java` (`requestFor` + `summarize` construction sites), both providers' request builders (`AnthropicRequests`, `OpenAiRequests` read `request.context().messages()`), `internal/ZoneBoundariesTest` (learns `spi.context`)

**Interfaces — Produces:**

```java
// spi.context — same contract, new home and return type:
public interface ContextBuilder {
    Context project(SessionState state);
    static ContextBuilder identity() { return state -> Context.of(state.messages()); }
    static ContextBuilder elidingToolResults(int keepRecentMessages) { … }
}

public interface TokenEstimator {
    /** An honest estimate for one message; models never report this figure. */
    long estimate(Message message);
    /** Total characters of textual content (text + tool-result text) / 4, minimum 1. */
    static TokenEstimator heuristic() { … }
}
```

- `ModelRequest`: the `List<Message> messages` component becomes `Context context` (same position, non-null; no list copy — `Context` is already immutable). ~21 construction sites sweep mechanically; providers read `.context().messages()`.
- `Effect.Compact` is NOT touched in this task (Task 4 reshapes it once).
- Minting moments are safe: `identity()` runs at `CallModel` performance — post-flush, transcript complete — so `Context.of` cannot throw there; `ElidingToolResults` preserves ids and pairing, so its projected list validates. Its `project` builds the projected list then `Context.of(...)`.
- `ZoneBoundariesTest`: `spi.context` may import `api`, never the reverse; no `internal` leaks.

- [ ] **Step 1: failing tests.** `TokenEstimatorTest`: `four_characters_make_a_token` (400 chars of text → 100); `every_message_costs_at_least_one_token` (empty text → 1); `tool_results_count_their_content`. `ContextBuilderTest` moves and adjusts (identity returns `Context`; assert `.messages()` equality with the state's list). Provider request-builder tests: constructions swept; keep existing wire assertions intact — adjust, never weaken.
- [ ] **Step 2: red.** **Step 3: implement + sweep.** **Step 4: both profiles green (provider modules too).** **Step 5: commit** `refactor(spi): domain packaging — spi.context, Context on the wire, TokenEstimator`.

---

### Task 4: The `CompactionStrategy` seam — propose/dispose (HIGH-RISK: reducer semantics — Opus review)

**Files:**
- Create: `api/CompactionStrategy.java`; `spi/compaction/Summarizer.java` (+ package-private `ProviderSummarizer`, package-private `SummarizingCompaction`)
- Modify: `api/Event.java` (`Compacted(List<Message> workingSet, Usage spend)`), `spi/Effect.java` (`Compact(List<Message> workingSet)` — instructions component REMOVED; the strategy owns instructions), `spi/Reducer.java` (record component becomes `CompactionStrategy compaction`; consult/emit/apply reshaped; private cut methods DELETED), `spi/InProcessEngine.java` (Compact arm performs the strategy; private `summarize` deleted), `AgentBuilder.java` (`.compaction(CompactionPolicy)` retained as default-strategy tuning + new overload `.compaction(CompactionStrategy)`; `.summarizer(Summarizer)`; defaults assembled in `build()`), `internal/ZoneBoundariesTest` (learns `spi.compaction`)
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/ScriptedSummarizer.java`
- Test: `spi/compaction/SummarizingCompactionTest.java`, `spi/compaction/SummarizerTest.java`; reshaped `ReducerCompactionTest`, `InProcessEngineCompactionTest`, `EventTest`/`ValidationTest` pins

**Interfaces — Produces (spec §10.6 verbatim):**

```java
public interface CompactionStrategy {
    /** Pure — the reducer consults this at CallModel decision points. */
    boolean requiresCompaction(SessionState state);

    /** Effectful — the ENGINE performs this. Returns a smaller working set
     *  and what producing it cost (a bill, not a diff; non-LLM strategies
     *  return Usage.zero()). */
    Result compact(List<Message> workingSet);

    record Result(List<Message> workingSet, Usage spend) { /* house-validated */ }

    static CompactionStrategy summarizing(CompactionPolicy policy, Summarizer summarizer) { … }
    static CompactionStrategy disabled() { … }   // never compacts; compact() throws IllegalStateException
}

// spi.compaction:
public interface Summarizer {
    Summary summarize(Context head, CompactionPolicy policy);
    record Summary(String text, Usage usage) { /* house-validated */ }
    static Summarizer usingProvider(ModelProvider provider, ModelSettings config) { … }
}
```

**Semantics (the contract Tasks 5–6 build on):**
1. **Reducer consult** (both `CallModel` decision points, order still termination → compaction → call): `compaction.requiresCompaction(state)` true → emit `Effect.Compact(state.messages())` — the WHOLE working set — with status `COMPACTING`, no other effects. The reducer no longer computes cuts.
2. **Engine perform** (Compact arm): under the `nessy.compaction` observation — `Result r = reducer.compaction().compact(effect.workingSet()); Context.of(r.workingSet());` (validation — a pair-breaking result is a failure) → event = `Compacted(r.workingSet(), r.spend())`. ANY `RuntimeException` (strategy failure, validation failure, blank-summary `IllegalStateException`) takes the EXISTING best-effort path: observation error, hub `CompactionFailed`, feed `CompactionSkipped(reason)`. Event built inside the scope, fed outside — the F2 conventions stand.
3. **Reducer apply** (`Compacted`): if `event.workingSet().size() < state.messages().size()` → replace messages wholesale, `generation + 1`, `usage.plus(event.spend())`, `lastInputTokens = 0`, `AWAITING_MODEL`, emit `CallModel`. Otherwise (non-shrinking result) → apply as a SKIP: accumulate spend, status `AWAITING_MODEL`, `CallModel`, NO generation bump, messages untouched. `CompactionSkipped` semantics unchanged.
4. **The default, `summarizing(policy, summarizer)`** (package-private `SummarizingCompaction`): `requiresCompaction` = `policy.trigger().shouldCompact(state)`. `compact(ws)`: `int cut = Context.of(ws).pairSafeCut(policy.keepRecentMessages())`; `cut == 0` → `Result(ws, Usage.zero())` (no safe cut → the reducer's skip rule handles it); else `Summary s = summarizer.summarize(Context.of(ws).head(cut), policy)` → result list = `[Message.user(SUMMARY_PREFIX + s.text()), …ws.subList(cut, size)…]`, spend = `s.usage()`. `SUMMARY_PREFIX` ("[Conversation summary — earlier turns compacted]\n") MOVES from `Reducer` to `SummarizingCompaction` — the reducer no longer knows summary formatting.
5. **`ProviderSummarizer`** (behind `usingProvider`): the engine's current `summarize` behavior extracted verbatim — head messages + trailing `Message.user(policy.instructions())`, config's model/systemPrompt, `maxTokens = policy.summaryMaxTokens()`, no tools, empty capabilities, null responseSchema; text chunks concatenated; `TurnEnded` usage captured (absent → `Usage.zero()`); blank text → `IllegalStateException("summarizer returned no text")`.
6. **Builder**: `Reducer` record becomes `(TerminationPolicy termination, CompactionStrategy compaction)`; `Reducer.defaults()` uses `summarizing(CompactionPolicy.defaults(), …)` — but the default summarizer needs a provider, which `Reducer.defaults()` lacks: `Reducer.defaults()` uses `CompactionStrategy.disabled()` and JAVADOCS why (the builder is where providers live). `AgentBuilder.build()` assembles: explicit strategy wins; else explicit policy (or window-derived trigger per Task 2) + `.summarizer(...)` (default `usingProvider(provider, settings)`) → `summarizing(...)`. `CompactionPolicy.disabled()` passed as policy → builder uses `CompactionStrategy.disabled()`.
7. **`ScriptedSummarizer`** (nessy-testing, public, mirrors `ScriptedModelProvider`'s idiom): queue of `Summary` results or a throwing mode; records handed heads (`List<Context> heads()`).

- [ ] **Step 1: failing tests.**
  - `EventTest`/`ValidationTest`: `Compacted` pins — null list rejected, null spend rejected, list defensively copied.
  - `ReducerCompactionTest` (reshaped, still driven): `at_the_trigger_the_whole_working_set_goes_to_the_strategy` (over-trigger state → `Effect.Compact` whose workingSet == state.messages, status COMPACTING, no other effects); `termination_still_beats_compaction`; `a_shrinking_result_replaces_the_working_set` (feed `Compacted([summary, tail…], Usage(5_000, 200, 0))` → messages replaced, generation 1, usage summed, lastInputTokens 0, callModel); `a_non_shrinking_result_is_a_skip` (Compacted with same-size list → messages untouched, generation 0, spend still accumulated, callModel); `a_skip_proceeds_without_retrying_in_place` (unchanged).
  - `SummarizingCompactionTest` (pure over ScriptedSummarizer): `the_head_is_summarized_and_the_tail_survives_verbatim` (12-message fixture, keepRecent 4 → result = prefix-summary message + exact tail; spend = scripted usage; handed head == messages [0, cut)); `no_safe_cut_returns_the_working_set_unchanged` (all-tool-exchange history → same list, zero spend); `the_summary_prefix_is_the_strategys_business` (result message text starts with the prefix).
  - `SummarizerTest` (usingProvider over ScriptedModelProvider): `the_head_and_instructions_become_a_tool_free_request` (captured request: context = head + instructions-as-user, tools empty, maxTokens = policy's); `the_summary_carries_text_and_spend`; `a_blank_summary_is_a_failure`.
  - `InProcessEngineCompactionTest` (reshaped): `a_triggered_compaction_summarizes_and_the_conversation_continues` (unchanged behavior, now through the strategy); `a_failing_strategy_emits_the_hub_event_and_the_turn_proceeds` (throwing ScriptedSummarizer → CompactionFailed + reply completes + generation 0); `a_pair_breaking_strategy_is_a_failure_not_a_corruption` (custom strategy returning an orphaned tool_use list → CompactionFailed path, working set untouched); `the_engine_reports_what_the_strategy_spent` (state.usage grew by scripted spend); observation tests unchanged.
- [ ] **Step 2: red.** **Step 3: implement** (grammar, reducer, strategy, summarizer, engine, builder — one coherent unit). **Step 4: both profiles green.** **Step 5: commit** `feat(api): the CompactionStrategy seam — the strategy proposes, the reducer disposes`.

---

### Task 5: `TranscriptStore`, `TranscriptEntry`, `MessageCodec`, and the strict write path

**Files:**
- Create: `spi/session/TranscriptStore.java`, `spi/session/TranscriptEntry.java`, `spi/session/MessageCodec.java`, package-private `spi/session/InMemoryTranscriptStore.java`, `internal/MessageJson.java`
- Modify: `spi/InProcessEngine.java` (append discipline; constructor takes `TranscriptStore`), `AgentBuilder.java` (`.transcript(...)`, default `inMemory()`)
- Test: `spi/session/TranscriptStoreTest.java`, `spi/session/MessageCodecTest.java`, journaling additions to `InProcessEngineTest`/`InProcessEngineCompactionTest`

**Interfaces — Produces:**

```java
public record TranscriptEntry(Message message, Usage turnUsage) { /* both non-null */ }

public interface TranscriptStore {
    void append(SessionId id, TranscriptEntry entry);   // STRICT: a thrown failure fails the run (javadoc'd)
    List<TranscriptEntry> read(SessionId id);           // append order; empty for unknown ids
    static TranscriptStore inMemory() { … }
}

public interface MessageCodec {
    byte[] encode(Message message);
    Message decode(byte[] bytes);
    static MessageCodec json(ObjectMapper mapper) { … } // canonical JSON as UTF-8
}
```

- **Append discipline:** one private engine method `journal(before, after, event)` called on the reduce-notify path so every arm is covered once:
  - Normal growth (`before.messages()` is a proper prefix of `after.messages()`): append each new message; `turnUsage` = the event's usage when the event is `ModelTurnEnded` (the flushed assistant message), else `Usage.zero()`.
  - Generation bump (compaction applied): newborn = messages of `after` not present in `before` (for the summarizing default that is exactly the summary message) — append them with the `Compacted` event's spend as `turnUsage`. Never re-append survivors.
- **Strict:** no try/catch around `append` — failure propagates, the run fails, the `finally` still persists the snapshot.
- `MessageCodec.json`: round-trips the sealed `Message`/`ContentBlock` grammar WITHOUT annotating it — `internal/MessageJson` supplies a Jackson module (mixins/subtype registration). The api zone stays annotation-free.
- In-memory store: `ConcurrentHashMap<SessionId, List<TranscriptEntry>>`, synchronized append, defensive read copies, no codec (codec is the seam durable modules and encryption decorators build on).
- `AgentBuilder.transcript(TranscriptStore)`, default `inMemory()`.

- [ ] **Step 1: failing tests.**
  - `TranscriptStoreTest`: `appends_read_back_in_order`; `an_unknown_session_reads_empty`; `entries_are_immutable_to_readers`.
  - `MessageCodecTest`: `every_block_variant_survives_the_round_trip` (one message per `ContentBlock` variant); `the_encoding_is_utf8_json` (decode bytes as UTF-8, parse as JSON, assert a known field).
  - Engine journaling: `every_message_is_journaled_at_birth` (tool round-trip → journal holds user, assistant(tool_use) with the turn's usage, results message with zero, final assistant with usage, in order); `compaction_journals_the_summary_with_its_spend` (post-compaction journal = originals + summary entry carrying the strategy's spend; nothing re-appended); `a_failing_journal_fails_the_run_loudly` (throwing store → run throws; snapshot still saved).
- [ ] **Step 2: red.** **Step 3: implement.** **Step 4: both profiles green.** **Step 5: commit** `feat(spi): the append-only transcript journal, strict, with MessageCodec`.

---

### Task 6: End-to-end proofs and documentation

**Files:**
- Test: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/EndToEndTest.java` additions
- Modify: `README.md`, `CHANGELOG.md`, spec §14 (gate table + sequencing), `AgentFacadeTest` (README snippet mirrors)

Facade-level proofs:
- `the_journal_keeps_what_compaction_removes` — compacting conversation with a test-held `TranscriptStore`; afterwards the working set is `[summary, …tail]` while the journal holds every original message plus the summary entry, in order.
- `the_ledger_counts_the_strategys_spend` — scripted summary usage lands in `reply.state().usage()`.
- `a_declared_window_compacts_a_small_model` — `.contextWindow(16_000)` with default `maxTokens`; scripted usage above `0.8 × (window − maxTokens)` → compaction fires.
- `a_custom_strategy_replaces_the_mechanism_wholesale` — a no-LLM strategy (drop-all-but-last-N via a lambda in the test) wired with `.compaction(strategy)`; conversation compacts with zero added spend and no summarizer involved.

Documentation:
- README Context Management: glossary nomenclature (transcript / working set / context), the strategy story (`CompactionStrategy`, default `summarizing`, `.compaction(policy)` vs `.compaction(strategy)`), `contextWindow` snippet, journal paragraph (strict, audit-grade, `MessageCodec` with encryption-decorator mention), usage-accounting sentence corrected (spend is billed to the ledger). Snippets mirrored in `AgentFacadeTest` verbatim.
- CHANGELOG Unreleased: Context type; CompactionStrategy + trigger + declared windows; complete usage accounting; spi.context/spi.compaction packaging; Summarizer; TranscriptStore + MessageCodec; ModelRequest carries Context.
- Spec §14: flip the `Context` adoption gate row to ✅ cleared; sequencing paragraph updated (this plan done; next: Plan 6 — harness reification, per-grant authority, Memory, context assembler).

- [ ] **Step 1: failing E2E tests → red.** **Step 2: green both profiles.** **Step 3: docs + snippet mirrors.** **Step 4: both profiles green again.** **Step 5: commit** `test+docs: the context collaborators, end to end`.

---

## Self-Review

**Spec coverage:** §5.0 → T6 docs; §10.6 strategy rulings (propose/dispose, spend-is-a-bill, non-shrinking-is-skip, default demotion, trigger, windows) → T2+T4; §10.8 Context → T1, adoption → T3; Summarizer sub-seam → T4; TranscriptStore/TranscriptEntry/strict/MessageCodec → T5; TokenEstimator → T3; packaging → T3+T4; gate row → T6. NOT in scope (Plan 6 per §14): Harness reification, ToolGrant/UsagePolicy, Memory, context assembler; typed agents await their design round.

**Type consistency:** `Context.pairSafeCut(int)`/`head(int)` (T1) are what T4's `SummarizingCompaction` calls; `CompactionPolicy(trigger, keepRecent, summaryMaxTokens, instructions)` (T2) is the knob bundle T4's default consumes; `CompactionStrategy.Result(workingSet, spend)` (T4) matches `Event.Compacted(workingSet, spend)`; `Summarizer.Summary(text, usage)` (T4) feeds the default's `Result`; `TranscriptEntry(message, turnUsage)` (T5) journals T4's spend on the summary entry; `ModelRequest.context()` (T3) is what T4's `ProviderSummarizer` constructs.

**Ordering:** T2 lands trigger/policy/window with a one-line reducer touch (small, green); T3 sweeps `ModelRequest` before T4 so the big task's diff is purely compaction-shaped; T4 is the single high-risk unit (Opus review per CLAUDE.md — reducer semantics); T5's journal diff rule is strategy-agnostic (set-difference newborns) so custom strategies journal correctly from day one.

**Placeholder scan:** none — every task carries exact signatures, arithmetic, choreography, and named test scenarios.
