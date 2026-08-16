# Bedrock Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-model-bedrock` — the fourth native provider, speaking Amazon Bedrock's
Converse/ConverseStream API through the AWS SDK for Java v2, plus env/Spring wiring and
offline-honest docs.

**Architecture:** Mirror the Gemini generation's module anatomy exactly: a builder-based
`BedrockModelProvider` with a package-private client seam faked in tests, a
`BedrockRequests` mapper (Context → Converse request), a `BedrockStream` mapper
(ConverseStream events → ModelEvent grammar), a `@Tag("live")` suite the owner runs with
AWS credentials, and README/providers-guide sections that never claim what offline tests
can't prove. Explicit-only selection in nessy-model-env and autoconfigure (no
key-presence auto-detect — AWS credentials are ambient).

**Tech Stack:** Java 21, `software.amazon.awssdk:bedrockruntime` (BOM-managed version at
the newest stable), JUnit 5 + AssertJ, victools schemas via the existing path.

**Spec:** `docs/superpowers/specs/2026-08-16-bedrock-provider-design.md`

## Global Constraints

- `./mvnw -q clean verify` green with no AWS credentials and no network, always.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`.
- No mocking libraries; prose test names; no star imports; no suppressions; S5778/S5976/S107.
- Mixed-content USER messages (ToolResultBlock + TextBlock together) are legal grammar —
  the mapping must translate them per block from birth (the total-mappings lesson).
- ThinkingBlock/RedactedThinkingBlock dropped at the boundary; ToolUseBlock.signature
  ignored (THINKING deferred, spec §3).
- Docs truth: offline-validated wording only; live status flips only after the owner's run.
- Run builds in the FOREGROUND.

---

### Task 1: The module — provider, mappers, seam, offline tests

**Files:**
- Create: `nessy-model-bedrock/pom.xml` (mirror nessy-model-gemini's shape; register in
  root pom modules + BOM + dependencyManagement for the AWS SDK artifact)
- Create: `nessy-model-bedrock/src/main/java/org/jwcarman/nessy/model/bedrock/{BedrockModelProvider,BedrockRequests,BedrockStream,BedrockClient}.java`
  (+ package-info; BedrockClient is the package-private seam over the SDK client +
  stream response handling, shaped by what the SDK actually exposes — GeminiClient is
  the pattern)
- Create: mirrored test classes + seam fakes; `BedrockLiveTest` (`@Tag("live")`)

**Interfaces (Produces):**
- `BedrockModelProvider.builder()`: `region(...)`, `credentialsProvider(...)`,
  `client(...)` override, `fromEnv()` (default credentials chain +
  AWS_REGION/AWS_DEFAULT_REGION; missing region → IllegalStateException naming both vars),
  `build()`. `name()` returns `"Bedrock"`. Capabilities: TOOLS + PARALLEL_TOOL_CALLS.
- Consumed by Task 2 as an optional dependency the same way gemini is.

**Steps:**
- [ ] Study nessy-model-gemini end to end first (pom, provider, mappers, seam, tests) —
  it is the template; deviations need a reported reason.
- [ ] Request mapping: system prompt, user/assistant messages (per-block: text, toolUse
  with victools-schema'd toolConfig, toolResult — mixed user content mapped per block),
  inferenceConfig (maxTokens, temperature), thinking blocks dropped, signature ignored.
- [ ] Stream mapping: text deltas → TextChunk; toolUse start/input-delta/stop →
  ToolUseEmitted (argument accumulation per the OpenAI/Gemini precedent); stopReason
  mapping (end_turn/stop_sequence → complete; tool_use → tools; max_tokens → the house
  truncation-fatal rule; guardrail/content-filtered → fatal with reason); usage metadata
  → Usage (input/output + cache tokens when the SDK exposes them).
- [ ] Offline tests through the seam fakes: request shapes (incl. the mixed-content
  message), schema-shape pinning for toolConfig, stop-reason table (parameterized),
  usage mapping, stream accumulation.
- [ ] `BedrockLiveTest` mirroring GeminiLiveTest: real conversation + tool round trip;
  model id from Task 2's default constant (cross-region inference profile for Claude
  Haiku 4.5 — pin the exact string from the AWS catalog, date-stamp the javadoc, flag in
  the report that it is live-unverified).
- [ ] README (offline-honest: not yet live-validated; the live-run command; dependency
  footprint note like gemini's).
- [ ] Full verify; license + spotless; commit.

### Task 2: Env selection + Spring autoconfigure + docs

**Files:**
- Modify: `nessy-model-env/pom.xml` + `EnvModelProviders.java` + its test + README
- Create: `nessy-autoconfigure/src/main/java/org/jwcarman/nessy/autoconfigure/BedrockProviderAutoConfiguration.java` (+ imports registration file entry)
- Modify: autoconfigure's invalid-provider message + tests
- Modify: `docs/guides/providers.md` (Bedrock section), `docs/reference/configuration.md`
  if provider vocabulary is listed there
- Test: mirrored env/autoconfigure tests

**Interfaces (Consumes):** Task 1's provider builder.

**Steps:**
- [ ] EnvModelProviders: `bedrock` joins NESSY_PROVIDER vocabulary — EXPLICIT ONLY. No
  key-presence detection, never a participant in the which-key tiebreak or the ambiguity
  count (spec §4 rationale: ambient AWS credentials must not hijack selection).
  `BEDROCK_DEFAULT_MODEL` constant = the pinned inference-profile id. select()/fromEnv
  throw paths updated; Selection carries providerName "bedrock".
- [ ] Autoconfigure: class-presence + explicit `nessy.provider=bedrock` condition; the
  keyed-ambiguity condition untouched (bedrock never counts as keyed); invalid-provider
  message now names four providers. Tests mirror the gemini autoconfigure suite.
- [ ] Docs: providers guide Bedrock section (builder, fromEnv, region/credentials idiom,
  explicit-selection rationale stated plainly, offline-honest validation note);
  README-env updates; no live claims.
- [ ] Full verify; license + spotless; commit.
