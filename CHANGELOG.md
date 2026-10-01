# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`JsonSchemaGenerator.generate(Type)`**, which keeps type arguments:
  `List<Item>` is described with typed items. The `Class` form stays.
- **Spring Boot: strict tools by default on the measured chat-wire presets.**
  `xai`, `groq`, `mistral`, `openrouter`, `nvidia` and `cerebras` now send
  strict tools by default, overridable with
  `nessy.providers.<id>.properties.openai.tools.strict: false`.
- **`OpenAiResponsesInferenceProvider`**, an adapter for OpenAI's Responses
  API, in `nessy-inference-openai` beside the Chat Completions one. It is
  stateless (`store: false`, the whole context on every call), keeps each
  encrypted reasoning item as a `Block.Provider` block, and sends function
  tools in strict mode, falling back per tool with a warning when a schema
  cannot be expressed strictly. Reasoning models such as GPT-6 call tools
  through it.
- **Spring Boot: a `cerebras` preset**, lit by `cerebras.api-key`, at
  `https://api.cerebras.ai/v1` on the `openai-chat` wire, sending strict tools
  by default (`openai.tools.strict=true`).
- **Spring Boot: the `openai-responses` wire.** A custom provider, or the
  `openai` preset with `nessy.providers.openai.wire: openai-responses`,
  builds the Responses adapter.
- **Vendor properties.** `InferenceConfig.property(name, value)` sets a
  vendor-prefixed setting on an agent type (`openai.reasoning.effort`,
  `anthropic.thinking.budget_tokens`,
  `gemini.generationConfig.thinkingConfig.thinkingBudget`,
  `bedrock.inferenceConfig.temperature`); each adapter supports the names it
  lists, parsed into the SDK's typed fields with a bad value refused when the
  harness is built. Any other name under the adapter's own prefix is ignored
  and logged at `WARN`, once, naming the property and the supported names;
  another adapter's prefix is logged at `DEBUG`. Every provider config and
  `EmbedderConfig` take properties too, though no embedding adapter supports
  one yet.
- **Typed vendor properties.** `VendorProperty<T>` in `nessy-api` declares a
  property's name and type once (`ofInteger`, `ofBoolean`, `ofFloat`,
  `ofEnum`), and `property(VendorProperty<T>, T)` on
  `InferenceConfig`, `EmbedderConfig` and each provider config sets it in
  code, stored as the same text the by-name form carries. Each inference
  adapter publishes its constants (`OpenAiProperties`, `AnthropicProperties`, `GeminiProperties`,
  `BedrockProperties`), and every fixed value set is an enum:
  `OpenAiReasoningEffort`, `OpenAiReasoningSummary`, `OpenAiServiceTier`,
  `AnthropicThinkingType`, `AnthropicCacheTtl`, `AnthropicServiceTier` and
  `GeminiThinkingLevel`. A value outside an enum fails at build listing the
  spellings; `openai.service_tier=ultrafast` is carried by the Responses
  wire only, and the chat wire refuses it at build. Boot binding is
  unchanged: YAML values are still text.
- **`InferenceProvider.validate(InferenceOptions)`** and
  **`EmbeddingProvider.validate(EmbeddingOptions)`**, default no-ops, which
  the factories call when a harness or embedder is built.
- **`VendorProperties`** in `nessy-api`, which adapters read properties
  through. The BOM now also lists `nessy-inference-spi` and
  `nessy-embedding-spi`.
- **`openai.tools.strict`** on the chat adapter: function tools go out in
  strict mode over a rewritten schema.
- **Spring Boot: `nessy.providers.<id>.properties.*`.**
- **Named embedders.** `DefaultEmbedderFactory.of(...)` over
  `EmbedderFactoryConfig`: `provider(ProviderId, EmbeddingProvider)`
  registers an embedding provider by name, `embedding(ProviderId,
  EmbeddingOptions)` sets the default, `observations(...)` gives every
  embedder it mints its spans. `EmbedderConfig.provider(...)` names the
  provider a store wants; an unknown or missing one fails when the embedder
  is made, listing what is registered.
