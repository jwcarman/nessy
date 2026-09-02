# Nessy Model Gemini

A native `ModelProvider` on Google's own [java-genai](https://github.com/googleapis/java-genai)
SDK, talking to the Gemini Developer API via a plain API key — the same family as
`nessy-model-anthropic` and `nessy-model-openai`, built the same way.

```java
ModelProvider provider = GeminiModelProvider.create(c -> c.apiKey(apiKey));
```

or, for the common no-argument case:

```java
ModelProvider provider = GeminiModelProvider.fromEnv();
```

## Credentials

`GeminiModelProvider.create(GeminiProviderCustomizer)` hands the customizer a
`GeminiProviderConfig` with fluent setters:

- `.apiKey(String)` — explicit key, the usual path.
- `.fromEnv()` — reads `GEMINI_API_KEY`, then `GOOGLE_API_KEY` (Google's own documented pair, in
  that order) itself, rather than delegating to the SDK's own environment resolution. An explicit
  `.apiKey(...)` set alongside `.fromEnv()` still wins. Neither variable set fails fast when the
  provider is built, with an `IllegalStateException` naming both.
- `.client(Client)` — escape hatch: supply a fully preconfigured java-genai `Client` instead.
- `.baseUrl(String)` — for proxies, gateways, or Gemini-compatible endpoints.

## Mapping

- System prompt → `systemInstruction`; a blank prompt omits it entirely.
- `Context` messages → `Content`s with `user`/`model` roles.
- `ToolSpec` JSON schemas → `FunctionDeclaration.parametersJsonSchema`, copied as-is.
- Tool results → `functionResponse` parts, addressed back to the function they answer by name
  (looked up from the matching `tool_use` call id — `ToolResultBlock` itself carries no name).
- `maxTokens` → `maxOutputTokens`.
- Text deltas → `ModelEvent.TextChunk`; function calls → `ModelEvent.ToolUseEmitted`. The Gemini
  Developer API never streams a function call's arguments incrementally the way OpenAI's Chat
  Completions does — each `functionCall` part arrives already complete — so no id-keyed
  accumulation is needed; an id is minted deterministically (`gemini-call-<n>`) only when the SDK
  omits one.
- Tool-call continuity: a `functionCall` part's `thoughtSignature` — Gemini's opaque continuity
  token — is base64-encoded into `ToolUseEmitted.signature`/`ToolUseBlock.signature` when present.
  On replay, `GeminiRequests` decodes a stored signature back onto the rebuilt part's
  `thoughtSignature`; a block with no signature (a history that predates this capture, or one
  authored by another provider) gets Google's documented `skip_thought_signature_validator`
  sentinel instead, so the replay stays legal at the cost of degraded reasoning continuity for
  that one call. See `GeminiRequests.SKIP_THOUGHT_SIGNATURE_VALIDATOR`'s javadoc.
- `finishReason` has no dedicated "the model called a tool" value (a tool-calling turn still
  reports `STOP`), so the stream tracks whether any function call was seen and reports
  `StopReason.TOOL_USE` in that case regardless of the wire's own `finishReason`.
- Usage: `promptTokenCount`/`candidatesTokenCount`/`cachedContentTokenCount`, honestly zeroed via
  `Usage.zero()` for anything the SDK doesn't expose — never invented.

## Retries

Unlike `nessy-model-anthropic` and `nessy-model-openai`, this module ships no `RETRYABLE`
predicate for `RetryingModel.wrap` yet — it arrives once real Gemini failure modes have been
observed live, rather than guessed at from the SDK's exception hierarchy alone.

## Capabilities

v1 advertises `PARALLEL_TOOL_CALLS`: the request mapping already sends several `functionCall`
parts in one `model`-role `Content` and the stream mapping already emits each as its own
`ModelEvent.ToolUseEmitted`, so claiming it is honest, not aspirational. `THINKING` is deliberately
deferred — Gemini's `thought`-flagged parts are dropped rather than translated; wiring them up
needs both a thought-part mapping and a capabilities flag, banked for later. `PROMPT_CACHING` and
`IMAGE_INPUT` are equally unwired in this module's request/response mapping, so neither is
claimed. `IMAGE_INPUT` in particular: an `ImageBlock` in a user message fails loudly
(`IllegalArgumentException`) rather than being silently dropped.

## Dependency footprint

The java-genai SDK pulls a materially heavier transitive tree than its siblings: where
`nessy-model-anthropic`/`nessy-model-openai` each resolve to roughly 23–24 jars, this module
resolves to around 46 — Guava, Protobuf, gRPC's API surface, OpenCensus, Gson, OkHttp, and the
Kotlin stdlib all ride along underneath Google's auth/HTTP plumbing (`google-auth-library-*`,
`google-http-client`, `api-common`). This is a conscious trade, not an oversight: it's the cost of
depending on Google's own official SDK rather than hand-rolling a Gemini client, and no dependency
exclusions are currently applied — none of those transitives have shown a conflict with the rest
of the reactor. Revisit if that changes (a version clash, or if the weight becomes a real problem
for a consumer that wants Gemini without the rest of Google's client plumbing).

## Testing

Offline mapping tests (`GeminiRequestsTest`, `GeminiStreamTest`, `GeminiModelProviderTest`) build
every fixture from the java-genai SDK's own builders — real `GenerateContentResponse`, `Candidate`,
`Content`, `Part` objects — no mocking library. The one thing that resists offline construction is
the SDK's own `Client`/`Models` (both `final`, no interface) and `ResponseStream` (needs a real
`ApiResponse` plus a reflection-resolved converter method); `GeminiClient` is the thin
package-private seam that seam-tests fake directly instead.

`GeminiLiveTest` (`@Tag("live")`) mirrors the sibling providers' live suites: a real conversation
and a real tool-call round trip against the Gemini Developer API. **Live-validated 2026-08-15**
against `gemini-3.6-flash` with a real `GEMINI_API_KEY`: both live tests pass, including the
tool-call round trip that exercises `thoughtSignature` capture and replay for real. Rerun it
anytime: `GEMINI_API_KEY=... ./mvnw test -Dnessy.excludedGroups= -pl :nessy-model-gemini`.

Tool-call replay carries real `thoughtSignature` bytes captured from the stream, and
pre-existing histories that carry no signature degrade gracefully via Google's own sanctioned
skip-validation sentinel rather than failing the call outright — both paths are covered offline
(`GeminiStreamTest$ThoughtSignatures`, `GeminiRequestsTest$ToolCallSignatures`), and the signed
path is what the live tool round trip above validated. One residual unknown: replayed
function-call parts now carry a `thoughtSignature` field unconditionally, including against
`gemini-2.5-*` models that previously received none — the live run used a 3.6 model, so whether
2.5 models tolerate the added field remains unverified.
