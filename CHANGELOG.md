# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Breaking changes

- **Narration is reshaped into the agent's story.** `Narration` has two groups, `Story` (stored
  events) and `Live` (signals heard only as they happen). `TurnEnded` is removed: a turn ends in
  exactly one `TurnEnding` event (`Answered`, `TurnRefused`, `TurnFailed` or `TurnStopped`), and
  `NarrationListenerConfig.onTurnEnding` hears all four. A turn a policy stopped is `TurnStopped`,
  no longer `TurnFailed`. `Answered`, `TurnRefused`, `TurnFailed` and `ActionsRequested` carry
  their turn and the model call's `Usage`; `TurnFailed` carries a `FailureKind`; each requested
  call carries its `IdempotencyKey`. A retried model call is told as `InferenceRetried`.
- **`NarrationListener.on` takes a `Narrated` envelope:** the agent, the event, and for a story
  event its position (`seq` and time written). `NarrationListenerConfig.Handler` is
  `on(Narrated narrated, E event)`, and `Narrator.narrate` takes a `Narrated`. A story event's
  time is the engine's clock, passed to the store: `AgentEvents.append` takes a trailing
  `Instant at`, which a store writes as given rather than reading a clock of its own, so an event
  heard live and read back later carry the same instant. `InMemoryAgentEvents` no longer takes a
  `Clock`. `AgentNarrator.narrate` takes only a `Narration.Live` signal.
- **The JSON and SSE names of the turn endings changed.** `turn-ended` is gone; `turn-stopped` and
  `inference-retried` are new. A browser client that listened for `turn-ended` must listen for the
  four endings: `answered`, `turn-refused`, `turn-failed` and `turn-stopped`.
- **The stored event for a turn a policy stopped is `turn-stopped`;** it was `turn-failed`. It is
  `AgentEvent.TurnStopped`, no longer `AgentEvent.TurnFailed`. Recreate the database.
- **`NarrationListenerConfig.on(Class, handler)` hears every member of a group.** Given a group
  type (`Narration.TurnEnding`, `Narration.Story`, `Narration.Live` or `Narration`), it now hears
  each event of that group, not only an event of exactly that class.
- **A stored event's `written_at` is the engine's clock reading for its step,** to the microsecond,
  not the database's `now()`.

### Added

- `StoryProjection.of(initial, step)` makes a projection from a lambda; `StoryContent.allResults(after)`
  streams every successful result, reading a page at a time.
- **`AgentStory.content()` reads what a story refers to:** a turn's input, what the model wrote and its answer; a call's result by its `IdempotencyKey`; and an agent's successful results, paged.
- **`AgentStory.project` folds an agent's story with a `StoryProjection`; `UsageReports` is one.**
- **`AgentStories` replays an agent's story:** the stored events, as the `Narrated` a live
  listener hears, with each event's position. `AgentStories.of(type, id).replay(after, limit)`
  reads up to `limit` story events after a `Seq`, oldest first. A story store implements the new
  `AgentEvents.readWrittenFrom`, which reads up to a limit of events with the time each was written. The
  engine truncates the time it writes to microseconds, which is what PostgreSQL keeps, so an
  event heard live equals the same event replayed.

## [0.4.0] - 2026-10-03

### Breaking changes

- **`ApprovalRequest.callKey()` is replaced by `idempotencyKey()`**, a typed
  `IdempotencyKey` wrapping a UUID. `callKey()` was `turn/callId`: unique only
  within one agent, and not even there when a model repeats a call id within
  a turn. The new key is made once per call when the model's request is
  recorded, stays the same across retries and restarts, and is unique across
  every agent. `ToolCallRequest.idempotencyKey()` carries the same key, so a
  tool can deduplicate on it and find its own approval. **Stored events and
  effects change shape; recreate the database.**
- **`DirectHarness.ask` refuses to run inside a caller's transaction.** It
  throws `IllegalStateException` before writing anything. A turn makes a model
  call, and a transaction should not stay open across a network call; on JDBC
  the call failed anyway, with "no event at 1 for agent ...". Suspend the
  transaction for the call (`PROPAGATION_NOT_SUPPORTED`). The queued door's
  `tell` still joins the caller's transaction. `nessy-engine` now depends on
  `spring-tx` for this check, as it already does on `spring-context`.
