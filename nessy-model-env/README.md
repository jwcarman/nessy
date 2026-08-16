# Nessy Model Env

The provider follows the key (design §4a, owner: "switching to openai would
be simply including that env var"). This module depends on
`nessy-model-anthropic`, `nessy-model-openai`, and `nessy-model-gemini`
non-optionally — that's the whole point: every provider rides along by
design, so any of their keys just works with no per-provider dependency
choice left to the consumer. xAI needs no fourth dependency at all — Grok
speaks OpenAI's own wire protocol, so it rides on `nessy-model-openai` with
its base URL pointed at `https://api.x.ai/v1`.

Two entry points, both reading the real process environment:

```java
ModelProvider provider = EnvModelProviders.fromEnv();
```

```java
EnvModelProviders.Selection selection = EnvModelProviders.select();
ModelProvider provider = selection.provider();
```

`fromEnv()` is the minimal form — just the provider. `select()` is for demos
and applications that also want to *show* what was chosen: it returns a
`Selection` record carrying the built `provider`, its lowercase
`providerName` (`"anthropic"`/`"openai"`/`"gemini"`/`"xai"`), and the `model`
that goes with it — so a banner or a log line can report both without
re-deriving them via `instanceof`.

## The five env vars

| Variable | Meaning |
|---|---|
| `ANTHROPIC_API_KEY` | Anthropic's key |
| `OPENAI_API_KEY` | OpenAI's key |
| `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) | Gemini's key — Google's own documented pair, in that order |
| `XAI_API_KEY` | xAI's (Grok's) key |
| `OPENAI_BASE_URL` | layered onto the OpenAI provider when `OPENAI_API_KEY` is the chosen path |
| `NESSY_PROVIDER` | breaks a tie when more than one key is present |
| `NESSY_MODEL` | names a model explicitly, honored first by `select()` |

`NESSY_PROVIDER` accepts `anthropic`, `openai`, `gemini`, or `xai`
(case-insensitive), plus the alias `grok` for `xai`.

## The switch

- Exactly one key present chooses that provider outright.
- Two or more present: `NESSY_PROVIDER` breaks the tie, read
  case-insensitively. Naming one of the keys actually present chooses it
  silently. Naming anything else — unset, unrecognized, or a provider whose
  key isn't among those present — falls back to the first present key in
  table order (Anthropic, then OpenAI, then Gemini, then xAI), logging one
  `WARN` line (SLF4J, `org.jwcarman.nessy.model.env.EnvModelProviders`)
  naming the default and how to override it — visible in the demos'
  consoles because their logback thresholds pass `WARN` through.
- None present → fails fast with an `IllegalStateException` naming all five
  variables it checked (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
  `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `XAI_API_KEY`, plus `NESSY_PROVIDER` as
  the way to break a future tie).

| Env var(s) | Provider built |
|---|---|
| `ANTHROPIC_API_KEY` | `AnthropicModelProvider` |
| `OPENAI_API_KEY` (plus `OPENAI_BASE_URL` if set) | `OpenAiModelProvider` |
| `GEMINI_API_KEY`, then `GOOGLE_API_KEY` | `GeminiModelProvider` |
| `XAI_API_KEY` | `OpenAiModelProvider` with `baseUrl("https://api.x.ai/v1")` |

Each provider is built the same way its own module builds one from an
explicit key (`Provider.builder().apiKey(key).build()`), not that provider's
own `fromEnv()` — the choice this class makes from the map handed to it is
the choice that gets built, not a second, independent read of the real
environment underneath it. `OPENAI_BASE_URL` is the one exception: it is
layered onto the OpenAI provider via `.baseUrl(String)` when
`OPENAI_API_KEY` is the chosen path, exactly as the OpenAI SDK's own
`fromEnv()` would honor it — the xAI path never reads it, since xAI's base
URL is fixed. One consequence of building this way: no other SDK-level
environment variable (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and
friends) is read by this helper — construct the provider directly
(`Provider.builder().fromEnv()`, which *does* delegate to the underlying
SDK's full environment support) when one of those is needed.

## The model

`select()` honors `NESSY_MODEL` first, when set and non-blank — it wins
outright regardless of which provider was chosen. That's the one way to name
a model whose provider instance can't reveal it on its own: a Grok,
OpenRouter, or LM Studio model reached through `OpenAiModelProvider`'s
base-url override looks, by type, exactly like an OpenAI model, so nothing
else can tell `select()` which model name is right for it. Without
`NESSY_MODEL`, `select()` falls back to a small, cheap default for the
chosen provider — Anthropic's Haiku, OpenAI's `gpt-4o-mini`, Gemini's
`gemini-2.5-flash`, or a current Grok default for xAI.

## Testing

Offline, entirely: the public `fromEnv()`/`select()` read the real process
environment, but every test drives the package-private
`fromEnv(Map<String, String>)`/`select(Map<String, String>)` seams instead —
no environment variable set, no network, the same honest-minimum shape the
store rework's own seams use.

## More recipes

Local runtimes (LM Studio, Ollama), gateways (OpenRouter, Gemini's own
OpenAI-compat endpoint), and every other `OPENAI_BASE_URL` combination are
covered in [`docs/guides/providers.md`](../docs/guides/providers.md) rather
than repeated here.
