# Configuration

Every property below binds through `NessyProperties`
(`@ConfigurationProperties(prefix = "nessy")`) in `nessy-autoconfigure`, or —
for the JDBC master switch — straight off the Spring `Environment` before
that bean exists at all. None of it is required: an application with a
`ModelProvider` on the classpath and an API key in the environment gets a
working `Harness` with no properties set.

Five beans are autoconfigured when their conditions are met: `ConversationStore`,
`Parks`, `Transcript`, `Memory`, and `PlanStore`. `SummaryStore` is **not**
autoconfigured — wire it yourself if you use summarizing memory.

## Properties

| Property | Default | Effect |
|---|---|---|
| `nessy.provider` | (unset) | Selects `anthropic` or `openai` when *both* provider modules are on the classpath and neither is unambiguously keyed. Required only in that two-jar, no-key case; an unrecognized value fails startup loudly. |
| `nessy.anthropic.api-key` | (unset) | Anthropic credential, layered on top of the SDK's own `fromEnv()` resolution (`ANTHROPIC_AUTH_TOKEN`, profile files, workload identity). An explicit property here always wins over an ambient environment variable. |
| `nessy.anthropic.base-url` | (unset) | Anthropic API base URL override, same layering as the API key. |
| `nessy.openai.api-key` | (unset) | OpenAI credential, layered on top of the SDK's own `fromEnv()` resolution the same way. |
| `nessy.openai.base-url` | (unset) | OpenAI API base URL override, same layering as the API key. |
| `nessy.default-model` | (unset) | Seeds `HarnessBuilder#defaultModel(String)`. Only applied when the property has text; otherwise every agent must name a model with `.model(...)` or building the agent fails. |
| `nessy.jdbc.enabled` | `true` | Master switch for the JDBC persistence autoconfiguration. Read straight from the environment by a `@ConditionalOnProperty` before any `@ConfigurationProperties` bean exists — setting it to `false` disables `ConversationStore`, `Parks`, `Transcript`, `Memory`, and `PlanStore` autoconfiguration even when a `DataSource` and `nessy-jdbc` are both present. |
| `nessy.jdbc.bootstrap-schema` | `true` | Whether each JDBC door runs its idempotent `CREATE TABLE IF NOT EXISTS` DDL once at startup. Set to `false` for a datasource whose schema another process already bootstrapped — the doors then use their bare constructors and open no DDL connection at all. |
| `nessy.jdbc.dialect` | (unset, resolved) | Overrides automatic dialect detection. One of `postgres`, `mysql`, `mariadb`, `sqlserver`, `oracle` (case-insensitive). Unset means every door resolves the dialect itself from the connection's `DatabaseMetaData` — set this only for a driver or proxy whose metadata reports something misleading. An unrecognized value fails startup loudly rather than silently falling back to resolution. |

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
OpenAiProviderAutoConfiguration.class, JdbcPersistenceAutoConfiguration.class})`,
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