- **Anthropic prompt caching is on by default.** With
  `anthropic.cache_control.ttl` absent, a request now carries `FIVE_MINUTES`
  cache markers; before, it carried none. A one-off request still carries
  none, and neither does a request that asks for an answer
  (`ToolChoice.Answer`), which is sent without its tools.
- **`Narration.ActionsRequested` carries one `Call` per requested call**
  (`callId`, `toolName`, `action`) in place of tool names alone, so a watcher
  can join each later call event, which names only the id, to its tool.
- **`Turn` has no `tokens` field.** It was always 0 and nothing read it.
  Usage is reported as a whole, through `UsageReports`.

### Added

- **`AnthropicCacheTtl.DISABLED`** turns Anthropic prompt caching off. Set it on
  the provider, or on one agent type to override a provider that caches.
- **`UsageReports`** reads an agent's usage over its whole history, by model:
  `of(type, id)` returns one `ModelUsage` per model, never added across
  models. It is projected from the agent's stored events, so it counts every
  inference, retries and failures included, and is fit for cost accounting,
  which narration is not. The starter offers a `UsageReports` bean.

### Changed

- The OPA adapter and the policy example are tested against OPA 1.21.1. Every
  response shape the adapter relies on is the same as on 0.68.0.

### Fixed

- **The watchman example no longer hides a repeated call id.** Its approvals
  board was keyed on the model's call id, so once an agent's `call_1` was
  answered, a later `call_1` from the same agent was never shown and timed
  out. The board is now keyed on the call's `IdempotencyKey`, and its
  approve and deny URLs carry that key. Drop `watchman_pending_approval` (or
  recreate the watchman's database) before running it.
- **Gemini no longer returns half a reply as the answer.** A stream that
  stopped before its finish reason was read as a complete answer. It is now a
  fault.
- **A stream cut short is `Failure.Unknown`, not `Permanent`**, on Anthropic,
  OpenAI Chat, OpenAI Responses, Bedrock and Gemini. A server that closes the
  stream mid-answer is a dropped connection, so a retry policy can now try
  again. A stream that delivers no answer at all is still `Permanent`.
- **A dropped connection to the model can be retried.** Every adapter
  reports a dropped connection as `Failure.Unknown`, and the dispatcher sent
  only `Failure.Transient` to the retry policy, so a dropped connection ended
  the turn whatever the policy said. An `Unknown` model failure now reaches
  the policy too: a model call that runs twice changes nothing but the bill.
  The default policy is still `Never`; set one with
  `InferenceConfig.retryPolicy` for the queued door to try again.
- **The Boot starter closes every inference provider's client at shutdown.**
  Each provider bean is an `ObservedInferenceProvider`, which was not
  `AutoCloseable`, so the container never closed the adapter inside it and
  the adapter's HTTP client leaked. The wrapper now closes what it wraps, as
  `ObservedEmbedder` already did.
- **A generated answer type works on Anthropic.** Anthropic refuses an answer
  schema with an object that does not say `"additionalProperties": false`,
  and the schema generator writes none, so every generated answer type failed
  with a 400. The adapter now closes every object in the answer schema, and
  leaves one that already says what it allows as written.
- **The queued dispatcher can no longer stop for good.** A claim that returned
  more rows than its batch made the dispatcher release a negative count of
  permits; the semaphore threw and the agent type never dispatched again. The
  dispatcher now runs at most its batch and puts any surplus straight back on
  the queue, and the JDBC claim picks and locks its rows in a `MATERIALIZED`
  CTE, evaluated exactly once.

## [0.3.0] - 2026-10-02

### Breaking changes

- **A database written by an earlier build cannot be read and must be
  recreated.**
- **Every piece of content Nessy stores goes through the storage codec.**
  A chapter's summary (`nessy_chapter.summary`), a note's hook and body
  (`nessy_note.hook`, `nessy_note.body`) and a plan task's title
  (`nessy_plan_task.title`) were `TEXT`; they are now `BYTEA`, encoded with
  the application's `CodecFactory`, so a `StorageCodecConfigurer`'s compression
  or encryption covers them as it covers the events and the payloads.
  `JdbcNotebook` and `JdbcPlans` take a `CodecFactory` as a third constructor
  argument, `JdbcChapters` takes one as its second, and `InMemoryChapters`
  takes one, so each holds the encoded bytes of a summary as the in-memory
  payload store holds content. The constructors without one are removed. A
  database written by an earlier build must be recreated.
- **A custom `InferenceProvider`, or a `switch` over `InferenceResult`, needs
  the new arm.** A provider returns `Truncated` when its vendor says the
  output limit was reached and the reply holds text.
- **`ToolConfig.action` takes a `Stringifier` of the tool's input.** A line is
  one line of at most 1,000 characters; with none named it is cut at 255,
  keeping its start.
- **`Exchange`, `ActionRequest.ToolCall` and `ToolSucceeded` each gained
  components:** `actions` and `results`, `action`, and `rendered`.
- **`ActionRenderer` is removed.** A `Stringifier<I>` takes its place.
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
- **`nessy-memory-summarizing` is removed**, with `HeadSummarizer`,
  `JdbcSummaries` and the table `nessy_summary`. Chapters replace it, and are on
  by default. Existing `nessy_summary` rows are no longer read.
- **`nessy-memory-episodic` is removed**, with `EpisodeSummarizer`,
  `EpisodeTools`, `JdbcEpisodes`, the tools `begin_episode` and
  `recall_episode`, and the table `nessy_episode`. Chapters replace it; to let
  the model draw the boundaries, use `DeclaredChapters.feature(histories)` and
  its `begin_chapter`. Existing `nessy_episode` rows are no longer read.
- **`Summarizer` is a different interface.** The read-side `Summarizer`
  (`forAgent`, `summarizedThrough`) is gone; `org.jwcarman.nessy.api.Summarizer`
  now has one method, `String summarize(Chapter)`, and writes a chapter's
  summary. A source that supplied recollections to a turn is a `MemorySource`.
- **`summaries(...)` is removed** from `HarnessConfig` and `ContextConfig`. Use
  `chapterPolicy(...)` and `summarizer(...)` to decide what stands in for old
  history, or `memory(...)` to offer recalled text.
- **`Block.SummaryContent` is removed.** A `Summary` is now
  `Summary(Chapter chapter, String text)`.
- **`InferenceContext` has new components:** `(summaries, tail, memory, state,
  activeTurn, ambient)`. A provider adapter that reads its parts must place
  the new strata; see the providers guide.
- **Ambient background is no longer in the system prompt.** An adapter written
  against the old context, or an application that relied on ambient text
  being part of the system field, sees a different request.
- **`SystemPromptSource` is removed**, with `systemPrompt(SystemPromptSource)`
  on both doors and on `ReplConfig`. Use `systemPrompt(String)`, adding
  sections with `instructions(String)`; anything that varied per agent or per
  call becomes a `StateSource` or an `AmbientSource`.
- **`PromptVariableSource` is removed.** Its factories are on `PromptVariables`
  (`of`, `supplied`, `firstOf`, `none`), which no longer takes an agent.
  `EnvironmentVariables.of(...)` returns a `PromptVariables`.
- **`TemplatedSystemPrompt.of(...)` is replaced by `render(...)`,** which
  renders once and returns a `SystemPrompt`.
- **The starter's `SystemPromptSource` bean is now a `SystemPrompt` bean,**
  and `PromptVariableSource` beans are no longer consulted: declare
  `PromptVariables` beans.
- **A backend implementation must provide `chapters()` and `leases()`** on
  `DirectBackend` and `QueuedBackend`.

### Added

- **`InferencePurpose`**, what a model call is for, with the supplied values
  `ANSWER` and `SUMMARY`. `InferenceRequest` gains `purpose` (`ANSWER` unless
  it says otherwise) and `withPurpose`, and `ProseSummarizer` sends `SUMMARY`.
  The `nessy.inference.purpose` key is on the model-call span and on the
  `gen_ai.client.token.usage` and `gen_ai.client.operation.duration` metrics,
  so spend and cache use can be split by purpose. It is never sent to the
  vendor.
- **`InferenceResult.Truncated`**, a reply the vendor cut off at the output
  limit. It holds the blocks written before it stopped, at least one of them
  text. All five adapters return it (Anthropic `max_tokens`, OpenAI chat
  `length`, OpenAI Responses `max_output_tokens`, Gemini `MAX_TOKENS`,
  Bedrock `max_tokens`). An agent's turn delivers it as the answer, logs a
  WARN and reports `length` as the finish reason.
- **`AnthropicThinkingType.BETWEEN_TOOLS`** (`anthropic.thinking.type:
  between_tools`), sent as the vendor names it. It is how thinking is turned
  off on a model that thinks with no thinking field and refuses `disabled`, as
  Sonnet 5.5 does: the model does not think before it responds.
- **`InferenceRequest.oneOff`**, set with `asOneOff()`: a request that
  belongs to no series, so nothing it sends will be sent again. The Anthropic
  adapter sends no cache markers for one, whatever
  `anthropic.cache_control.ttl` says, and `ProseSummarizer` sends one-offs, so
  a chapter's summary no longer pays for a cache write nobody reads.
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
- **Chapters.** History is cut into chapters when a turn ends, and a closed
  chapter is replaced in the context by a summary. `ChapterPolicy` says where
  a chapter ends (`ends(OpenTurns)`, with `ChapterPolicy.every(int)`) and
  `Summarizer` writes the text that stands in for one
  (`summarize(Chapter)`); `Chapter` and `Summary` are in
  `org.jwcarman.nessy.api.turn`. Both run off the agent's thread, under a
  `nessy.chapters` lease, driven by the engine's `ChapterKeeper`. Closed
  chapters and their summaries are kept by the backend, as `Chapters` in
  `nessy-backend-spi` (reached as `backend.chapters()`) and, for JDBC, the
  table `nessy_chapter`, which `Schemas.initialize` creates. A chapter that is
  closed but not yet summarised is still shown as turns, and a failed summary
  is tried again at a later turn end.
- **Chapter settings.** `chapterPolicy(...)` and `summarizer(...)` on the
  harness config; `maxChapterLength(int)` (default 30),
  `chapterLeaseTtl(Duration)` (default two minutes), `maxTail(int)` and
  `withoutChapters()` on the context config. A harness refuses to build when
  `maxTail` is not greater than `maxChapterLength` with chapters on.
- **`ProseSummarizer`**, the default summariser: a prose record of the
  chapter, written by the agent's own provider and model.
- **`DeclaredChapters`**, which lets the model say where chapters begin:
  `DeclaredChapters.feature(histories)` installs the tool `begin_chapter` and
  a policy that closes the chapter before any turn that called it.
- **The context has six strata, in a fixed order:** instructions; history
  (summaries of closed chapters, then the tail of completed turns); memory;
  state; the active turn; ambient. `InferenceContext` is now
  `(summaries, tail, memory, state, activeTurn, ambient)`.
- **`MemorySource` and `StateSource`**, beside `AmbientSource`, with the
  records `Memory` and `State` and `memory(...)` and `state(...)` on the
  harness and context configs. Both are asked with
  `forAgent(AgentId, Turn current)`, once per call, and nothing holds an
  earlier answer for them. Two sources of the same stratum may not offer the
  same kind.
- **`instructions(String)` on `HarnessConfig`**, which adds a section after the
  system prompt, fixed when the harness is built.
- **`PromptVariables.supplied(...)`, `firstOf(...)` and `none()`**, and
  `TemplatedSystemPrompt.render(...)`, which renders a template once.
- **Spans for chapters and sources:** `nessy.summary` for each summary
  written, `nessy.chapter.policy` for each question put to the policy, and
  `nessy.context memory <kind>` and `nessy.context state <kind>` beside the
  ambient ones.
- **`nessy-examples/chapter-lab`**, a command-line lab that replays a long
  recorded conversation under different chapter policies and summarisers.
- **`Stringifier<T>`**, `String stringify(T value)`, with `dropTail`,
  `dropHead` and `dropMiddle` to bound what it writes, `truncated` to bound it
  with a `Truncator` of your own, `Stringifier.byToString()` and
  `Stringifier.json(mapper)`. **`Truncator`** cuts a string to a limit and
  supplies the three droppers.
- **`ToolConfig.result`**, a `Stringifier<ToolResult.Success>` that says what a
  call returned, with `ToolConfig.resultText()` as the default, plus
  `ToolConfig.LINE_CAP` (1,000) and `ToolConfig.DEFAULT_LINE_LIMIT` (255).
- **Two lines stored for each tool call:** `ActionRequest.ToolCall.action`,
  written when the model asks, and `ToolSucceeded.rendered`, written when the
  call succeeds. Each is at most 1,000 characters and is never worked out
  again.
- **`Exchange.actionOf(CallId)` and `Exchange.resultOf(CallId)`**, the two
  lines for a call in a turn's history.
- **chat-web: `chat.summary-model`** (`CHAT_SUMMARY_MODEL_ID`) names a second
  model on the same provider to write chapter summaries. The local default
  model of chat-web, watchman and chat-cli is `qwen/qwen3-coder-30b`.

### Changed

- **Listeners hear an agent's events after the step that wrote them commits.**
  Both doors write an agent's events in short locked steps, which on a
  database are transactions, and used to tell listeners inside the step. What a
  step narrates is now held until its lock returns and delivered then, in the
  order the steps committed in for that agent; narration from outside any
  step, such as streamed deltas, keeps its place behind a step reserved
  before it. Another agent's narration is never held up. The chapter keeper
  no longer waits for the turn it was told about to become visible, because
  it already is.
- **A reply cut off at the output limit is `Truncated`.** It was an `Answer`.
- **A tool call cut off at the output limit is a `Fault`.** It was `Actions`,
  carrying arguments that could parse as `{}` and run.
- **Gemini's `MALFORMED_FUNCTION_CALL` is a `Fault` that says a tool call was
  malformed and was not run.** It was the empty-answer fault. Gemini reports a
  tool call cut off at the output limit this way.
- **A chapter summary cut off at the output limit is refused.** It used to be
  stored. The chapter stays unsummarised and is tried again when a later turn
  ends, so an agent type's `maxTokens` has to leave room for the summary.
  While a summary does not fit, that chapter and every chapter after it stay
  unsummarised, and once more than `maxTail` turns have completed since the
  last written summary, the oldest of them are sent neither as a summary nor
  in the tail.
- **The `openai` preset sends strict function tools**
  (`openai.tools.strict=true` by default). Set
  `nessy.providers.openai.properties.openai.tools.strict: "false"` to turn
  it off, for instance when `openai.base-url` points at a server that
  rejects strict mode.
- Anthropic `enabled` thinking with no budget sends 1024 tokens instead of
  refusing at build; the budget must still be below `maxTokens`.
- **Ambient no longer travels in the system prompt, on any provider.** The
  system field carries the instructions alone. Memory and state are text at the
  head of the active turn's first message, tagged `<memory kind="...">` and
  `<state kind="...">`, and ambient is text at the very end of the request. On
  Anthropic the cache markers are chosen before the ambient text is appended,
  so none sits on it. A consequence on Anthropic: with any ambient background
  present, the vendor drops replayed thinking blocks on later calls.
- **Chapters are on by default.** An agent that sets nothing gets a chapter
  every 20 turns, summarised by its own provider and model, so it spends
  tokens on summaries unless `withoutChapters()` is set.
- **The default `maxTail` is 40 on both doors.** It was 50 on the direct door
  and 20 on the queued one, so a queued harness now shows more. It counts
  completed turns; the turn being answered is sent besides.
- **The system prompt is fixed when a harness is built.** It was resolved on
  every call. What varies by agent belongs in a `StateSource`, and what
  varies by the moment in an `AmbientSource`.
- **Spring Boot renders `nessy.system-prompt` once, at startup,** from every
  `PromptVariables` bean and then the `Environment`, and publishes a
  `SystemPrompt` bean. A hole nothing fills fails startup.
- **A chapter is summarised from text.** `ProseSummarizer` writes the chapter's
  turns out as lines, each call as `assistant did: <action> -- succeeded:
  <result>`, and sends them as one user message. Nothing changes for the turn
  being answered: the adapters still receive every call and result whole.
- **A failed call's message is at most 1,000 characters,** its middle dropped
  and `...` in the gap. That is the text the model reads back for the call.
- **A refused turn reads as one line in a chapter's transcript,**
  `(a message was withdrawn)`, and a line that runs onto more than one is
  indented four spaces after its first, so the summariser can tell where each
  line ends.

### Fixed

- A listener could be told about a step that then rolled back, or that failed
  to commit. A step that does not commit is now never heard, unless the harness
  was called inside an application's own transaction, which its steps then join.
- **A chapter that was due at a turn's end could be left open, or unsummarised,
  until a later turn when two turns ended close together.** The keeper that
  found the agent's lease held walked away, and the keeper holding it had read
  the history before the later turn, or had already listed the chapters to
  summarise. The keeper that held the lease now reads the history again when it
  lets go and goes round again for anything that became due meanwhile, and
  re-reads the unsummarised chapters after each summary. Nothing waits on a
  lease.
- **A chapter whose turns called a tool could not be summarised on Anthropic.**
  The summary request carried tool history and offered no tools, and the model
  answered nothing.
- **Anthropic: a changed prefix no longer rejects a request that replays
  thinking.** With thinking on, the adapter now sends
  `thinking.block_binding.prefix_mismatch_behavior: drop_block` and the
  `thinking-binding-controls-2026-08-01` beta header. Claude Fable 5.1,
  Opus 5.5 and Sonnet 5.5 bind a thinking block to everything before it, and
  accounts created since 2026-08-31 got a 400 when a request replayed
  thinking and the tail had moved or the background had changed. The
  request now asks the vendor to drop a thinking block whose prefix has
  changed, which is what makes a moved tail or changed background an answer
  and not a refusal. On older accounts, where such blocks used to reach the model
  unchanged, they are dropped too. The adapter logs how many blocks were
  dropped, at DEBUG. A request that does not think, a summariser's for
  instance, no longer replays thinking at all.
- **Anthropic: prompt caching no longer fails a turn that uses tools.** With
  `anthropic.cache_control.ttl` set, a turn's third model call (two tool
  rounds in), or any request after a turn that used tools twice or that
  failed, threw `ArrayIndexOutOfBoundsException` while the request was being
  built. The conversation's two cache markers now sit on the last block of
  the request, which in a tool loop is the newest tool result, and on the last
  block of the user-side message before it, which is where the previous
  request ended.
