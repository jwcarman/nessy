# Providers

`Nessy.harness(h -> h.provider(provider))` takes any `ModelProvider`. Four native modules ship
today — `nessy-model-anthropic`, `nessy-model-openai`, `nessy-model-gemini`,
and `nessy-model-bedrock` — and a fifth, `nessy-model-env`, picks between
them from the environment so an application can switch providers by
switching a variable, not its code. `OpenAiModelProvider` also reaches every
service that speaks OpenAI's wire protocol, covered below.

All four native providers are live-validated against their real APIs —
Gemini on 2026-08-15 including the tool-call round trip with real thought
signatures, and Bedrock on 2026-08-16 including the tool round trip through
the ConverseStream bridge.

## Building one directly

Each provider module builds a `ModelProvider` the same way — a static
`create(ProviderCustomizer)` factory over a config, not a builder:

```java
ModelProvider provider = AnthropicModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider provider = OpenAiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider provider = GeminiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider provider = BedrockModelProvider.create(c -> c.region(Region.US_EAST_1));
```

Each also ships a `fromEnv()` static — the blessed one-call shape,
equivalent to `create(config -> config.fromEnv())` — that delegates to that
provider's own seam-integrity read of the environment:

```java
ModelProvider provider = AnthropicModelProvider.fromEnv();
```

