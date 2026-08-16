# Providers

`Nessy.harness(provider)` takes any `ModelProvider`. Three native modules
ship today — `nessy-model-anthropic`, `nessy-model-openai`, and
`nessy-model-gemini` — and a fourth, `nessy-model-env`, picks between them
from the environment so an application can switch providers by switching a
variable, not its code. `OpenAiModelProvider` also reaches every service that
speaks OpenAI's wire protocol, covered below.

Anthropic and OpenAI are live-validated against their real APIs. Gemini's
request/response mapping is offline-validated only — see
[Gemini](#gemini) below for what that means in practice.

## Building one directly

Each provider module builds a `ModelProvider` the same way:

```java
ModelProvider provider = AnthropicModelProvider.builder().apiKey(key).build();
```

```java
ModelProvider provider = OpenAiModelProvider.builder().apiKey(key).build();
```

```java
ModelProvider provider = GeminiModelProvider.builder().apiKey(key).build();
```

`.fromEnv()` on any of the three builders delegates to that provider's own
seam-integrity read of the environment — for Anthropic and OpenAI this
resolves to the underlying SDK's own environment resolution
(`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and the rest of what each SDK
understands); for Gemini, `.fromEnv()` reads `GEMINI_API_KEY` then
`GOOGLE_API_KEY` itself, rather than delegating to the SDK's own resolution.
Reach for the builder directly whenever one of those matters;
`nessy-model-env`, below, only ever reads the API key.

## Switching by environment variable

`nessy-model-env` depends on all three provider modules non-optionally —
that's the whole point, so any key just works with no per-provider
dependency choice left to the consumer:

```java
ModelProvider provider = EnvModelProviders.fromEnv();
```

`fromEnv()` decides which provider to build from what it finds in the
environment, checked in this order:

- `ANTHROPIC_API_KEY` → Anthropic.
- `OPENAI_API_KEY` → OpenAI (layered with `OPENAI_BASE_URL` if that is also
  set — see [The OpenAI-compatible universe](#the-openai-compatible-universe)
  below).
- `GEMINI_API_KEY`, then `GOOGLE_API_KEY` → Gemini.
- `XAI_API_KEY` → OpenAI wired to `https://api.x.ai/v1` — Grok as a
  zero-code env citizen.

Exactly one key present chooses that provider outright. Two or more present
are broken by `NESSY_PROVIDER` (`anthropic`/`openai`/`gemini`/`xai`, alias
`grok`, case-insensitive): an explicit, recognized choice naming a key that
is actually present is silent; anything else falls back to the first
present key in the order above and logs one `WARN` line naming the default
and how to override it. None present fails fast with an
`IllegalStateException` naming every variable it checked.

Each provider is built the same way its own module builds one from an
explicit key — not that provider's own `fromEnv()`. The choice
`EnvModelProviders` makes from the environment is the choice that gets
built, not a second, independent read underneath it. One consequence: only
the API key (and, for OpenAI, `OPENAI_BASE_URL`) is read. Other SDK-level
environment variables are silently ignored here — construct the provider
directly when one of those is needed.

### Picking a model too — `select()`

`fromEnv()` returns only the `ModelProvider`. `select()` returns a
`Selection` — the provider, its lowercase name (`"anthropic"`/`"openai"`/
`"gemini"`/`"xai"`, the same vocabulary `NESSY_PROVIDER` accepts), and a
model — so an application that wants to show or log what was picked (a
demo's banner, for instance) doesn't have to re-derive the provider's
identity itself via `instanceof`: the knowledge of which provider was
chosen, and which model goes with it, belongs to the selector, not the
caller.

```java
EnvModelProviders.Selection selection = EnvModelProviders.select();
ModelProvider provider = selection.provider();
```

The model comes from `NESSY_MODEL` when that variable is set and
non-blank — it wins outright, regardless of which provider was chosen. This
is the one way to name a model whose provider instance can't reveal it on
its own: a Grok model reached through `OpenAiModelProvider`'s base-url
override looks, by type, exactly like an OpenAI model, so nothing else can
tell `select()` which model name is right for it. The same applies to
OpenRouter and LM Studio models reached the same way. Without `NESSY_MODEL`,
`select()` falls back to a small, cheap default for the chosen provider
(Anthropic's Haiku, OpenAI's `gpt-4o-mini`, Gemini's `gemini-2.5-flash`, or
`grok-4.6` for xAI — read from docs.x.ai on 2026-08-15; not exercised against
the live API).

`nessy-examples/chat-cli`'s `Chat` main is this in practice: one main, no
`if` branch for which provider module to import, because `select()` already
decided both the provider and the model.

```java
EnvModelProviders.Selection selection;
try {
    selection = EnvModelProviders.select();
} catch (IllegalStateException e) {
    System.out.println(e.getMessage());
    System.exit(1);
    return;
}
```

## Gemini

`nessy-model-gemini` is a native `ModelProvider` on Google's own
[java-genai](https://github.com/googleapis/java-genai) SDK, talking to the
Gemini Developer API via a plain API key (Vertex AI auth is out of scope for
v1):

```java
ModelProvider provider = GeminiModelProvider.builder().apiKey(key).build();
```

```java
ModelProvider provider = GeminiModelProvider.builder().fromEnv().build();
```

`.fromEnv()` reads `GEMINI_API_KEY`, then — if unset — `GOOGLE_API_KEY`,
Google's own documented pair, in that order. `.baseUrl(String)` overrides
the endpoint for proxies, gateways, or Gemini-compatible services. Model
names are the Gemini Developer API's own, e.g. `gemini-2.5-flash` or
`gemini-2.5-pro`.

Capabilities in v1: text and tool calls, including parallel tool calls in
one turn, plus usage reporting. Thinking output is not yet mapped — Gemini's
`thought`-flagged parts are dropped rather than translated.

!!! warning "Not yet live-validated"
    The Gemini mapping is covered by offline unit tests against the SDK's
    own response types, but the live suite — a real conversation and tool
    round trip against the Gemini Developer API — has not been run against a
    real key as of this writing. Run it yourself before depending on this
    path in production: `GEMINI_API_KEY=... ./mvnw test
    -Dnessy.excludedGroups= -pl nessy-model-gemini`.

## The OpenAI-compatible universe

`OpenAiModelProvider` + a base URL + a key is, itself, an integration: every
service below speaks the same OpenAI chat-completions wire protocol, so no
provider-specific module exists or is needed for any of them. Nessy
validates against OpenAI proper — a compatible endpoint is the vendor's
compatibility promise, not ours.

**Grok** (xAI ships no official Java SDK; its API is deliberately
OpenAI-compatible):

```java
ModelProvider provider =
    OpenAiModelProvider.builder().apiKey(key).baseUrl("https://api.x.ai/v1").build();
```

`XAI_API_KEY` is a first-class `EnvModelProviders` citizen — set it alone
and `fromEnv()` wires Grok with no other code, as shown above.

**OpenRouter**:

```java
ModelProvider provider =
    OpenAiModelProvider.builder().apiKey(key).baseUrl("https://openrouter.ai/api/v1").build();
```

**Ollama** (local, no key required — any non-empty string works):

```java
ModelProvider provider =
    OpenAiModelProvider.builder().apiKey("ollama").baseUrl("http://localhost:11434/v1").build();
```

**LM Studio** (local; validated 2026-08-15 against two models —
`google/gemma-4-e4b` and `qwen/qwen3.6-35b-a3b` — both a streamed text turn
and a tool-call round trip, on LM Studio's OpenAI-compatible endpoint):

```java
ModelProvider provider =
    OpenAiModelProvider.builder().apiKey("lm-studio").baseUrl("http://127.0.0.1:1234/v1").build();
```

Note the `/v1` suffix on the base URL — the OpenAI SDK does not append it
itself.

`OPENAI_BASE_URL`, set alongside `OPENAI_API_KEY`, makes any of these a
zero-code env citizen too: `EnvModelProviders.fromEnv()` layers it onto the
OpenAI provider exactly as shown above, the same way it wires Grok.

### Anthropic-compatible endpoints

LM Studio also speaks Anthropic's Messages dialect, and `AnthropicModelProvider`
was validated against it the same date, same two models, same coverage
(streamed text and a tool-call round trip):

```java
ModelProvider provider =
    AnthropicModelProvider.builder().apiKey("lm-studio").baseUrl("http://127.0.0.1:1234").build();
```

!!! warning "The base URL is not symmetric with the OpenAI path"
    The Anthropic Java SDK's default base URL is the bare origin
    (`https://api.anthropic.com`, no `/v1`) — it appends `/v1/messages`
    itself. Passing `http://127.0.0.1:1234/v1` here, by analogy with the
    OpenAI example above, produces a `.../v1/v1/messages` double path that
    fails. Use the bare origin for `AnthropicModelProvider.baseUrl(...)`,
    and keep the `/v1` suffix for `OpenAiModelProvider.baseUrl(...)`.

## Running the examples

`nessy-examples/chat-cli` and `nessy-examples/scout` both build their model
choice from `EnvModelProviders.select()`, so any of the four env setups
above just works — set the key, run `exec:java`:

```console
$ GEMINI_API_KEY=... ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

xAI has no small/cheap alias, so `select()`'s built-in Grok default may not
be the model you want — name one explicitly with `NESSY_MODEL`, the same
override described above:

```console
$ XAI_API_KEY=... NESSY_MODEL=<your-grok-model> \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

The same `NESSY_MODEL` override reaches any OpenAI-compatible local runtime
wired through `OPENAI_BASE_URL` — LM Studio, for instance, once a model is
loaded there:

```console
$ OPENAI_API_KEY=lm-studio OPENAI_BASE_URL=http://127.0.0.1:1234/v1 \
    NESSY_MODEL=<loaded-model> \
    ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

`nessy-examples/scout` takes the same three env recipes — swap the module
coordinate for `nessy-examples/scout` in any of the commands above.

## In a Spring Boot application

`nessy-autoconfigure` reads the same decision out of `nessy.provider` and
`nessy.{anthropic,openai,gemini}.*` properties instead of environment
variables, layered over each SDK's own `fromEnv()` resolution. See
[Spring Boot](spring-boot.md) for the property table and the
ambiguous-classpath failure mode.

## Where next

- [Getting Started](getting-started.md) — the smallest agent, provider swap
  included.
- [Spring Boot](spring-boot.md) — the same provider selection, driven by
  `nessy.*` properties instead of environment variables.
- [The Durable Loop](../concepts/durable-loop.md) — what a `Harness` built
  from a provider actually gives you.
