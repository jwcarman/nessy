# Provider Expansion — Gemini native, and the OpenAI-compatible universe

**Date:** 2026-08-15
**Status:** APPROVED in conversation (owner: "nessy-model-gemini needs to get built. We
should provide documentation in the openai provider's docs about how you can actually use
that for a bunch of stuff (openrouter, grok, etc)").

## 1. Two deliverables, one theme

More models, honestly. A **native Gemini provider** on Google's GA Java SDK, and a
**documented base-url story** for the OpenAI provider: xAI's Grok, OpenRouter, Gemini's own
compatibility endpoint, and local runtimes (Ollama, LM Studio) all speak the OpenAI wire
protocol — `OpenAiModelProvider` + a base URL + a key IS the integration, and nobody knows
because we never wrote it down.

Research grounding (verified 2026-08-15): xAI ships **no official Java SDK** — its API is
deliberately OpenAI-compatible at `https://api.x.ai/v1`. Google's **Gen AI Java SDK**
(`com.google.genai:google-genai`) is GA, actively maintained, supports the Gemini Developer
API via plain API key — the module-worthy dependency.

## 2. nessy-model-gemini

Sibling of `nessy-model-anthropic`/`nessy-model-openai` — mirror their module layout,
builder shape, javadoc voice, and test posture. Read both COMPLETELY before writing.

- Dependency: `com.google.genai:google-genai` (pin the current release at build time;
  version property in the root pom beside the other SDK pins).
- `GeminiModelProvider implements ModelProvider`: `builder()` with `.apiKey(String)` and
  `.baseUrl(String)` seams (never the SDK's own env reading — the seam-integrity rule the
  env module established), plus `fromEnv()` reading `GEMINI_API_KEY` then `GOOGLE_API_KEY`
  (Google's documented pair, in that order).
- `stream(ModelRequest)` maps to the SDK's streaming generate-content call:
  - request: system prompt → systemInstruction; Context messages → contents with roles
    (user/model); tools → functionDeclarations from each `ToolSpec`'s JSON schema; maxTokens
    → generation config; tool results → functionResponse parts.
  - response events: text deltas → `ModelEvent.TextChunk`; function calls →
    the tool-use event family exactly as the OpenAI provider emits them (read its mapping —
    ids may need minting if Gemini omits call ids; mint deterministically per stream index);
    finish + usage metadata → the terminal event with `Usage` (prompt/candidates token
    counts; cached counts if the SDK exposes them, else zero — never invent).
- Capabilities: text + tools + usage in v1. Gemini thinking/reasoning output is deferred
  (banked; requires the thought-part mapping and a capabilities flag) — the class javadoc
  says so plainly.
- Tests: offline unit tests for the request/response MAPPING layer (hand-rolled fakes of the
  SDK's response types where feasible; if the SDK's types resist construction, map through a
  thin package-private seam interface the tests can fake — no mocking libraries, ever), plus
  `@Tag("live")` validation tests mirroring the other providers' live suites (real key, real
  call, streaming + tool round-trip). HONESTY RULE: if the live tests have not been executed
  against a real key before merge, the module README and the ledger say so in so many words.
- Implementer MUST fetch and read the SDK's official README/docs
  (https://github.com/googleapis/java-genai) before writing the mapping — no API-from-memory.

## 3. EnvModelProviders learns two keys

- `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) → `GeminiModelProvider` (nessy-model-env grows the
  optional dependency the same way it depends on the other two provider modules).
- `XAI_API_KEY` → `OpenAiModelProvider` with `baseUrl("https://api.x.ai/v1")` — Grok as a
  first-class env citizen with zero new provider code.
- `NESSY_PROVIDER` tiebreak vocabulary grows: `gemini`, `xai` (alias `grok`). Precedence and
  WARN semantics unchanged (explicit-and-recognized silent; unset/ambiguous → existing
  behavior; document the new multi-key ambiguity cases in the class javadoc and tests).

## 4. Autoconfigure + starter parity

`nessy.gemini.api-key` / `nessy.gemini.base-url` properties; a
`GeminiProviderAutoConfiguration` mirroring the Anthropic/OpenAI ones EXACTLY (same
`@ConditionalOnMissingBean({ModelProvider.class, Harness.class})` gate, same
provider-selection ambiguity conditions extended to three providers — read
`AnthropicIsTheChoiceCondition`/`AmbiguousProviderCondition` and extend their logic
honestly), BOM entry, starter passthrough. The configuration reference page gains the rows.

## 5. Docs

- `docs/guides/providers.md` gains: a Gemini section (native module, env keys, model-name
  examples) and **"The OpenAI-compatible universe"** — a section documenting
  `OpenAiModelProvider.builder().baseUrl(...)` as the integration for Grok
  (`https://api.x.ai/v1`), OpenRouter (`https://openrouter.ai/api/v1`), Gemini's own
  OpenAI-compat endpoint, and local runtimes (Ollama `http://localhost:11434/v1`, LM
  Studio) — each with a two-line wiring snippet and an honest note that nessy validates
  against OpenAI proper; compatible endpoints are the vendor's compatibility promise, not
  ours.
- README capabilities table row stays as-is (providers row already links the guide).
- `nessy-model-gemini/README.md` in the family's README voice.

## 6. Out of scope

- Gemini thinking-part mapping and Vertex AI auth (API-key Developer API only in v1).
- A Grok-specific module (the base-url story IS the Grok story).
- Anthropic-compatible endpoints for third parties (same idea, different day).
