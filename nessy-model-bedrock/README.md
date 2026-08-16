# Nessy Model Bedrock

A native `ModelProvider` on the AWS SDK for Java v2's `bedrockruntime` client, talking to Amazon
Bedrock's Converse/ConverseStream API — the same family as `nessy-model-anthropic`,
`nessy-model-openai`, and `nessy-model-gemini`, built the same way.

```java
ModelProvider provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build();
```

## Credentials

- `.region(Region)` — required unless `.client(...)` or `.fromEnv()` supplies one. Bedrock has no
  region default the way the API-key providers have no endpoint default.
- `.credentialsProvider(AwsCredentialsProvider)` — overrides the default AWS credentials chain.
- `.fromEnv()` — uses the AWS SDK's own default credentials provider chain (env vars, shared
  config/credentials files, container/instance metadata, …), but resolves the **region** itself by
  reading `AWS_REGION` then, if that is unset, `AWS_DEFAULT_REGION` — Amazon's own documented pair
  — rather than delegating to the SDK's own default region provider chain. An explicit
  `.region(...)` set alongside `.fromEnv()` still wins. Neither variable set fails fast at
  `.build()` with an `IllegalStateException` naming both.
- `.client(BedrockRuntimeAsyncClient)` — escape hatch: supply a fully preconfigured async SDK
  client instead of `region`/`credentialsProvider`.

Credentials themselves are **not** re-resolved by hand the way the Gemini/OpenAI API-key path is:
`.fromEnv()` (and the no-`.credentialsProvider(...)` default on the plain `.region(...)` path)
delegates entirely to `DefaultCredentialsProvider.create()`, trusting the AWS SDK's own resolution
of every credential source it understands. Only the region is read directly, so `.build()` fails
with a friendly, named-variable message instead of the SDK's own less-specific error.

## Mapping

- System prompt → a `SystemContentBlock` text block; a blank prompt omits `system` entirely.
- `Context` messages → `Message`s with `user`/`assistant` `ConversationRole`s, one Converse
  `ContentBlock` per wire-neutral block, mapped in place — mixed content (a `ToolResultBlock`
  alongside a `TextBlock` in one user turn) preserves order within a single message, the same
  precedent `GeminiRequests` sets for its own `functionResponse`/text mixing.
- `ToolSpec` JSON schemas → `ToolInputSchema.fromJson(Document)`; `ToolCall` arguments →
  `ToolUseBlock.input(Document)`. The AWS SDK has no built-in Jackson bridge for `Document`, so
  `BedrockRequests` converts Jackson `JsonNode` trees into `Document` trees recursively (numbers
  convert via `Document.fromNumber(String)` on the node's own text form, to avoid re-deriving
  Jackson's number-type dispatch).
- Tool results → `ToolResultBlock`s addressed by `toolUseId` directly — unlike Gemini's
  `functionResponse`, Bedrock's tool result carries no separate "which function" field, so (unlike
  `GeminiRequests`) no call-id-to-name lookup is needed. `isError` → `ToolResultStatus.ERROR` vs
  `SUCCESS`.
- `maxTokens` → `InferenceConfiguration.maxTokens`. `InferenceConfiguration` also has a
  `temperature` field, but `ModelRequest` carries none — no provider module in this harness sets
  one — so nothing is threaded onto it.
- `ThinkingBlock`/`RedactedThinkingBlock` are dropped outright on replay (this v1 module claims no
  `THINKING` capability), and `ToolUseBlock.signature()` is ignored on replay — Bedrock issues no
  per-call continuity token for this harness to thread through, unlike Gemini's `thoughtSignature`.
- `ImageBlock` has no mapping: this v1 provider claims no `IMAGE_INPUT` capability, so one fails
  loudly (`IllegalArgumentException`) rather than being silently dropped — the same contract
  `GeminiRequests` keeps.
- Text deltas → `ModelEvent.TextChunk`; `toolUse` content blocks → `ModelEvent.ToolUseEmitted`,
  emitted once the block's `content_block_stop` closes it. Bedrock streams a `toolUse` block's
  input as JSON string fragments across several `content_block_delta` events — the same
  fragmentary shape `AnthropicStream` accumulates — keyed by `contentBlockIndex` and parsed once
  the block closes. Every `ToolUseEmitted` uses the no-signature convenience constructor: Bedrock
  issues no per-call continuity token.
- `stopReason` mapping: `end_turn`/`stop_sequence` → `StopReason.END_TURN`; `tool_use` →
  `StopReason.TOOL_USE`; `max_tokens`/`model_context_window_exceeded` → `StopReason.MAX_TOKENS`
  (the same "ran out of room" reasoning `AnthropicStream` documents for its own
  `model_context_window_exceeded` mapping); `guardrail_intervened`/`content_filtered` →
  `StopReason.REFUSAL` (Bedrock's two flavors of "a safety mechanism stopped the model", mirroring
  how `AnthropicStream` maps `refusal` and `GeminiStream` maps `SAFETY`/`RECITATION`); anything
  else (`malformed_model_output`, `malformed_tool_use`, or a wire value newer than this SDK build
  knows) fails loudly, naming the unrecognized reason.
- Usage: `TokenUsage.inputTokens`/`outputTokens`/`cacheReadInputTokens`, honestly zeroed via
  `Usage.zero()` for a stream that never carries a `metadata` event — never invented.

## The async-to-blocking bridge