For Anthropic and OpenAI this resolves to the underlying SDK's own
environment resolution (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and
the rest of what each SDK understands); for Gemini, `fromEnv()` reads
`GEMINI_API_KEY` then `GOOGLE_API_KEY` itself, rather than delegating to the
SDK's own resolution; for Bedrock, `fromEnv()` uses the AWS SDK's own
default credentials chain (env vars, shared profile files, container/instance
metadata) and resolves the region by reading `AWS_REGION` then, if unset,
`AWS_DEFAULT_REGION` itself — see [Bedrock](#bedrock) below. Reach for
`create(...)` directly whenever one of those matters; `nessy-model-env`,
below, only ever reads the API key (and, for Bedrock, never reads a key at
all).

## Switching by environment variable

`nessy-model-env` depends on all four provider modules non-optionally —
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

**Bedrock is the one exception to this whole scheme.** It has no env var of
its own, and is never chosen by key presence — not even as a fallback, not
even as a participant in the tiebreak above. The only way to choose it is
`NESSY_PROVIDER=bedrock`, checked before any key is even looked at; it wins
outright regardless of which other keys happen to be set. See
[Bedrock](#bedrock) below for why.

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
`"gemini"`/`"xai"`/`"bedrock"`, the same vocabulary `NESSY_PROVIDER`
accepts), and a model — so an application that wants to show or log what was
picked (a demo's banner, for instance) doesn't have to re-derive the
provider's identity itself via `instanceof`: the knowledge of which provider
was chosen, and which model goes with it, belongs to the selector, not the
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
(Anthropic's Haiku, OpenAI's `gpt-4o-mini`, Gemini's `gemini-3.6-flash`,
`grok-4.6` for xAI — live-validated 2026-08-16, including a multi-tool turn
with an approval gate — or, for Bedrock, `us.anthropic.claude-haiku-4-5-20251001-v1:0`,
the `us` cross-region inference profile id for Claude Haiku 4.5,
live-validated 2026-08-16).

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
ModelProvider provider = GeminiModelProvider.create(c -> c.apiKey(key));
```

```java
ModelProvider provider = GeminiModelProvider.fromEnv();
```

`fromEnv()` reads `GEMINI_API_KEY`, then — if unset — `GOOGLE_API_KEY`,
Google's own documented pair, in that order. `.baseUrl(String)` overrides
the endpoint for proxies, gateways, or Gemini-compatible services. Model
names are the Gemini Developer API's own, e.g. `gemini-3.6-flash` or
`gemini-2.5-pro`.

Capabilities in v1: text and tool calls, including parallel tool calls in
one turn, plus usage reporting. Thinking output is not yet mapped — Gemini's
`thought`-flagged parts are dropped rather than translated.

Tool calls carry real continuity: the stream captures each function call's
`thoughtSignature` and the request builder replays it verbatim on the next
turn. A history with no stored signature — one predating this capture, or
authored by another provider in a mixed setup — replays with Google's own
documented skip-validation sentinel instead of failing the call, at the cost
of degraded reasoning continuity for that one call only.

!!! note "Live-validated"
    The Gemini mapping, including the signature capture/replay above, passed
    the live suite — a real conversation and tool round trip against the
    Gemini Developer API on `gemini-3.6-flash` — on 2026-08-15. Rerun it
    yourself anytime:
    `GEMINI_API_KEY=... ./mvnw test -Dnessy.excludedGroups= -pl
    nessy-model-gemini`.

## Bedrock

`nessy-model-bedrock` is a native `ModelProvider` on the AWS SDK for Java
v2's `bedrockruntime` client, talking to Amazon Bedrock's unified
Converse/ConverseStream API — one nessy provider covers Claude, Nova, Llama,
Mistral, and the rest of the Bedrock catalog, since Converse is
model-agnostic on the wire (`InvokeModel*`'s per-model JSON bodies are out
of scope, deliberately: they re-fragment exactly what Converse unified):

```java
ModelProvider provider = BedrockModelProvider.create(c -> c.region(Region.US_EAST_1));
```

```java
ModelProvider provider = BedrockModelProvider.fromEnv();
```

`fromEnv()` uses the AWS SDK's own default credentials provider chain —
env vars, shared profile/credentials files, container/instance metadata —
the AWS idiom of ambient credentials, the same reason there is no
`.apiKey(...)` on this config at all. Only the **region** is resolved
directly rather than delegated to the SDK's own region chain: `AWS_REGION`
first, then `AWS_DEFAULT_REGION` if that is unset — Amazon's own documented
pair. An explicit `.region(...)` set alongside `.fromEnv()` still wins;
neither variable set fails fast the instant the customizer returns, with an
`IllegalStateException` naming both. `.credentialsProvider(AwsCredentialsProvider)`
overrides the credentials chain outright, and `.client(BedrockRuntimeAsyncClient)`
is the escape hatch for a fully preconfigured async SDK client.

**Close ownership is not symmetric.** `BedrockModelProvider` is
`AutoCloseable` — the real client holds Netty resources (an event-loop
group, a connection pool) that outlive one `stream()` call. Closing the
provider closes that client only when the provider built it itself (the
`region`/`credentialsProvider`/`fromEnv()` path); a client handed in via
`.client(...)` is the caller's own to close, on whatever lifecycle the
caller built it against — the provider never closes it, since it never
opened it either.

**Explicit selection only.** Neither `EnvModelProviders` nor
`nessy-autoconfigure` ever choose Bedrock by key presence, classpath
presence, or any other ambient signal — `NESSY_PROVIDER=bedrock` /
`nessy.provider=bedrock` is the only door, checked before any other
provider's candidacy is even computed. This is not an oversight: AWS
credentials (and, on some platforms, even `AWS_REGION` itself — AWS Lambda
sets it automatically) are ambient on a large fraction of machines, so
letting their mere presence win, or even enter a tiebreak, would silently
hijack any application with a stray AWS profile into talking to Bedrock the
moment `nessy-model-bedrock` rode the classpath. See
[Switching by environment variable](#switching-by-environment-variable)
above and Spring Boot for exactly how each door enforces
this.

Capabilities in v1: text and tool calls, including parallel tool calls in
one assistant turn (Converse already streams several `toolUse` content
blocks per turn, each on its own `contentBlockIndex`). Thinking output is
not yet mapped — `ThinkingBlock`/`RedactedThinkingBlock` are dropped on
replay, the same discipline Gemini's own unadvertised capabilities document.

!!! note "Live-validated"
    The Bedrock mapping passed the live suite — a real conversation and a
    real tool round trip through the ConverseStream bridge against Amazon
    Bedrock — on 2026-08-16, on the default model id
    (`us.anthropic.claude-haiku-4-5-20251001-v1:0`, the `us` cross-region
    inference profile for Claude Haiku 4.5). Offline mapping tests
    (request/response translation, the async-to-blocking bridge, stop-reason
    and usage tables) run entirely against hand-built SDK fixtures and a
    hand-rolled async-client fake — no mocking library, no network. Rerun the
    live suite anytime — `AWS_ACCESS_KEY_ID` is the gate the suite itself
    checks, so it, not `AWS_REGION` alone, is what opts the tests in:

    ```sh
    AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=... AWS_REGION=us-east-1 \
      ./mvnw test -Dnessy.excludedGroups= -pl nessy-model-bedrock
    ```

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
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://api.x.ai/v1"));
```

`XAI_API_KEY` is a first-class `EnvModelProviders` citizen — set it alone
and `fromEnv()` wires Grok with no other code, as shown above.

**OpenRouter** (validated 2026-08-16 — `openai/gpt-4o-mini` through chat-cli:
text, an approval-gated tool round trip, and a notebook write; note
OpenRouter model ids are vendor-prefixed slugs, so set `NESSY_MODEL`, and
cached-token counts may read zero since usage passthrough varies by
upstream model):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://openrouter.ai/api/v1"));
```

**Groq** (validated 2026-08-16 — the chip company with the LPU inference
silicon, no relation to xAI's Grok — serving open-weight models at extreme
speed; `openai/gpt-oss-120b` through chat-cli: text, an approval-gated tool
round trip, and a notebook write, with time-to-first-token in the tens of
milliseconds. Keys are `gsk_...` from console.groq.com. One field-tested
quirk: a freshly minted key can intermittently 401 for a few minutes while
it propagates across their edge — the failed request won't even appear in
their logs; just retry):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://api.groq.com/openai/v1"));
```

**NVIDIA NIM** (validated 2026-08-16 — the free open-weight
`nvidia/nemotron-3.5-lightning-30b-a3b` on NVIDIA's free developer tier,
through chat-cli: text, an approval-gated tool round trip, and a notebook
write — a no-cost model driving the whole loop; keys are `nvapi-...` from
build.nvidia.com, model ids are NVIDIA's catalog ids):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey(key).baseUrl("https://integrate.api.nvidia.com/v1"));
```

**Ollama** (local, no key required — any non-empty string works; validated
2026-08-16 with `qwen3.6` through chat-cli: text, an approval-gated tool
round trip, and notebook writes. Honest performance note: on the same
Apple-Silicon machine and model family, LM Studio's MLX engine was notably
faster than Ollama's GGUF serving — both work, one waits):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey("ollama").baseUrl("http://localhost:11434/v1"));
```

**LM Studio** (local; validated 2026-08-15 against two models —
`google/gemma-4-e4b` and `qwen/qwen3.6-35b-a3b` — both a streamed text turn
and a tool-call round trip, on LM Studio's OpenAI-compatible endpoint):

```java
ModelProvider provider =
    OpenAiModelProvider.create(c -> c.apiKey("lm-studio").baseUrl("http://127.0.0.1:1234/v1"));
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
    AnthropicModelProvider.create(c -> c.apiKey("lm-studio").baseUrl("http://127.0.0.1:1234"));
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
Spring Boot for the property table and the
ambiguous-classpath failure mode.

## Where next

- Trying a Provider — the two-minute live smoke test
  for any provider path above.
- [Getting Started](getting-started.md) — the smallest agent, provider swap
  included.
- Spring Boot — the same provider selection, driven by
  `nessy.*` properties instead of environment variables.
- The Durable Loop — what a `Harness` built
  from a provider actually gives you.
