# An OpenAI Responses adapter: the second shape from one vendor

**Status: DESIGN, NOTHING BUILT. The rulings were made in conversation with James on 2026-09-29
and this record writes them down. Every new public name is listed in §11, marked as agreed or as
proposed here; the questions in §12 are asked, not answered.**

Date: 2026-09-29. Sits on `2026-09-29-named-providers-design.md` (provider, preset, wire, vendor;
the Boot catalogue) and its same-day amendment ruling that wire values are vendor-named -- "a wire
is the API shape a vendor defined; a second shape from one vendor gets a qualified name." This is
that second shape, and it triggers the class rename that spec's §4c said would come with it: "if a
wire ever becomes a named SPI type -- when a Responses-API adapter arrives, say -- a class and
module rename is in order then." The module keeps its name; the classes move. Every path, name,
count and SDK signature below was checked against `src/main/java`, `src/test/java` and
`openai-java-core-4.69.2.jar` on branch `responses-api` at the time of writing; where the brief
for this record and the code disagreed, the disagreement is in §12 rather than smoothed over.

---

## 1. The problem

Nessy speaks to OpenAI over Chat Completions, and Chat Completions is no longer enough for two
vendors that matter.

Measured against live OpenAI on 2026-09-25: GPT-6 (`gpt-6-sol`, `gpt-6-luna`) with function
tools **fails** on `/v1/chat/completions` with

> `400: Function tools with reasoning_effort are not supported for gpt-6-sol in
> /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to
> 'none'.`

Without tools it answers. An agent is a model with tools, so an agent on GPT-6 needs the
Responses API.

Measured on 2026-09-29: Perplexity rejects `/chat/completions` outright with a 403,
`agent_api_migration_required` -- "Sonar is now the Agent API. Use /v1/responses". Whether
Perplexity's `/v1/responses` matches OpenAI's shape closely enough for one adapter to serve both
is **unknown until measured** (§9c).

Every other vendor measured on 2026-09-29 speaks Chat Completions -- openai, xai, groq, mistral,
cerebras, nvidia, openrouter, lmstudio, ollama -- so Chat Completions stays the lingua franca of
the compatible universe, and the Responses API joins it as a second shape from the same vendor
rather than replacing it.

What exists, verified in source:

