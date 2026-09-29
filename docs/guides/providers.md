# Providers

An `InferenceProvider` is the adapter between the engine and one vendor's
API. It is an application singleton holding the SDK client, credentials and
transport, and it does one thing:

```java
public interface InferenceProvider {
  InferenceResult infer(InferenceRequest request, InferenceNarrator narrator);
}
```

The engine builds the request (system prompt, summaries, the tail of turns,
ambient blocks, the tools on offer, the model and token cap) and the adapter
turns it into the vendor's wire shape, narrates the deltas as they stream,
and hands back one of four results: an `Answer`, `Actions` the model wants
taken, a `Refusal`, or a `Fault` with a `Failure` that says whether retrying
could help. Whichever it is, the result carries a `Usage`; the engine
records it and puts it on the call's span. See [Usage](#usage) below for
what it holds.

Four adapters ship: `nessy-inference-anthropic` on Anthropic's Java SDK,
`nessy-inference-openai` on OpenAI's, `nessy-inference-gemini` on Google's
java-genai SDK, and `nessy-inference-bedrock` on the AWS SDK's Converse API.
The OpenAI one also reaches every service that speaks OpenAI's wire protocol,
covered [below](#the-openai-compatible-universe).

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

## Naming providers

A factory holds providers by name: each is registered under a `ProviderId`,
and an agent type says which one it wants alongside its model. Both are
required, one way or the other — an agent type that names neither and a
factory with no default fail when the harness is built.

```java
DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(new InMemoryDirectBackend(codecs))
        .provider(ProviderId.of("anthropic"), AnthropicInferenceProvider.fromEnv())
        .provider(ProviderId.of("openai"), OpenAiInferenceProvider.fromEnv()));

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

Provider-level features such as thinking and prompt caching are settings on
the provider, not requests a harness makes. Two agent types that need the
provider configured differently get two providers, registered under two
names, on the same factory.

## Building a provider

Each adapter is built the same way, a static `of(customizer)` over a
config, never a public builder:

```java
InferenceProvider anthropic = AnthropicInferenceProvider.of(c -> c.apiKey(key));
InferenceProvider openai = OpenAiInferenceProvider.of(c -> c.apiKey(key));
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

```java
InferenceProvider provider = AnthropicInferenceProvider.of(c -> c
        .fromEnv()
        .thinking(true)
        .thinkingBudget(4096)
        .promptCaching(PromptCaching.FIVE_MINUTES));
```

Thinking is off by default. When on, the model's reasoning is spent out of
each call's `maxTokens`, which must exceed the budget or the answer comes
back empty. The budget defaults to 1024 tokens. Prompt caching is
`OFF`, `FIVE_MINUTES` or `ONE_HOUR`, and marks the system prompt and the
tool list as cacheable.

### Empty answers

Both adapters treat an answer with no content as a `Fault` with a
`Permanent` failure naming the finish reason, rather than a turn that ended
in silence. The usual cause is a thinking model that spent the whole token
cap on reasoning; raise `maxTokens` or turn the reasoning off at the
provider.

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
| `openai` | `openai` | the vendor's own | `openai` | `openai.api-key` (`OPENAI_API_KEY`) |
| `xai` | `openai` | `https://api.x.ai/v1` | `x_ai` | `xai.api-key` (`XAI_API_KEY`) |
| `anthropic` | `anthropic` | the vendor's own | `anthropic` | `anthropic.api-key` (`ANTHROPIC_API_KEY`) |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` (`GEMINI_API_KEY` / `GOOGLE_API_KEY`) |
| `openrouter` | `openai` | `https://openrouter.ai/api/v1` | `openrouter` | `openrouter.api-key` (`OPENROUTER_API_KEY`) |
| `nvidia` | `openai` | `https://integrate.api.nvidia.com/v1` | `nvidia` | `nvidia.api-key` (`NVIDIA_API_KEY`) |
| `lmstudio` | `openai` | `http://localhost:1234/v1` | `lmstudio` | `nessy.providers.lmstudio.enabled: true` — keyless |

Every field is overridable under `nessy.providers.<id>.*`: a different
`base-url` for `anthropic` behind a proxy, a different `vendor` tag for an
`openai` endpoint that is really somebody else, or
`nessy.providers.<id>.api-key` in place of the vendor's own environment
variable. `openai.base-url` (`OPENAI_BASE_URL`) still overrides the
`openai` preset's endpoint on its own, the way it always has.

A key set under `nessy.providers.<id>.api-key` binds from the environment
with the property flattened, not underscore-joined at each dot: `xai`'s is
`NESSY_PROVIDERS_XAI_APIKEY`, with no underscore inside `APIKEY`.

`lmstudio` and any other keyless preset must be turned on explicitly with
`enabled: true`. Nothing here probes `localhost:1234` at startup: a
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
      wire: openai
      base-url: https://gateway.example.com/v1
      api-key: ${GATEWAY_KEY}
      vendor: openai
```

`wire` is one of `openai`, `anthropic` or `gemini` — a typo is a binding
error naming the allowed values, not a provider that silently fails to
exist. `vendor` defaults to the wire's own (`openai` for the `openai`
wire). Missing `wire` or `base-url` fails startup, naming the id and the
field.

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
NESSY INFERENCE: providers: openai (openai, the vendor's own endpoint, vendor openai); xai (openai, https://api.x.ai/v1, vendor x_ai)
```

An application bean the registrar never resolved prints only what it can
ask the bean for, its vendor: `bedrock (vendor aws.bedrock)`. No provider
registered at all is a warning, not a failure: whatever needs one says so
later, naming the agent type. Each harness then logs its own resolution
once, when it is built — the moment the fact exists, since `create` is
called by the application rather than at startup:

```
NESSY INFERENCE: agent type 'chat' -> openai / gpt-4.1-mini, up to 4096 tokens
```

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
a summary and the observation after it, both user-role, are merged into one
message before sending. And a model that reasons here, Claude with extended
thinking on, returns signed reasoning content that must go back untouched;
it travels as a `Block.Provider` block tagged `aws.bedrock`. A guardrail
intervention and a content filter come back as a `Refusal`.

## The OpenAI-compatible universe

The OpenAI adapter plus a base URL plus a key is, itself, an integration.
Every service below speaks the same openai wire, so no
service-specific module exists or is needed. Nessy validates against OpenAI
proper; a compatible endpoint is the vendor's compatibility promise.

Name the vendor when it is not OpenAI, so spans and metrics say who was
actually called:

```java
InferenceProvider grok = OpenAiInferenceProvider.of(c -> c
        .apiKey(key)
        .baseUrl("https://api.x.ai/v1")
        .vendor("x_ai"));
```

| Service | Base URL | Notes |
|---|---|---|
| xAI (Grok) | `https://api.x.ai/v1` | a first-class Boot citizen through `XAI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | model ids are vendor-prefixed slugs |
| Groq | `https://api.groq.com/openai/v1` | a freshly minted key can 401 for a few minutes while it propagates |
| NVIDIA NIM | `https://integrate.api.nvidia.com/v1` | model ids are NVIDIA's catalog ids |
| Ollama | `http://localhost:11434/v1` | local; any non-empty key |
| LM Studio | `http://127.0.0.1:1234/v1` | local; any non-empty key |

Note the `/v1` suffix. The OpenAI SDK does not append it itself.

`OPENAI_BASE_URL` set alongside `OPENAI_API_KEY` makes any of these a
zero-code Boot citizen too.

### Reasoning models on local runtimes

A local thinking model, such as the Qwen 3 family, spends reasoning tokens
out of the same cap as its answer. With a small `maxTokens` the answer never
arrives, and the adapter reports a `Fault` naming `finish_reason=length`.
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
  as its observation, its exchanges (the request for actions, then the
  outcomes quoting the calls they answer) and its result; ambient blocks
  into the system field, labelled by kind. Leave a refused turn out whole and
  answer for a failed one, so two questions never run together.
- Decide the shape of the reply on what the wire offers: a provider-level
  stop or block is a `Refusal` named by the vendor's own reason; a reply with
  tool calls is `Actions`, with any prose beside them as `Commentary`; prose
  alone is an `Answer`; an empty reply is a `Fault` naming the finish reason.
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