- **Spring Boot: `nessy.embedders.<id>`.** Presets `openai`, `gemini` and
  `voyage` light from a vendor key when the vendor's embedding module is on
  the classpath; custom embedders state `wire` (`openai`, `gemini`,
  `voyage`), `base-url` and `api-key`; application `EmbeddingProvider`
  beans join under their bean names; `nessy.embedder` +
  `nessy.embedding-model` (+ optional `nessy.embedding-dimension`) name the
  default; `EmbeddingReport` logs what lit. `VOYAGE_API_KEY` lights the
  `voyage` preset.
- **`OpenAiEmbedderConfig.vendor(String)`**, for a custom `openai`-wire
  embedder that is somebody else.

- **`Dimension`**, in `org.jwcarman.nessy.api.embedding`: how many
  coordinates a vector has, at least 1. `EmbedderConfig.dimension(Dimension)`
  takes it; `dimension(int)` remains as the convenience.

### Changed

- **The `openai` preset sends strict function tools**
  (`openai.tools.strict=true` by default). Set
  `nessy.providers.openai.properties.openai.tools.strict: "false"` to turn
  it off, for instance when `openai.base-url` points at a server that
  rejects strict mode.
- Anthropic `enabled` thinking with no budget sends 1024 tokens instead of
  refusing at build; the budget must still be below `maxTokens`.

### Fixed

- **Anthropic: a changed prefix no longer rejects a request that replays
  thinking.** With thinking on, the adapter now sends
  `thinking.block_binding.prefix_mismatch_behavior: drop_block` and the
  `thinking-binding-controls-2026-08-01` beta header. Claude Fable 5.1,
  Opus 5.5 and Sonnet 5.5 bind a thinking block to everything before it, and
  accounts created since 2026-08-31 got a 400 whenever background changed the
  system prompt or the tail moved. The vendor now drops the thinking blocks
  that no longer fit and answers. On older accounts, where such blocks used
  to reach the model unchanged, they are dropped too.
  The adapter logs how many blocks were dropped, at DEBUG.
- **Answers whose type is a list, enum, string or sealed type work on OpenAI
  and Azure.** A schema whose root is not an object travels wrapped as
  `{"value": ...}` and is unwrapped before the caller sees it, and generic
  answer types keep their generics.
- **Episode ranking no longer fails on a stored summary of another width.**
  A summary embedded under the same model name at a different width ranks
  last, like another model's, instead of failing the question.
- **A width of zero or less is refused where it is set.** `dimension(0)` and
  `nessy.embedding-dimension: 0` fail at once; the property's failure names it.

### Breaking changes

- **`nessy-approval-intent` is removed**, with `Intent`, `Intents`,
  `JdbcIntents`, `IntentTool`, `IntentEnricher`, `IntentPolicy` and the
  `nessy_intent` table.
- **`Embedder.dimension()` returns `Optional<Dimension>`,** not `int` (0 for
  not learned yet): empty until the first reply when no width was asked for.
  `Embedding.dimension()`, a vector's length, stays `int`.
- **`EmbeddingOptions.dimension` is `Optional<Dimension>`,** not `OptionalInt`.
- **`InferenceOptions` and `EmbeddingOptions` gain a `properties`
  component.** Their existing constructors and `of(...)` still work;
  a record pattern over either (`InferenceOptions(var model, var max)`)
  needs the third component.
- **The `openai` wire is now `openai-chat`.** A custom provider with
  `nessy.providers.<id>.wire: openai` fails at startup; write `openai-chat`.
  Presets are unaffected, and the startup report now prints `openai-chat`.
- **`OpenAiInferenceProvider` is now `OpenAiChatInferenceProvider`, and
  `OpenAiProviderConfig` is now `OpenAiChatProviderConfig`.** Same factories,
  same setters, same behaviour.
- **`OpenAiRequests` is no longer public.** It was the chat adapter's
  internal projection and is now the package-private `OpenAiChatRequests`.
- **`DefaultEmbedderFactory`'s three constructors are gone**; build it with
  `DefaultEmbedderFactory.of(f -> f.provider(id, provider).embedding(id,
  EmbeddingOptions.of(model)))`. Every embedder it mints is now an
  `ObservedEmbedder`.