- `nessy-inference/openai` holds one adapter: `OpenAiInferenceProvider` (streams through
  `client.chat().completions().createStreaming(...)`, folds with the SDK's
  `ChatCompletionAccumulator`, narrates text and the `reasoning_content` / `reasoning` fields
  compatible servers send, strips `usage` from a chunk that still carries choices so Groq's and
  Mistral's streams fold) and its projection `OpenAiRequests`, built from `OpenAiProviderConfig`.
- The Boot starter's `Wire` enum has three values, `OPENAI("openai")`, `ANTHROPIC("anthropic")`,
  `GEMINI("gcp.gemini")`, and spells them as property values by lower-casing the name. Eight of the
  ten presets in `Preset.CATALOGUE` ride `Wire.OPENAI`; `WireProviders` maps that wire to the one
  class. The `openai` wire value shipped in `0.2.0` (`CHANGELOG.md`, "must set `wire` (`openai`,
  `anthropic` or `gemini`)").
- The SDK in use, `openai-java 4.69.2`, carries the whole Responses API: `ResponseCreateParams`
  (with `store`, `include`, `instructions`, `text`, `toolChoice`, `reasoning`,
  `previousResponseId`, `conversation`, `background`), `ResponseStreamEvent`,
  `ResponseReasoningItem` (with `encryptedContent()`), `ResponseInputItem` and
  `com.openai.helpers.ResponseAccumulator`. Nothing has to be upgraded.

## 2. The shape in one paragraph

One module, two adapters. `OpenAiInferenceProvider` becomes `OpenAiChatInferenceProvider` and
keeps doing exactly what it does; `OpenAiResponsesInferenceProvider` joins it, built the same way
from its own config, sharing the SDK, the client construction and the failure classification. The
new adapter is **stateless**: every call sends the whole context with `store: false`, so Nessy's
event log stays the only conversation there is. A reasoning model's encrypted reasoning items are
asked for, carried in the transcript as `Block.Provider` blocks, and replayed on the next call of
the same turn. Under Boot the wire values become `openai-chat` and `openai-responses`; every
preset that speaks Chat Completions moves to `openai-chat` and nobody using a preset notices; the
`openai` preset's default moves to `openai-responses` only after the live tests pass through the
new adapter. Hosted tools, reasoning effort and server-side state are out, each for a stated
reason.

## 3. Vocabulary

The named-providers terms are reused unchanged: a **provider** is a registered
`InferenceProvider`, a **preset** is a Boot catalogue entry, a **wire** is the protocol a provider
speaks (prose and a property value, never an SPI type), a **vendor** is the OpenTelemetry
`gen_ai.provider.name` value. Two additions:

| word | meaning |
|---|---|
| **shape** | one vendor's API as the wire sees it: Chat Completions and Responses are two shapes OpenAI defined. Used only in prose, to say why one vendor has two wires |
| **reasoning item** | the Responses API's `reasoning` output item, which for a reasoning model carries `encrypted_content` when asked for (`include: ["reasoning.encrypted_content"]`) and must be sent back in order for the model's chain of thought to continue across a tool call |

## 4. The rename

This lands **first, as its own commit**, mechanical and reviewable alone (§10, step 1).

### 4a. Wires: `openai` becomes `openai-chat`, and `openai-responses` joins it

**Ruling (James, 2026-09-29):** the two wire values are `openai-chat` and `openai-responses`, for
symmetry. The `openai` value that shipped in `0.2.0` is **renamed**, not kept: a custom provider
that sets `wire: openai` today breaks at binding, with the enum's own error naming the allowed
values. **No alias.** Pre-1.0, an alias is permanent surface bought to save one line in a handful
of `application.yaml` files; the enum error is the migration guide.

The `Wire` enum becomes `OPENAI_CHAT("openai")`, `OPENAI_RESPONSES("openai")`,
`ANTHROPIC("anthropic")`, `GEMINI("gcp.gemini")`. `propertyValue()` is untouched: it already lower
cases the name and turns `_` into `-` (verified, `Wire.java` line 46), so the two new constants
spell themselves. Both OpenAI wires default the vendor to `openai`, because that is the honest
default for an endpoint about which nothing else is known -- the same reasoning `VENDOR`'s javadoc
gives today.

### 4b. Classes: `OpenAiInferenceProvider` becomes `OpenAiChatInferenceProvider`

**Ruling (James, 2026-09-29):** the chat adapter is renamed `OpenAiChatInferenceProvider`; the
new one is `OpenAiResponsesInferenceProvider`. Both live in the one module
`nessy-inference-openai`, because they share the SDK dependency, the client construction and the
error classification, and a second module would carry a second copy of each.

**The config is per class, so it is renamed too -- decided by reading the code.**
`OpenAiProviderConfig.build()` is package-private and returns the concrete
`OpenAiInferenceProvider`; the factory `OpenAiInferenceProvider.of(Customizer<OpenAiProviderConfig>)`
is typed on both. A config that could build either adapter would have to return
`InferenceProvider`, and every caller that opens the concrete type in a try-with-resources
(`OpenAiLiveTest`, `OpenAiCloseOwnershipTest`) would lose `AutoCloseable` at the call site. So:

- `OpenAiProviderConfig` becomes `OpenAiChatProviderConfig`, and `OpenAiResponsesProviderConfig`
  is new, with the same setters -- `apiKey`, `fromEnv`, `baseUrl`, `organization`, `client`,
  `mapper`, `timeout`, `vendor` -- because every one of them is about the client and the vendor
  tag, not the wire.
- The client-building half of `build()` / `buildFromEnv()` (the SDK's `fromEnv()` layering, the
  explicit-override precedence, the friendly missing-credential message, the `Timeout.request`
  bound) moves to a package-private helper both configs call. Mechanical internal, no name to
  approve.
- `OpenAiRequests` (public, "the only place in this module that knows what a role is") becomes
  `OpenAiChatRequests`, and the new projection is `OpenAiResponsesRequests`. This is a public
  class rename the brief did not name; it is listed in §11 as proposed.
- `classify(OpenAIException)` moves out of the chat adapter into a package-private
  `OpenAiFailures` both adapters call, unchanged in behaviour. Mechanical internal.
- `VENDOR` and `NAME` stay on the chat class and are read by the Responses class; `name()` returns
  `"OpenAI"` from both.

### 4c. Inventory, by search

Files referencing `OpenAiInferenceProvider` or `OpenAiProviderConfig` (excluding `target/`):

| file | refs | what |
|---|---|---|
| `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiInferenceProvider.java` | 13 | renamed; the class, the factories, the javadoc citing "the `openai` wire" |
| `nessy-inference/openai/src/main/java/org/jwcarman/nessy/inference/openai/OpenAiProviderConfig.java` | 19 | renamed; `build()` and its javadoc |
| `nessy-spring-boot/autoconfigure/src/main/java/org/jwcarman/nessy/spring/boot/inference/WireProviders.java` | 3 | the `OPENAI_CLASS` string constant, the import, the `OpenAi.build` arm |
| `nessy-inference/openai/src/test/java/.../OpenAiInferenceProviderTest.java` | 34 | renamed `OpenAiChatInferenceProviderTest` |
| `nessy-inference/openai/src/test/java/.../OpenAiLiveTest.java` | 11 | renamed `OpenAiChatLiveTest` |
| `nessy-inference/openai/src/test/java/.../OpenAiProviderConfigTest.java` | 9 | renamed `OpenAiChatProviderConfigTest` |
| `nessy-inference/openai/src/test/java/.../OpenAiCloseOwnershipTest.java` | 6 | keeps its name; the module's close-ownership rule, exercised on the chat class |
| `nessy-inference/openai/src/test/java/.../OpenAiVendorTest.java` | 5 | keeps its name; reads `VENDOR` |
| `nessy-engine/src/test/java/org/jwcarman/nessy/engine/harness/direct/DirectHarnessLiveTest.java` | 2 | the import and `OpenAiInferenceProvider.of(...)` at line 89 |
| `docs/guides/providers.md` | 3 | lines 75, 119, 344 |
| `CHANGELOG.md` | 1 | the `0.2.0` entry; history, untouched; `[Unreleased]` gains the breaking-change lines |
| six dated records under `docs/superpowers/` | 22 | `plans/2026-08-25-model-discovery.md`, `plans/2026-09-04-pekko-out.md`, `plans/2026-09-29-named-providers.md`, `specs/2026-08-28-principles-and-findings.md`, `specs/2026-09-25-locks-as-plumbing-design.md`, `specs/2026-09-25-spring-boot-autoconfiguration-design.md`, `specs/2026-09-29-named-providers-design.md`; dated records, untouched |

Counts: three main-source files, six test files (three of them renamed), one guide page. The
seven dated records and the changelog entry are history and stay as written.

`OpenAiRequests`, additionally: `OpenAiRequests.java` (7, renamed), `OpenAiInferenceProvider.java`
(3, the calls), `OpenAiRequestsTest.java` (9, renamed `OpenAiChatRequestsTest`); four dated
records under `docs/superpowers/` stay.

`Wire.OPENAI` and the `openai` wire value:

| file | refs | what |
|---|---|---|
| `nessy-spring-boot/autoconfigure/src/main/java/.../inference/Wire.java` | 1 | the constant |
| `.../inference/Preset.java` | 8 | every chat-shaped preset: `openai`, `xai`, `openrouter`, `nvidia`, `groq`, `mistral`, `lmstudio`, `ollama` |
| `.../inference/WireProviders.java` | 2 | `artifactId` (line 61) and `build` (line 88) arms, plus `adapterClassName` |
| `.../inference/ProviderCatalogueTest.java` | 17 | expected `ResolvedProvider`s and `ProviderSettings` |
| `.../inference/ResolvedProviderTest.java` | 1 | line 30 |
| `.../inference/ProviderSettingsTest.java` | 1 | line 30 |
| `.../inference/InferenceProvidersAutoConfigurationTest.java` | 1 | `nessy.providers.mine.wire=openai`, line 216 |
| `.../inference/InferenceReportTest.java` | 2 | the expected report line, line 76: `openai (openai, ...)` and `xai (openai, ...)` |
| `.../inference/PresetCandidatesLiveTest.java` | 1 | `.wire=openai`, line 323 |
| `docs/guides/providers.md` | 5 | `wire: openai` (227), the allowed values (233), the default vendor (235-236), the report line (260) |
| `CHANGELOG.md` | 1 | the `0.2.0` entry, line 56; history |

`ProviderCatalogue.java`'s `"openai".equals(preset.id())` (the `openai.base-url` override) is a
preset id, not a wire, and does not change. `InferenceReport` prints `wire().propertyValue()` and
does not change.

## 5. The Responses adapter

`OpenAiResponsesInferenceProvider` mirrors the chat adapter's shape exactly: the config builds it,
`infer` opens `client.responses().createStreaming(OpenAiResponsesRequests.toParams(request,
mapper))`, every event is handed to the SDK's `ResponseAccumulator` and narrated on the way, and
the result is read from what the accumulator holds. The reader of the chat class should be able
to read the Responses class without learning anything new except the wire.

### 5a. Stateless, and why

**Ruling (James, 2026-09-29):** every call sends `store: false` and the full context. Nessy's
event log is the single source of truth, and each of these depends on it: replay (the fold
rebuilds state from events), the summarisers (which rewrite what the model is shown), episodes,
turn policy (which counts what was spent), provider switching (an agent answered by OpenAI today
and Anthropic tomorrow) and zero-data-retention deployments. A conversation that lived on
OpenAI's side under a `previous_response_id` would be a second copy of the story that none of
those can see, and the first time the two disagreed there would be no way to say which was right.

So **`previous_response_id` is rejected, and so is `conversation`**: neither is a setting, neither
is reachable from the config. `store: false` is sent explicitly rather than left to the default,
because the API's default is `true` and a default is a thing that changes.

### 5b. Request mapping: the system prompt

The system prompt, with its ambient sections rendered exactly as `OpenAiChatRequests.system(...)`
renders them (the `<kind>` tags, sections omitted when empty), goes in **`instructions`**, the
request's top-level field. Chosen over a `developer` or `system` input item because:

- It is the field the API defines for the standing instruction; the input list is the
  conversation. Putting the prompt where the API puts it is the projection saying what it means.
- Nothing is lost by the choice. The one documented difference -- `instructions` does not carry
  over between responses chained by `previous_response_id`, while an input item does -- is moot
  under §5a, where nothing carries over and everything is sent every time.
- The mid-conversation explanatory lines the chat adapter sends as `system` messages ("The
  previous attempt to answer did not complete.", the withdrawn-message line) stay input items,
  role `system`, because they stand *in the conversation* at the point where a turn is missing.
  The SDK's `EasyInputMessage.Role` has `SYSTEM` and `DEVELOPER` (verified); `system` keeps the
  two projections reading alike, and the API maps it for reasoning models.

### 5c. Turns, calls and results as input items

`request.context()` projects to `ResponseCreateParams.Input.ofResponse(List<ResponseInputItem>)`
in the same order the chat projection uses -- summaries first, then each turn -- with these
translations, every one of them the chat adapter's decision restated on the new wire:

| turn-speak | chat wire | responses wire |
|---|---|---|
| a `Summary` | user message, `<summary from= through=>` tagged | `EasyInputMessage` role `user`, same text |
| `Turn.input()` | user message of the readable text | `EasyInputMessage` role `user` |
| `Exchange.request()`, in block order | one assistant message: content plus `tool_calls` | one input item **per block**, in the order stored: `Block.Commentary` becomes an `EasyInputMessage` role `assistant`; `Block.ToolCall` becomes `ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall{callId, name, arguments})`; `Block.Provider` tagged with this provider's vendor becomes `ResponseInputItem.ofReasoning(...)` (§5j); any other vendor's `Provider` block is dropped |
| each `ToolOutcome` | a `tool` message quoting `tool_call_id` | `ResponseInputItem.ofFunctionCallOutput(FunctionCallOutput{callId, output})`, the three outcomes flattened to the same three strings the chat adapter sends (`Succeeded` as the text, `Failed` as `Error: ...`, `Denied` as `This call was not run because it was not permitted: ...`) |
| `TurnResult.Answered` | assistant message | `EasyInputMessage` role `assistant` of the readable text |
| `TurnResult.Failed` | a `system` line | the same line, role `system` |
| `TurnResult.Refused` | question dropped, a `system` line in its place | the same |

**One item per block, in stored order, is not a convenience -- it is what the reasoning item
needs.** The API requires a reasoning item to precede the function call it led to, and
`Block.Provider`'s javadoc already says order is part of the payload and siblings must not be
dropped. `Exchange.request` is a `List.copyOf` of what the adapter returned (verified), and the
adapter returns the output items in the order they arrived, so replaying the list in order is
replaying the model's own output.

`ResponseFunctionToolCall.id()` (the `fc_...` item id) is `Optional` on input (verified) and is not
kept: `Block.ToolCall.id` is the `call_id`, the one a `function_call_output` must quote and the one
`CallId` already is on every other wire.

### 5d. Tools

Each `ToolOffer` becomes `Tool.ofFunction(FunctionTool{name, description, parameters, strict:
false})`, the parameters read from `JsonSchema.json()` into `FunctionTool.Parameters` through
`putAdditionalProperty` exactly as `toFunctionParameters` does for the chat wire (the Jackson 3 to
Jackson 2 bridge through `JsonValue.from(Object)`, uncached, for the reasons given there).

**`strict: false` is sent explicitly.** On Chat Completions, a function definition's `strict`
defaults to false and the chat adapter never sets it. The Responses API documents the default for
a function tool's `strict` as **true**, and strict mode rejects any schema whose properties are
not all `required` with `additionalProperties: false` -- which is not what `Schemas.of(inputType)`
produces for an optional field. Sending `false` keeps the schemas Nessy already generates working
unchanged; the live test (§9c) is where the documented default is measured. `strict` is
`Optional` in the SDK's builder (verified, `FunctionTool.kt` line 299), so sending it is a choice
and omitting it would be the API's.

### 5e. `ToolChoice`

The mapping is the chat adapter's, on the Responses vocabulary (`ToolChoiceOptions.NONE`, `AUTO`,
`REQUIRED`; `ToolChoiceFunction{name}`; all verified):

| `ToolChoice` | sent | reason |
|---|---|---|
| `Auto` | nothing | what the absent field already means; several compatible servers are happier without it |
| `None` | `tool_choice: "none"` | a ban, offers left in place |
| `Answer` | `tool_choice: "none"` | **emulated, as on the chat wire**: "none" is documented as "do not call a tool, generate a message", which is what answering now means, and the offers stay in the request so a cached prefix is not disturbed. Documented rather than measured until §9c; the fallback if a model answers empty under it is to send no tools at all, at the cost of the cache |
| `Any` | `tool_choice: "required"` | |
| `Named(name)` | `ToolChoiceFunction{name}` | |

Nothing is sent when there are no offers, as today.

### 5f. Structured output

`request.outputSchema()` becomes `text.format`:
`ResponseTextConfig.builder().format(ResponseFormatTextJsonSchemaConfig{name: "answer", schema,
strict: true})`. This is the same shape as the chat adapter's `response_format` (`name`, `schema`,
`strict`) under a different key; the schema is read into
`ResponseFormatTextJsonSchemaConfig.Schema` through `putAdditionalProperty` as `constrainAnswer`
does today. The name is a label the wire requires and nothing reads, so it stays the constant
`"answer"`. As on the chat wire, the constraint applies to the answer and says nothing about the
calls made on the way to it.