- **Answers whose type is a list, enum, string or sealed type work on OpenAI
  and Azure.** A schema whose root is not an object travels wrapped as
  `{"value": ...}` and is unwrapped before the caller sees it, and generic
  answer types keep their generics.
- **Episode ranking no longer fails on a stored summary of another width.**
  A summary embedded under the same model name at a different width ranks
  last, like another model's, instead of failing the question.
- **A width of zero or less is refused where it is set.** `dimension(0)` and
  `nessy.embedding-dimension: 0` fail at once; the property's failure names it.
- **A late answer to a call could be recorded as the answer to a later call
  with the same id.** A call id can repeat across two requests of one turn,
  and the Gemini adapter mints ids by position when the vendor sends none. A
  tool that finished after its deadline had been given up on, or an approval
  that came back late, was taken for the second request's call. An answer to
  a tool call or an approval now names the request it answers, and one for
  another request is ignored.

### Documentation

- **Context**, a concepts page for the six strata, chapters, and how to keep
  a conversation cacheable: what caching costs and saves, each Claude model's
  minimum cacheable size, and how to choose a chapter size, with measured runs.
- **12-Factor Agents**, a factor-by-factor check of Nessy against the
  12-Factor Agents principles.
- Every example on the site compiles, and every command runs.

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

[0.4.0]: https://github.com/jwcarman/nessy/releases/tag/0.4.0
[0.3.0]: https://github.com/jwcarman/nessy/releases/tag/0.3.0
[0.2.0]: https://github.com/jwcarman/nessy/releases/tag/0.2.0
[0.1.1]: https://github.com/jwcarman/nessy/releases/tag/0.1.1
[0.1.0]: https://github.com/jwcarman/nessy/releases/tag/0.1.0
