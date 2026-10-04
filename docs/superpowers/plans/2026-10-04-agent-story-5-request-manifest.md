# The Agent's Story, Plan 5: The Request Manifest — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every stored model-call event says what the request was made of, each part named by reference and none copied.

**Architecture:** The one place that assembles a model request (`DefaultInferenceService.infer`) also builds its `RequestManifest`, storing each section through `Payloads` and returning the manifest beside the result. The handler puts it on the outcome; it rides the outcome, the command and (for a retried attempt) the `FailedAttempt`; the fold copies it onto the event it already writes. The manifest is stored and is not public: nothing in `nessy-api` names it.

**Tech Stack:** Java 25, Maven (`./mvnw`), JUnit 5, AssertJ, Awaitility, Testcontainers (PostgreSQL 18), Jackson 3.

**Spec:** `docs/superpowers/specs/2026-10-04-agent-story-design.md`. Read §8b and all of §10 before any task. Plans 1 and 2 are merged, and so is Part A of plan 4 (`Payloads.putDocument`).

**Order of execution:** see "Order of execution" in `2026-10-04-agent-story-4-approval-question.md`. This plan runs in its own worktree, at the same time as plan 3.

**Names in the code that differ from the spec's prose:** the tool-calls event is `AgentEvent.ActionsRequested` (outcome `EffectOutcome.InferenceRequestedActions`); a retried attempt is `AgentEvent.InferenceAttempted` (told as `Narration.InferenceRetried`). The five model-call events are `InferenceAnswered`, `InferenceRefused`, `InferenceFailed`, `InferenceAttempted` and `ActionsRequested`.

## This plan touches the fold

Task 3 edits `nessy-engine/src/main/java/org/jwcarman/nessy/engine/core/AgentState.java`. **What it may do there is exactly this, and nothing else:** add a component to the four inference outcomes, and copy it (and `FailedAttempt.request()`) onto the event the existing code already writes.

**No guard, no `when` clause, no phase check, no effect, no state transition, no `TurnPolicy` call and no `Decision.ignore()` changes.** `TurnTally` reads no new field. A task that seems to need one stops and reports.

Stored shapes change. Databases are recreated; there is no migration.

## Resolutions this plan makes

Each is a choice the spec left open, or a place the code does not allow what the spec says. They are listed so James can overrule one before it is built.

1. **The manifest is optional on a stored event, and absent when no request was in hand.** The spec says every stored model-call event carries one. Some failures are recorded without a request ever being assembled, or without its result coming back: the handler threw, the row could not be decoded, the deadline passed while the call was in flight or still queued, the direct door timed out. Those `InferenceFailed` events carry `Optional.empty()`. Every event that comes from a provider's reply carries one.
2. **`InferenceService.infer` returns an engine-internal `Inferred(InferenceResult result, RequestManifest request)`.** `DefaultInferenceService` gains a `Payloads` to store the sections with. The manifest is built from the very `InferenceRequest` that is sent, before the provider is called.
3. **The engine's version comes from a filtered resource** in `nessy-engine` (`org/jwcarman/nessy/engine/version.properties`, `version=${project.version}`), read once. Nothing reads the jar manifest, which is absent when running from classes.
4. **What each reference holds:**
   - `instructions`: the system prompt as blocks (one `Block.Text`).
   - `tools`: a document, `{"offers":[{"name","description","schema"}...],"choice":{...}}`, the offers in their bound order and the choice of this call (so an answer-only call has a different `tools` reference).
   - `answerShape`: a document, the output schema.
   - `options`: a document, `{"model","maxTokens","properties":{...}}`. Vendor property values are stored; the storage codec covers them. The `InferenceOptions` javadoc that says "never written to the event log" is corrected.
   - a summary's `text`: blocks (one `Block.Text`). Summary text is a string in the chapter table today, not a payload; the assembly stores it to get a reference, once for each chapter.
   - a memory, state or ambient section's `content`: its own blocks, in the order the sources were bound. Kinds are not unique, so the list order is the record.
5. **`SummarySection.chapter` is the `Chapter` the spec names,** which repeats the agent's type and id inside each entry. It is the spec's shape, kept as written.
6. **No in-process memory of what was stored.** Each call puts every section again; an unchanged section is the same reference and the store writes nothing new (`ON CONFLICT DO NOTHING`). A dozen small statements for each model call is the cost, as spec §8b says.