### 5g. Streaming: events into narration

Every event goes to the accumulator; three kinds are also narrated, best-effort, through the same
`InferenceNarrator` the chat adapter uses:

| event | narrated as |
|---|---|
| `response.output_text.delta` (`ResponseTextDeltaEvent.delta()`) | `text` |
| `response.reasoning_summary_text.delta` (`ResponseReasoningSummaryTextDeltaEvent.delta()`) | `thinking` |
| `response.reasoning_text.delta` (`ResponseReasoningTextDeltaEvent.delta()`) | `thinking` -- the raw reasoning text a non-OpenAI Responses server may stream, the way compatible chat servers send `reasoning_content` |

Empty deltas are skipped, as empty chunks are today. Function-call argument fragments
(`response.function_call_arguments.delta`) are not narrated, for the reason the chat adapter
gives: half a JSON argument is not something anybody can watch. Refusal deltas are not narrated;
the refusal arrives whole in the result.

**OpenAI proper streams reasoning summaries only when the request asks for them**, with
`reasoning: {summary: "auto"}` -- and the `reasoning` object is rejected with a 400 by a model that
does not reason. This adapter does not know which kind of model it was handed (the model travels
in `InferenceOptions`, by design), so **no `reasoning` object is sent** by this design. The
narration path for both reasoning delta kinds is built and tested by replay (§9a); on OpenAI it
lights the day the reasoning-effort follow-on (§8, §10) adds the setting that says what to send,
which is the same setting that will carry `summary`. Recorded as §12 (1).

