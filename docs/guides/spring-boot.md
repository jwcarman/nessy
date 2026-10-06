# Spring Boot

`nessy-spring-boot-starter` wires an application's beans into a running
harness. It carries no code of its own; every bean lives in
`nessy-spring-boot-autoconfigure`, which the starter pulls in.

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-backend-jdbc</artifactId>
</dependency>
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-inference-anthropic</artifactId>
</dependency>
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/nessy
    username: nessy
    password: secret
nessy:
  provider: anthropic
  model: claude-sonnet-5-5
```

Set `ANTHROPIC_API_KEY` and the context has a `DirectHarnessFactory` bean,
built from that `DataSource` and the `anthropic` preset, registered and set
as the factory's default provider. Nothing answers on its own yet: an agent
type is a decision an application makes, not one the starter can make for
it, so the next step is declaring a harness from the factory. See
[The Harness](harness.md).

## Two doors, and a factory bean for each

An application picks the door its work needs. `DirectHarness.ask` is for a
caller standing there waiting on an answer; `QueuedHarness.tell` is for
work nobody is waiting on, written down and picked up by a dispatcher
later. Both can be present in the same application, and each has its own
factory interface, its own auto-configuration, and its own condition for
appearing at all:

| Auto-configuration | Fires when | Factory bean |
|---|---|---|
| `DirectHarnessAutoConfiguration` | a `DirectBackend` bean exists | `DefaultDirectHarnessFactory`, against the `DirectHarnessFactory` interface |
| `QueuedHarnessAutoConfiguration` | a `QueuedBackend` bean exists | `DefaultQueuedHarnessFactory`, against the `QueuedHarnessFactory` interface |

Neither auto-configuration asks for a database directly. Each asks for a
*backend*, and which backend answers that ask is the next section.

## The backend is chosen by the classpath

`nessy-backend-jdbc` on the classpath with a `DataSource` bean gives you
`JdbcBackendAutoConfiguration`: a `DirectBackend` and a `QueuedBackend`
backed by PostgreSQL, and a `Leases` bean for background work that must run
once across processes. `nessy-backend-inmemory` gives you
`InMemoryBackendAutoConfiguration`: the same three beans, with nothing
behind them but this process, so a restart loses everything they held.

`InMemoryBackendAutoConfiguration` is ordered after the JDBC one, so an
application with both modules on its classpath and a `DataSource` gets the
durable backend, and only an application with neither — a CLI, a test —
falls back to the in-memory one. That is what lets `nessy-console`'s
`Repl.run` and a plain unit test start the same starter with no database at
all: the direct door still appears, because something on the classpath
still supplied a `DirectBackend`.

There is no in-memory `QueuedBackend` fallback distinct from the in-memory
module's: `nessy-backend-inmemory` supplies both a `DirectBackend` and a
`QueuedBackend`, so the queued door is available under the same conditions
the direct door is — durable when JDBC wins the classpath race, in-process
otherwise.

An application that wants neither backend excludes both auto-configurations,
and both doors simply do not appear: nothing downstream demands a backend
that was never asked for.

## Properties

Everything below is read from `nessy.*`, bound by `NessyProperties`.

| Property | Default | Read by |
|---|---|---|
| `nessy.provider` | none; paired with `nessy.model` | both doors' factories, as the default `ProviderId` an agent type falls back on when it names none |
| `nessy.model` | none; paired with `nessy.provider` | both doors' factories, as the default model an agent type falls back on when it names none |
| `nessy.max-tokens` | 4096 | the same factory default, alongside `nessy.model` |
| `nessy.providers.<id>.api-key`, `.enabled`, `.wire`, `.base-url`, `.vendor` | none | turns a preset on or declares a custom provider, registered as an `InferenceProvider` bean named by its id; see [Providers](providers.md#boot-auto-configuration) |
| `nessy.providers.<id>.properties.<name>` | the preset's defaults (`openai.tools.strict=true` on `openai`) | the provider's [vendor properties](providers.md#vendor-properties); overlaid on the preset's by name, and overridden by an agent type's own |
| `nessy.embedder` | none; paired with `nessy.embedding-model` | the `EmbedderFactory` bean, as the default embedding provider a store falls back on when it names none; blank is unset |
| `nessy.embedding-model` | none; paired with `nessy.embedder` | the same factory default: the model a store falls back on |
| `nessy.embedding-dimension` | none; only beside the pair | the same factory default's width |
| `nessy.embedders.<id>.api-key`, `.enabled`, `.wire`, `.base-url`, `.vendor`, `.properties.*` | none | turns an embedding preset on or declares a custom embedder, registered as an `EmbeddingProvider` bean named `<id>Embeddings`; see [Providers](providers.md#embedders) |
| `nessy.system-prompt` | none | your own configuration, via `NessyProperties.resolveSystemPrompt()`; also the prompt-template auto-configuration when a prompt engine is on the classpath, where one of the two prompt properties is required at startup unless you declare your own `SystemPrompt` bean |
| `nessy.system-prompt-file` | none; a `Resource`. **Setting both is an error** | the same places as `nessy.system-prompt` |
| `nessy.type` | `agent` | ignored: nothing reads it. An agent's type is named when you call `factory.create(agentType, ...)`, not from a property |
| `nessy.initialize-schema` | `true`: apply every module's `nessy-schema.sql` at startup | `JdbcBackendAutoConfiguration`'s schema bean |
| `nessy.prompt.engine` | `spring`, or `mustache` | `PromptEngineAutoConfiguration` |
| `nessy.narration.odyssey.inactivity-ttl`, `entry-ttl`, `retention-ttl` | a day, a day, an hour | `OdysseyNarrationAutoConfiguration`, when Odyssey is present |
| `anthropic.api-key`, `openai.api-key`, `openai.base-url`, `xai.api-key`, `gemini.api-key`, `google.api-key`, `openrouter.api-key`, `nvidia.api-key`, `groq.api-key`, `mistral.api-key`, `cerebras.api-key`, `voyage.api-key` | light the matching inference or embedding preset; see [Providers](providers.md#boot-auto-configuration) |

`nessy.provider` and `nessy.model` are a pair: set both, or set neither and
name a provider and a model on every agent type instead. Both doors refuse
to start if only one is set, naming which.

`nessy.embedder` and `nessy.embedding-model` are the same kind of pair, for
embeddings: set both, or neither and name a provider and a model on every
store. The starter's own embedder factory refuses to start if only one is
set, and refuses a pair naming an embedder that is not registered, listing
the ones that are; an application `EmbedderFactory` bean replaces it, and
these checks with it.

Vendor properties bind as a map of strings, so a dotted name stays one key:

```yaml
nessy:
  providers:
    openai:
      properties:
        openai.reasoning.effort: high
        openai.tools.strict: "false"
    anthropic:
      properties:
        anthropic.thinking.budget_tokens: "8192"