## Global Constraints

- The fold rule above binds Task 3.
- **Red first.** Every task writes its tests first and sees them fail for the right reason. The fold test goes in `AgentStateTest`.
- **Nothing else moved.** `AgentStateTest`, `AgentStateRepeatedCallIdTest`, `UnattributableAnswerTest`, `RepeatedCallIdLateOutcomeTest`, `DispatcherFailureTest`, `GiveUpTest` and `HarnessLoopTest` change only where a constructor gained an argument. No assertion in them is removed or weakened. Each report lists the pre-existing test files it edited and why.
- **The request that is sent does not change.** `DirectHarnessStrataTest`, `DirectHarnessInstructionsTest`, `ContextAssemblerTest` and `ContextReplay`-based tests pass unchanged: the manifest is a record of the request, built beside it, and nothing is driven from it.
- **The stored shape is written out by hand.** `ValueTypeCodecTest` holds a whole `RequestManifest` as a JSON literal, and one literal for each of the five events with its `request`, read and written; and one literal without the key, which reads as absent.
- **The manifest is not public.** `RequestManifest` lives in `org.jwcarman.nessy.backend.event`, beside `AgentEvent`. `nessy-api` does not depend on the backend SPI, so it cannot name it; no narration, no `StoryContent` method and no doc page for application authors exposes it.
- New names are exactly: `RequestManifest` with its nested `Section`, `SummarySection` and `TurnRange` (backend SPI, spec §8b verbatim); engine-internal `Inferred` and `EngineVersion`. A task that needs another stops and reports.
- No `@SuppressWarnings`, `// NOSONAR` or any other suppression. No star imports. No mocking library. Prose test names. One throwing call inside an exception-assertion lambda (S5778). Non-emptiness asserted before an all-match or none-match (S5841).
- `./mvnw spotless:apply license:format` before each commit. Scoped builds by artifactId with `-am`. One Maven process in a worktree. Before the gate: `./mvnw -B -q clean test-compile; echo $? > target/tc.exit` reads 0.
- A task's last step is the gate: `./mvnw -B -q clean verify -Dnessy.excludedGroups=live; echo $? > target/gate.exit`, and Maven's exit code is read from that file.
- **Review:** Task 3 gets the high-risk review on Opus; the reviewer reads the `engine/core/` diff line by line against "This plan touches the fold". Tasks 1 and 2 get the task review on Sonnet. The final whole-branch review is on Opus.
- Docs describe what is, in plain American wording, with no history.

## Review Focus

1. **Two calls with nothing changed store nothing new.** After the first model call of an agent, a second with the same prompt, tools, options and sections adds no row to `nessy_payload` beyond the turn's own content. (Task 2)
2. **One changed ambient section stores exactly one new payload,** and only that section's reference differs between the two manifests. (Task 2)
3. **The manifest describes the request that was sent.** Each reference resolves to exactly what the `InferenceRequest` the provider received held: prompt text, offers and choice, schema, options, each summary's text, each section's blocks, and the tail's first and last turn. (Task 2)
4. **An answer-only call.** Its `tools` reference differs from a normal call's, because the choice differs. (Task 2)
5. **A retried call.** Each `InferenceAttempted` carries the manifest of its own failed attempt, and the closing event carries the manifest of the attempt that closed it. An attempt that failed with no request in hand carries none. (Task 3)

---

### Task 1: The manifest, and the engine's version

Not a fold task. Nothing is wired yet.

**Files:** create `nessy-backend/spi/.../event/RequestManifest.java`; create `nessy-engine/src/main/resources/org/jwcarman/nessy/engine/version.properties` and turn on resource filtering for that file in `nessy-engine/pom.xml`; create `nessy-engine/.../inference/EngineVersion.java`; tests `RequestManifestTest` (spi), `ValueTypeCodecTest`, `EngineVersionTest`.

**Interfaces:**
- The record exactly as spec §8b gives it:
  ```java
  public record RequestManifest(
      String engineVersion,
      PayloadRef instructions,
      PayloadRef tools,
      Optional<PayloadRef> answerShape,
      PayloadRef options,
      List<SummarySection> summaries,
      Optional<TurnRange> tail,
      List<Section> memory,
      List<Section> state,
      List<Section> ambient) {

    public record Section(String kind, PayloadRef content) {}
    public record SummarySection(Chapter chapter, PayloadRef text) {}
    public record TurnRange(TurnId from, TurnId through) {}
  }
  ```
  Null refusals on every required component; the lists are copied; a null `Optional` or list read from storage becomes empty. `TurnRange` refuses `through` before `from`.