### 5h. Accumulation, and what the SDK's accumulator actually is

The brief said "stream events -> the SDK's `ResponseAccumulator`", mirroring the chat adapter,
and that is the shape. What the accumulator does is narrower than its name, and the design has to
say so because the tolerance story (§6) rests on it. Read from `ResponseAccumulator.kt` in the
4.69.2 sources jar:

- It **folds nothing**. Every delta event is a no-op visitor arm. The only events it acts on are
  the three terminal ones, `response.completed`, `response.failed` and `response.incomplete`, each
  of which carries the **whole `Response`** -- output items, usage, status, error -- and the
  accumulator keeps that object.
- After a terminal event it ignores everything else: `if (response != null) return event`.
- An unknown event type reaches `Visitor.unknown`, which it overrides as a no-op.
- `response()` throws `IllegalStateException("Completed response is not yet received.")` if no
  terminal event arrived.

So the answer is read from the terminal event's `Response`, and the deltas exist for narration.
The reading, in `read(...)`, mirrors the chat adapter's three shapes decided on content:

- no terminal event: `Fault(Permanent("the stream ended before the answer was complete: ..."))`,
  the chat adapter's own words for the same case;
- `response.error()` present (a `response.failed`): `Fault` classified from the error's code and
  message, `Permanent` unless the code names a rate limit or a server-side failure;
- output items walked in order: a `message` item's `output_text` parts are the said text and a
  `refusal` part is `InferenceResult.Refusal(refusal)` -- checked first, as on the chat wire, because
  this wire reports it in a field of its own; every `function_call` item is a `Block.ToolCall(callId,
  name, arguments)`; every `reasoning` item with `encrypted_content` is a `Block.Provider` (§5j);
  every other item kind (web search, file search, code interpreter, MCP, shell, computer, image
  generation, ...) is dropped, because nothing here offered it -- the same reasoning the chat
  adapter gives for dropping custom tool calls, and §8's hosted-tools ruling in miniature;
