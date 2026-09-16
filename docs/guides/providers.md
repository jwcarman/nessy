# Providers

An `InferenceProvider` is the adapter between the engine and one vendor's
API. It is an application singleton holding the SDK client, credentials and
transport, and it does one thing:

```java
public interface InferenceProvider {
  InferenceResult infer(InferenceRequest request, AgentNarrator narrator);
}
```

The engine builds the request (system prompt, summaries, the tail of turns,
ambient blocks, the tools on offer, the model and token cap) and the adapter
turns it into the vendor's wire shape, narrates the deltas as they stream,
and hands back one of four results: an `Answer`, `Actions` the model wants
taken, a `Refusal`, or a `Fault` with a `Failure` that says whether retrying
could help.

Two adapters ship: `nessy-inference-anthropic` on Anthropic's Java SDK and
`nessy-inference-openai` on OpenAI's. The OpenAI one also reaches every
service that speaks OpenAI's wire protocol, covered
[below](#the-openai-compatible-universe).

## Which model

The provider is engine-wide. The model is a setting: the engine's default,
overridden per harness.

```java
DefaultHarnessFactory factory = new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)
        .inference(AnthropicInferenceProvider.fromEnv(), InferenceOptions.of("claude-sonnet-5")));

Harness<String> triage = factory.create(config -> config
        .agentType(new AgentType("triage"))
        .systemPrompt(triagePrompt)
        .inference(in -> in.model("claude-haiku-4-5").maxTokens(512)));

Harness<String> review = factory.create(config -> config
        .agentType(new AgentType("review"))
        .systemPrompt(reviewPrompt)
        .inference(in -> in.model("claude-opus-5")));
```

`maxTokens` is per harness for a reason: it is how you make a model give a
short answer. A timeout and a retry policy for the call live beside it.

Provider-level features such as thinking and prompt caching are settings on
the provider, not requests a harness makes. Two agent types that need the
provider configured differently get two factories, each with its own
provider.

## Building a provider

Each adapter is built the same way, a static `create(customizer)` over a
config, never a public builder:

```java
InferenceProvider anthropic = AnthropicInferenceProvider.create(c -> c.apiKey(key));
InferenceProvider openai = OpenAiInferenceProvider.create(c -> c.apiKey(key));
```

Each also has `fromEnv()`, which delegates to the SDK's own reading of the
environment: `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` and
`ANTHROPIC_BASE_URL` for one, `OPENAI_API_KEY`, `OPENAI_BASE_URL` and
`OPENAI_ORG_ID` for the other. Anything set explicitly on the config wins
over the environment:

```java
InferenceProvider provider = AnthropicInferenceProvider.create(c -> c
        .fromEnv()
        .baseUrl("http://127.0.0.1:1234"));
```

`client(...)` hands in a fully built SDK client, which the provider then
never closes; `mapper(...)` supplies the `JsonMapper` used for tool schemas
and arguments.

### Anthropic features

```java
InferenceProvider provider = AnthropicInferenceProvider.create(c -> c
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

`nessy-spring-boot-autoconfigure` contributes an `InferenceProvider` bean
when an adapter is on the classpath and its key is in the environment:

| Property | Bean |
|---|---|
| `anthropic.api-key` (`ANTHROPIC_API_KEY`) | `AnthropicInferenceProvider.create(c -> c.apiKey(key))` |
| `openai.api-key` (`OPENAI_API_KEY`), with `openai.base-url` layered on when present | `OpenAiInferenceProvider` |
| `xai.api-key` (`XAI_API_KEY`) | the OpenAI adapter at xAI's base URL, reporting `x_ai` as its provider name |

Every one of these is `@ConditionalOnMissingBean(InferenceProvider.class)`:
declare your own and they all back off. Set one vendor's key, or declare the
bean yourself; two keys at once resolve to whichever bean the container
reaches first.

The model id comes from `nessy.model` (`NESSY_MODEL`), the same as every
other Boot-wired setting; see [Spring Boot](spring-boot.md).

`Repl.run` in `nessy-console` uses exactly this mechanism: it raises a
minimal Boot context around itself so these auto-configurations run, and
tears it down when the loop ends.

## The OpenAI-compatible universe

The OpenAI adapter plus a base URL plus a key is, itself, an integration.
Every service below speaks the same chat-completions wire protocol, so no
service-specific module exists or is needed. Nessy validates against OpenAI
proper; a compatible endpoint is the vendor's compatibility promise.

Name the vendor when it is not OpenAI, so spans and metrics say who was
actually called:

```java
InferenceProvider grok = OpenAiInferenceProvider.create(c -> c
        .apiKey(key)
        .baseUrl("https://api.x.ai/v1")
        .provider("x_ai"));
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
InferenceProvider provider = AnthropicInferenceProvider.create(c -> c
        .apiKey("lm-studio")
        .baseUrl("http://127.0.0.1:1234"));
```

!!! warning "The base URL is not symmetric with the OpenAI path"
    The Anthropic SDK's default base URL is the bare origin; it appends
    `/v1/messages` itself. Passing `http://127.0.0.1:1234/v1` here produces
    a `/v1/v1/messages` double path that fails. Use the bare origin for the
    Anthropic adapter, and keep the `/v1` suffix for the OpenAI one.

## What the engine records about a call

Every call's request is written down whole in `nessy_inference_context`
before the provider is asked, and its outcome afterwards. When a call goes
wrong, that row is what the model actually saw. See
[Storage](../concepts/storage.md#what-the-model-was-shown).

## Where next

- [Getting Started](getting-started.md), the smallest harness
- [The Harness](harness.md), the per-harness inference settings
- [Observability](observability.md), what a call reports about itself
- [Spring Boot](spring-boot.md), the properties that pick a provider
