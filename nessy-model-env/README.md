# Nessy Model Env

The provider follows the key. One method, `EnvModelProviders.fromEnv()`,
picks a `ModelProvider` by which API key is present in the environment — so
an application built against this module switches providers by switching an
environment variable, not its code. This module depends on both
`nessy-model-anthropic` and `nessy-model-openai` non-optionally: that's the
whole point — both providers ride along by design, so either key just works
with no per-provider dependency choice left to the consumer.

```java
ModelProvider provider = EnvModelProviders.fromEnv();
```

## The switch

- `ANTHROPIC_API_KEY` present, `OPENAI_API_KEY` absent → Anthropic.
- `OPENAI_API_KEY` present, `ANTHROPIC_API_KEY` absent → OpenAI.
- Both present → `NESSY_PROVIDER` (`anthropic`/`openai`, case-insensitive)
  breaks the tie. An explicit, recognized choice is silent. Unset or
  unrecognized defaults to Anthropic and prints exactly one line to
  `System.err` naming the default and how to override it.
- Neither present → fails fast with an `IllegalStateException` naming all
  three variables it checked (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
  `NESSY_PROVIDER`).

Each provider is built the same way its own module builds one from an
explicit key (`Provider.builder().apiKey(key).build()`), not that provider's
own `fromEnv()` — the choice this class makes from the environment is the
choice that gets built, not a second, independent read underneath it.

## Testing

Offline, entirely: the public `fromEnv()` reads the real process environment,
but every test drives the package-private `fromEnv(Map<String, String>)` seam
instead — no environment variable set, no network, the same honest-minimum
shape the store rework's own seams use.
