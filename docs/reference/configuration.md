# Configuration

Every property below binds through `NessyProperties`
(`@ConfigurationProperties(prefix = "nessy")`) in `nessy-autoconfigure`, or —
for the JDBC master switch — straight off the Spring `Environment` before
that bean exists at all. None of it is required: an application with a
`ModelProvider` on the classpath and an API key in the environment gets a
working `Harness` with no properties set.

Six beans are autoconfigured when their conditions are met: `ConversationStore`,
`Parks`, `Transcript`, `AgentMemory`, `PlanStore`, and `Notebook`. `SummaryStore` is **not**
autoconfigured — wire it yourself if you use summarizing memory.

## Properties

| Property | Default | Effect |
|---|---|---|
| `nessy.provider` | (unset) | Selects `anthropic`, `openai`, `gemini`, or `bedrock` when two or more provider modules are on the classpath and each has its own `nessy.*.api-key` set — that combination fails fast, naming the property, unless this names the winner; an unrecognized value also fails startup loudly. With no `nessy.*.api-key` set at all, this fail-fast does not fire — no `ModelProvider` bean is created and the application instead fails later on an unrelated missing-bean error. Set exactly one `nessy.<provider>.api-key`, or this property plus that provider's key. `bedrock` is the one exception to all of the above: it has no `nessy.bedrock.api-key` and is never selected by key presence — `nessy.provider=bedrock` is the *only* way to choose it (see below). |
| `nessy.anthropic.api-key` | (unset) | Anthropic credential, layered on top of the SDK's own `fromEnv()` resolution (`ANTHROPIC_AUTH_TOKEN`, profile files, workload identity). An explicit property here always wins over an ambient environment variable. |
| `nessy.anthropic.base-url` | (unset) | Anthropic API base URL override, same layering as the API key. |
| `nessy.openai.api-key` | (unset) | OpenAI credential, layered on top of the SDK's own `fromEnv()` resolution the same way. |
| `nessy.openai.base-url` | (unset) | OpenAI API base URL override, same layering as the API key. |
| `nessy.gemini.api-key` | (unset) | Gemini credential, layered on top of `GeminiProviderConfig#fromEnv()`'s own resolution of `GEMINI_API_KEY` then `GOOGLE_API_KEY`. An explicit property here always wins over either ambient environment variable. |
| `nessy.gemini.base-url` | (unset) | Gemini API base URL override, same layering as the API key. |
| `nessy.default-model` | (unset) | Seeds `HarnessConfig#defaultModel(String)`. Only applied when the property has text; otherwise every agent must name a model with `.model(...)` or building the agent fails. |
| `nessy.jdbc.enabled` | `true` | Master switch for the JDBC persistence autoconfiguration. Read straight from the environment by a `@ConditionalOnProperty` before any `@ConfigurationProperties` bean exists — setting it to `false` disables `ConversationStore`, `Parks`, `Transcript`, `AgentMemory`, `PlanStore`, and `Notebook` autoconfiguration even when a `DataSource` and `nessy-jdbc` are both present. |
| `nessy.jdbc.bootstrap-schema` | `true` | Whether each JDBC door runs its idempotent `CREATE TABLE IF NOT EXISTS` DDL once at startup. Set to `false` for a datasource whose schema another process already bootstrapped — the doors then use their bare constructors and open no DDL connection at all. |
| `nessy.jdbc.dialect` | (unset, resolved) | Overrides automatic dialect detection. One of `postgres`, `mysql`, `mariadb`, `sqlserver`, `oracle` (case-insensitive). Unset means every door resolves the dialect itself from the connection's `DatabaseMetaData` — set this only for a driver or proxy whose metadata reports something misleading. An unrecognized value fails startup loudly rather than silently falling back to resolution. |

!!! note "Bedrock has no `nessy.bedrock.*` properties"
    Unlike the other three providers, `BedrockProviderAutoConfiguration`
    builds `BedrockModelProvider.fromEnv()` outright — the
    AWS SDK's own default credentials chain plus `AWS_REGION` /
    `AWS_DEFAULT_REGION`. There is nothing to override at the `nessy.*`
    layer; set those AWS variables (or run somewhere the SDK's credentials
    chain already resolves them) alongside `nessy.provider=bedrock`.

!!! note "`nessy.jdbc.enabled` is never read as a bound property"
    `Jdbc.enabled()` exists on `NessyProperties` for documentation and
    binding symmetry, but the persistence autoconfiguration never calls it.
    The condition that turns JDBC persistence on or off reads
    `nessy.jdbc.enabled` directly from the environment, because Spring
    evaluates `@ConditionalOnProperty` before any `@ConfigurationProperties`
    bean — including this one — is constructed.

## Autoconfiguration order

`NessyAutoConfiguration` — the class that builds the `Harness` bean itself —
is annotated `@AutoConfiguration(after = {AnthropicProviderAutoConfiguration.class,
OpenAiProviderAutoConfiguration.class, GeminiProviderAutoConfiguration.class,
BedrockProviderAutoConfiguration.class, JdbcPersistenceAutoConfiguration.class})`,
so it always composes last: whichever `ModelProvider`, `ConversationStore`,
and `Parks` beans the provider and JDBC autoconfigurations produced are
already in context by the time it runs.

## Where next

- [Spring Boot](../guides/spring-boot.md) — the starter end to end: what
  gets wired, what doesn't, and how to override any of it.
- [Durable Persistence](../guides/durable-persistence.md) — what surviving a
  restart actually requires.
- [TCK](tck.md) — certifying a custom store against the same contracts the
  JDBC doors pass.
