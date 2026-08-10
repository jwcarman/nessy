# Nessy Context Management Implementation Plan (Plan 4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Long sessions stop dying at the context window — measured-trigger compaction through the reducer, the `ContextBuilder` projection seam (identity + tool-result elision), and the two freeze-gate record components (`Usage.cachedInputTokens`, `ModelRequest.responseSchema`) ride along.

**Architecture:** Per spec §10.6 (the settled design — read it before any task): Layer 1 is a pure projection seam consulted at request assembly; Layer 2 is stateful compaction as reducer semantics — `Effect.Compact` carries the messages to summarize, the engine performs it as an ordinary no-tools model call, `Event.Compacted` replaces the prefix with a summary while a pair-safe tail survives, and `generation` signals stores. The trigger is *measured*: the provider's own `TurnEnded.usage.inputTokens`, captured into state — no tokenizer anywhere. Failure is best-effort (hub `CompactionFailed` + `Event.CompactionSkipped` + proceed).

**Tech Stack:** existing modules only; zero new dependencies.

**Source spec:** `docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md` §10.6, §10.7, §14 gate table.

## Global Constraints

- Java 25; zones per spec §4.2; no new dependencies anywhere.
- **No star imports. No inline fully-qualified class names (documented simple-name collisions only). No `@SuppressWarnings` of any kind.**
- Apache header via `./mvnw license:format -Plicense`; Spotless via `./mvnw spotless:apply` before every commit.
- Tests read as prose: `snake_case` sentences, `@Nested` `Capitalized_phrases`.
- Keyless default build forever; release profile (`./mvnw -q -P release -DskipTests -Dgpg.skip=true verify`) must stay green — run BOTH profiles in the FOREGROUND (backgrounded builds have stalled three prior tasks).
- Core sealed switches stay exhaustive with no `default` arm; staged arms throw `UnsupportedOperationException("Task N")` exactly as v1 staged them.
- Never weaken an existing assertion; mechanical sweeps for new record components are expected (`Usage(a, b)` → `Usage(a, b, 0)` etc.); **no auxiliary constructors with silent defaults** — the P1 review killed that pattern for good reasons.
- Validation convention: `requireNonNull(x, "x must not be null")`; the sanctioned nullables are `SessionState.failureReason` and (new, Task 7) `ModelRequest.responseSchema`.
- Commit after every task.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `api/CompactionPolicy.java` (new) | trigger/tail/summary-budget/instructions record + `defaults()`/`disabled()` | 1 |
| `api/Event.java`, `spi/Effect.java`, `api/SessionStatus.java`, `api/SessionState.java` | `Compacted`, `CompactionSkipped`; `Compact`; `COMPACTING`; `generation` + `lastInputTokens` | 1 |
| `api/event/CompactionFailed.java` (new) | hub record (open vocabulary, not grammar) | 1 |
| `spi/Reducer.java` | capture, trigger, pair-safe cut, replacement, skip path | 2 |
| `spi/InProcessEngine.java` | `Compact` execution, failure→hub+skip, `nessy.compaction` observation | 3 |
| `spi/ContextBuilder.java` (new) | the seam: `project(SessionState)`; `identity()`; `elidingToolResults(int)` | 4, 5 |
| `AgentBuilder.java` | `.compaction(policy)`, `.contextBuilder(cb)` | 2, 4 |
| `api/Usage.java`, `spi/model/ModelRequest.java` + providers | freeze-gate components | 7 |
| `README.md`, `CHANGELOG.md`, spec gate table | docs | 8 |

---

### Task 1: Grammar III — compaction vocabulary (staged)