- calls present: `Actions(Provider..., Commentary?, ToolCall...)` in arrival order, prose beside the
  calls kept as `Commentary`, whitespace not;
- no calls and text present: `Answer([Text])`;
- no calls and nothing said: `Fault(Permanent("model returned an empty answer (status=...,
  reason=...)"))`, naming `Response.status()` and `incompleteDetails().reason()` where present, so
  a reasoning model that spent its whole `max_output_tokens` thinking says so
  (`Reason.MAX_OUTPUT_TOKENS`, verified) exactly as `finish_reason=length` does today.

A mid-stream `error` event (`ResponseErrorEvent`, which the accumulator ignores) is remembered by
the adapter so that, if no terminal event follows, the fault names the error's message rather than
only "the stream ended".

### 5i. Usage

`Response.usage()` is `Optional` (verified). When present:

| `Usage` field | from |
|---|---|
| `model` | `Response.model()` |
| `inputTokens` | `input_tokens` -- already includes cached input, as `prompt_tokens` does, so nothing is summed |
| `outputTokens` | `output_tokens` |
| `cacheReadTokens` | `input_tokens_details.cached_tokens`, or null |
| `cacheWriteTokens` | `input_tokens_details.cache_write_tokens`, or null |
| `reasoningTokens` | `output_tokens_details.reasoning_tokens`, or null |

**The details are read through the SDK's `JsonField.asKnown()`, never the plain accessors.** The
SDK marks `inputTokensDetails`, `outputTokensDetails`, `cachedTokens`, `cacheWriteTokens` and
`reasoningTokens` as required (`checkRequired`, `ResponseUsage.kt` lines 297-301, 512-513, 697),
so `usage.inputTokensDetails()` throws `OpenAIInvalidDataException` on a server that omits the
detail object -- and the chat adapter's reason for nullable cache counts applies here with more
force: a compatible server that says nothing about caching has not said zero. `_inputTokensDetails()
.asKnown().flatMap(d -> d._cachedTokens().asKnown())` is the reading that cannot throw. Usage
absent is `Usage.unreported(model)`, as today.

### 5j. Reasoning items: round-trip within a turn

**Ruling (James, 2026-09-29):** the request carries `include: ["reasoning.encrypted_content"]`
(`ResponseIncludable.REASONING_ENCRYPTED_CONTENT`, verified) on every call, the encrypted items
travel in the transcript as `Block.Provider(vendor, payload)`, and they are replayed on the next
call of the same turn. This is OpenAI's documented path for `store: false`: the reasoning that led
to a function call has to be sent back with the call's result, or the model starts the next step
of the same turn without it.

**`Block.Provider` fits, and was built for this.** Its javadoc names the case in so many words --
"OpenAI's reasoning items are encrypted -- and a signature covers bytes" -- and its three rules
are exactly the three this adapter needs: text never a tree (the payload is stored as handed
over), tagged by vendor (only this adapter replays it; the chat adapter's `readable(...)` already
drops every `Provider` block, verified at `OpenAiRequests.java` line 383, so a transcript that
switches wires loses reasoning state and nothing else, which the javadoc says must be fine), and
order is part of the payload (§5c). It is legal as `ActionRequestContent` and `AnswerContent`
(verified), and this adapter uses the first.

The payload is the reasoning item's own fields written as one JSON object by the configured
`JsonMapper` -- `id`, `encrypted_content`, and `summary` as the list of `{type, text}` parts --
built back with `ResponseReasoningItem.builder().id(...).encryptedContent(...).summary(...)`
(verified). Writing the object with Nessy's mapper rather than the SDK's is safe here because the
signed bytes are the `encrypted_content` string itself, base64, which no JSON re-serialisation can
alter; the Anthropic adapter's `provider(Map)` does the same. The tag is `vendor()` -- the
provider's configured vendor, `openai` by default -- and `ours(vendor, payload)` on the way out
replays a block only when the tag equals this provider's own, as `AnthropicRequests.ours` does.

**Kept in `Actions`, dropped from `Answer`.** A reasoning item that arrives beside function calls
is replayed on the next call of the turn; one that arrives beside the final answer ends the turn
with it, and under this ruling nothing would ever send it again. An encrypted item is kilobytes of
base64, and the event log should not carry bytes with no reader. Whether earlier turns' reasoning
should also be replayed -- OpenAI accepts it and says it can help -- is §12 (2), because it wants a
measurement (does a model change between turns break it?) and a ruling on log size, not a guess.

### 5k. Error classification, shared

`classify(OpenAIException)` moves to `OpenAiFailures` unchanged: `RateLimitException`,
`InternalServerException` and `OpenAIRetryableException` are `Transient`; `OpenAIIoException` is
`Unknown`; everything else is `Permanent`; nothing is `Rejected`. The SDK's retry budget is spent
before any of these surfaces, on this wire as on the other. A `response.failed` terminal event is
the one classification the Responses wire adds (§5h): a `Response.error()` inside a 200 stream
rather than a thrown exception, classified by the same rule on its code.

## 6. Tolerance of deviating servers

The chat adapter already tolerates one measured deviation (usage on a chunk that still carries
choices -- Groq, Mistral). Non-OpenAI Responses servers will deviate in ways not yet measured, and
this design fixes **where** tolerance lives rather than enumerating quirks it has not seen:

1. **Trailing metadata after the terminal event costs nothing.** The accumulator ignores every
   event after it holds a `Response` (§5h), so a server that sends a usage event, a `[DONE]`-style
   trailer or a second `completed` after the first is already tolerated by construction.
