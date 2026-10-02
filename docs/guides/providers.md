# Providers

An `InferenceProvider` is the adapter between the engine and one vendor's
API. It is an application singleton holding the SDK client, credentials and
transport, and it does one thing:

```java
public interface InferenceProvider {
  InferenceResult infer(InferenceRequest request, InferenceNarrator narrator);
}
```

The engine builds the request (the system prompt, then the context's strata:
summaries, the tail of turns, memory, state, the active turn and ambient
blocks; the tools on offer, the model and token cap) and the adapter
turns it into the vendor's wire shape, narrates the deltas as they stream,
and hands back one of five results: an `Answer`, `Actions` the model wants
taken, a `Refusal`, a `Truncated` reply the vendor cut off at the output
limit (see [Truncated replies](#truncated-replies)), or a `Fault` with a
`Failure` that says whether retrying could help. Whichever it is, the result carries a `Usage`; the engine
records it and puts it on the call's span. See [Usage](#usage) below for
what it holds.

Four adapter modules ship: `nessy-inference-anthropic` on Anthropic's Java
SDK, `nessy-inference-openai` on OpenAI's, `nessy-inference-gemini` on
Google's java-genai SDK, and `nessy-inference-bedrock` on the AWS SDK's
Converse API. The OpenAI module holds two adapters, one per shape OpenAI
defined: `OpenAiChatInferenceProvider` for Chat Completions, which every
service in [the OpenAI-compatible universe](#the-openai-compatible-universe)
also speaks, and `OpenAiResponsesInferenceProvider` for the
[Responses API](#openai-responses).

## Usage

`Usage` is a model name plus five nullable token counts: `inputTokens`,
`outputTokens`, `cacheReadTokens`, `cacheWriteTokens` and
`reasoningTokens`. A null count means the provider did not say, not that
it was zero — a reply that genuinely cost nothing and a reply nobody
measured are different facts, and only a nullable count can tell them
apart. `Usage.unreported()` is nothing counted and no model named, for an
effect that never reached a vendor; `Usage.unreported(String model)` is a
call that was really made but came back with no count at all, on a model
worth naming.

`inputTokens` is normalised to mean ALL input processed, cache reads
included, because the vendors disagree about what their own input count
covers. OpenAI's `prompt_tokens` and Gemini's `promptTokenCount` already
include what was read from cache, so an adapter for either takes the
number as given. Anthropic's `input_tokens` and Bedrock's `inputTokens`
exclude it, so those two adapters derive the normalised count by summing
the vendor's input figure with its cache-read and cache-write counts.
Deriving is safe only there: a count either vendor leaves out means
nothing was cached, so the sum is exact, whereas subtracting to find an
uncached figure from OpenAI or Gemini would be unsafe, since a compatible
server such as LM Studio omits cache detail entirely and the subtraction
would discard a real count.

`cacheReadTokens` and `cacheWriteTokens` are a breakdown of `inputTokens`,
not an addition to it — the three token prices differ by an order of
magnitude, so a single input count cannot be turned into money.
`reasoningTokens` is likewise a breakdown of `outputTokens`, kept because
it answers whether an expensive turn thought a lot rather than produced a
lot, not because it changes billing.

`totalTokens()` covers `inputTokens` plus `outputTokens` only. Cache and
reasoning counts are already inside one of those two numbers, so adding
them again would double-count.

## Where each stratum goes

The context a request carries has six strata, in a fixed order (see
[Memory](../concepts/memory.md)). Every adapter places them the same way,
because a provider caches a request's leading text and the strata are
ordered by how often each changes:

- **Instructions** are the system field and nothing else. It is fixed for
  the life of the harness, so the cache that starts with it survives.
- **Summaries and the tail** follow, unchanged from call to call. A summary
  is a user-role message of text tagged
  `<summary from="12" through="31">`, standing where the turns it replaces
  once stood.
- **Memory and state** are text at the head of the active turn's first
  message, ahead of the question, tagged `<memory kind="...">` and
  `<state kind="...">`. Memory comes first, then state. Once the turn has
  finished and moved into the tail, it is sent without them.
- **Ambient** is text at the very end of the request, one section per
  source, tagged by its kind (`<clock>`, `<plan>`). It is last because it
  can change on every call, and anything after it would be re-read every
  time. A section with blank text is left out.

| Provider | Instructions | Ambient joins |
|---|---|---|
| Anthropic | the `system` field, one text block | the last message's blocks when it is a user message, otherwise a user message of its own |
| OpenAI Chat Completions | the system message | the question's message while the request ends on the question, otherwise a user message of its own |
| OpenAI Responses | `instructions` | the same as Chat Completions, as input items |
| Gemini | `systemInstruction` | the last content when it is a user content of text alone, otherwise a user content of its own |
| Bedrock | the system list | the last user message, otherwise a user message of its own |

Where Anthropic's cache markers go, when caching is on: the system prompt, the
tool list, the last message before the ambient text is added, and the user
message before that. The ambient text is appended after the markers are
chosen, so none sits on it and the cached prefix never contains it.

**Thinking and background.** Anthropic binds a replayed thinking block to
everything before it. With any ambient background present, the message that
ends one request is sent on the next call without it, so the text ahead of
the reasoning differs and the vendor drops the replayed thinking blocks on
later calls, the active turn's own included. Memory and state do the same to
a turn once it is finished, since the first message that carried them is
sent without them from then on. The adapter's `drop_block` setting makes
that a drop rather than a refusal; a block whose prefix is intact is kept.
Measured on Anthropic, with background in the messages every replayed
thinking block was dropped (193 of 193), and none were with no background
present. An agent whose work depends on reasoning carried from call to call
pays for ambient content with that reasoning.

## Truncated replies

`InferenceResult.Truncated` is a reply the vendor stopped at the
output-token limit. It carries the blocks written before it stopped, and at
least one of them is text. It is not an answer: the text may end
mid-sentence, and nothing in the result says what was left unsaid. A caller
that keeps one must decide to, which is why it is an arm of its own and not a
flag on `Answer`: every `switch` over `InferenceResult` has to say what it
does with a reply that is not whole.

Each adapter reads the cut-off from the vendor's own field:

| Adapter | Field | Value taken as a cut-off |
|---|---|---|
| Anthropic | `stop_reason` | `max_tokens` |
| OpenAI Chat Completions | `finish_reason` | `length` |
| OpenAI Responses | `status` and `incomplete_details.reason` | `incomplete` and `max_output_tokens` |
| Gemini | `finishReason` | `MAX_TOKENS`; `MALFORMED_FUNCTION_CALL` for a tool call |
| Bedrock Converse | `stopReason` | `max_tokens` |

What the cut-off result is depends on what the reply held when it stopped:

- **Text.** The result is `Truncated`.
- **A tool call.** The result is a `Fault` with a `Permanent` failure, never
  `Actions`. A call cut off inside its arguments is not trusted. Measured on
  2026-10-02, the non-streamed Anthropic Messages API returns such a call
  with `input: {}`. On the streamed API, which the adapter uses, the
  arguments arrive as a JSON fragment and the SDK's `MessageAccumulator`
  substitutes an empty object when it does not parse. Either way the adapter
  sees `{}`, which parses and would run.
  Gemini reports a call cut off at the limit as `MALFORMED_FUNCTION_CALL`,
  with no function call and no text in the reply (measured on 2026-10-02 on
  `gemini-3.1-pro-preview`), so the Gemini adapter returns the `Fault` for that
  finish reason whatever the reply holds, and its message says the call was
  malformed and was not run. `gemini-3.6-flash` instead ended on `MAX_TOKENS`
  with no function call and no text (measured the same day), which is the
  empty-answer `Fault` naming that finish reason.
  On Bedrock, Nova Lite's cut-off call never reached the adapter as a reply:
  the service rejected it with a 424, "Model produced invalid sequence as part
  of ToolUse" (measured the same day), which the adapter reports as a
  `Permanent` fault carrying that message.
- **Nothing but reasoning.** The result is the empty-answer `Fault`, naming
  the stop reason. There is no text to keep.

On the OpenAI chat wire only a reported `length` is trusted. Compatible
servers report the finish reason inconsistently, so an absent one, `stop`
for a reply that was cut, or any reason the adapter does not know is read as if the reply were whole: a reply with
text is an `Answer`, and tool calls are `Actions`.

### What the engine does with one

In an agent's turn the engine delivers a `Truncated` reply as the turn's
answer. It logs a WARN naming the agent, and the call's span reports `length`
as its finish reason. Nothing in the agent's recorded history marks the reply
as partial; the WARN and the span are where it is told apart.

The chapter summariser does not accept one. `ProseSummarizer` throws when the
summary is cut off at the output limit, the chapter stays unsummarised, and
the keeper stops that pass. It tries again when a later turn ends, and the
chapters behind it wait. An agent type's `maxTokens` therefore has to leave
room for a chapter's summary: one that never fits is attempted, and cut off
again, at every turn's end. There are two ways to make a summary fit: raise
the agent type's `maxTokens`, which also raises the ceiling on its answers, or
close chapters sooner, with a smaller `ChapterPolicy.every(n)` or a lower
`maxChapterLength`, so each summary has less to cover.

While a chapter's summary does not fit, that chapter and every chapter after
it stay unsummarised. The context holds only the newest `maxTail` completed
turns after the last written summary, so once more than `maxTail` turns have
completed since then, the oldest of them are sent neither as a summary nor in
the tail.

## Naming providers

A factory holds providers by name: each is registered under a `ProviderId`,
and an agent type says which one it wants alongside its model. Both are
required, one way or the other — an agent type that names neither and a
factory with no default fail when the harness is built.

```java
DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(new InMemoryDirectBackend(codecs))
        .provider(ProviderId.of("anthropic"), AnthropicInferenceProvider.fromEnv())
        .provider(ProviderId.of("openai"), OpenAiChatInferenceProvider.fromEnv()));

DirectHarness<String, String> triage = factory.create(
        new AgentType("triage"),
        config -> config
                .systemPrompt(triagePrompt)
                .inference(in -> in.provider("anthropic").model("claude-haiku-4-5").maxTokens(512)));

DirectHarness<String, String> review = factory.create(
        new AgentType("review"),
        config -> config
                .systemPrompt(reviewPrompt)
                .inference(in -> in.provider("openai").model("gpt-5.1")));
```

`maxTokens` is per harness for a reason: it is how you make a model give a
short answer. A timeout and a retry policy for the call live beside it.

A factory with one provider can set it as the default for every agent type
that names none, with the same `ProviderId` and an `InferenceOptions`:

```java
DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(new InMemoryDirectBackend(codecs))
        .provider(ProviderId.of("anthropic"), AnthropicInferenceProvider.fromEnv())
        .inference(ProviderId.of("anthropic"), InferenceOptions.of("claude-haiku-4-5")));

DirectHarness<String, String> triage = factory.create(
        new AgentType("triage"),
        config -> config.systemPrompt(triagePrompt));
```

Settings a vendor has and the neutral API does not name -- OpenAI's
reasoning effort, Anthropic's thinking budget, Gemini's thinking config --
are [vendor properties](#vendor-properties), set per agent type, so two agent
types that want the same provider configured differently share one provider.

## Vendor properties

An agent type carries named properties that the adapter answering it reads.
Each adapter declares the properties it supports as typed constants, and code
sets them with a value of the property's type:

```java
config.inference(in -> in
        .provider("openai")
        .model("gpt-6-sol")
        .property(OpenAiProperties.REASONING_EFFORT, OpenAiReasoningEffort.HIGH)
        .property(AnthropicProperties.THINKING_BUDGET, 8192));
```

The same settings in YAML, where every value is text:

```yaml
nessy:
  providers:
    openai:
      properties:
        openai.reasoning.effort: high
```

or by name in code, `.property("openai.reasoning.effort", "high")`. A typed
property is stored as the text its constant writes it as, so the two forms
are one map entry. `InferenceConfig`, `EmbedderConfig` and each provider
config take both.

Each adapter owns a prefix -- `openai.` (both OpenAI adapters, whatever
vendor they report), `anthropic.`, `gemini.`, `bedrock.` -- and supports
exactly the names listed for it below. Properties are fixed when the harness
is built, sent with every request, and never written to the event log.

- **A supported name is parsed** into its constant's type, and a value that
  does not parse fails the build naming the property and the value
  (`property 'anthropic.thinking.budget_tokens' must be an integer, was
  'lots'`). A property with a fixed set of values is an enum, and a value
  outside it fails the build listing the accepted values (`property
  'openai.reasoning.effort' must be one of [NONE, MINIMAL, LOW, MEDIUM, HIGH,
  XHIGH, MAX], was 'extreme'`). An enum's text is the constant's name, matched
  ignoring case: `high` and `HIGH` both work. The enums are the value sets of the pinned
  vendor SDKs, so a value a vendor adds later needs a Nessy release before it
  can be set. A supported name the wire cannot carry
  (`openai.reasoning.summary` on the chat wire, `openai.tools.strict=false` on
  the Responses wire, `openai.service_tier=ultrafast` on the chat wire) also
  fails the build.
- **Any other name under the adapter's own prefix is ignored.** It is not
  sent, it fails nothing, and the request goes out without it. The adapter
  logs a warning naming the property and the names it supports, once per
  name, when the property is first checked: when the provider is built for a
  provider-level property, and when the harness is built for an agent type's.
  Never once per request. `openai.max_completion_tokens` and `openai.seed`
  are unsupported names, so `openai.seed=42` is a warning and nothing more.
- **A name under another adapter's prefix** is left for that adapter and
  logged at `DEBUG`: one agent type may carry settings for several vendors,
  and the agent type above runs on either provider, each reading its own.
- **A name with no prefix at all** is refused when the harness is built, since
  it can belong to no adapter.

A provider carries properties of its own, set on its config
(`OpenAiChatInferenceProvider.of(c -> c.apiKey(key).property("openai.tools.strict", "true"))`)
or under Boot with `nessy.providers.<id>.properties.*`. An agent type's
property overrides the provider's of the same name. A provider's property
under another adapter's prefix fails when the provider is built.

### OpenAI

`OpenAiProperties` holds the constants; the warning lists the names the
adapter supports.

| name | constant | accepts | chat wire | Responses wire |
|---|---|---|---|---|
| `openai.reasoning.effort` | `REASONING_EFFORT` | `OpenAiReasoningEffort`: `NONE` `MINIMAL` `LOW` `MEDIUM` `HIGH` `XHIGH` `MAX` | `reasoning_effort` | `reasoning.effort` |
| `openai.reasoning.summary` | `REASONING_SUMMARY` | `OpenAiReasoningSummary`: `AUTO` `CONCISE` `DETAILED` | refused: the wire has no summary | `reasoning.summary`, narrated as thinking |
| `openai.tools.strict` | `TOOLS_STRICT` | `true` or `false` | `true` sends every function tool strict over a rewritten schema | sent strict regardless; `false` is refused |
| `openai.service_tier` | `SERVICE_TIER` | `OpenAiServiceTier`: `AUTO` `DEFAULT` `FLEX` `SCALE` `PRIORITY` `FAST` `ULTRAFAST` | `service_tier`; `ultrafast` is refused | `service_tier` |

The Responses wire's `reasoning` object is sent only when one of the two
reasoning names is set: it is a 400 on a model that does not reason.

Under `openai.tools.strict=true` a tool whose schema strict mode cannot
express (a sealed type's `oneOf`, a map) goes out as generated with
`strict: false`, and the adapter logs a warning naming the tool and the
keyword; the other tools stay strict.

### Anthropic

`AnthropicProperties` holds the constants.

| name | constant | accepts | lands in |
|---|---|---|---|
| `anthropic.thinking.type` | `THINKING_TYPE` | `AnthropicThinkingType`: `ENABLED` `DISABLED` `ADAPTIVE` `BETWEEN_TOOLS` | `enabled` needs a budget; `adaptive`; `disabled` sends no thinking field; `between_tools` is sent as named |
| `anthropic.thinking.budget_tokens` | `THINKING_BUDGET` | an integer | the thinking budget; alone, it turns thinking on. Must be below the agent type's `maxTokens` |
| `anthropic.cache_control.ttl` | `CACHE_TTL` | `AnthropicCacheTtl`: `FIVE_MINUTES` `ONE_HOUR` | the cache markers on the system prompt and the tools |
| `anthropic.service_tier` | `SERVICE_TIER` | `AnthropicServiceTier`: `AUTO` `STANDARD_ONLY` | `service_tier` |

`anthropic.thinking.type=enabled` without a budget is refused, and so is a
budget that is not below the agent type's `maxTokens`, at harness build.

### Gemini

`GeminiProperties` holds the constants.

| name | constant | accepts | lands in |
|---|---|---|---|
| `gemini.generationConfig.thinkingConfig.thinkingBudget` | `THINKING_BUDGET` | an integer | the SDK's `ThinkingConfig` |
| `gemini.generationConfig.thinkingConfig.includeThoughts` | `INCLUDE_THOUGHTS` | `true` or `false` | the same; thought summaries are then narrated as thinking |
| `gemini.generationConfig.thinkingConfig.thinkingLevel` | `THINKING_LEVEL` | `GeminiThinkingLevel`: `MINIMAL` `LOW` `MEDIUM` `HIGH` | the same |

Gemini counts thinking tokens against the output ceiling, so an agent type's
`maxTokens` has to leave room for the thinking as well as the answer: a model
that thinks by default can spend a small ceiling before it writes a word. With
`includeThoughts`, a thought summary is narrated when the model returns one;
on a prompt with little to work out it may think without returning any.

### Bedrock

`BedrockProperties` holds the constants.

| name | constant | accepts | lands in |
|---|---|---|---|
| `bedrock.inferenceConfig.temperature` | `TEMPERATURE` | a finite number (`Float`) | Converse's typed inference config |
| `bedrock.inferenceConfig.topP` | `TOP_P` | a finite number (`Float`) | the same |

Every other `bedrock.` name is unsupported, Claude's extended thinking on
Bedrock (`bedrock.thinking.*`) included.

### Embedding adapters

No embedding adapter supports a vendor property yet; the rules for the names
they accept and ignore are under [Embedder properties](#embedder-properties).

## Building a provider

Each adapter is built the same way, a static `of(customizer)` over a
config, never a public builder:

```java
InferenceProvider anthropic = AnthropicInferenceProvider.of(c -> c.apiKey(key));
InferenceProvider openai = OpenAiChatInferenceProvider.of(c -> c.apiKey(key));
InferenceProvider responses = OpenAiResponsesInferenceProvider.of(c -> c.apiKey(key));
InferenceProvider gemini = GeminiInferenceProvider.of(c -> c.apiKey(key));
InferenceProvider bedrock = BedrockInferenceProvider.of(c -> c.region(Region.US_EAST_1));
```

Each also has `fromEnv()`, which delegates to the SDK's own reading of the
environment: `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` and
`ANTHROPIC_BASE_URL` for one, `OPENAI_API_KEY`, `OPENAI_BASE_URL` and
`OPENAI_ORG_ID` for the other. Anything set explicitly on the config wins
over the environment:

```java
InferenceProvider provider = AnthropicInferenceProvider.of(c -> c
        .fromEnv()
        .baseUrl("http://127.0.0.1:1234"));
```

`client(...)` hands in a fully built SDK client, which the provider then
never closes; `mapper(...)` supplies the `JsonMapper` used for tool schemas
and arguments.

### Anthropic features

Thinking and prompt caching are vendor properties, set on the provider as a
default for every agent type it serves:

```java
InferenceProvider provider = AnthropicInferenceProvider.of(c -> c
        .fromEnv()
        .property(AnthropicProperties.THINKING_TYPE, AnthropicThinkingType.ENABLED)
        .property(AnthropicProperties.THINKING_BUDGET, 4096)
        .property(AnthropicProperties.CACHE_TTL, AnthropicCacheTtl.FIVE_MINUTES));
```

```yaml
nessy:
  providers:
    anthropic:
      properties:
        anthropic.thinking.type: enabled
        anthropic.thinking.budget_tokens: "4096"
        anthropic.cache_control.ttl: FIVE_MINUTES
```

The defaults, when a property is absent:

- No thinking field is sent unless `anthropic.thinking.type` or
  `anthropic.thinking.budget_tokens` is set; a budget alone means `enabled`.
  With no field the model's own default applies, and Sonnet 5.5 thinks.
- `between_tools` turns off the thinking before a response: the model does
  not think before it responds, and the short updates it writes between tool
  calls come back as thinking blocks. Sonnet 5.5 refuses `disabled` and names
  this mode as its way to turn thinking off. The request replays no earlier
  thinking, like any other that does not think.
- `enabled` without a budget sends 1024 tokens. The reasoning is spent out of
  each call's `maxTokens`, which must exceed the budget or the request is
  refused before the call.
- `adaptive` sends no budget; `disabled` sends nothing.
- With thinking on, the adapter also sends
  `thinking.block_binding.prefix_mismatch_behavior: drop_block` and the
  `anthropic-beta: thinking-binding-controls-2026-08-01` header, beside any
  betas the client already sends. Anthropic binds a thinking block to the
  system prompt, tools and messages before it; with this setting it drops
  thinking whose prefix changed in place of rejecting the request, and the
  adapter logs how many blocks were dropped at `DEBUG`. With ambient
  background present this is every replayed block on later calls; see
  [Where each stratum goes](#where-each-stratum-goes). A gateway or proxy
  reached through `baseUrl` receives both. A request that does not think
  replays no thinking.
- Prompt caching is off unless `anthropic.cache_control.ttl` is set
  (`FIVE_MINUTES` or `ONE_HOUR`); it marks the system prompt, the tool list
  and two messages of the conversation as cacheable, none of them the
  ambient text.
- A request that is a one-off (`InferenceRequest.asOneOff()`) is marked
  nowhere, whatever the property says. Nothing it sends is sent again, so a
  cache write would be paid for and never read. The engine's summariser sends
  one-offs.

The constants are `THINKING_TYPE`, `THINKING_BUDGET` and `CACHE_TTL` on
`AnthropicProperties`. An agent type's property overrides the provider's, so
one provider with thinking enabled serves an agent type that asks for
`anthropic.thinking.budget_tokens=16000` and one that asks for
`anthropic.thinking.type=disabled`.

### Empty answers

Both adapters treat an answer with no content as a `Fault` with a
`Permanent` failure naming the finish reason, rather than a turn that ended
in silence. The usual cause is a thinking model that spent the whole token
cap on reasoning; raise `maxTokens` or turn the reasoning off at the
provider. A reply cut off with text in it is a `Truncated`, not this fault;
see [Truncated replies](#truncated-replies).

## Boot auto-configuration

`nessy-spring-boot-autoconfigure` turns `nessy.providers.<id>` into a
registry of `InferenceProvider` beans, one per id lit by a preset or a
custom provider, named by that id — alongside every `InferenceProvider`
bean the application declares itself.

### Presets

A preset is a catalogue entry the starter already knows the wire for, and
for four of them the base URL too. It becomes a provider once its
*ingredient* — a key, or an explicit `enabled` — is supplied:

| id | wire | base URL | vendor | ingredient |
|---|---|---|---|---|
| `openai` | `openai-chat` | the vendor's own | `openai` | `openai.api-key` (`OPENAI_API_KEY`) |
| `xai` | `openai-chat` | `https://api.x.ai/v1` | `x_ai` | `xai.api-key` (`XAI_API_KEY`) |
| `anthropic` | `anthropic` | the vendor's own | `anthropic` | `anthropic.api-key` (`ANTHROPIC_API_KEY`) |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` (`GEMINI_API_KEY` / `GOOGLE_API_KEY`) |
| `openrouter` | `openai-chat` | `https://openrouter.ai/api/v1` | `openrouter` | `openrouter.api-key` (`OPENROUTER_API_KEY`) |
| `nvidia` | `openai-chat` | `https://integrate.api.nvidia.com/v1` | `nvidia` | `nvidia.api-key` (`NVIDIA_API_KEY`) |
| `groq` | `openai-chat` | `https://api.groq.com/openai/v1` | `groq` | `groq.api-key` (`GROQ_API_KEY`) |
| `mistral` | `openai-chat` | `https://api.mistral.ai/v1` | `mistral_ai` | `mistral.api-key` (`MISTRAL_API_KEY`) |
| `cerebras` | `openai-chat` | `https://api.cerebras.ai/v1` | `cerebras` | `cerebras.api-key` (`CEREBRAS_API_KEY`) |
| `lmstudio` | `openai-chat` | `http://localhost:1234/v1` | `lmstudio` | `nessy.providers.lmstudio.enabled: true` — keyless |
| `ollama` | `openai-chat` | `http://localhost:11434/v1` | `ollama` | `nessy.providers.ollama.enabled: true` — keyless |

Every field is overridable under `nessy.providers.<id>.*`: a different
`base-url` for `anthropic` behind a proxy, a different `vendor` tag for an
`openai` endpoint that is really somebody else, or
`nessy.providers.<id>.api-key` in place of the vendor's own environment
variable. `openai.base-url` (`OPENAI_BASE_URL`) still overrides the
`openai` preset's endpoint on its own, the way it always has.

A key set under `nessy.providers.<id>.api-key` binds from the environment
with the property flattened, not underscore-joined at each dot: `xai`'s is
`NESSY_PROVIDERS_XAI_APIKEY`, with no underscore inside `APIKEY`.

The `openai`, `xai`, `groq`, `mistral`, `openrouter`, `nvidia` and `cerebras`
presets default `openai.tools.strict` to `true`: those chat-wire endpoints
were measured accepting strict tools. `lmstudio`, `ollama` and custom
providers do not, and neither do `anthropic` and `gemini`, which are not on
the OpenAI wire. Override it with
`nessy.providers.<id>.properties.openai.tools.strict: false`.

`lmstudio`, `ollama` and any other keyless preset must be turned on explicitly with
`enabled: true`. Nothing here probes `localhost:1234` or `localhost:11434` at startup: a
provider that exists because something happened to answer on a port is a
provider that silently vanishes the next time nothing does.

`nessy.providers.<id>.enabled: false` turns a provider off regardless of
what ingredient it has — a hosted preset with its key set, a custom
provider with its `wire` and `base-url` stated, or a keyless preset. An
unset `enabled` means "on if its ingredient is present" for everything
except a keyless preset, which stays off until `enabled: true` says
otherwise.

Bedrock ships no preset. AWS credentials are ambient on a large fraction of
machines, so any mechanism that let their presence choose a provider would
silently route an application with a stray profile to Bedrock — it joins
the registry as an application bean instead, below.

### Custom providers

An id under `nessy.providers.*` that is not in the table above is a custom
provider, and must state its own `wire` and `base-url`:

```yaml
nessy:
  providers:
    my-gateway:
      wire: openai-chat
      base-url: https://gateway.example.com/v1
      api-key: ${GATEWAY_KEY}
      vendor: openai
```

`wire` is one of `openai-chat`, `openai-responses`, `anthropic` or `gemini` —
a typo is a binding error naming the allowed values, not a provider that
silently fails to exist. `vendor` defaults to the wire's own (`openai` for
both OpenAI wires). Missing `wire` or `base-url` fails startup, naming the
id and the field.

The `openai` preset speaks `openai-chat`. To reach OpenAI over the Responses
API instead, tell the preset its wire:

```yaml
nessy:
  providers:
    openai:
      wire: openai-responses
```

### Application beans

An application's own `InferenceProvider` bean joins the registry under its
**bean name**, beside the presets:

```java
@Bean
InferenceProvider bedrock() {
  return BedrockInferenceProvider.fromEnv();
}
```

registers as `bedrock`. A bean whose name equals a lit preset's id fails
startup, naming both — rename the bean or unset the preset's ingredient.

### The report

At startup, `InferenceReport` logs every registered provider once — id,
wire, endpoint, vendor, never the key:

```
NESSY INFERENCE: providers: anthropic (anthropic, the vendor's own endpoint, vendor anthropic); gemini (gemini, the vendor's own endpoint, vendor gcp.gemini)
```

A provider with vendor properties names them, never their values:
`openai (openai-chat, the vendor's own endpoint, vendor openai, properties [openai.tools.strict])`.

An application bean the registrar never resolved prints only what it can
ask the bean for, its vendor: `bedrock (vendor aws.bedrock)`. No provider
registered at all is a warning, not a failure: whatever needs one says so
later, naming the agent type. Each harness then logs its own resolution
once, when it is built — the moment the fact exists, since `create` is
called by the application rather than at startup:

```
NESSY INFERENCE: agent type 'chat' -> openai / gpt-4.1-mini, up to 4096 tokens
```

The line ends `, properties [openai.reasoning.effort]` when the agent type
carries any -- names only.

`nessy.provider` and `nessy.model` are the factory-wide default a
harness falls back on when it names neither; see
[Spring Boot](spring-boot.md#properties).

`Repl.run` in `nessy-console` uses exactly this mechanism: it raises a
minimal Boot context around itself so these auto-configurations run, and
tears it down when the loop ends.

## Gemini

`nessy-inference-gemini` talks to the Gemini Developer API through Google's
own [java-genai](https://github.com/googleapis/java-genai) SDK, with a plain
API key. `fromEnv()` reads `GEMINI_API_KEY`, then `GOOGLE_API_KEY`, Google's
documented pair in that order; `baseUrl(...)` reaches a proxy or a
Gemini-compatible endpoint. Model names are the API's own, such as
`gemini-3.6-pro`.

Gemini ties an opaque **thought signature** to each function call it makes
and wants it back with that call on the next turn. The adapter carries it as
a `Block.Provider` block tagged `gcp.gemini` and replays it onto the rebuilt
call; a call with no signature, one made before capture or by another
vendor, is replayed with Google's documented skip-validation sentinel rather
than refused, at the cost of reasoning continuity for that one call. Thought
summaries are dropped: they are prose about the reasoning, not state the
vendor wants back.

A prompt the provider blocks outright, and a reply it stops for safety,
recitation or prohibited content, both come back as a `Refusal` named by the
vendor's own reason.

## Bedrock

`nessy-inference-bedrock` talks to Amazon Bedrock's Converse API through the
AWS SDK for Java v2, so one adapter covers Claude, Nova, Llama, Mistral and
the rest of the catalog: Converse is model-agnostic on the wire. There is no
`apiKey`. Credentials come from the SDK's default chain (environment,
profile files, instance metadata) or a `credentialsProvider(...)` you hand
in; the region comes from `region(...)`, or with `fromEnv()` from
`AWS_REGION` then `AWS_DEFAULT_REGION`. Model ids are Bedrock's, such as the
cross-region inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`.

```java
InferenceProvider bedrock = BedrockInferenceProvider.fromEnv();
```

A Bedrock API key works in place of IAM credentials: the SDK reads
`AWS_BEARER_TOKEN_BEDROCK` from the environment and uses it for every
Bedrock call, so a short-term key from the Bedrock console's API keys page
plus `AWS_REGION` is enough to run the adapter and its live tests.

Two things this wire does that the adapter absorbs. Roles must alternate, so
a summary and the input after it, both user-role, are merged into one
message before sending. And a model that reasons here, Claude with extended
thinking on, returns signed reasoning content that must go back untouched;
it travels as a `Block.Provider` block tagged `aws.bedrock`. A guardrail
intervention and a content filter come back as a `Refusal`.

## OpenAI Responses

`OpenAiResponsesInferenceProvider` speaks OpenAI's Responses API, the
`openai-responses` wire under Boot. A reasoning model such as GPT-6 calls
function tools only over this API.

It is stateless. Every call sends the whole context with `store: false`, and
nothing in the adapter can name a previous response or a conversation, so
Nessy's event log stays the only record of the conversation: replay,
chapter summaries, turn policy and switching providers all work from it.
`instructions` holds the system prompt alone; memory and state lead the
question in the input, and ambient background ends it.

A reasoning model's encrypted reasoning items are asked for on every call
and each one is stored in the transcript as a `Block.Provider` block tagged
with the provider's vendor, in the position it arrived. The adapter sends
back the ones that led to the tool results it is returning, within the turn
in flight; another vendor's blocks, and any from an earlier turn, are not
sent. An encrypted item is kilobytes, one per inference step.

Function tools go out in strict mode: the adapter rewrites each tool's
schema so every property is required, an optional one admits `null`, and
every object forbids properties it does not list. A sealed type or an
`Optional` record inside the input goes strict, its `oneOf` written as
`anyOf`, and a `const` or `enum` that names no type is given the one it
implies. A tool whose schema uses something strict mode cannot express, such
as a map or a keyword outside the strict subset (`minLength`, say), is sent
as generated with `strict: false`, and the adapter logs a warning naming the
tool and the keyword. The other tools in the request stay strict. A structured answer's schema is rewritten
the same way on both wires (`text.format` on the Responses wire,
`response_format` on the chat wire). The harness sends
every generated answer schema with an object root, so an answer is always
strict here. A custom schema handed straight to the adapter whose root is not
an object is asked for without strict mode, sent as generated, and the adapter
logs a warning naming the root.

Only function tools are offered. OpenAI's hosted tools (web search, file
search, code interpreter, remote MCP) run where Nessy cannot approve or
record them, and any hosted-tool output a server sends back is dropped.

Usage reads `input_tokens`, `output_tokens` and the cache and reasoning
details; a count a server leaves out is null.

## The OpenAI-compatible universe

The OpenAI adapter plus a base URL plus a key is, itself, an integration.
Every service below speaks Chat Completions, the `openai-chat` wire, so no
service-specific module exists or is needed. Nessy validates against OpenAI
proper; a compatible endpoint is the vendor's compatibility promise. Some
vendors (Groq, Mistral) report usage on the chunk that finishes the answer
rather than only on a final, choices-less chunk; the adapter accepts both.

Name the vendor when it is not OpenAI, so spans and metrics say who was
actually called:

```java
InferenceProvider grok = OpenAiChatInferenceProvider.of(c -> c
        .apiKey(key)
        .baseUrl("https://api.x.ai/v1")
        .vendor("x_ai"));
```

| Service | Base URL | Notes |
|---|---|---|
| xAI (Grok) | `https://api.x.ai/v1` | a first-class Boot citizen through `XAI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | model ids are vendor-prefixed slugs |
| Groq | `https://api.groq.com/openai/v1` | a freshly minted key can 401 for a few minutes while it propagates |
| Mistral | `https://api.mistral.ai/v1` | vendor name `mistral_ai`, OpenTelemetry's registered value; Z.ai's GLM models served here answer in a shape Chat Completions cannot read (`content` as a list with a `thinking` part), so the call fails |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | model ids are NVIDIA's catalog ids |
| Ollama | `http://localhost:11434/v1` | local; any non-empty key |
| LM Studio | `http://127.0.0.1:1234/v1` | local; any non-empty key |

Note the `/v1` suffix. The OpenAI SDK does not append it itself.

`OPENAI_BASE_URL` set alongside `OPENAI_API_KEY` makes any of these a
zero-code Boot citizen too.

### Reasoning models on local runtimes

A local thinking model, such as the Qwen 3 family, spends reasoning tokens
out of the same cap as its answer. With a small `maxTokens` the answer never
arrives, and the adapter reports a `Fault` naming `finish_reason=length`. A
reply that got some text out before the cap is a `Truncated` instead.
Raise the cap for that harness, or serve a model that does not reason by
default.

### Anthropic-compatible endpoints

LM Studio also speaks Anthropic's Messages dialect, and the Anthropic
adapter reaches it through the same `baseUrl` setting:

```java
InferenceProvider provider = AnthropicInferenceProvider.of(c -> c
        .apiKey("lm-studio")
        .baseUrl("http://127.0.0.1:1234"));
```

!!! warning "The base URL is not symmetric with the OpenAI path"
    The Anthropic SDK's default base URL is the bare origin; it appends
    `/v1/messages` itself. Passing `http://127.0.0.1:1234/v1` here produces
    a `/v1/v1/messages` double path that fails. Use the bare origin for the
    Anthropic adapter, and keep the `/v1` suffix for the OpenAI one.

## Embedders

Embeddings have a registry of their own, beside the inference one and with
the same mechanics: providers registered by name, presets lit by a key,
custom entries stated in full, application beans joining under their bean
names, and a report at startup. The namespace is `nessy.embedders.<id>`. An id
may name an inference provider and an embedder at once (`openai`, lit by the
one `OPENAI_API_KEY`), and the two are separate registrations.

A store names the embedder it wants, or takes the factory's default:

```java
Embedder embedder = embedders.create(c -> c.provider("voyage").model("voyage-3.5").dimension(1024));
Embedder theDefault = embedders.create(c -> {});
```

Both a provider and a model are required, from the store or from the default.
An unknown or missing provider fails when the embedder is made, listing what
is registered:

```
an embedder names provider 'cohere', which is not registered; registered: [openai, voyage]
```

### Presets

| id | wire | base URL | vendor | ingredient |
|---|---|---|---|---|
| `openai` | `openai` | the vendor's own; `openai.base-url` overrides | `openai` | `openai.api-key` (`OPENAI_API_KEY`) |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` (`GEMINI_API_KEY` / `GOOGLE_API_KEY`) |
| `voyage` | `voyage` | `https://api.voyageai.com/v1` | `voyage` | `voyage.api-key` (`VOYAGE_API_KEY`) |

A key lights a preset only when that vendor's embedding adapter is on the
classpath: `nessy-embedding-openai`, `nessy-embedding-gemini` or
`nessy-embedding-voyage`. Otherwise the starter logs one line and registers
nothing:

```
NESSY EMBEDDING: openai is configured but nessy-embedding-openai is not on the classpath; skipped
```

`nessy.embedders.<id>.api-key` works in place of the vendor's own variable;
from the environment it is `NESSY_EMBEDDERS_VOYAGE_APIKEY`.
`nessy.embedders.<id>.enabled: false` turns an embedder off whatever its
ingredient, which is the way to say "a Gemini key is set for chat, and no
Gemini embeddings are wanted". No preset carries a default model: a store's
vectors are keyed on its model, so the model is always written down.

Bedrock ships no preset, for the reason it has none on the inference side. A
`BedrockEmbeddingProvider` bean joins the registry like any application bean.

### Custom embedders, and local servers

An id that is not a preset must state `wire` (`openai`, `gemini` or
`voyage`), `base-url` and `api-key`. `vendor` is optional, defaults to the
wire's own, and is honoured on the `openai` wire only. A local server is a
custom embedder; no local preset ships:

```yaml
nessy:
  embedder: local
  embedding-model: text-embedding-nomic-embed-text-v1.5
  embedders:
    local:
      wire: openai
      base-url: http://localhost:1234/v1
      api-key: lm-studio
      vendor: lmstudio
```

A missing field fails startup, naming the id and the field.

A server that ignores the width it is asked for answers at its model's own.
An embedder that asked for a width fails on every reply that differs, naming
both numbers (`asked for 256 coordinates, the model returned 768`).

A width is a `Dimension`, at least 1: `c.dimension(0)` and
`nessy.embedding-dimension: 0` are refused where they are set, and an
embedder that asked for none reports `dimension()` as empty until its first
reply.

!!! warning "A local server may answer any model name"
    Some local OpenAI-compatible servers answer whatever model name they are
    sent with the model they have loaded. The model a store records is the
    one it asked for, so name the model the server actually serves. The
    client cannot detect a mismatch.

Two providers can serve one model name. A row records the model, not the
provider, so vectors from the two are comparable only if both really run that
model.

### Application beans

An application's own `EmbeddingProvider` bean joins under its **bean name**.
A preset's or custom embedder's bean is named `<id>Embeddings`
(`openaiEmbeddings`), because the inference provider of the same id is
already the bean `openai`; its registry id is the id. An application bean
named `openaiEmbeddings` beside a lit `openai` preset fails startup, naming
both. With the starter's factory, one named like a lit preset's id
(`voyage`) fails too, because two providers would register under one id.

An application `EmbedderFactory` bean replaces the starter's factory. The
presets are still registered as `EmbeddingProvider` beans, for it to use or
ignore.

### The default, and the report

`nessy.embedder` and `nessy.embedding-model` name the default a store gets
when it says nothing; set both or neither. `nessy.embedding-dimension` is its
width, optional, and only beside the pair. Blank counts as unset. A default
naming an unregistered embedder fails at startup, listing what is registered.
With no default, a store names its own.

At startup the report lists every embedder (id, wire, endpoint, vendor, and
the names of any properties set; never the key or a value) and, when the
starter's own factory is in use, the default:

```
NESSY EMBEDDING: embedders: openai (openai, the vendor's own endpoint, vendor openai); voyage (voyage, https://api.voyageai.com/v1, vendor voyage)
NESSY EMBEDDING: default: voyage / voyage-3.5, 1024 wide
```

The default line omits `, N wide` when no width is set. With nothing
registered the report says `NESSY EMBEDDING: no embedder is configured;
stores rank by recency`, and with no default, `NESSY EMBEDDING: no default
embedder; every store names its own`. All are INFO.

### Embedder properties

`nessy.embedders.<id>.properties.<name>` and `EmbedderConfig.property(name,
value)` carry vendor-prefixed names (`openai.`, `gemini.`, `bedrock.`,
`voyage.`), but no embedding adapter supports a property yet, and nothing
reaches an embedding request.

- A name under the adapter's own prefix is ignored, with one `WARN` naming it
  and the empty list of supported names. It is logged when the provider is
  built, and again when an embedder carrying it is made.
- A name with no prefix is refused.
- A name under another adapter's prefix is refused when a provider is built;
  on an embedder's own terms it is ignored and named at `DEBUG`, so a default
  written for one provider does not trouble a store that names another.

What does run when an embedder is made is Bedrock's model-family check: a
model that is neither Titan nor Cohere fails there rather than at the first
call.

## Writing a provider

An adapter is one method, and the four that ship are the pattern:

```java
public interface InferenceProvider {
  InferenceResult infer(InferenceRequest request, InferenceNarrator narrator);
}
```

- Translate the request in a class of its own that never touches the
  network, so the projection is testable without a key: summaries first, as
  user-role text tagged with the turn range they stand for; then each turn
  as its input, its exchanges (the request for actions, then the
  outcomes quoting the calls they answer) and its result. Put the system
  prompt in the system field and nothing else. Put memory and state, tagged
  `memory` and `state` with their kind, at the head of the active turn's
  first message, and ambient text, labelled by kind, at the very end of the
  request. Leave a refused turn out whole and answer for a failed one, so
  two questions never run together.
- Decide the shape of the reply on what the wire offers: a provider-level
  stop or block is a `Refusal` named by the vendor's own reason; a reply with
  tool calls is `Actions`, with any prose beside them as `Commentary`; prose
  alone is an `Answer`; an empty reply is a `Fault` naming the finish reason.
  When the vendor says the output limit was reached, a reply that holds text
  is `Truncated`; one that holds a tool call is a `Fault`, because its
  arguments cannot be trusted; one that holds only reasoning is the empty
  reply's `Fault`.
- Keep vendor state whole. Anything the vendor wants back untouched, a
  thinking signature, a thought signature, travels as a `Block.Provider`
  tagged with your provider name, and you replay only your own tag. Return
  that name from `vendor()` too, as semconv spells it, so every span
  your calls make says which vendor they went to.
- Catch the SDK's root exception and classify it into a `Failure`:
  `Transient` when the vendor admits retrying may work (429, 5xx),
  `Unknown` for a transport failure where the request may have been
  processed, `Permanent` otherwise, and never `Rejected`, which is the one
  classification that authorises dropping something a person said. Let a
  bug in the adapter escape rather than recording it as the model's fault.
- Own a prefix and declare your supported properties as `VendorProperty`
  constants (`nessy-api`): `ofInteger`, `ofBoolean`, `ofFloat`
  or `ofEnum` for a fixed value set, each carrying the full prefixed name.
  Read them through `VendorProperties` (`nessy-api`): merge the
  provider's map under `request.options().properties()`, take the entries
  `under` your prefix, and read each constant with `in(merged)`. Publish the
  constants, and keep the name-to-property map of what you support in your
  package-private reader, as the four inference adapters do. Send
  only what you parsed; log a warning, once per name, for every other name under your
  prefix. Override `InferenceProvider.validate(InferenceOptions)` to run the
  same reading, so a mistake fails the harness build rather than its first
  turn.
- `InferenceNarrator.narrate(event)` is how a streaming adapter reports deltas
  as they arrive: a `ContentDelta` per piece of the answer, a `ThinkingDelta`
  per piece of visible reasoning. All four shipped adapters use their
  vendor's streaming call and narrate this way, folding the stream back into
  the one result the engine reads (with the SDK's own accumulator where one
  exists, OpenAI and Anthropic; with a fold of their own for Gemini and
  Bedrock). An adapter that does not stream may ignore the narrator, and
  nothing above it can tell; only the person watching can.

## Where next

- [Getting Started](getting-started.md), the smallest harness
- [The Harness](harness.md), the per-harness inference settings
- [Observability](observability.md), what a call reports about itself
- [Spring Boot](spring-boot.md), the properties that pick a provider