- **An embedding provider holds nothing about a model.** `model(...)` and
  `dimension(...)` are gone from `OpenAiEmbedderConfig`,
  `GeminiEmbedderConfig`, `BedrockEmbedderConfig` and `VoyageEmbedderConfig`,
  and `defaultModel()` / `defaultDimension()` from the four providers. The
  `DEFAULT_MODEL` constants stay, to cite: `EmbeddingOptions.of(
  OpenAiEmbedderConfig.DEFAULT_MODEL)`.
- **An embedder that asked for a width and got another fails**, naming both
  (`asked for 256 coordinates, the model returned 768`), on every call.
- **A Bedrock embedder over a model of neither family fails when it is
  made**, not at its first call.
- **`nessy.embedding.*` is replaced by `nessy.embedders.*`**, with no
  aliases:

  | old | new |
  |---|---|
  | `nessy.embedding.openai.model` | `nessy.embedder: openai` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.openai.dimension` | `nessy.embedding-dimension: <n>` |
  | `openai.api-key` + `openai.base-url` + `nessy.embedding.openai.model` | a custom embedder (`nessy.embedders.local.wire: openai`, `.base-url`, `.api-key`, `.vendor`) plus the pair naming it; or the `openai` preset with `openai.base-url`, plus the pair |
  | `nessy.embedding.gemini.model` | `nessy.embedder: gemini` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.gemini.dimension` | `nessy.embedding-dimension: <n>` |
  | `nessy.embedding.voyage.api-key` | `voyage.api-key` (`VOYAGE_API_KEY`), or `nessy.embedders.voyage.api-key` |
  | `nessy.embedding.voyage.model` | `nessy.embedder: voyage` + `nessy.embedding-model: <model>` |
  | `nessy.embedding.voyage.dimension` | `nessy.embedding-dimension: <n>` |
  | a key alone, with the vendor's default model | a key registers the provider; minting an embedder needs a model, from the store or the pair |

- **`VoyageEmbeddingAutoConfiguration`, `OpenAiEmbeddingAutoConfiguration`
  and `GeminiEmbeddingAutoConfiguration` are replaced by
  `EmbeddingProvidersAutoConfiguration`**; update any
  `spring.autoconfigure.exclude` naming them. Several keys set now register
  several embedders instead of the first in auto-configuration order.
- **The starter's `EmbedderFactory` bean is always present.** A store that
  decided "no embeddings" by the factory's absence now asks whether
  `nessy.embedder` is set. An application `EmbedderFactory` bean still
  replaces the starter's factory, and no longer switches the presets off.
- **Anthropic's typed setters are removed.** `AnthropicProviderConfig`
  `thinking(boolean)`, `thinkingBudget(int)` and `promptCaching(PromptCaching)`
  are gone, as is the `PromptCaching` enum; so are `AnthropicRequests.Features`
  and `AnthropicRequests.toParams(InferenceRequest, Features, JsonMapper)`. Use
  `property(AnthropicProperties.THINKING_TYPE, AnthropicThinkingType.ENABLED)`,
  `THINKING_BUDGET` and `CACHE_TTL`. Thinking and caching stay off unless a
  property turns them on.

## [0.2.0] - 2026-09-29

### Breaking changes

- **`InferenceProvider.providerName()` is now `vendor()`**, and so are
  `Embedder.providerName()` and `EmbeddingProvider.providerName()`. The value
  is unchanged: the OpenTelemetry `gen_ai.provider.name` (`openai`, `x_ai`,
  `gcp.gemini`, ...), which two providers can share. `OpenAiProviderConfig.provider(String)`
  is now `vendor(String)`. Traces are byte-identical.
- **A harness factory holds its providers by name.**
  `DirectHarnessFactoryConfig.provider(InferenceProvider)` and
  `QueuedHarnessFactoryConfig.inference(InferenceProvider, InferenceOptions)`
  are replaced by a repeatable `provider(ProviderId, InferenceProvider)` on
  both configs, plus `inference(ProviderId, InferenceOptions)` for the
  factory's defaults. Migrate
  `f.provider(p)` to `f.provider(ProviderId.of("openai"), p).inference(ProviderId.of("openai"), InferenceOptions.of("<model>"))`.
- **Every agent type needs a provider and a model**, its own
  (`inference(in -> in.provider("openai").model("..."))`) or the factory's
  defaults. A missing or unregistered one fails when the harness is built,
  naming the agent type and listing the registered providers.
