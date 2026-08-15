# Providers

`Nessy.harness(provider)` takes any `ModelProvider`. Two ship today —
`nessy-model-anthropic` and `nessy-model-openai` — and a third module,
`nessy-model-env`, picks between them from the environment so an application
can switch providers by switching a variable, not its code.

## Building one directly

Each provider module builds a `ModelProvider` the same way:

```java
ModelProvider provider = AnthropicModelProvider.builder().apiKey(key).build();
```

```java
ModelProvider provider = OpenAiModelProvider.builder().apiKey(key).build();
```

`.fromEnv()` on either builder delegates to that provider's own SDK
environment resolution — `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and
the rest of what the underlying SDK understands. Reach for the builder
directly whenever one of those matters; `nessy-model-env`, below, only ever
reads the API key.

## Switching by environment variable

`nessy-model-env` depends on both provider modules non-optionally — that's
the whole point, so either key just works with no per-provider dependency
choice left to the consumer:

```java
ModelProvider provider = EnvModelProviders.fromEnv();
```

`fromEnv()` decides which provider to build from what it finds in the
environment:

- `ANTHROPIC_API_KEY` present, `OPENAI_API_KEY` absent → Anthropic.
- `OPENAI_API_KEY` present, `ANTHROPIC_API_KEY` absent → OpenAI.
- Both present → `NESSY_PROVIDER` (`anthropic`/`openai`, case-insensitive)
  breaks the tie. An explicit, recognized choice is silent; unset or
  unrecognized defaults to Anthropic and logs one `WARN` line naming the
  default and how to override it.
- Neither present → fails fast with an `IllegalStateException` naming all
  three variables it checked.

Each provider is built the same way its own module builds one from an
explicit key — not that provider's own `fromEnv()`. The choice
`EnvModelProviders` makes from the environment is the choice that gets
built, not a second, independent read underneath it. One consequence: only
the API key is read. Base-URL overrides and other SDK-level environment
variables are silently ignored here — construct the provider directly when
one of those is needed.

`nessy-examples/chat-cli`'s `Chat` main is this in practice: one main, no
`if` branch for which provider module to import, because `fromEnv()` already
decided.

```java
ModelProvider provider;
try {
    provider = EnvModelProviders.fromEnv();
} catch (IllegalStateException e) {
    System.out.println(e.getMessage());
    System.exit(1);
    return;
}
```

## In a Spring Boot application

`nessy-autoconfigure` reads the same decision out of `nessy.provider` and
`nessy.{anthropic,openai}.*` properties instead of environment variables,
layered over each SDK's own `fromEnv()` resolution. See
[Spring Boot](spring-boot.md) for the property table and the ambiguous-both-
jars failure mode.

## Where next

- [Getting Started](getting-started.md) — the smallest agent, provider swap
  included.
- [Spring Boot](spring-boot.md) — the same provider selection, driven by
  `nessy.*` properties instead of environment variables.
- [The Durable Loop](../concepts/durable-loop.md) — what a `Harness` built
  from a provider actually gives you.
