# Spring Boot

`nessy-spring-boot-starter` (plus `nessy-autoconfigure`, both in the BOM)
wires the durable stack by classpath: add a jar, get a bean, keep writing
your own agent. The starter itself ships no code of its own — every class an
application actually uses comes from `nessy-autoconfigure` and `nessy-core`.

## The razor

Substrate arrives by autoconfiguration; identity stays yours. A `Harness` is
infrastructure — the model provider, session store, observation registry,
object mapper, a seeded default model — so it's exactly what
`NessyAutoConfiguration` assembles from whatever beans the classpath and
configuration produced. An `AgentConfig` is identity: model, system prompt,
tools, policies, a particular agent's own shape. Nothing in this module ever
builds one. `Harness#agent(AgentCustomizer)` is always the application's own call, never
Boot's — an application declares one bean, the agent, and gets a durable
harness underneath it for free.

## Provider selection

Add a provider module (`nessy-model-anthropic`, `nessy-model-openai`,
`nessy-model-gemini`, and/or `nessy-model-bedrock`) and a `ModelProvider`
bean is autoconfigured from `nessy.provider` and
`nessy.{anthropic,openai,gemini}.*` properties, layered over the SDK's own
`fromEnv()` resolution. Those properties are overrides, not replacements: an
explicit `nessy.*` property outranks an ambient environment variable, and
`fromEnv()` is still called first so nothing else the SDK understands is
lost. Two or more `nessy.*.api-key` properties set at once with
`nessy.provider` unset fails fast, naming the property that resolves it —
the same shape [Providers](providers.md) describes for
`EnvModelProviders.fromEnv()`, expressed as configuration instead of
environment variables. With **no** `nessy.*.api-key` property set at all,
that fail-fast never fires — no `ModelProvider` bean is created, and the
application instead dies later on an unrelated missing-bean error. Set
exactly one `nessy.<provider>.api-key`, or `nessy.provider` plus that
provider's key.

**Bedrock is explicit-selection-only** and does not fit the pattern above:
it has no `nessy.bedrock.api-key` at all, and `BedrockProviderAutoConfiguration`
never builds its bean by classpath presence or by any key — only
`nessy.provider=bedrock`, set explicitly, wires it (bedrock-provider design
§4: ambient AWS credentials are common enough that letting them win, or even
enter an ambiguity count, would silently hijack unrelated deployments). Once
selected, the provider is built via `BedrockModelProvider.fromEnv()`
— the AWS SDK's own default credentials chain, plus `AWS_REGION` /
`AWS_DEFAULT_REGION` for the region — so an explicit choice with neither
region variable set fails startup, naming both.

Every autoconfigured bean here backs off the moment the application declares
its own: a hand-declared `Harness` suppresses the provider autoconfiguration
outright, since each provider bean also backs off the moment either a
`ModelProvider` or a `Harness` bean is already present — an app that
supplied its own `Harness` has, by construction, already brought its own
provider.

## Persistence

Add `nessy-jdbc` next to a `DataSource` bean, and a JDBC-backed
`ConversationStore`, `Parks`, `Transcript`, `AgentMemory`, `PlanStore`, `Notebook`,
`SubagentLinks`, and `IntentStore` are all autoconfigured — eight beans, covering
seven of the eight components [Durable Persistence](durable-persistence.md) wires
by hand with `JdbcPersistence.create` (`AgentMemory` is synthesized from the
autoconfigured `Transcript` bean, the same way `JdbcPersistence#memory()`
synthesizes it).
`nessy.jdbc.enabled` is the master switch;
`nessy.jdbc.bootstrap-schema` picks DDL-on-startup versus bring-your-own-
schema. Persistence wiring does not back off for a hand-declared `Harness`
the way provider selection does — it wires independently from classpath plus
`DataSource` plus property alone, so a hand-declared `Harness` may still
consume the autoconfigured store the same way `NessyAutoConfiguration`
itself does.