- **`DirectHarnessFactory.providerName()` is removed**: a factory holding
  several providers has no single answer.
- **Spring Boot: `nessy.provider` and `nessy.model` are a pair.** Set both,
  or neither and name them on each agent type; setting one fails at startup.
  Several vendor keys set no longer means one provider picked by
  auto-configuration order: every lit provider is registered.
- **Spring Boot: an application's own `InferenceProvider` beans no longer
  switch the vendor presets off.** They are registered beside them under
  their bean names. A bean named like a lit preset fails startup.
- **Spring Boot: `OpenAiAutoConfiguration`, `AnthropicAutoConfiguration` and
  `GeminiAutoConfiguration` are replaced by `InferenceProvidersAutoConfiguration`.**
  Update any `spring.autoconfigure.exclude` that names them.

### Added

- `ProviderId`, the name an application gives a provider; `InferenceConfig.provider(ProviderId)`
  and `provider(String)`.
- Spring Boot presets. A hosted preset is registered when its key is set:
  `openai` (`OPENAI_API_KEY`), `xai` (`XAI_API_KEY`), `anthropic`
  (`ANTHROPIC_API_KEY`), `gemini` (`GEMINI_API_KEY` or `GOOGLE_API_KEY`),
  `openrouter` (`OPENROUTER_API_KEY`), `nvidia` (`NVIDIA_API_KEY`). A local one
  when switched on: `lmstudio` and `ollama` (`nessy.providers.<id>.enabled: true`).
  Every preset was measured against its real endpoint before it shipped: a
  tool call, reported usage, and a forced answer.
- `nessy.providers.<id>.*` (`api-key`, `enabled`, `wire`, `base-url`,
  `vendor`) overrides any preset field; an id that is not a preset declares a
  custom provider and must set `wire` (`openai`, `anthropic` or `gemini`) and
  `base-url`. `enabled: false` turns any provider off, key or no key.
- The startup report lists every registered provider (id, wire, endpoint,
  vendor, never the key), and each harness logs the provider and model its
  agent type resolved to when it is built.
- `PresetCandidatesLiveTest` (`@Tag("live")`) measures OpenAI-shaped vendors
  and the existing presets against their real endpoints from the keys in the
  environment, and writes `target/preset-measurements.md`.

### Changed

- An agent type built from factory defaults that carry no max-tokens gets
  4096 on both doors.
- The console: `NESSY_PROVIDER` and `NESSY_MODEL` are required together; a
  mistyped `NESSY_PROVIDER` is reported, not thrown. `/config` shows the
  provider only when the console chose it.
- The examples name their provider: chat-web and watchman use the `lmstudio`
  preset by default; watchman's scripted mode is `WATCHMAN_PROVIDER=scripted`.
- `nessy-bom` keeps its parent for the build and is flattened when published:
  it manages only Nessy's own artifacts. Every module publishes the
  repository's own URL and SCM connection.
- Dependencies: openai-java 4.69.2, anthropic-java 2.65.0, google-genai
  1.73.0, AWS SDK 2.55.5, Jackson 3.2.3 and 2.22.3, Netty 4.2.18, Tomcat
  11.0.26, HikariCP 7.1.0, SLF4J 2.0.20.

## [0.1.1] - 2026-09-27

### Fixed

- A turn ended by a `TurnPolicy` returning `FailTurn` could not be replayed,
  which left the agent unusable rather than merely ended: every later
  `ask`, `terminate`, or any other read reconstitutes from the event stream
  and threw `TurnFailed cannot happen in Inferring`. The default policy is
  `TurnPolicy.calls(20, 25)`, so this reached any turn that made
  twenty-five model calls without finishing.

  `AgentState.Inferring` now accepts `AgentEvent.TurnFailed` and returns to
  `Idle`. The event is emitted by `AwaitingActions` but arrives in
  `Inferring`, because the discharge that freed the last outstanding call is
  applied first.

## [0.1.0] - 2026-09-27

Nessy is an agent harness framework for Java. This is the first release.

### Added

