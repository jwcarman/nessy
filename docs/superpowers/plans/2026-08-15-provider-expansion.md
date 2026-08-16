# Provider Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-model-gemini` on Google's GA Java SDK; `XAI_API_KEY`/`GEMINI_API_KEY` branches in EnvModelProviders; autoconfigure/starter/BOM parity; the OpenAI-compatible-universe documentation.

**Architecture:** Mirror the existing provider-module family exactly; no kernel change anywhere.

**Spec:** `docs/superpowers/specs/2026-08-15-provider-expansion-design.md` (binding; §2's mapping rules and honesty rule especially). Every task reads it first.

## Global Constraints

- House rules: no suppressions, no star imports, no mocking libraries (SDK types that resist hand-rolled fakes get a package-private seam interface instead), prose snake_case tests, S5778/S5841, constant strings, javadoc on all public members with fully-qualified {@link} for non-imported targets, reactor `./mvnw -q javadoc:javadoc` green per task.
- Offline `./mvnw -q clean verify` green at every boundary — the new module's default test suite must run with no key and no network; live calls live behind `@Tag("live")`.
- The seam-integrity rule: providers never read env vars except in their own `fromEnv()`; builders take explicit values.
- Formatters before commit; trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never push (controller pushes).

---

### Task 1: The nessy-model-gemini module

**Files:** Create the module wholesale (pom, `GeminiModelProvider` + builder, mapping layer, `package-info`, README, offline mapping tests, `@Tag("live")` validation suite); Modify root `pom.xml` (module + version property `google-genai.version`), `nessy-bom/pom.xml`.

- [ ] **Step 1:** Read `nessy-model-openai` COMPLETELY (module layout, builder/fromEnv shape, the ModelEvent mapping and tool-call id handling, live-test structure, README voice) and skim `nessy-model-anthropic` for the same. Fetch and read https://github.com/googleapis/java-genai README + streaming/function-calling docs — the mapping is written from the SDK's documented API, never memory. Record the SDK version you pin and why in your report.
- [ ] **Step 2:** Implement per spec §2: builder (apiKey/baseUrl seams), `fromEnv()` (`GEMINI_API_KEY` then `GOOGLE_API_KEY`, error message naming both), request mapping (system instruction, role mapping, functionDeclarations from ToolSpec schemas, maxTokens, functionResponse for tool results), streaming response mapping (TextChunk deltas; tool-use family with deterministic id minting if the SDK omits ids; terminal event with honest Usage — zeros for anything the SDK doesn't expose, never invented), capabilities = text+tools+usage with the thinking-deferred javadoc note.
- [ ] **Step 3:** Offline mapping tests (fakes or the seam interface); live suite mirroring the siblings'. If you cannot execute the live suite (no key in env), the README's Testing section and your report MUST say the live path is unvalidated — spec §2's honesty rule.
- [ ] **Step 4:** Offline verify + reactor javadoc green. Commit `feat: nessy-model-gemini — the third native provider, on Google's own SDK`.

### Task 2: Env keys, autoconfigure, starter

**Files:** Modify `nessy-model-env` (pom + `EnvModelProviders` + tests), `nessy-autoconfigure` (new `GeminiProviderAutoConfiguration`, `NessyProperties` gemini block, the provider-selection conditions extended to three, imports file, tests), `nessy-spring-boot-starter/pom.xml` if it names provider modules, `nessy-bom` if not already done in T1.

- [ ] **Step 1:** Read EnvModelProviders + its test suite COMPLETELY; extend per spec §3: GEMINI_API_KEY/GOOGLE_API_KEY → Gemini; XAI_API_KEY → `OpenAiModelProvider.builder().baseUrl("https://api.x.ai/v1").apiKey(...)`; NESSY_PROVIDER grows `gemini` + `xai` (alias `grok`); every new ambiguity/tiebreak case gets a test in the existing ListAppender style; javadoc precedence table updated.
- [ ] **Step 2:** Autoconfigure per spec §4 — mirror the Anthropic autoconfiguration file-for-file (conditions, properties, bean gate); extend the choice/ambiguity conditions honestly to three providers; register in the imports file; tests mirroring the existing provider-autoconfig cases (present key → bean; two keys → ambiguity behavior; user Harness bean suppresses; nessy.provider=gemini selects).
- [ ] **Step 3:** Offline verify + reactor javadoc green. Commit `feat: gemini and grok join the environment — two keys, zero ceremony`.

### Task 3: The docs

**Files:** Modify `docs/guides/providers.md` (Gemini section + "The OpenAI-compatible universe" per spec §5 — Grok/OpenRouter/Gemini-compat/Ollama/LM Studio snippets with the honest compatibility-promise note), `docs/reference/configuration.md` (nessy.gemini.* rows; bean count if it names providers), anything the count sweep touches (grep "two native providers"/"Anthropic and OpenAI" phrasings across docs/ and README).

- [ ] **Step 1:** Write per docs-writer conventions (truth: grep every builder call against the new module; voice; Where-next; strict build).
- [ ] **Step 2:** `python3 -m mkdocs build --strict` green. Commit `docs: three native providers, and the universe that speaks OpenAI`.

## Self-review notes
- T1 produces `GeminiModelProvider.builder()/.fromEnv()` exactly as T2 consumes; T2's autoconfigure property names match T3's reference rows.
- Live-validation honesty is a stated deliverable, not a footnote — both T1's README and the final ledger carry it.
- No placeholders; mapping specifics deferred to the SDK's own docs by explicit instruction (fetch-and-read step), which is the honest form for a third-party API.