- `EngineVersion.current()`: the string from the filtered resource; a missing or unfiltered value (`${project.version}`) is a fault at first use, not a silent "unknown".

- [ ] **Step 1: Tests, red.** `RequestManifestTest`: the refusals, the copies. `ValueTypeCodecTest`: `aManifestIsStoredAsItsReferences`, the whole manifest as a hand-written literal (two summaries, a tail, one section of each kind, an answer shape), read and written; and a minimal one (no summaries, no tail, no sections, no answer shape). `EngineVersionTest`: `the_engine_knows_its_own_version` (matches the project's version pattern, and is not the unfiltered placeholder).
- [ ] **Step 2:** Implement. Gate (the filtered resource must survive `clean verify` and the release profile's `install -DskipTests -Dgpg.skip`; run the second once and read its exit code). Commit: `feat: a request manifest names what a model request was made of`.

### Task 2: The assembly builds the manifest beside the request

Not a fold task.

**Files:** create `nessy-engine/.../inference/Inferred.java`; `InferenceService.java` (returns `Inferred`); `DefaultInferenceService.java` (gains `Payloads`; builds the manifest from the `InferenceRequest` it sends); the two factories that construct it (`DefaultQueuedHarnessFactory`, `DefaultDirectHarnessFactory`; `payloads` is in scope at both); `InferenceHandler.java` (reads `inferred.result()`; the manifest is not yet carried); `InferenceOptions` javadoc; every test that implements `InferenceService` as a lambda (`InferenceHandlerTest` and others: they return `new Inferred(result, <a fixed manifest>)`); tests `DefaultInferenceServiceTest` (new or existing), a container test for the payload counts.

**Interfaces:**
- `record Inferred(InferenceResult result, RequestManifest request)`, engine-internal.
- `InferenceService.infer(InferenceInvocation)` returns `Inferred`.
- `DefaultInferenceService.infer`: assembles the `InferenceRequest` exactly as today; builds the manifest from that request, storing each part with `payloads.forAgent(invocation.agentId())` as "Resolutions" item 4 says; calls the provider; returns both. The tail is `Optional.empty()` when the request's tail is empty, else the first and last tail turn ids.
- If the provider throws, `infer` throws as today, and no manifest is returned.

- [ ] **Step 1: Tests, red.** With a recording provider and an `InMemoryPayloads`:
  - `the_manifest_resolves_to_exactly_what_was_sent` (Review Focus 3): each reference is resolved and compared with the recorded `InferenceRequest`.
  - `an_answer_only_call_names_different_tools` (Review Focus 4).
  - `a_request_with_no_tail_no_summaries_and_no_sections_has_an_empty_manifest_for_them`.
  - `sections_are_listed_in_the_order_their_sources_were_bound`, with two sources of the same kind.
  - `the_manifest_names_the_engines_version`.
  - Container (`EngineFixture`, PostgreSQL), counting rows of `nessy_payload` for the agent: `two_calls_with_nothing_changed_store_nothing_new` (Review Focus 1) and `a_changed_ambient_section_stores_one_new_payload` (Review Focus 2).
- [ ] **Step 2:** Implement. The request handed to the provider is the same object the manifest was built from.
- [ ] **Step 3:** The request-asserting tests named in Global Constraints pass unchanged. Gate. Commit: `feat: the assembly that builds a model request also records what it was made of`.

### Task 3: Every model-call event carries its manifest

**The fold task.** High-risk review on Opus.

**Files:** `EffectOutcome.java` (the four inference outcomes gain `Optional<RequestManifest> request`; keep the shorter constructors where callers that have none use them); `FailedAttempt.java` (gains `Optional<RequestManifest> request`; a null read from an effect row is empty); `InferenceHandler.java` (puts `Optional.of(inferred.request())` on every arm's outcome); `EffectDispatcher.accumulated` (the `FailedAttempt` takes the failed outcome's `request`; nothing else in the dispatcher changes); `EffectTermsSource` `InferenceTerms.failed` / `undispatchable` (empty); `EffectOutcomes.java` (four arms); `AgentCommand.InferenceOutcome` (four arms); `AgentState.java` (`completed` and `closing`: five constructions gain one argument each); `AgentEvent.java` (five events gain `Optional<RequestManifest> request`; null is empty); tests; `docs/concepts/events.md` (the stored event lists; one paragraph: what was sent is recorded by reference, and is not part of the public story), `CHANGELOG.md`.

**Interfaces:**
- `EffectOutcome.InferenceAnswered(PayloadRef answer, boolean truncated, Usage usage, Optional<RequestManifest> request)`; `InferenceRefused(String category, Usage usage, Optional<RequestManifest> request)`; `InferenceFailed(Failure failure, Usage usage, Optional<RequestManifest> request)`; `InferenceRequestedActions(PayloadRef request, List<ActionRequest> actions, Usage usage, Optional<RequestManifest> manifest)`. On the last one the component is named `manifest`, because `request` is already the reference to what the model wrote.
- `InferenceOutcome.Answered`, `Refused`, `Failed`, `RequestedActions`: the same trailing component, under the same names.
- `FailedAttempt(Failure failure, Usage usage, Optional<RequestManifest> request)`.
- `AgentEvent.InferenceAnswered(seq, turn, answer, truncated, usage, request)`; `InferenceRefused(seq, turn, category, usage, request)`; `InferenceFailed(seq, turn, failure, usage, request)`; `InferenceAttempted(seq, turn, failure, usage, request)`; `ActionsRequested(seq, turn, request, actions, usage, manifest)`.
- Narration does not change. `StoryEvents`, `Transcript`, `TurnTally`, `StoredContent` and `EventUsageReports` read no new field; they change only where a record pattern gained a component.

- [ ] **Step 1: Fold tests, red.** In `AgentStateTest`: `an_answer_is_recorded_with_what_its_request_was_made_of`; the same for a refusal, a failure and requested actions; `each_retried_attempt_is_recorded_with_its_own_manifest_and_the_closing_event_with_its_own` (Review Focus 5: three distinct manifests in, the same three out, in order); `an_event_with_no_manifest_in_hand_records_none`.
- [ ] **Step 2: Handler and dispatcher tests, red.** `InferenceHandlerTest`: every arm's outcome carries the manifest the service returned. `DispatcherFailureTest`: `a_failed_attempt_keeps_the_manifest_of_its_request` (the list handed to `reschedule` holds it); `an_attempt_that_threw_keeps_none`. `EffectTermsSourceTest`: the inference terms' failures carry none.
- [ ] **Step 3: Stored shapes, red.** `ValueTypeCodecTest`: one literal for each of the five events with a manifest, read and written; one `inference-failed` literal without the key reads as empty. A `FailedAttempt` list literal with and without a manifest (the effect row's stored attempts).
- [ ] **Step 4:** Carry it. In `AgentState`, five constructor calls gain one argument each, and nothing else changes.
- [ ] **Step 5: End to end, red-first as a pin** (container): on the queued door, a turn with one retried model call, one tool call and an answer stores five model-call events' worth of manifests as expected: each `InferenceAttempted` and each closing event has one, and each resolves through `engine.payloads()` to the request the recording provider received for that call. On the direct door, an inference that times out stores an `InferenceFailed` with no manifest.
- [ ] **Step 6:** Replay equals live is unchanged: the story-equivalence tests pass with no edit beyond constructor shape (the manifest is not in the narration). Docs, CHANGELOG (`### Added`; `### Breaking changes`: stored events changed shape, recreate the database). Gate. Commit: `feat: every model-call event records what its request was made of`.

---

## After the last task

The final whole-branch review (Opus) is given the spec, this plan and the branch diff, and is asked to:

- read the whole `engine/core/` diff line by line against "This plan touches the fold" and list every line that is not a carried field;
- confirm the pre-existing fold and dispatcher tests lost no assertion, and that the dispatcher's only change is the manifest on the accumulated `FailedAttempt`;
- confirm the request handed to a provider is unchanged by this plan (the request-asserting tests are untouched);
- confirm nothing in `nessy-api`, the narration, or an application-facing doc names the manifest;
- confirm an unchanged section stores nothing new on PostgreSQL, and that nothing in the engine remembers what it stored between calls;
- confirm the filtered version resource is in the built jar and reads correctly from it.