`SummaryStore` is not part of this autoconfiguration — no `SummaryStore` bean
exists anywhere in `nessy-autoconfigure` today. An application that wants a
summarizing pipeline builds its own (`JdbcSummaryStore.create(dataSource)`)
and wires it in by hand, past what the starter gives you:

```java
@Bean
Memory memory(Transcript transcript, DataSource dataSource, ModelProvider provider) {
    SummaryStore summaries = JdbcSummaryStore.create(dataSource);
    return Memory.pipeline(
        transcript,
        config ->
            config.summarizing(
                summaries, provider, "claude-haiku-4-5-20251001", "Summarize this conversation.", 20));
}
```

That bean, once declared, satisfies `@ConditionalOnMissingBean` and replaces
the autoconfigured plain-pipeline `AgentMemory` above.

A `Harness` is also fine with no store at all: `ConversationStore` and
`AgentMemory` are each optional, defaulting to an in-memory implementation when
neither the JDBC autoconfiguration nor the application supplies one.

## Wiring an agent on top

The application's own configuration injects the autoconfigured `Harness`
and, when `nessy-jdbc` is present, the autoconfigured `AgentMemory`:

```java
@Bean
Agent<String> agent(Harness harness, Memory memory) {
    return harness.agent(
        a ->
            a.name("assistant")
                .model("claude-sonnet-4-5")
                .memory(memory));
}
```

Nothing about `AgentConfig` changes in a Spring Boot application — tools,
grants, and policies are declared exactly the way [Getting
Started](getting-started.md) declares them.

## Properties

The whole surface is deliberately small — everything more exotic rides
`fromEnv()`'s own ambient resolution or a hand-declared bean:

| Property | Default | Meaning |
|---|---|---|
| `nessy.provider` | (none) | required only when two or more `nessy.*.api-key` properties are set at once |
| `nessy.anthropic.api-key` / `base-url` | SDK env | provider credentials, layered over `fromEnv()` |
| `nessy.openai.api-key` / `base-url` | SDK env | provider credentials, layered over `fromEnv()` |
| `nessy.gemini.api-key` / `base-url` | SDK env | provider credentials, layered over `fromEnv()` |
| `nessy.provider=bedrock` | (none) | the *only* way to select Bedrock — no `nessy.bedrock.*` properties exist; region/credentials come entirely from the AWS SDK's own `fromEnv()` (`AWS_REGION`/`AWS_DEFAULT_REGION` plus the default credentials chain) |
| `nessy.default-model` | (none) | harness-level default model, optional |
| `nessy.jdbc.enabled` | `true` | JDBC wiring master switch |
| `nessy.jdbc.bootstrap-schema` | `true` | run the idempotent DDL at startup |
| `nessy.jdbc.dialect` | (none, resolved) | `postgres`\|`mysql`\|`mariadb`\|`sqlserver`\|`oracle` override for a driver whose metadata lies |

`nessy.jdbc.bootstrap-schema` binds as a boxed `Boolean`, not a primitive, so
an absent property is distinguishable from an explicit `false` — the
autoconfiguration's own default is `true` when unset.

## Streaming over HTTP

With `spring-webmvc` on the classpath, a `TurnRunner` bean also appears: it
runs a turn on a virtual thread with the request's Micrometer context
propagated onto it, handing back the `SseEmitter` a controller streams from.
`TurnEventSse` maps that turn's `TurnEvent`s onto a stable wire vocabulary —
`delta`, `thinking`, `tool-requested`, `tool-progress`, `tool-decided`,
`tool-completed`, `tool-parked`, `message`, and `done` — so a browser client
can key off named listeners rather than parsing prose.

## Where next

- [Durable Persistence](durable-persistence.md) — the eight components
  `JdbcPersistence.create` wires by hand, `SummaryStore` included.
- [Observability](observability.md) — the `ObservationRegistry` bean
  `nessy-autoconfigure` picks up automatically, no wiring required.
- [Configuration](../reference/configuration.md) — the full property
  reference.
