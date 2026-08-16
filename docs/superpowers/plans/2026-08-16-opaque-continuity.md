# Opaque Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `ToolUseBlock` the optional provider-issued `signature` the grammar already
gives `ThinkingBlock`, carry it stream→fold→store→replay, and have Gemini close the loop
(real signatures when present, Google's documented skip sentinel when absent).

**Architecture:** One nullable `String signature` component added to `ToolUseBlock` and to
`ModelEvent.ToolUseEmitted`, each with a convenience constructor preserving every existing
call site. The fold (`ProviderModelCallExecutor`) carries event→block. StateCodec picks the
component up via Jackson automatically — the task there is proof, not production code.
Gemini's stream captures `thoughtSignature` (base64), its request builder replays it or
stamps the sentinel.

**Tech Stack:** Java 21 records, Jackson, google-genai 1.66.0, JUnit 5 + AssertJ (no
mocking libraries, prose test names).

**Spec:** `docs/superpowers/specs/2026-08-16-opaque-continuity-design.md`

## Global Constraints

- `./mvnw -q clean verify` green with no API key and no network, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking libraries; prose test method names (house style).
- No star imports; no warning suppressions.
- Exception-assertion lambdas contain exactly ONE throwing invocation (S5778).
- Field name is `signature` everywhere — never `thoughtSignature` outside the Gemini
  mapping boundary; javadoc stays vendor-neutral in core.
- Bundle S107/S5976 risks at add time: parameterize same-shape tests.

---

### Task 1: The grammar slot — block, event, fold, testing seam

**Files:**
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/message/ToolUseBlock.java`
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/model/ModelEvent.java` (line 54)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/ProviderModelCallExecutor.java` (line 122)
- Modify: `nessy-core/src/main/java/org/jwcarman/nessy/api/message/Context.java` (line 378 deconstruction)
- Modify: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/ScriptedModelProvider.java`
- Tests: existing ToolUseBlock/executor/scripted test classes in the same packages

**Interfaces (Produces):**
- `ToolUseBlock(ToolCall call, String signature)` — canonical; `signature` nullable, no
  requireNonNull on it; `ToolUseBlock(ToolCall call)` convenience delegates with `null`.
- `ModelEvent.ToolUseEmitted(ToolCall call, String signature)` — same shape, same
  convenience overload `ToolUseEmitted(ToolCall call)`.
- `ScriptedModelProvider.Builder.toolUseSigned(String id, String name, ObjectNode arguments, String signature)`
  beside the existing `toolUse(...)` (line 128), mirroring `thinkingSigned` (line 118).

**Steps:**
- [ ] Add the `signature` component to `ToolUseBlock` with the vendor-neutral javadoc from
  spec §2 (opaque provider-issued continuity token, echoed verbatim on replay, absent when
  the provider issues none) AND the equality rationale (constructed once at stream time,
  replays reuse the stored value, so signature-in-equals cannot break no-stutter dedup).
  Keep `Objects.requireNonNull(call, ...)` only.
- [ ] Update every deconstruction/construction site the compiler flags — at minimum
  `Context.java:378` (pattern gains the second binding, which that code ignores) and
  `ProviderModelCallExecutor.java:122`.
- [ ] Add `signature` to `ToolUseEmitted` the same way; fold carries
  `new ToolUseBlock(event.call(), event.signature())` in the executor.
- [ ] Anthropic/OpenAI/Gemini streams keep compiling via the convenience overload —
  change nothing in those modules in this task.
- [ ] Add `toolUseSigned` to ScriptedModelProvider emitting a signed `ToolUseEmitted`.
- [ ] Tests (prose names, parameterized where same-shape): convenience ctor yields null
  signature; canonical ctor round-trips it; two blocks differing only in signature are
  not equal (name the test for the replay-identity rationale); fold pass-through — a
  scripted signed tool-use lands in the folded assistant message with its signature.
- [ ] `./mvnw -q clean verify`, license + spotless, commit.

### Task 2: Persistence proof — StateCodec and transcript round-trips

**Files:**
- Modify (tests only, expected): `nessy-jdbc/src/test/java/.../StateCodecTest.java` and the
  transcript round-trip test class; production `StateCodec.java` only if the round-trip
  proves Jackson needs help (it should not — records serialize components automatically,
  and absent JSON properties deserialize to null).

**Interfaces (Consumes):** Task 1's `ToolUseBlock(ToolCall, String)`.

**Steps:**
- [ ] Round-trip a state/message containing a SIGNED ToolUseBlock through StateCodec —
  signature survives byte-for-byte.
- [ ] Round-trip an UNSIGNED one — stays null, no property invented.
- [ ] Backward compatibility pinned: deserialize a JSON literal of the OLD shape (tool_use
  with no `signature` property, written as a string constant in the test) → block with
  null signature. This is the no-migration guarantee from spec §4.
- [ ] Same three cases through the transcript message path if it has its own codec test
  class; skip if it provably shares StateCodec's serializer (say which in the report).
- [ ] `./mvnw -q clean verify`, license + spotless, commit.

### Task 3: Gemini closes the loop — capture, replay, sentinel, docs

**Files:**
- Modify: `nessy-model-gemini/src/main/java/org/jwcarman/nessy/model/gemini/GeminiStream.java`
- Modify: `nessy-model-gemini/src/main/java/org/jwcarman/nessy/model/gemini/GeminiRequests.java` (lines ~135, ~204)
- Modify: `nessy-model-gemini/src/test/java/.../GeminiStreamTest.java`, `GeminiRequestsTest.java`
- Modify: `nessy-model-gemini/README.md`; docs site providers guide Gemini section

**Interfaces (Consumes):** Task 1's signed `ToolUseEmitted`; SDK `Part.thoughtSignature()`
(returns `Optional<byte[]>`-style accessor) and `Part.Builder.thoughtSignature(byte[])`.

**Steps:**
- [ ] Capture: where GeminiStream builds `ToolUseEmitted` from a functionCall part, read
  the part's thoughtSignature; when present, `Base64.getEncoder().encodeToString(bytes)`
  into the event; when absent, use the convenience overload.
- [ ] Replay: where GeminiRequests rebuilds functionCall parts from `ToolUseBlock`, set
  `thoughtSignature(Base64.getDecoder().decode(signature))` when signature non-null;
  when null, set the sentinel bytes
  `"skip_thought_signature_validator".getBytes(StandardCharsets.UTF_8)`. Sentinel is a
  named constant; its javadoc cites
  https://ai.google.dev/gemini-api/docs/thought-signatures and names the tradeoff
  (validation skipped = degraded reasoning continuity for that call only).
- [ ] Offline tests via the existing GeminiClient seam fakes: signed part → signed event;
  signed block → part carrying the same bytes; unsigned block → part carrying sentinel
  bytes. Parameterize same-shape cases (S5976).
- [ ] README + providers guide: replace the tool-calls-fail-on-3.x warning with the spec
  §6 story (real signatures; pre-existing histories degrade via Google's sanctioned
  sentinel). Do NOT claim live validation — that status changes only after the owner's
  key reruns the live suite.
- [ ] `./mvnw -q clean verify`, license + spotless, commit.
