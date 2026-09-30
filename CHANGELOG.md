# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`OpenAiResponsesInferenceProvider`**, an adapter for OpenAI's Responses
  API, in `nessy-inference-openai` beside the Chat Completions one. It is
  stateless (`store: false`, the whole context on every call), keeps each
  encrypted reasoning item as a `Block.Provider` block, and sends function
  tools in strict mode, falling back per tool with a warning when a schema
  cannot be expressed strictly. Reasoning models such as GPT-6 call tools
  through it.
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
- **`InferenceProvider.validate(InferenceOptions)`** and
  **`EmbeddingProvider.validate(EmbeddingOptions)`**, default no-ops, which
  the factories call when a harness or embedder is built.
- **`nessy-vendor-properties`**, the module adapters read properties
  through. The BOM now also lists `nessy-inference-spi` and
  `nessy-embedding-spi`.
- **`openai.tools.strict`** on the chat adapter: function tools go out in
  strict mode over a rewritten schema.
- **Spring Boot: `nessy.providers.<id>.properties.*`.**

### Changed

- **The `openai` preset sends strict function tools**
  (`openai.tools.strict=true` by default). Set
  `nessy.providers.openai.properties.openai.tools.strict: "false"` to turn
  it off, for instance when `openai.base-url` points at a server that
  rejects strict mode.
- Anthropic's `thinking`, `thinkingBudget` and `promptCaching` setters and
  their `anthropic.*` properties are one setting: setting both for one
  field on one provider fails at build.

### Breaking changes

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