2. **Unknown event types cost nothing.** They deserialise to the union's `_json` arm and reach a
   no-op `unknown` visitor (§5h). The adapter narrates only the three delta kinds it knows and
   asks nothing of the rest.
3. **Usage detail is read as unknown-until-present** (§5i), so a server that omits
   `input_tokens_details` reports its input and output counts rather than throwing.
4. **All reading happens in one place.** `read(Response)` is the single method that turns the
   terminal event into a result, and `infer` hands it the accumulator's response. If a measured
   server turns out never to send a terminal event -- ending the stream after
   `response.output_text.done`, say -- the fold that would rebuild a `Response` from the deltas is
   a second argument to that method and nothing else changes. That fold is **not built** until a
   server needs it: the chat adapter's `strippedOfEarlyUsage` was written the day Groq's shape was
   measured, and the same discipline holds here.

Perplexity is the first candidate for measurement (§9c), and its row in the live test is where the
first quirk, if any, gets its name.

## 7. The Boot side

### 7a. `Wire` and `WireProviders`

The enum change is §4a. `WireProviders` gains the arm: `artifactId` answers
`nessy-inference-openai` for both OpenAI wires; `adapterClassName` answers
`org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider` for `OPENAI_CHAT` and
`...OpenAiResponsesInferenceProvider` for `OPENAI_RESPONSES`; `build` has a `Responses` nested
class beside `OpenAi` (renamed `OpenAiChat`) with the same seven lines: key, base URL when set,
vendor, `TransportTimeouts.PROVIDER_TRANSPORT`, mapper when present.

### 7b. Presets

Every preset on `Wire.OPENAI` today moves to `Wire.OPENAI_CHAT`: `openai`, `xai`, `openrouter`,
`nvidia`, `groq`, `mistral`, `lmstudio`, `ollama`. Users of presets notice nothing: the wire is
inside the catalogue row and the report line changes from `(openai, ...)` to `(openai-chat, ...)`.

No new preset ships with this record. Perplexity becomes a preset only with a measured row
(named-providers §7d: "no preset ships with an unmeasured URL or vendor value"), and its
measurement is §9c.

### 7c. The `openai` preset's default wire: staged

**Ruling (James, 2026-09-29, leaning yes; the switch itself is the step after the live proof):**
the `openai` preset's default wire becomes `openai-responses` -- but only once the live tests pass
through the new adapter. Until then it stays `openai-chat`. In sequence terms (§10) the catalogue
row changes in step 4, alone, after step 3's measurement.

Anyone pointing the `openai` preset at a Chat Completions server through `openai.base-url` (LM
Studio, Ollama, a gateway -- chat-web's and watchman's `application.yml` did this before the
`lmstudio` preset existed, and the getting-started guide taught it) keeps working by overriding
the preset's wire, which every preset field already allows:

```yaml
nessy:
  providers:
    openai:
      wire: openai-chat
```

The `lmstudio` and `ollama` presets are the better route for a local server and the guide says so.
**No automatic wire choice from the base URL**: a rule that said "a non-OpenAI host means chat"
would be right until the first Responses-speaking gateway, and would be the alphabet problem in a
new coat -- rejected as magic.

### 7d. Custom providers and the report

A custom provider states `wire: openai-chat` or `wire: openai-responses`; `openai` is a binding
error listing the four values. The report line needs no code change; it prints
`propertyValue()`.

## 8. Not being done

Each of these was raised on 2026-09-29 and set aside, with the reason:

- **OpenAI hosted tools** -- web search, file search, code interpreter, remote MCP. They run where
  Nessy cannot approve, gate or record them: a hosted tool's call and result never pass through
  the fold, the approval policy or the event log. First-class support -- enabling a hosted tool per
  agent type and recording each call and result as events, so the audit trail stays whole -- is
  its own later design. **A passthrough was rejected**: letting an application add a `web_search`
  tool to the request would be a hole in the audit trail dressed as a feature. This adapter offers
  function tools only, and drops any hosted-tool output item it is sent (§5h).
- **Reasoning effort** -- `reasoning.effort` here, and the `reasoning.summary` that rides with it
  (§5g). **Queued as the immediate follow-on**: a vendor-neutral, per-agent-type setting on
  `InferenceConfig` that each adapter translates -- OpenAI `reasoning.effort`, Anthropic's thinking
  budget, Gemini's thinking budget -- is a new public concept and needs its own short design and
  James's yes. It follows this record directly (§10).
- **Background mode** (`background: true`) -- a response that completes on OpenAI's side while the
  client polls is server-side state by another name, and §5a rules it out.
- **Server-side conversation state** -- `previous_response_id`, `conversation`. §5a.
- **Image and file inputs** beyond what the chat adapter supports today, which is none: the block
  grammar has no image, and "when it grows one, `OpenAiRequests` is where it lands" holds for both
  projections.
- **Embedding providers** -- named embedding providers mirror the inference design and are the
  third item in the `0.3.0` sequence (§10); nothing here touches them.

## 9. Testing

House rules throughout: prose-style test names in the module's existing voice
(`plain_prose_is_an_answer`, `a_rate_limit_is_worth_another_attempt`), `@DisplayName` sentences,
**no mocking library**, every `assertThatThrownBy` lambda holding exactly one call that can throw
with construction outside it (S5778), and an emptiness assertion before any `allMatch` /
`noneMatch` over a collection. Everything in §9a and §9b passes with no API key and no network.

### 9a. Replay tests, mirroring the chat adapter's

The brief described the chat adapter's tests as running "against the SDK's fake transport". They
do not; `OpenAiInferenceProviderTest` builds **JDK dynamic proxies** (`Proxy.newProxyInstance`)
over `OpenAIClient`, `ChatService` and `ChatCompletionService`, answering `createStreaming` from a
list of chunks the test hands in and throwing `UnsupportedOperationException` for every other
method -- "not a mocking library, just `Proxy`". `OpenAiResponsesInferenceProviderTest` does the
same over `OpenAIClient` and `ResponseService`, answering `createStreaming(ResponseCreateParams,
RequestOptions)` with a `StreamResponse<ResponseStreamEvent>` over events the test builds. The
chunking helper's counterpart, `eventsOf(Response)`, cuts a finished `Response` into the events a
server would have streamed it as: `created`, an `output_item.added` per item, the text a few
characters at a time, argument deltas, and `completed` carrying the whole response last.