Unlike the anthropic-java, openai-java, and java-genai SDKs — each of which offers some
synchronous, pull-shaped streaming entry point — the AWS SDK for Java v2 streams `ConverseStream`
only through `BedrockRuntimeAsyncClient`, whose `converseStream(request, responseHandler)` is
push-based: the SDK invokes the response handler's visitor callbacks on its own threads and
completes a `CompletableFuture<Void>` when the stream ends. `ModelStream`, like every other
provider module here, is a blocking `Iterable`. `BedrockModelProvider.Builder.wrap` bridges the
two: a visitor pushes every raw `ConverseStreamOutput` (plus a completion or failure sentinel) onto
a `BlockingQueue`, and a small `Iterator` pulls from that same queue on the caller's thread. This
bridge is production-only glue — `BedrockClient` is the seam that isolates it: `BedrockStream`
itself is constructed from a plain `Iterable<ConverseStreamOutput>` and a close callback, exactly
the shape `GeminiStream` takes, so the pure mapping tests never need the bridge, the async client,
or any mocking library — only the AWS SDK's own builder-constructed fixtures. The bridge itself
*is* covered offline, through the public `.client(BedrockRuntimeAsyncClient)` escape hatch and a
hand-rolled `ScriptedBedrockRuntimeAsyncClient` fake (see Testing below) — not a mock, and no live
credentials needed.

The queue is **deliberately unbounded**: the producer side runs on the SDK's own Netty event-loop
thread, and blocking that thread on a full queue would stall every other request multiplexed over
the same loop — far worse than letting one turn's events buffer in memory. `maxTokens` bounds how
much one turn can produce, so the buffer stays bounded in practice.

The bridge also **primes the pump**: `wrap` blocks for the stream's first queue item —
translated event, completion, or failure — before `stream()` returns, so a failure on the very
first call (a 429 throttle, an expired credential) throws from `stream()` itself. This is what
lets `RetryingModelProvider` retry a Bedrock opening failure exactly the way it retries the
identical failure on every synchronous-SDK sibling provider — `RetryingModelProvider` only ever
retries the call that opens a stream, not a mid-stream failure.

`BedrockModelProvider` is also `AutoCloseable`: the real `BedrockClient` owns a
`BedrockRuntimeAsyncClient`, whose Netty transport holds resources that outlive one `stream()`
call. Closing the provider closes that client — but only when the provider built it itself, via
`region(...)`/`credentialsProvider(...)`/`fromEnv()`. A client passed to `.client(...)` is the
caller's own: the provider never closes it, since it never opened it either — close that client
yourself, on whatever lifecycle you built it against.

A stream failure's `CompletionException` wrapper (the SDK's own future-chaining artifact) is
unwrapped before it reaches the harness: the underlying cause — an `SdkServiceException` for a
throttle, a guardrail block, an auth failure, … — is rethrown directly when it is already a
`RuntimeException`, so the reason recorded in the durable transcript names the provider's own
diagnosis instead of a generic wrapper every Bedrock failure would otherwise collapse into.

## Capabilities

v1 advertises `PARALLEL_TOOL_CALLS`: Bedrock's Converse API already streams several `toolUse`
content blocks in one assistant turn (each on its own `contentBlockIndex`), and
`BedrockRequests`/`BedrockStream` already handle that shape in both directions, so claiming it is
honest, not aspirational. There is no dedicated `TOOLS` entry in `Capability` — the enum only
tracks capabilities a provider might lack, and every provider module in this harness already
handles tool calls unconditionally, so nothing further is claimed for plain (non-parallel) tool use
beyond what `PARALLEL_TOOL_CALLS` alone already communicates. `THINKING`, `PROMPT_CACHING`, and
`IMAGE_INPUT` are deliberately
absent: none is wired into this module's request/response mapping, so none is claimed.

## Dependency footprint

`bedrockruntime` pulls the AWS SDK for Java v2's own plumbing: `sdk-core`, `regions`, `auth`,
`netty-nio-client` (the async transport `BedrockRuntimeAsyncClient` needs for `converseStream`),
and their own transitives. Managed via the AWS SDK's own BOM (`software.amazon.awssdk:bom`,
imported in the root `pom.xml`'s `dependencyManagement`, the same pattern already used for
`micrometer-bom` and `testcontainers-bom`) rather than a single pinned artifact version, since
`bedrockruntime` and its siblings must resolve to one consistent line.

## Testing

Offline mapping tests (`BedrockRequestsTest`, `BedrockStreamTest`, `BedrockModelProviderTest`)
build every fixture from the AWS SDK's own builders — real `MessageStartEvent`,
`ContentBlockStartEvent`, `ContentBlockDeltaEvent`, `ContentBlockStopEvent`, `MessageStopEvent`,
`ConverseStreamMetadataEvent` objects, each of which implements `ConverseStreamOutput` directly and
is fully offline-constructible — no mocking library. `BedrockClient` is the thin package-private
seam the pure mapping tests fake directly.

`BedrockModelProviderTest$Bridge` pins the async-to-blocking bridge itself, offline: a hand-rolled
`ScriptedBedrockRuntimeAsyncClient` (not a mock — a real `BedrockRuntimeAsyncClient` implementation
driven through the SDK's own `SdkPublisher.fromIterable`) is passed through the public
`.client(...)` escape hatch, covering the ordering guarantee (events before the terminal signal),
mid-stream failure, missing-`messageStop` completion, `close()`-cancels-the-future, and a
before-any-event failure throwing from `stream()` itself rather than from iteration.

`BedrockLiveTest` (`@Tag("live")`) mirrors the sibling providers' live suites: a real conversation
and a real tool-call round trip against Amazon Bedrock. **Live-validated 2026-08-16** — both live
tests passed against real Bedrock with real AWS credentials, including the tool round trip through
the ConverseStream bridge, on the default model id
(`us.anthropic.claude-haiku-4-5-20251001-v1:0`, the `us` cross-region inference profile for Claude
Haiku 4.5). Rerun anytime:

```sh
AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
  ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-bedrock
```