**Files:**
- Create: `nessy-core/src/main/java/org/jwcarman/nessy/api/CompactionPolicy.java`, `api/event/CompactionFailed.java`
- Modify: `api/Event.java`, `spi/Effect.java`, `api/SessionStatus.java`, `api/SessionState.java`, `spi/Reducer.java` (staged arms only), `spi/InProcessEngine.java` (staged `perform` arm only), plus every exhaustive-switch site the compiler surfaces (e.g. `EventTest`'s exhaustiveness test)
- Test: `api/CompactionPolicyTest.java` (new), `ValidationTest` additions, `EventTest` arms

**Interfaces:**
- Consumes: existing grammar.
- Produces (Tasks 2–6 rely on exactly):
  - `record CompactionPolicy(long triggerTokens, int keepRecentMessages, int summaryMaxTokens, String instructions)` — validation: `triggerTokens >= 1`, `keepRecentMessages >= 0`, `summaryMaxTokens >= 1`, instructions non-null; `static CompactionPolicy defaults()` = `(100_000, 10, 2_048, DEFAULT_INSTRUCTIONS)`; `static CompactionPolicy disabled()` = `(Long.MAX_VALUE, …defaults otherwise…)` with javadoc stating disabled == unreachable trigger. `DEFAULT_INSTRUCTIONS` (public constant): `"Summarize the conversation so far for your own future reference: goals, decisions, facts established, tool results that matter, and open questions. Be dense and factual; omit pleasantries."`
  - `Event.Compacted(String summary)`; `Event.CompactionSkipped(String reason)` — house validation
  - `Effect.Compact(List<Message> messages, String instructions)` — `List.copyOf`, both validated
  - `SessionStatus.COMPACTING` (javadoc: a summarization call is in flight)
  - `SessionState` components in order: `id, messages, pendingBlocks, pendingCalls, pendingResults, consecutiveErrors, turns, usage, lastInputTokens, generation, failureReason, status` — `long lastInputTokens` (>=0), `int generation` (>=0); `newSession` starts both 0; withers `withLastInputTokens(long)`, `withGeneration(int)`
  - `record CompactionFailed(SessionId sessionId, String reason)` in `api.event` — hub vocabulary, NOT sealed
- Staged arms: `Reducer.reduce` gains `case Event.Compacted … -> throw new UnsupportedOperationException("Task 2")` (and `CompactionSkipped` likewise); `InProcessEngine.perform` gains `case Effect.Compact … -> throw new UnsupportedOperationException("Task 3")`.

Steps follow the house TDD sequence: write `CompactionPolicyTest` (construction, both factories, each validation rejection — `defaults_trigger_at_one_hundred_thousand_tokens`, `disabled_never_triggers`, `ceilings_below_their_floors_are_rejected` style) and `ValidationTest`/`SessionStateTest` additions (new withers return new instances, negative `lastInputTokens`/`generation` rejected) → red → implement → both profiles green → commit `feat(core): compaction vocabulary (staged)`. The `SessionState` component sweep touches every construction site the compiler surfaces (tests included; positional updates only, assertions unchanged).

---

### Task 2: Reducer compaction semantics

**Files:**
- Modify: `spi/Reducer.java` (record gains `CompactionPolicy compaction` component; staged arms replaced), `org/jwcarman/nessy/AgentBuilder.java` (`.compaction(CompactionPolicy)` default `defaults()`; passes to `Reducer`)
- Test: `spi/ReducerCompactionTest.java` (new), mechanical `new Reducer(policy)` sweeps

**Interfaces:**
- Consumes: Task 1's vocabulary.
- Produces: `record Reducer(TerminationPolicy termination, CompactionPolicy compaction)`; `Reducer.defaults()` uses both defaults. Semantics (the contract Tasks 3/6 build on):
  1. `modelTurnEnded` captures `event.usage().inputTokens()` into `lastInputTokens` alongside the existing turn/usage accounting — on EVERY turn end.
  2. At both `CallModel` emission sites (`userSaid`, `toolFinished` final flush), the decision order is **termination first, then compaction, then CallModel**: if `lastInputTokens >= compaction.triggerTokens()`, compute the pair-safe cut; if the cut index is `> 0`, emit `Effect.Compact(messages.subList(0, cut) copied, compaction.instructions())` with status `COMPACTING` and no other effects; if no safe cut exists (cut == 0), proceed straight to `CallModel` (nothing compactable — do NOT loop).
  3. **Pair-safe cut**: the largest index `cut <= messages.size() - keepRecentMessages` such that `messages.get(cut)` is a `USER` message whose blocks are ALL `TextBlock`s (a genuine user turn — never between an assistant `tool_use` and its results message). Walk downward from the limit; 0 if none qualifies.
  4. `Compacted(summary)`: recompute the same cut (state is unchanged — determinism holds), replace `[0, cut)` with ONE `Message.user("[Conversation summary — earlier turns compacted]\n" + summary)`, keep the tail, `generation + 1`, `lastInputTokens = 0`, status `AWAITING_MODEL`, emit `CallModel`.
  5. `CompactionSkipped(reason)`: status `AWAITING_MODEL`, emit `CallModel` directly (no re-check — one attempt per decision point; the untouched `lastInputTokens` retriggers naturally at the next one).

- [ ] **Step 1: failing tests** — `ReducerCompactionTest`, `@Nested` groups `Triggering`, `The_pair_safe_cut`, `Applying_a_summary`, `Skipping`:

```java
@Test
void a_turn_end_records_the_measured_input_tokens() { /* ModelTurnEnded(END_TURN, new Usage(120_000, 50)) → state.lastInputTokens() == 120_000 */ }

@Test
void below_the_trigger_a_user_message_calls_the_model_as_always() { /* lastInputTokens 99_999, trigger 100_000 → effects containsExactly(callModel()) */ }

@Test
void at_the_trigger_a_user_message_compacts_instead_of_calling() { /* seed state with 12 messages (10 plain user/assistant text pairs + tool exchange), lastInputTokens 100_000, keepRecent 4 → effects containsExactly one Effect.Compact whose messages are the first cut messages; status COMPACTING */ }

@Test
void termination_beats_compaction() { /* turn-exhausted AND over-trigger → FAILED, no Compact */ }

@Test
void the_cut_never_separates_a_tool_use_from_its_results() { /* messages: [user text, assistant(tool_use), user(tool_results), assistant text, user text, ...tail...]; keepRecent forces the naive cut into the middle of the exchange → actual cut walks DOWN to the user-text boundary before it */ }

@Test
void with_no_safe_cut_the_model_is_called_uncompacted() { /* over-trigger but history is one giant tool exchange → callModel, not Compact, and NOT a Compact loop */ }

@Test
void a_summary_replaces_the_prefix_and_keeps_the_tail_in_order() { /* drive Compact emission, then reduce Compacted("the gist"); assert messages = [user "[Conversation summary…]\nthe gist", ...exact tail...], generation 1, lastInputTokens 0, effects callModel() */ }

@Test
void a_skip_proceeds_to_the_model_without_retrying_in_place() { /* reduce CompactionSkipped("429"); AWAITING_MODEL + callModel; lastInputTokens untouched */ }
```

(Build seed states by driving the real reducer, matching the house fixture style in `ReducerToolResultTest` — no hand-rolled `SessionState` where a driven one is feasible; withers are fine for `lastInputTokens` seeding.)

- [ ] **Step 2: red** — staged arms throw. **Step 3: implement** per the produces-contract. **Step 4: both profiles green** (every prior reducer test untouched). **Step 5: commit** `feat(core): measured-trigger compaction semantics`.

---

### Task 3: Engine — performing `Compact`

**Files:**
- Modify: `spi/InProcessEngine.java` (staged arm replaced), `internal/EngineObservations.java` (+`compaction(registry)` observation, name `nessy.compaction`, contextual name `compact`)
- Test: `spi/InProcessEngineCompactionTest.java` (new, on `EngineFixtures`)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces: `perform`'s `Compact` case — builds a summarization `ModelRequest`: messages = `effect.messages()` + one trailing `Message.user(effect.instructions())`; NO tools; same model; `maxTokens = reducer.compaction().summaryMaxTokens()`; empty capabilities. Streams it (same provider), concatenating `TextChunk` text (thinking chunks ignored; `TurnEnded` ends collection); success feeds `Event.Compacted(text)`; ANY `RuntimeException` (including a blank/empty collected summary treated as `IllegalStateException("summarizer returned no text")`) emits `hub.emit(new CompactionFailed(id, describe(e)))` then feeds `Event.CompactionSkipped(describe(e))`. The whole execution wraps in the `nessy.compaction` observation (error-marked on the failure path, per the F2 convention). The summarization call is NOT projected through `ContextBuilder` (the effect carries its own messages — javadoc this).

- [ ] Tests (scripted fixtures — a compaction turn is just another scripted turn):

```java
@Test
void a_triggered_compaction_summarizes_and_the_conversation_continues() { /* two-turn script: [summary text turn], [normal answer turn]; seed by sending once with huge scripted usage (endTurn(new Usage(150_000, 10))) then send again; assert final state's messages start with the summary user message, generation 1, and the reply text is the normal answer */ }

@Test
void a_failing_summarizer_emits_the_hub_event_and_the_turn_proceeds() { /* provider throws on the compact call (FakeProvider variant); RecordingSubscriber sees CompactionFailed; reply still completes from the follow-up scripted turn; generation stays 0 */ }

@Test
void the_compaction_call_carries_no_tools_and_the_policy_budget() { /* capture via ScriptedModelProvider.requests(): the summarization request has empty tools and maxTokens == summaryMaxTokens */ }

@Test
void compaction_produces_its_own_observation() { /* TestObservationRegistry: hasObservationWithNameEqualTo("nessy.compaction"); failure variant .hasError() */ }
```

Red → implement → both profiles green → commit `feat(engine): perform compaction as an ordinary model call`.

---

### Task 4: The `ContextBuilder` seam

**Files:**
- Create: `spi/ContextBuilder.java`
- Modify: `spi/InProcessEngine.java` (`requestFor` projects: `contextBuilder.project(state)` instead of `state.messages()`; constructor position 10), `AgentBuilder.java` (`.contextBuilder(ContextBuilder)` default `identity()`)
- Test: `spi/ContextBuilderTest.java` (new); engine test proving consultation

**Interfaces:**
- Produces:

```java
package org.jwcarman.nessy.spi;

import java.util.List;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;

/**
 * Projects session state into the messages one model call sees.
 *
 * <p>State stays the full source of truth; the projection decides what THIS request carries —
 * windows, redaction, elision, budgeting. Pure and total: no I/O, no mutation, same output for the
 * same state. Consulted by engines at request assembly for conversational calls only; a
 * compaction call carries its own messages and is never projected.
 */
public interface ContextBuilder {

  List<Message> project(SessionState state);

  /** The default: the model sees everything. */
  static ContextBuilder identity() {
    return SessionState::messages;
  }

  static ContextBuilder elidingToolResults(int keepRecentMessages) {
    return new ElidingToolResults(keepRecentMessages);   // Task 5; Task 4 stages it as a thrown UnsupportedOperationException("Task 5") factory body
  }
}
```

- Tests: `identity_projects_every_message_unchanged`; engine-level `the_engine_consults_the_context_builder` (a marking projection — appends nothing but e.g. drops the first message — via `AgentBuilder.contextBuilder`; assert `ScriptedModelProvider.requests()` saw the projected list, and `state.messages()` kept the full one). Both profiles green → commit `feat(spi): the ContextBuilder projection seam`.

---

### Task 5: `elidingToolResults`

**Files:**
- Create: `spi/ElidingToolResults.java` (package-private)
- Test: additions to `spi/ContextBuilderTest.java`

**Interfaces:**
- Produces: for every message OLDER than the last `keepRecentMessages`, each `ToolResultBlock(id, content, isError)` becomes `ToolResultBlock(id, "[elided]", isError)` — ids, pairing, error flags, and every non-tool-result block untouched; recent messages verbatim. Class javadoc carries the spec's cache tradeoff verbatim in substance: the sliding boundary rewrites one old message per turn, churning the prompt-cache prefix — elision trades cache hits for context space, which is why `identity()` is the default.
- Tests: `old_tool_results_are_elided_but_their_ids_and_pairing_survive` (order-sensitive full-list assertion); `recent_messages_are_verbatim`; `non_tool_blocks_are_never_touched`; `keep_zero_elides_everything_and_keep_huge_elides_nothing`; validation `keepRecentMessages >= 0`. Commit `feat(spi): tool-result elision projection`.

---

### Task 6: End-to-end through the facade

**Files:**
- Test: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/EndToEndTest.java` additions

Three facade-level proofs, scripted:
- `a_long_conversation_compacts_and_keeps_answering` — turn 1 with `endTurn(new Usage(150_000, 20))`, send 2 scripts `[summary turn][answer turn]`; assert the reply answers, the state's first message is the summary, `generation == 1`, and a THIRD send works normally.
- `a_failed_compaction_is_invisible_to_the_user_but_visible_on_the_hub` — summarizer turn throws; `RecordingSubscriber` captured `CompactionFailed`; the reply still answers.
- `an_eliding_context_builder_shrinks_what_the_model_sees_not_what_the_state_keeps` — agent with `.contextBuilder(ContextBuilder.elidingToolResults(2))`, a tool conversation, then a second send; assert via `provider.requests()` that an old tool result went `[elided]` on the wire while `reply.state()` still holds the real content.

Both profiles green → commit `test: compaction and projection end to end`.

---

### Task 7: Freeze-gate record components

**Files:**
- Modify: `api/Usage.java` (component `long cachedInputTokens`, position 3; validation ≥0; `zero()` and `plus` updated — NO auxiliary constructor, full mechanical sweep of every `new Usage(a, b)` → `(a, b, 0)` across all modules), `spi/model/ModelRequest.java` (component `ObjectNode responseSchema`, nullable — the second sanctioned nullable, javadoc'd as the structured-output slot providers currently ignore; engine passes `null`; sweep constructors)
- Modify: `nessy-model-anthropic/.../AnthropicStream.java` (populate `cachedInputTokens` from `message_start` usage's `cache_read_input_tokens` — verify the SDK accessor in sources; absent → 0), `nessy-model-openai/.../OpenAiStream.java` (from usage `prompt_tokens_details.cached_tokens` when present — verify accessor; absent → 0)
- Test: `ValidationTest`/`Usage` additions (negative cached rejected; `plus` sums all three); per-provider stream tests (a fixture carrying cache-read tokens lands in `TurnEnded.usage.cachedInputTokens`; a fixture without them yields 0); `ModelRequest` test (null `responseSchema` accepted and readable; non-null carried).

Both profiles green (the sweep is large but mechanical; assertions unchanged except where they now legitimately assert the third component) → commit `feat: freeze-gate components — cached tokens and the response-format slot`.

---

### Task 8: Documentation

- README: a Context Management section (compaction-by-default with the measured trigger, `CompactionPolicy` knobs, `disabled()`; `ContextBuilder` with the elision example and its cache tradeoff, compile-verified snippets via the scratch method); CHANGELOG Unreleased (compaction, ContextBuilder, elision, cached tokens, response-format slot); spec §14 gate table → compaction row ✅ cleared, `Usage` row ✅ cleared, `responseSchema` row ✅ cleared (slot shipped; feature post-1.0). Remaining open gate after this plan: artifact references only.
- Both profiles green → commit `docs: context management is real`.

---

## Self-Review

**Spec coverage:** §10.6 Layer 1 → Tasks 4–5; Layer 2 (trigger, survivors, pair-safe cut, generation, best-effort failure, `nessy.compaction`, policy + builder knobs) → Tasks 1–3, 6; §10.7 needs no code (design note; gate already resolved); §14 gates: compaction + `Usage` + `responseSchema` cleared by Tasks 1–8; artifact references remain open by design.

**Placeholder scan:** Task 4's `elidingToolResults` factory staging is explicit (`UnsupportedOperationException("Task 5")`), matching house staging convention; test bodies given as contracts-with-comments follow the same style Plans 3's briefs used successfully — every scenario names its exact fixtures and assertions.

**Type consistency:** `CompactionPolicy(triggerTokens, keepRecentMessages, summaryMaxTokens, instructions)` (T1) matches T2's trigger/cut/skip usage and T3's `summaryMaxTokens`; `Reducer(TerminationPolicy, CompactionPolicy)` (T2) matches T3's `reducer.compaction()` access and the builder wiring; `ContextBuilder.project` (T4) matches T5's implementation and T6's facade test; `Usage` three-component shape (T7) is used nowhere earlier (T2/T3/T6 fixtures use two-arg `Usage` until T7's sweep — T7 explicitly sweeps them).