Nested groups, matching the chat test's:

- **Usage**: counts on the completed event are the result's; a server that does not count leaves
  the cost unknown; a server that omits `input_tokens_details` reports input and output and null
  cache counts (§5i, the one that would throw on the plain accessors).
- **Narration**: text is narrated piece by piece and answered whole; a reasoning summary delta is
  narrated as thinking; a raw reasoning text delta is narrated as thinking; function-call argument
  fragments are not narrated; a stream of nothing is a fault.
- **Reading**: an empty answer is a fault naming the status and reason; plain prose is an answer;
  calls are a request for actions carrying the arguments the model wrote; prose beside calls is
  commentary and whitespace is not; a refusal part is a refusal; a `response.failed` is a fault
  classified from its error; a hosted-tool output item is dropped leaving its siblings in order;
  the model asked for is the one in the options.
- **Reasoning items**: an encrypted reasoning item beside calls comes back as a `Provider` block
  tagged with the vendor, in its arrival position; one beside a final answer is not kept; a
  `Provider` block with this vendor's tag is replayed as a reasoning item preceding the call it
  arrived with; another vendor's tag is dropped; an `x_ai`-tagged provider does not replay
  `openai`-tagged items.
- **Deviating servers**: events after `completed` are ignored; an unknown event type is ignored; a
  mid-stream `error` with no terminal event is a fault naming the error.
- **Construction**: the six config tests, on `OpenAiResponsesProviderConfig`.
- **Classification**: the thirteen classification tests, against the shared `OpenAiFailures`,
  written once and run through the Responses provider's `infer`.

`OpenAiResponsesRequestsTest` mirrors `OpenAiChatRequestsTest`'s twenty-one: the prompt leads as
`instructions` and carries ambient sections; the model and ceiling come from the options
(`max_output_tokens` omitted when none was asked for); a turn becomes a user item and the assistant
item that followed it; a failed turn is explained and a refused one says so in its place; an
exchange becomes one item per block in stored order followed by one output per call; the three
outcomes flatten to the three strings; a tool becomes a function tool carrying its schema with
`strict: false`; tool choice by arm; the schema becomes `text.format`; `store` is false and
`include` names encrypted reasoning on every request; `previous_response_id` is never set.

### 9b. Boot

`ProviderCatalogueTest`, `ResolvedProviderTest`, `ProviderSettingsTest` and
`InferenceProvidersAutoConfigurationTest` move to `Wire.OPENAI_CHAT`; `InferenceReportTest`'s
expected line reads `(openai-chat, ...)`. Added: `nessy.providers.mine.wire=openai-responses`
registers a provider that is an `OpenAiResponsesInferenceProvider` under the observation wrapper;
`nessy.providers.mine.wire=openai` fails to start naming the four allowed values;
`nessy.providers.openai.wire=openai-chat` alongside `openai.api-key` builds the chat class. When
step 4 lands, the catalogue test's `openai` row expects `OPENAI_RESPONSES`, and
`openai.api-key` with `openai.base-url=http://localhost:1234/v1` still builds one provider.

### 9c. Live

`OpenAiResponsesLiveTest` (`@Tag("live")`, skipped without `OPENAI_API_KEY`) carries the chat live
test's nine cases through the new adapter -- a real answer, narration in pieces, a tool call with
arguments that fit the schema, two tools, `Any`, `Answer` producing prose with the tools still on
offer, `None`, and the two structured-output cases -- plus one this wire adds: on a reasoning model
(`NESSY_LIVE_REASONING_MODEL`, defaulting to `gpt-6-sol`), a tool call comes back with a `Provider`
block, the result is sent back with it, and the second call answers. That case is the measurement
behind §1 and the proof §7c waits for.

`PresetCandidatesLiveTest`'s `Candidate` gains a wire field (today line 323 hard-codes
`.wire=openai`), and two rows join the twelve:

| id | base URL | wire | measures |
|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | `openai-responses` | the tool call, the forced answer and the usage through the new wire, beside the existing chat row |
| `perplexity` | the host the 403 named; `PERPLEXITY_API_KEY` | `openai-responses` | whether the Agent API fits this adapter at all; a FAIL row with the vendor's message is a result |

The results file `target/preset-measurements.md` says which shape each vendor answered, and the
Perplexity row decides whether a preset follows.

## 10. Sequencing

Five steps, each a reviewable commit, each green under `./mvnw -q clean verify` with no key and
no network before the next starts:

1. **The rename** (§4): wires `OPENAI_CHAT` / `OPENAI_RESPONSES` (the second unmapped in
   `WireProviders` for one commit, failing loudly if selected), classes
   `OpenAiChatInferenceProvider`, `OpenAiChatProviderConfig`, `OpenAiChatRequests`, the three test
   files, the Boot tests, the guide, the changelog's `[Unreleased]` breaking-change lines. No
   behaviour change.
2. **The Responses adapter** (§5, §6): `OpenAiResponsesProviderConfig`,
   `OpenAiResponsesRequests`, `OpenAiResponsesInferenceProvider`, the shared client helper and
   `OpenAiFailures`, the replay tests of §9a.
3. **Boot wiring and live measurement** (§7a, §7b, §9b, §9c): the `WireProviders` arm, the Boot
   tests, `OpenAiResponsesLiveTest`, the two candidate rows; run live, results into the
   measurements file.
