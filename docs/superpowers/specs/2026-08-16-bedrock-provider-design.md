# Bedrock provider — the fourth native door

**Date:** 2026-08-16
**Status:** RATIFIED direction (owner: provider roadmap ruled Bedrock next, "then, I want
bedrock provider"); details follow the Gemini-generation precedent unless noted.
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`;
sibling to `2026-08-15-provider-expansion-design.md` (Gemini), whose module conventions
this spec inherits wholesale.

## 1. Why Bedrock, and which API

`nessy-model-bedrock` speaks Amazon Bedrock's **Converse/ConverseStream** API via the AWS
SDK for Java v2 (`software.amazon.awssdk:bedrockruntime`). ConverseStream is Bedrock's
unified, model-agnostic layer — tool use, system prompts, streaming deltas, usage — which
means one nessy provider covers Claude, Nova, Llama, Mistral and the rest of the Bedrock
catalog without per-model body formats. `InvokeModel*` (per-model JSON bodies) is
explicitly rejected: it re-fragments exactly what Converse unified.

## 2. The provider

- `BedrockModelProvider` (builder): `region(Region)`, `credentialsProvider(...)`,
  `client(...)` override for tests/custom wiring, and `fromEnv()` — which uses the AWS
  default credentials chain and `AWS_REGION`/`AWS_DEFAULT_REGION`, because ambient
  credentials are the AWS idiom (env, profile, SSO, IMDS). No `apiKey(...)` — Bedrock has
  no bearer-key mode worth teaching.
- `name()` override returns `"Bedrock"` (ModelProvider.name() convention).
- **Capabilities:** TOOLS and PARALLEL_TOOL_CALLS advertised. THINKING deferred (Claude
  reasoning-content blocks exist on Converse; unlocking them is the same future generation
  that unlocks Gemini's — the signature grammar is already in place if needed).
- **Client seam:** the SDK's async client and response-handler types get the
  GeminiClient-style seam — a small package-private interface the stream/request mapping
  talks to, faked directly in tests (no-mocking promise).

## 3. Mapping (BedrockRequests / BedrockStream)

- Request: nessy `Context` → Converse `Message`s (user/assistant), `ToolResultBlock`s →
  `toolResult` content blocks (Converse's user-message tool results — mixed user content
  is legal on this wire, map per block, Anthropic-style; the total-mappings lesson is a
  birth requirement here, not a retrofit), `ToolUseBlock` → `toolUse` blocks, system
  prompt → `system`, tools → `toolConfig` with JSON schemas via the existing victools
  path, maxTokens/temperature → `inferenceConfig`.
- Stream: ConverseStream visitor events → `ModelEvent` grammar — `contentBlockDelta` text
  → TextChunk; `contentBlockStart(toolUse)` + input deltas + `contentBlockStop` →
  ToolUseEmitted (arguments accumulated exactly like the OpenAI/Gemini streams);
  `messageStop.stopReason` → nessy stop reasons (end_turn/stop_sequence → complete,
  tool_use → tools, max_tokens → truncation-fatal per house rule, guardrail/content
  filtered → fatal with reason); `metadata.usage` → Usage (input/output; cache
  read/write tokens when present).
- ThinkingBlock/RedactedThinkingBlock in history: dropped at the mapping boundary
  (Converse `reasoningContent` replay stays out of scope with THINKING deferred);
  ToolUseBlock.signature is ignored (Bedrock Converse manages Claude signatures
  server-side at this layer).

## 4. Env + Spring wiring

- `EnvModelProviders`: `bedrock` joins the `NESSY_PROVIDER` vocabulary — **explicit
  selection only, never key-presence auto-detection.** AWS credentials are ambient on
  half the machines in the world; letting them win (or even enter) the which-key-is-set
  tiebreak would hijack every laptop with a stray profile. No `BEDROCK_*` pseudo-key
  invented. Default model constant: Bedrock's cross-region inference profile id for
  Claude Haiku 4.5 (exact string pinned at implementation from the AWS catalog,
  date-stamped javadoc, same convention as the Gemini default).
- Autoconfigure: `BedrockProviderAutoConfiguration` — class-presence + explicit
  `nessy.provider=bedrock` (no key condition, consistent with the above; the ambiguity
  condition's keyed-count logic is untouched because Bedrock never counts as "keyed").
  Invalid-provider message vocabulary grows to four names.

## 5. Testing + honesty

- Offline: request/stream mapping through the client seam fakes; schema-shape pinning for
  toolConfig; stop-reason and usage mapping tables (parameterized).
- `BedrockLiveTest` (`@Tag("live")`) mirroring the sibling suites: real conversation +
  tool round trip, runnable by the owner with AWS credentials
  (`./mvnw test -Dnessy.excludedGroups= -pl nessy-model-bedrock`).
- README + providers-guide section written offline-honest ("not yet live-validated"),
  flipped only after the owner's live run — the Gemini ritual exactly.

## 6. Out of scope (v1)

- THINKING / reasoningContent replay (paired future generation with Gemini's).
- Guardrails configuration, Bedrock Knowledge Bases, provisioned throughput knobs.
- Auto-detection of AWS credentials in EnvModelProviders (explicit ruling above).
- Azure OpenAI and Vertex — next on the ruled roadmap, separate generations.

## Amendment (2026-08-25): §4's mechanism changes, its rule survives

Explicit-only selection is now enforced by non-registration:
`nessy-model-bedrock` ships no `ModelProviderBootstrap`, so there is no code
path by which it enters discovery's candidate list. `NESSY_PROVIDER=bedrock`
retires; applications construct `BedrockModelProvider` directly. See
`2026-08-25-model-discovery-design.md` §6.