- **Two doors.** `DirectHarness<I, O>.ask(agentId, input)` runs a turn on the
  calling thread and returns an `Outcome<O>` — `Answered`, `Refused`,
  `Failed`, or `Busy` if another turn is already running for that agent.
  `QueuedHarness<I>.tell(agentId, input)` always accepts and returns nothing;
  the turn runs later. Both doors exclude each other per agent with a lock
  (`Locks.TURN`), so only one turn ever runs for a given agent at a time.
- **A decide/accept fold.** `AgentState` is a sealed type — `Idle`,
  `Inferring`, `AwaitingActions`, `Terminal` — that decides a command and
  folds the resulting events. State is rebuilt by replaying events, so
  recovery after a crash is the same code path as normal operation.
- **Storage backends.** `DirectBackend` and `QueuedBackend` are implemented
  by `nessy-backend-jdbc` (PostgreSQL, via `Schemas.initialize`) and
  `nessy-backend-inmemory`. On the queued door, effects are rows performed
  by a dispatcher; on the direct door, the calling thread performs them.
- **Turn policy and cost.** `TurnPolicy.calls(20, 25)` is the default: the
  model is asked to answer at twenty calls and the turn fails at
  twenty-five, communicated to providers as `ToolChoice.Answer`. Every
  `Outcome.Answered`/`Refused`/`Failed` carries a `TurnStats` tally built
  from `Usage` (one model call) and `Tokens`, which is either `Counted` or
  `Uncounted` depending on what the vendor reports.
- **Retries.** Every retry default is `RetryPolicy.Never`. A model call
  reaches a configured retry policy only when its adapter classifies the
  failure as `Failure.Transient`.
- **Structured output.** `OutputReader<O>` reads a model's answer into a
  typed result; `JsonSchema` and `JsonSchemaGenerator` derive the schema
  sent to the provider.
- **Model providers.** Inference adapters for Anthropic, OpenAI (and any
  OpenAI-compatible endpoint), Gemini, and Bedrock. Embedding adapters for
  OpenAI, Gemini, Voyage, and Bedrock.
- **Tools and approvals.** `Tool<I>` binds a typed input and executes
  against a `ToolCallRequest<I>`. `Awaited` lets a call answer immediately
  or defer, and every call goes through an `Approver`. `nessy-approval-risk`
  scores a call with NIST SP 800-30's likelihood/impact matrix and routes
  it to auto-approve, auto-deny, or a human desk. `nessy-approval-policy`
  delegates the decision to a `PolicyEngine`, with `nessy-approval-policy-opa`
  speaking Rego to Open Policy Agent. `nessy-approval-intent` is a claim
  channel for a model to declare what it is about to do before it does it.
- **Memory.** `nessy-memory-episodic` cuts a story into named episodes and
  summarizes each as it closes. `nessy-memory-notebook` gives an agent notes
  it recalls by heading. `nessy-memory-summarizing` writes a background
  summary of the head of a long conversation.
- **Planning.** `nessy-planning` gives an agent a plan it writes, holds
  across turns, and works through, resent wholesale on every turn.
- **MCP tools.** `nessy-tool-mcp` imports a remote MCP server's tools as
  ordinary `Tool`s, over the official MCP Java SDK.
- **Observability.** `Narration` and `NarrationListener` report what an
  agent is doing as it happens; `nessy-narration-odyssey` journals events
  to an Odyssey stream so a page can subscribe, resume, and replay them as
  Server-Sent Events. Async listeners run outside a turn's trace by design.
- **Spring Boot.** `nessy-spring-boot-starter` is a single dependency that
  assembles a harness from `nessy.*` properties and beans. Each door,
  backend, provider, and embedder has its own `@ConditionalOnMissingBean`
  auto-configuration in `nessy-spring-boot-autoconfigure`, so an
  application can override any bean without the starter's opinions.
- **Console.** `nessy-console` builds a working terminal agent from a main
  method in one call.

### Requirements

- Java 25.
- Spring Boot 4.1 (optional — only needed for `nessy-spring-boot-starter`).

[0.2.0]: https://github.com/jwcarman/nessy/releases/tag/0.2.0
[0.1.1]: https://github.com/jwcarman/nessy/releases/tag/0.1.1
[0.1.0]: https://github.com/jwcarman/nessy/releases/tag/0.1.0