4. **The `openai` preset's default** (§7c): one catalogue row, its test, the changelog line --
   after step 3's live pass, not before.
5. **Docs**: `docs/guides/providers.md` (the wires, the `openai` preset's override, the compatible
   universe table's note on which shape each vendor speaks, Perplexity if it measured), the
   getting-started line, the README's module table, describing what is (`docs-describe-what-is`).

**The `0.3.0` release plan, for orientation (James, 2026-09-29).** `0.3.0` is cut after three
things, in this order: (1) this Responses adapter; (2) reasoning effort as a vendor-neutral,
per-agent-type setting, which follows immediately with its own short design and is what lights
§5g's reasoning narration on OpenAI; (3) named embedding providers, mirroring the inference design
-- preset catalogue, `ProviderId` registry, application beans joining, the startup report. The
second and third are out of this record's scope and are named here only so the sequence is
written down once.

## 11. Design authority

Listed by name, as the rule requires. "Agreed" means discussed with James on 2026-09-29;
"proposed" means it appears first in this record and needs his yes before it lands.

| concept | where | status | § |
|---|---|---|---|
| `OpenAiChatInferenceProvider` -- the rename of `OpenAiInferenceProvider` | public type, renamed | agreed | 4b |
| `OpenAiResponsesInferenceProvider` | public type | agreed | 4b, 5 |
| `OpenAiChatProviderConfig` -- the rename of `OpenAiProviderConfig` | public type, renamed | agreed in principle ("if the config is per-class"); the finding that it is per-class is this record's | 4b |
| `OpenAiResponsesProviderConfig` | public type | agreed in principle, same clause | 4b |
| `OpenAiChatRequests` -- the rename of `OpenAiRequests` -- and `OpenAiResponsesRequests` | public types | **proposed** | 4b |
| wire values `openai-chat` and `openai-responses`; the removal of `openai` with no alias | Boot property values | agreed | 4a |
| `store: false`, `include: ["reasoning.encrypted_content"]`, `previous_response_id` and `conversation` unreachable | request contract | agreed | 5a, 5j |
| the system prompt in `instructions` | request contract | **proposed** | 5b |
| `strict: false` on every function tool | request contract | **proposed** | 5d |
| reasoning items kept in `Actions` and dropped from `Answer` | transcript contract | **proposed** | 5j |
| no `reasoning` object sent until the effort follow-on | request contract | **proposed** | 5g |
| the `openai` preset's default wire moving to `openai-responses` after the live pass | Boot catalogue | agreed, leaning yes; the switch is step 4 | 7c |
| the words **shape** and **reasoning item** as §3 defines them | design vocabulary | proposed | 3 |

Not new, and needing no yes: the package-private client helper and `OpenAiFailures`, the `Wire`
enum's constants (package-private), the renamed tests, the live rows, the report's wording.

## 12. Open, for James

1. **Reasoning narration on OpenAI proper is dark until the effort follow-on.** The brief lists
   "narration of text and reasoning summaries" in scope. The narration path is built and
   replay-tested (§5g, §9a), but OpenAI emits summary deltas only when the request carries
   `reasoning: {summary: ...}`, and that object is a 400 on a model that does not reason. Sending
   it blind would break `gpt-4.1-mini` under the same provider that serves `gpt-6-sol`. Options:
   accept the gap for the weeks between (1) and (2) of the `0.3.0` sequence, which this record
   does; or add a `reasoningSummary(boolean)` setter on `OpenAiResponsesProviderConfig` now, which
   is a per-provider knob for a per-model fact and would be superseded by the follow-on.
2. **Replay reasoning items across turns?** §5j replays within a turn only and drops the item
   that arrives with a final answer. OpenAI accepts earlier turns' encrypted items and documents
   that they can help. Replaying them means keeping every item in the log (kilobytes each, one per
   inference call on a reasoning model) and measuring what happens when the agent type's model
   changes between turns. Recommendation: measure in step 3 and decide then; the projection change
   is one arm.
3. **Perplexity's fit is unmeasured, including its base URL.** The 403 said "Use /v1/responses"
   and nothing about the host; the row in §9c is written with a placeholder for what the vendor's
   docs say, and the measurement decides whether a `perplexity` preset follows or the guide says
   "not this adapter".
4. **`strict` on function tools.** §5d sends `false` on the strength of the documented default
   being `true`. If the live test shows the API accepts Nessy's schemas under strict mode, sending
   `true` buys constrained arguments for free and the design flips; if it shows `false` is
   necessary, the line stays. Either way the measurement is recorded.
5. **Found in the code, at odds with the brief.** (a) The brief said the chat adapter's tests run
   "against the SDK's fake transport"; they use JDK proxies over the service interfaces (§9a), and
   the new tests do the same. (b) The brief listed `OpenAiInferenceProvider`, `OpenAiRequests` and
   `OpenAiProviderConfig` as the adapter; `OpenAiRequests` is public and was not named in the
   rename ruling, so its rename is proposed in §11 rather than assumed. (c) `git tag` in this
   checkout lists `0.1.0` and `0.1.1` only, while `CHANGELOG.md` records `0.2.0` on 2026-09-29 and
   the reactor is at `0.3.0-SNAPSHOT`; "shipped in `0.2.0`" in §4a is the changelog's word and the
   tag may simply not be fetched here. (d) The SDK's `ResponseAccumulator` folds nothing (§5h);
   the brief's "stream events -> the SDK's `ResponseAccumulator`" holds as a shape, but the
   tolerance story would be wrong if it were read as a fold, so §6 says what it really is.
6. **`ResponseUsage.InputTokensDetails` requires `cache_write_tokens` in this SDK** (`ResponseUsage.kt`
   line 512), a field OpenAI's own usage object does not document today. Reading through
   `asKnown()` (§5i) makes the question moot for the adapter; noting it because it is the kind of
   SDK-versus-API drift the next SDK bump could change in either direction.