```

Each value is read as the adapter's typed property
([`OpenAiProperties`, `AnthropicProperties` and the rest](providers.md#vendor-properties)):
a value outside a property's fixed set fails at startup listing the accepted
values (enum values are matched ignoring case).

They are best set in a configuration file. An environment variable cannot
name a property whose name contains an underscore or a capital letter
(`anthropic.thinking.budget_tokens`,
`gemini.generationConfig.thinkingConfig.thinkingBudget`): that follows
Boot's relaxed binding, which turns every `_` into `.` and lower-cases the
rest. A name with neither binds from the environment. To take any value from
the environment, name the property in the file and let the environment
supply the value: `openai.service_tier: ${OPENAI_SERVICE_TIER}`.

`nessy.type` does not name an agent type the way `nessy.model` names a
model. Nothing reads it, so setting it has no effect.

## Every bean backs off

Each bean below is `@ConditionalOnMissingBean`. Declare your own of the
same type and the starter's step aside, so the choice is written down where
a reader can find it.

**Always present** (`NessyAutoConfiguration`, unconditional):

| Bean | What it is |
|---|---|
| `CodecFactory` | Jackson over the context's `ObjectMapper`, with the `StorageCodecConfigurer` bean's transform appended |
| `StorageCodecConfigurer` | nothing appended, unless you declare one |
| `InferenceReport` | logs every registered provider once, at startup |

**Embeddings** (`EmbeddingProvidersAutoConfiguration`, unconditional):

| Bean | What it is |
|---|---|
| `EmbedderFactory` | `DefaultEmbedderFactory` under the bean name `nessyEmbedderFactory`: every registered embedding provider by id, the `nessy.embedder` default, every embedder observed; present with nothing registered, and says so when a store asks |
| `EmbeddingReport` | logs every registered embedder and the default once, at startup |

**With a `DirectBackend` bean** (`DirectHarnessAutoConfiguration`):

| Bean | What it is |
|---|---|
| `JsonSchemaGenerator` | `VictoolsJsonSchemaGenerator`, for tool arguments and constrained answers |
| `DefaultDirectHarnessFactory` | against the `DirectHarnessFactory` interface; built from the backend, every registered provider, the schema generator and the `ObjectMapper` |

Every `NarrationListener` bean is attached to it once the context has
started, so a listener may depend on the factory without a cycle.

**With a `QueuedBackend` bean** (`QueuedHarnessAutoConfiguration`):

| Bean | What it is |
|---|---|
| `DefaultQueuedHarnessFactory` | against the `QueuedHarnessFactory` interface; built from the backend, every registered provider, `nessy.provider`, `nessy.model` and `nessy.max-tokens` |
| `TurnHistories` | the story, read-only |
| `Replies` | the door a deferred answer comes back through |

Every `NarrationListener` bean is attached to it the same way.

**With either backend** (`UsageReportsAutoConfiguration`): a `UsageReports` bean, the usage of
each agent by model, projected from the stored events of both doors.

**With either backend** (`AgentWorkAutoConfiguration`): an `AgentWork` bean. `status` is answered
from the first store that holds the agent, the queued store and then the direct one. Waiting
approvals come from the queued backend; with only a direct backend the list is empty. An
application's own `AgentWork` bean replaces it. See
[The Harness](harness.md#what-is-waiting-and-answering-it).

**With a `DataSource` bean and `nessy-backend-jdbc` on the classpath**
(`JdbcBackendAutoConfiguration`):

| Bean | What it is |
|---|---|
| `DirectBackend`, `QueuedBackend` | `JdbcDirectBackend`, `JdbcQueuedBackend`, over the `DataSource`, the `PlatformTransactionManager` and the `CodecFactory` |
| `Leases` | `JdbcLeases`, an `INSERT ... ON CONFLICT` against `nessy_lease` |

**With no `DataSource` and `nessy-backend-inmemory` on the classpath**
(`InMemoryBackendAutoConfiguration`):

| Bean | What it is |
|---|---|
| `DirectBackend`, `QueuedBackend` | in-process, gone on restart |
| `Leases` | in-process; excludes other work in this JVM only |

## Leases

There is no separate lease module or property to add. Whichever backend
auto-configuration fires — `JdbcBackendAutoConfiguration` or
`InMemoryBackendAutoConfiguration` — contributes the `Leases` bean beside
its `DirectBackend` and `QueuedBackend`, because a lease is storage the
backend already owns. The JDBC one takes the lease with an `INSERT ... ON
CONFLICT` against `nessy_lease`, so it excludes other processes; the
in-memory one excludes only work in this JVM. See
[Leases](../concepts/leases.md) for what a lease is for.

## One codec for the whole application

`CodecFactory` is a single bean, so every store the engine writes through —
an agent's events, its payloads, the queued door's backlog and the effects it
owes — encodes the same way. What it does beyond Jackson is a separate
seam, `StorageCodecConfigurer`, so an application appends compression,
encryption, or both, once, and every store gets it:

```java
@Bean
StorageCodecConfigurer storage() {
  return original -> original.andThen(gzip).andThen(aesGcm);
}
```

`configure` is handed the transform assembled so far — the identity
transform the first time anything runs — and returns the transform to use
from here on, composed with `Codec.andThen`. It takes and returns a plain
`Codec<byte[]>`, not something generic over the stored type, because the
transform runs *after* Jackson has already turned a value into bytes: by
that point every store looks the same, and a seam that could see the
original type would invite a transform that only works for one of them.

No transform ships with the starter. The codec, its keys and their rotation
are the application's; declare nothing and `CodecFactory` hands back plain
Jackson.

## The schema

`nessy.initialize-schema` defaults to `true`: with `nessy-backend-jdbc` and a
`DataSource`, the starter gathers every module's `nessy-schema.sql` from the
classpath and runs them at startup, safe to repeat. An application using
Flyway or Liquibase sets it to `false` and applies the same files through
whichever migration tool it already runs — Boot looks for `schema.sql`,
Nessy's file is named `nessy-schema.sql`, and that name is the whole opt-in.
See [Storage](../concepts/storage.md).

The bean this produces, `JdbcBackendAutoConfiguration.NessySchema`, is a
marker record: nothing queries it, and everything that needs the tables to
exist first depends on it, so Spring builds it before them.

## A worked example

`nessy-examples/chat-web` declares a `QueuedHarness<String>` bean. The
starter provides the `QueuedHarnessFactory`, and the example calls its
`create(...)`. The factory binds no tools, so the example binds its own: a
date tool, the notebook and plan tools, and an email tool that needs an
approver and an approval term. It also sets the ambient notebook index and
current plan, and a backlog policy that joins messages sent while the agent
works. The configuration reads `NessyProperties.model()`, `.maxTokens()` and
`.resolveSystemPrompt()` itself and passes them to that call. `nessy-examples/watchman` is the same shape, doing
rounds on a timer against a real host.

## Where next

- [The Harness](harness.md), building a harness from the factory the
  starter gives you
- [Providers](providers.md#boot-auto-configuration), the presets and how an
  `InferenceProvider` bean joins the registry
- [Storage](../concepts/storage.md), the tables and the codec seam
