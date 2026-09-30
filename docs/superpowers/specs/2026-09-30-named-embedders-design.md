# Named embedders: a store says who embeds

**Status: DESIGN, NOTHING BUILT. The rulings were made in conversation with James on 2026-09-29
and this record writes them down. The proposals this record had to make to honour them are marked
as proposals, listed by name in §14, and await his yes; the one question the rulings left open --
how the default embedder is named under Boot -- is §15 (1), asked with a recommendation and not
answered. The places where the code, the earlier records and the brief disagree are in §15
rather than smoothed over.**

Date: 2026-09-30. The third of the three `0.3.0` items named in
`2026-09-29-openai-responses-design.md` §10: (1) the Responses adapter, (2) vendor properties, (3)
this record. It **mirrors** `2026-09-29-named-providers-design.md` -- the preset catalogue, the
`ProviderId` registry, application beans joining under their bean names, the startup report, the
`enabled` rules, the both-or-neither factory defaults -- and completes the embedding-side forward
references of `2026-09-30-vendor-properties-design.md` (§4b, §5b, §6d, §8e, §9f). It supersedes
the Boot half of `2026-09-20-embedding-provider-decision-record.md` ("no default `Embedder` bean
from the starter; `nessy.embedding.<vendor>.model` becomes the factory's default model") and
leaves its engine shape -- one generic factory, vendor-flavoured providers, width learned in the
facade, observability wrapping the embedder -- standing. Every path, name, signature and count
below was checked against `src/main/java`, `src/test/java`, the module poms and the LM Studio
instance on this machine, on branch `named-embedders` at the time of writing. This branch carries
the two earlier `0.3.0` records as documents only: `Wire` still has three values, `InferenceOptions`
has no `properties`, and `VendorProperties` does not exist; §15 (9) says which names below are
cited from those records rather than from code.

---

## 1. The problem

Under Boot the embedder is chosen by auto-configuration order, and unlike inference the choice is
written into every vector it makes.

Verified in source. `nessy-spring-boot-autoconfigure` has three embedding auto-configurations in
`org.jwcarman.nessy.spring.boot.embedding`, five bean methods between them, every one
`@ConditionalOnMissingBean(EmbedderFactory.class)`:

| class | bean methods | lights on | order |
|---|---|---|---|
| `VoyageEmbeddingAutoConfiguration` | `voyageEmbedders` | `nessy.embedding.voyage.api-key` | first: no `after` |
| `OpenAiEmbeddingAutoConfiguration` | `openAiEmbedders` (`@Conditional(OnNoOpenAiBaseUrl.class)`), `openAiCompatibleEmbedders` (`@ConditionalOnConfiguredProperty("nessy.embedding.openai.model")`) | `openai.api-key` | `after = Voyage` |
| `GeminiEmbeddingAutoConfiguration` | `geminiEmbedders` (`gemini.api-key`), `googleEmbedders` (`google.api-key`) | either key | `after = OpenAi` |

So the order is deliberate rather than alphabetical -- Voyage, then OpenAI, then Gemini -- and
`EmbeddingAutoConfigurationTest` pins it under the name `a_voyage_key_wins`. Deliberate or not,
it is one slot: three keys set is one factory built and two silently not, and the one that is
built is decided by a class annotation nobody reading `application.yaml` can see. The
`ImportSelector`-time back-off also means an application's own `EmbedderFactory` bean switches
every preset off at once (`it_backs_off_entirely`), which is the all-or-nothing the
named-providers record removed for inference (§7f there).

Two things make this worse than the inference version of the same bug:

- **Stored vectors carry the model that made them.** `Embedding` (`nessy-api`, `api.embedding`)
  is `(String model, float[] vector)` and refuses to compare across models; `JdbcEpisodes`
  (`nessy-memory-episodic`) writes `embedding` and `embedding_model` beside every summary
  (`nessy-schema.sql`, the `SUMMARIZE` statement) and ranks a row embedded by another model at
  `Double.NEGATIVE_INFINITY` (`Row.similarity`). An inference provider chosen by accident costs a
  404; an embedder chosen by accident writes rows that the next boot, with the accident undone,
  cannot rank.
- **Silence where a decision was needed.** `openai.api-key` with `openai.base-url` set and no
  `nessy.embedding.openai.model` builds nothing, on purpose (`with_a_base_url_and_no_model_named_
  nothing_is_made`), and the store ranks by recency without a line of log saying why. chat-web's
  `application.yml` reaches its LM Studio embedder by setting `openai.api-key` and
  `openai.base-url` to the chat endpoint, which -- its own comment admits -- also lights the
  `openai` *inference* preset, "registering an inference provider named openai that nothing here
  selects".

The rest of the shape is sound and stays. `EmbeddingProvider` (`nessy-embedding-spi`) holds a
connection and answers `embedDocuments(List<String>, EmbeddingOptions)`, `embedQuery(String,
EmbeddingOptions)` and `vendor()`; `DefaultEmbedderFactory` (`nessy-engine`, `engine.embedding`)
mints a `DefaultEmbedder` per store over one provider; `ObservedEmbedder` wraps each one as it is
minted. `Embedder.providerName()` and `EmbeddingProvider.providerName()` were renamed `vendor()`
in `0.2.0` with the inference side (`CHANGELOG.md`, the first breaking-change line), so the
named-providers record's §14 (2) is closed and nothing here renames anything.

## 2. The shape in one paragraph

An embedder factory holds embedding providers **by name**. A store names the provider it wants and
the model it wants when it asks for an embedder, and both are required -- the factory carries
defaults for either, and a store that says nothing gets them. The name is the existing value type
`ProviderId`, resolved once when the embedder is minted; an unknown or missing one fails there,
listing what is registered. Under Boot a second catalogue of presets, under its own namespace
`nessy.embedders.<id>`, turns a vendor's key into a registered embedding provider -- only when
that vendor's embedding adapter jar is on the classpath -- an application's own
`EmbeddingProvider` beans join the registry under their bean names, the factory default is a
both-or-neither pair of properties, and a startup report lists every embedder that lit. The
existing `nessy.embedding.*` properties are replaced, not aliased. Vendor properties reach the
embedding adapters the same way they reach the inference ones, from a helper that lives where
both families can reach it. Nothing about how a vector is made, stored or compared changes; what
changes is that the model on every stored row is a decision somebody wrote down.

## 3. Vocabulary

The named-providers words are reused with their meanings unchanged, and one is added.

| word | meaning | where it lives |
|---|---|---|
| **embedding provider** | an `EmbeddingProvider` instance, registered in an application under a `ProviderId` -- `openai`, `voyage`, `local` | `EmbeddingProvider` (`nessy-embedding-spi`), unchanged |
| **embedder** | an `Embedder`: one model at one width over one registered provider, minted by the factory for one store. The thing a store holds; never registered, never named | `Embedder` (`nessy-api`), unchanged |
| **preset** | a catalogue entry shipped with the Boot starter: an id, a wire, a base URL, a vendor, and its ingredient (a key, or an explicit `enabled`). Becomes an embedding provider when the ingredient is supplied **and the adapter is present** | `nessy-spring-boot-autoconfigure` only |
| **wire** | the API shape an embedding provider speaks, named for the vendor that defined it: `openai`, `gemini`, `voyage`. Prose and a property value; no SPI type | a Boot-internal enum (§4c) and a property value |
| **vendor** | the OpenTelemetry `gen_ai.provider.name` value an embedder reports: `openai`, `gcp.gemini`, `aws.bedrock`, `voyage` (ours; semconv names none) | `EmbeddingProvider.vendor()`, unchanged |

**Two parallel namespaces -- ruled by James, 2026-09-29.** `nessy.providers.<id>` stays exactly as
it shipped in `0.2.0` and means inference; `nessy.embedders.<id>` is new and means embeddings,
with the same mechanics. One namespace serving both, with each preset declaring what it offers
(the ROADMAP's own framing), was considered and **withdrawn**: the two families do not share URLs
or wires. Voyage is embeddings-only and has no inference wire; Anthropic is inference-only and has
no embeddings; Azure serves the two from separate deployments; and an `openai`-wire embedding
endpoint at a gateway is not evidence that the same gateway speaks Chat Completions. A shared
entry would have to carry two URLs, two wires and two `enabled`s, which is two entries wearing one
id. The ids may coincide -- `openai` under both, lit by the one `OPENAI_API_KEY` -- and that is
what an operator expects: the same key, the same vendor, two things it can do.

**Why "embedders" and not "embedding-providers".** The Boot property namespace names the thing an
operator configures, and an operator configures *the embedder for Voyage*, not an embedding
provider; the SPI word stays the SPI's. The register-time noun and the runtime noun differ by
design, as `nessy.providers` (the namespace) and `InferenceProvider` (the type) already do.

## 4. Value types

### 4a. `ProviderId` is reused, not twinned

An embedding provider is registered under a `ProviderId` (`nessy-api`, `org.jwcarman.nessy.api`,
`public record ProviderId(String value)` through `Identifiers.require(value, "provider id", 64)`).
Its javadoc says "one of its inference providers" and is widened to "one of its providers,
inference or embedding". The two registries are two maps; an id in one never meets an id in the
other, so nothing is gained by a second type, and an `EmbedderId` would be a new public concept
with no rule inside it that `ProviderId` lacks. **Proposed here** (§14): reuse, with the javadoc
amended.

### 4b. The model name stays `String`

For the reason the named-providers record gives (§5b there): it is the vendor's namespace.
`EmbeddingOptions.modelName` is already `String`, `Embedding.model` is already `String`, and the
column `nessy_episode.embedding_model` is already `TEXT`.

### 4c. `EmbeddingWire` is a Boot-internal enum

`enum EmbeddingWire { OPENAI("openai"), GEMINI("gcp.gemini"), VOYAGE("voyage") }` in
`org.jwcarman.nessy.spring.boot.embedding`, package-private, with `defaultVendor()` and
`propertyValue()` exactly as `Wire` has them (verified: `Wire.propertyValue()` lower-cases the
name and turns `_` into `-`). Bound from `nessy.embedders.<id>.wire`'s values `openai`, `gemini`,
`voyage`, so a typo is a binding error naming the allowed values.

**The OpenAI embeddings wire is `openai`, not `openai-embeddings` -- decided here, with the
reason.** The named-providers amendment of 2026-09-29 rules that a wire is the API shape a vendor
defined and that *a second shape from one vendor* gets a qualified name. `/v1/embeddings` is
OpenAI's one embeddings shape; there is no second one to tell it apart from. The qualifier would be
telling it apart from Chat Completions, which lives in a different enum bound from a different
property (`nessy.providers.<id>.wire`, where it is `openai` today and `openai-chat` after the
Responses record's rename), and the property path already says which family is meant:
`nessy.embedders.mine.wire: openai` cannot be read as a chat wire. Writing `openai-embeddings`
there would repeat the namespace in the value, and would be the only wire in either enum
qualified by a family rather than by a shape. `gemini` and `voyage` follow the same rule; Bedrock
has no value because Bedrock is not a preset (§6d).

## 5. The engine: a registry, resolved when the embedder is minted

### 5a. The factory holds named providers

`DefaultEmbedderFactory` (`nessy-engine`, `engine.embedding`) is built from a config, the way both
harness factories are, and its three constructors go:

```java
public static DefaultEmbedderFactory of(Customizer<EmbedderFactoryConfig> customizer);
public static DefaultEmbedderFactory of(List<Customizer<EmbedderFactoryConfig>> customizers);

public final class EmbedderFactoryConfig {
  /** Repeatable; a second registration under one id fails at once. */
  public EmbedderFactoryConfig provider(ProviderId id, EmbeddingProvider provider);
  /** The factory defaults: what an embedder gets when it names no provider or no model. */
  public EmbedderFactoryConfig embedding(ProviderId provider, EmbeddingOptions options);
  /** Every embedder minted is wrapped with this; NOOP when none is given. */
  public EmbedderFactoryConfig observations(ObservationRegistry observations);
}
```

`provider(ProviderId, EmbeddingProvider)` is the mirror of `DirectHarnessFactoryConfig.provider
(ProviderId, InferenceProvider)`; `embedding(ProviderId, EmbeddingOptions)` is the mirror of
`inference(ProviderId, InferenceOptions)` -- the one call shape for both defaults, the id and the
terms together, so a default model cannot be set without saying whose it is. `EmbeddingOptions`
already carries the width (`OptionalInt dimension`), so the factory-level default width the
current constructor takes as its third argument arrives inside the options, and the
`defaultDimension` field goes. The registry is `ProviderRegistry` (`engine.harness`) generified
over the provider type, or a twin of it beside `DefaultEmbedder`; either is a mechanical internal
and the implementer's call, with the one constraint that the observation wrap stays where §5e
puts it. **`EmbedderFactoryConfig` is proposed here** (§14): a public type, because
`DefaultEmbedderFactory` is public and non-Spring applications build it.

This is a breaking change to every caller of the three constructors: the three auto-configurations
(replaced anyway, §6), `docs/concepts/memory.md` lines 158 and 269, and the tests in
`nessy-engine` (`DefaultEmbedderFactoryTest`), `nessy-embedding-openai`
(`OpenAiEmbeddingProviderTest`, `OpenAiEmbedderLiveTest`) and the other three adapters' tests,
which build a factory over the provider under test. Acceptable at `0.3.0`.

### 5b. A store says which

`EmbedderConfig` (`nessy-api`, `api.embedding`) gains, beside `model(String)` and
`dimension(int)`:

```java
EmbedderConfig provider(ProviderId id);
EmbedderConfig provider(String id);   // ProviderId.of(id); a convenience, nothing more
```

the mirror of `InferenceConfig.provider(...)`. **Both provider and model are required for every
embedder**, its own or the factory's defaults -- the named-providers ruling (§6c there) applied
without a tiered table, for the same reason: a rule that made the provider optional when exactly
one was registered would be this record's §1 again, one preset later. A store that writes
`embedders.create(c -> c.provider("voyage").model("voyage-3.5").dimension(1024))` has said
everything; one that writes `create(c -> {})` gets the defaults, and the defaults are where it got
them.

### 5c. Resolution happens once, at `create`

`DefaultEmbedderFactory.create` resolves the config's `ProviderId` -- its own, or the factory
default -- against the registry, then the model, then mints. The failures, in the shape
`ProviderRegistry.Resolved.choose` already produces:

- no provider named and no factory default: `an embedder names no provider and the factory has no
  default; registered: [openai, voyage]`
- an id that is not registered: `an embedder names provider 'cohere', which is not registered;
  registered: [openai, voyage]`
- no model and no default: the existing message, `an embedder needs a model: model(...), or a
  factory default`.

There is no agent type to name, because a store is not an agent; the messages name what an
embedder said. Nothing downstream of `create` sees an id: `DefaultEmbedder` is handed the
provider itself, exactly as today.

### 5d. Provider-level defaults go

All four providers carry `defaultModel()` and `defaultDimension()`, read from `model(String)` and
`dimension(int)` on their configs (`OpenAiEmbeddingProvider` lines 42-43 and 64-71, and the
same on Gemini, Bedrock and Voyage), and the three auto-configurations fold them into the factory
through `EmbeddingModels.modelOr` / `dimensionOr`. The decision record of 2026-09-20 says the
provider "holds credentials and an endpoint and nothing about a model", and the code disagrees
with it (§15 (5)). This record sides with the decision record, **proposed** (§14): the four
`model(...)` / `dimension(...)` config setters and the two getters are deleted, the `DEFAULT_MODEL`
constants stay as the vendor's documented default a caller may cite (`EmbeddingOptions.of
(OpenAiEmbedderConfig.DEFAULT_MODEL)`; the live tests do), and the only default is the factory's
(§5a). A default in two places is a default with an order between them, and the order is the bug
this record exists to remove. `BedrockEmbedderConfig.cohereInputType`, `GeminiEmbedderConfig.
taskType` and `VoyageEmbedderConfig.inputType` are not model defaults -- they are connection-level
overrides of the document/query role -- and stay.

### 5e. Observability: the factory wraps, once

`ObservedEmbedder.wrap` is idempotent (verified: `delegate instanceof ObservedEmbedder already ?
already : new ...`). Today each auto-configuration wraps in a lambda around the factory
(`customizer -> ObservedEmbedder.wrap(embedders.create(customizer), observations)`) and
`docs/guides/observability.md` line 151 describes that lambda. The wrap moves into
`DefaultEmbedderFactory.create`, driven by `EmbedderFactoryConfig.observations(...)`, so a
non-Spring application that gives the factory a registry gets observed embedders without knowing
the wrapper's name -- the rule of `nessy-observability-wrapping` (2026-09-17: the engine wraps
everything it is handed). The wrap stays on the embedder rather than the provider, for the reason
the decision record gives: the span says which model was asked and how wide its vectors are, facts
of the embedder. Two stores minted over one provider share the provider and have their own
wrappers, which is right, because they are two models.

### 5f. Closing

Unchanged: a provider is `AutoCloseable` and owns its client; an embedder owns nothing. Under Boot
every registered provider is a bean and the container closes it (§6f); in code, whoever built it
closes it. `DefaultEmbedderFactory` closes nothing, as today.

## 6. Boot: presets, and what lights them

### 6a. Properties

```yaml
nessy:
  embedder: voyage                     # the factory default ProviderId (§6i; the name is §15 (1))
  embedding-model: voyage-3.5          # the factory default model; paired with the above
  embedding-dimension: 1024            # optional; the factory default width
  embedders:
    voyage:
      api-key: ${VOYAGE_API_KEY}       # or just export VOYAGE_API_KEY (§6b)
    lmstudio:
      enabled: true                    # keyless: must be turned on (§6c)
    gemini:
      enabled: false                   # a Gemini key is set for chat; no Gemini embedder wanted
    my-gateway:                        # not in the catalogue: a custom embedder (§6e)
      wire: openai
      base-url: https://gateway.example.com/v1
      api-key: ${GATEWAY_KEY}
      vendor: openai
      properties:                      # vendor properties, §7
        openai.user: episodes
```

`nessy.embedders.<id>.*` binds through the same mechanism as `nessy.providers.<id>.*`:
`Binder.get(environment).bind("nessy.embedders", Bindable.mapOf(String.class,
EmbedderSettings.class))` in a registrar that is a `BeanDefinitionRegistryPostProcessor`, with
`EmbedderSettings(wire, baseUrl, apiKey, enabled, vendor, properties)` -- the mirror of
`ProviderSettings` plus the vendor-properties record's `properties` component -- and its key
redacted in `toString` as `ProviderSettings` redacts.

### 6b. Hosted presets light from a key -- if the adapter is there

A hosted preset registers itself when its ingredient is present, from either of two places:

- the vendor's conventional environment property, through Boot's relaxed binding: `openai.api-key`
  (`OPENAI_API_KEY`), `gemini.api-key` **or** `google.api-key` (`GEMINI_API_KEY` /
  `GOOGLE_API_KEY`), and -- new -- `voyage.api-key` (`VOYAGE_API_KEY`, the variable
  `VoyageEmbedderConfig.fromEnv()` already reads). Voyage's key moves out from under
  `nessy.embedding.voyage.api-key`, where `VoyageEmbeddingAutoConfiguration`'s javadoc put it
  "because there is no Voyage chat model for it to be shared with"; a conventional key does not
  need a chat model to share with, it needs a conventional name, and `xai.api-key` sets the
  pattern;
- or `nessy.embedders.<id>.api-key`, for an application that keeps everything under one prefix.

**Ruled by James, 2026-09-29: a vendor's key lights an embedder only if that vendor's embedding
adapter jar is on the classpath; otherwise it is skipped with one INFO line.** This is the
inference rule mirrored (`ProviderRegistrar` line 85: "NESSY INFERENCE: {} is configured but {}
is not on the classpath; skipped"). It matters more here because every application with
`OPENAI_API_KEY` set has lit the `openai` inference preset since `0.2.0`, and most of them have
`nessy-inference-openai` and not `nessy-embedding-openai`; for them the embedder catalogue says,
once at startup, `NESSY EMBEDDING: openai is configured but nessy-embedding-openai is not on the
classpath; skipped`, and registers nothing. An operator who wants embeddings from that key adds
the jar; one who does not reads one line. The classpath check is `ClassUtils.isPresent` on the
adapter class name against the bean factory's class loader, as `WireProviders.isPresent` does,
so a test's `FilteredClassLoader` is honoured.

`openai.base-url` (`OPENAI_BASE_URL`) keeps overriding the `openai` preset's base URL, for the
same reason it does on the inference side: the getting-started guide teaches it and chat-web's
`application.yml` relies on it. It is the same key and the same URL pair, so the two presets
named `openai` move together.

### 6c. Keyless presets must be turned on

`lmstudio` (`http://localhost:1234/v1`) and -- pending measurement, §6d -- `ollama`
(`http://localhost:11434/v1`) have no key, so their ingredient is `nessy.embedders.<id>.enabled=
true`. No startup probing of a port, for the reason the named-providers record gives (§7c there).
`enabled: false` turns any embedder off whatever its ingredient: a Gemini key set for chat with no
Gemini embeddings wanted is `nessy.embedders.gemini.enabled: false`, and that is the only way to
say it, since the key is shared.

### 6d. The catalogue

A preset is `(id, wire, base URL, vendor, key properties, keyless api-key, default properties)`,
the mirror of `Preset` with the vendor-properties record's `defaultProperties`. Only vendors with
an existing embedding adapter are in it, and no row ships unmeasured.

| id | wire | base URL | vendor | ingredient | status |
|---|---|---|---|---|---|
| `openai` | `openai` | the vendor's own; `openai.base-url` overrides | `openai` | `openai.api-key` | in: measured by `OpenAiEmbedderLiveTest` |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` | in: measured by `GeminiEmbedderLiveTest` |
| `voyage` | `voyage` | `https://api.voyageai.com/v1` | `voyage` | `voyage.api-key` | in: measured by `VoyageEmbedderLiveTest` |
| `lmstudio` | `openai` | `http://localhost:1234/v1` | `lmstudio` | `enabled: true`; key `lm-studio` | candidate, measured once by hand (below); ships when the live row of §11d passes through the adapter |
| `ollama` | `openai` | `http://localhost:11434/v1` | `ollama` | `enabled: true`; key `ollama` | candidate, unmeasured: nothing answered on `:11434` on this machine on 2026-09-30 |

**LM Studio, measured on 2026-09-30 against the instance on this machine** (`/v1/models` listed
`text-embedding-nomic-embed-text-v1.5` and `text-embedding-qwen3-embedding-4b` beside the chat
models), with `curl` against our request shape (`model`, `input` as an array, `dimensions`):

- `POST /v1/embeddings` with two inputs answers the OpenAI shape: `object: list`, `data[]` with
  `index` and `embedding`, `model` echoed, 768 coordinates each for nomic. The adapter's reading
  (`OpenAiEmbeddingProvider.embedDocuments`, ordered by `index`) fits it.
- `usage` is `{prompt_tokens: 0, total_tokens: 0}`. Nothing here reads embedding usage, so nothing
  breaks; recorded so nobody later takes zero for a count.
- **`dimensions: 256` is silently ignored**: the reply is 768 wide. `DefaultEmbedder` learns its
  width from the first reply only when none was asked for (`dimension == 0`), so an embedder built
  with `dimension(256)` at LM Studio would *report* 256 and *produce* 768, and a store that sized
  an index from `dimension()` would be wrong. §15 (3) proposes the guard.
- **Any model name is answered.** `model: text-embedding-3-small` -- OpenAI's name, not loaded
  here -- and `model: definitely-not-a-model` both return a 768-wide vector, and the reply's
  `model` field names `text-embedding-nomic-embed-text-v1.5`, the loaded model, not the one
  asked for. The adapter stamps `options.modelName()` on every `Embedding` it returns
  (`OpenAiEmbeddingProvider` line 120), never the reply's `model`, so a store at LM Studio with a
  mistyped model name records vectors under a name that no model made. This is the sharpest
  finding of the measurement and it is §15 (2), because the fix is an adapter behaviour and not a
  catalogue row.

Neither finding stops the row: they are facts about what the preset serves, and the guide's row
says them. What stops it until §11d is that a `curl` is not the adapter, and the
named-providers rule is measurement through our code.

**Bedrock is not a preset -- ruled.** AWS credentials are ambient, and `GeminiAutoConfiguration`'s
old javadoc and the providers guide both record why a mechanism that let their presence register
a provider would route a stray profile to Bedrock. `BedrockEmbeddingProvider` joins as a bean
(§6f), exactly as `BedrockInferenceProvider` does, and has no wire value.

### 6e. A custom embedder

An id under `nessy.embedders.*` that is not in the catalogue must state `wire` and `base-url`;
`api-key` as the wire requires (`voyage` and `openai` require one; `gemini` requires one);
`vendor` optional, defaulting to the wire's own (`openai` for the `openai` wire, as
`Wire.defaultVendor()` does today). Missing `wire` or `base-url` fails startup naming the id and
the field, in `EmbedderCatalogue.custom` with `ProviderCatalogue.custom`'s messages
(`nessy.embedders.<id>.wire is required: <id> is not a preset`). `vendor` is honoured on the
`openai` wire only, as on the inference side; the Gemini and Voyage adapters report a fixed
vendor and the setting is ignored for them, and `EmbedderSettings`' javadoc says so.

The `openai` embedding adapter has no `vendor(String)` setter today (`vendor()` returns the
constant `"openai"`, line 95). It gains one, `OpenAiEmbedderConfig.vendor(String)`, the
mirror of `OpenAiProviderConfig.vendor(String)`, so the `lmstudio` and `ollama` presets and a
custom `openai`-wire embedder can report who they really are -- **proposed** (§14), a public
method on a vendor module.

### 6f. Application beans join the registry, and what they are called

An application's own `EmbeddingProvider` bean is registered under its **bean name**, beside the
presets, and the all-or-nothing `@ConditionalOnMissingBean(EmbedderFactory.class)` back-off on
each preset goes. Bedrock, a scripted provider in a test, and an in-process embedder all arrive
this way. A bean name that breaks `ProviderId`'s rule fails naming the bean, as
`DirectHarnessAutoConfiguration.providerId` does.

In the other direction, every lit preset is published as an `EmbeddingProvider` bean, so the
container owns its connection and closes it at shutdown, and so the report can enumerate beans of
the type. **Here the mirror bends, and this record says where.** Spring bean names are one
namespace across types, and `ProviderRegistrar` already registers an `InferenceProvider` bean
named `openai` when `OPENAI_API_KEY` is set (and refuses any other definition of that name, line
73). The embedding preset lit by the same key cannot also be the bean `openai`. So a preset's
`EmbeddingProvider` bean is named **`<id>Embeddings`** -- `openaiEmbeddings`, `geminiEmbeddings`,
`voyageEmbeddings`, `lmstudioEmbeddings` -- while its **registry id is the id**: the registrar
publishes a `ResolvedEmbedders` bean (id, bean name, wire, endpoint, vendor, property names) and
the factory bean (§6i) reads it to register each preset under its id, then sweeps every other
`EmbeddingProvider` bean under its bean name verbatim. No stripping of a suffix, no guessing:
an application bean called `bedrock` joins as `bedrock`, one called `bedrockEmbeddings` joins as
`bedrockEmbeddings`, and an application bean called `openaiEmbeddings` beside a lit `openai`
preset fails at the registrar with the same message the inference registrar uses (`a bean named
'openaiEmbeddings' and the openai embedder would both be registered as 'openaiEmbeddings'`).
An application bean called `openai` collides with the *inference* preset's bean, and Spring says
so before this record's code runs. **Proposed** (§14), because "named by its id" was the ruling
and this is the nearest a second registry in one bean namespace can come to it; §15 (4) records
the alternative that was set aside.

### 6g. The report

`EmbeddingReport` (`spring.boot.embedding`, a `SmartInitializingSingleton` like
`InferenceReport`) lists every registered embedding provider -- id, wire, endpoint, vendor,
property **names** (the vendor-properties ruling: never values, never the key) -- and the factory
defaults, because the default is a fact that exists only once the two properties are read
together:

```
NESSY EMBEDDING: embedders: openai (openai, the vendor's own endpoint, vendor openai); voyage (voyage, https://api.voyageai.com/v1, vendor voyage, properties [voyage.truncation])
NESSY EMBEDDING: default: voyage / voyage-3.5, 1024 wide
```

With nothing registered it says `NESSY EMBEDDING: no embedder is configured; stores rank by
recency` at **INFO**, not WARN: an application without embeddings is not misconfigured, and the
only thing that would have wanted one -- a store -- says so itself when asked to mint one. With
providers registered and no default it says `NESSY EMBEDDING: no default embedder; every store
names its own`, INFO. The vendor printed is the bean's own `vendor()`, as `InferenceReport`
does, because `ResolvedEmbedder.vendor()` can disagree with it on the wires that ignore an
override.

### 6h. Migration from `nessy.embedding.*` -- replaced, no aliases

**Ruled by James, 2026-09-29: the existing `nessy.embedding.*` properties are replaced by
`nessy.embedders.*`; `0.3.0` allows the break; no aliases.** Every old property, with its new form,
for the `CHANGELOG.md` `[Unreleased]` breaking-change entry:

| old | new |
|---|---|
| `nessy.embedding.openai.model` | `nessy.embedder: openai` + `nessy.embedding-model: <model>` (the pair, §6i) |
| `nessy.embedding.openai.dimension` | `nessy.embedding-dimension: <n>` |
| `openai.api-key` + `openai.base-url` + `nessy.embedding.openai.model` (the OpenAI-compatible bean) | `nessy.embedders.lmstudio.enabled: true` (or `ollama`, once measured) + the pair naming `lmstudio`; or the `openai` preset with `openai.base-url` overriding its endpoint, as before, plus the pair |
| `nessy.embedding.gemini.model` | `nessy.embedder: gemini` + `nessy.embedding-model: <model>` |
| `nessy.embedding.gemini.dimension` | `nessy.embedding-dimension: <n>` |
| `nessy.embedding.voyage.api-key` | `voyage.api-key` (`VOYAGE_API_KEY`), or `nessy.embedders.voyage.api-key` |
| `nessy.embedding.voyage.model` | `nessy.embedder: voyage` + `nessy.embedding-model: <model>` |
| `nessy.embedding.voyage.dimension` | `nessy.embedding-dimension: <n>` |
| "a key alone is enough, and the model is the vendor's default" | gone: a key alone registers the provider; minting an embedder needs a model, from the store or from the pair. The vendor defaults live on in `*EmbedderConfig.DEFAULT_MODEL` for a caller to cite |
| `VoyageEmbeddingAutoConfiguration`, `OpenAiEmbeddingAutoConfiguration`, `GeminiEmbeddingAutoConfiguration` | `EmbeddingProvidersAutoConfiguration`; update any `spring.autoconfigure.exclude` naming them |
| an application `EmbedderFactory` bean switching every preset off | still backs the starter's **factory** off (`@ConditionalOnMissingBean(EmbedderFactory.class)` on the one factory bean, as `DirectHarnessFactory`'s is); the presets are still registered as `EmbeddingProvider` beans for it to use or ignore |
| an application `EmbeddingProvider` bean (none today; the type was not swept) | joins the registry under its bean name |

`ConditionalOnConfiguredProperty`, `OnConfiguredProperty`, `OnNoOpenAiBaseUrl` and
`EmbeddingModels` are deleted with the classes that used them. The one behaviour they carried
that must survive -- a property set to blank, as `${CHAT_EMBEDDING_MODEL:}` yields when the
variable is unset, means unset -- survives in `EmbedderCatalogue.firstNonBlank`, the copy of
`ProviderCatalogue.firstNonBlank`, and in the both-or-neither check treating blank as unset
(`DirectHarnessAutoConfiguration.requireBothOrNeither` already does). Whether a blank
`enabled=` binds as null or fails is a Boot binder fact this record has not measured; §11a pins
it either way.

### 6i. The factory bean, and how a store gets its embedder

`EmbeddingProvidersAutoConfiguration` contributes, beside the registrar and the report, one
`EmbedderFactory` bean, `nessyEmbedderFactory`, `@ConditionalOnMissingBean(EmbedderFactory.
class)` -- against the interface, for the reason `DirectHarnessAutoConfiguration` gives in its
comment. It is built as the harness factories are built: the presets from `ResolvedEmbedders`
under their ids, every other `EmbeddingProvider` bean under its bean name, the
`ObservationRegistry`, and the defaults from the pair of properties. It exists whether or not
anything is registered, because `@ConditionalOnBean` cannot see definitions a registry
post-processor added (Boot's own caveat), and a factory with nothing registered says so at
`create` by listing `[]`.

**The default embedder is a both-or-neither pair -- proposed, for James's ruling, §15 (1).**
`NessyProperties` gains `embedder` (the default `ProviderId`), `embeddingModel` and
`embeddingDimension`; the first two are a pair (`requireBothOrNeither`, the message naming both),
the third is optional and needs the pair. The reason is the one `nessy.provider` / `nessy.model`
have: a default model with no provider is a model sent to whoever comes first, and a default
provider with no model is the vendor-default behaviour §6h retires.

A store then asks the factory. chat-web's `ChatConfiguration.episodes` today takes
`ObjectProvider<EmbedderFactory>` and mints from it when a factory exists, because "no factory"
was how the starter said "no embeddings". The factory now always exists, so the application says
what it means instead:

```java
@Bean
public JdbcEpisodes episodes(DataSource dataSource, EmbedderFactory embedders, NessyProperties properties) {
  Embedder embedder = properties.embedder() == null ? null : embedders.create(c -> {});
  return JdbcEpisodes.of(c -> c.dataSource(dataSource).agentType(TYPE).embedder(embedder));
}
```

-- the store ranks by relevance when the application named a default embedder, and by recency
when it did not, and a mistyped default fails at startup listing what is registered rather than
at the first summary. A store with an opinion of its own names it (`create(c ->
c.provider("voyage").model("voyage-3.5"))`) and ignores the pair. chat-web's `application.yml`
becomes:

```yaml
nessy:
  embedder: ${CHAT_EMBEDDER:}            # set to lmstudio for relevance ranking; blank for recency
  embedding-model: ${CHAT_EMBEDDING_MODEL:}
  embedders:
    lmstudio:
      enabled: true
      base-url: ${CHAT_MODEL_URL:http://localhost:1234/v1}
```

and the `openai:` block that lit an inference provider nobody selected goes, which is the
comment in that file made true.

## 7. Vendor properties for embedders

The vendor-properties record specified the embedding side and deferred two things to this item:
the Boot binding and the home of the helper. Both are decided here.

### 7a. The binding

`nessy.embedders.<id>.properties.<prefixed.name>=<value>`, bound as `Map<String, String>` on
`EmbedderSettings` -- the same measured binder behaviour that keeps `openai.reasoning.effort` one
flat key (that record's §6b) keeps `voyage.truncation` one here. `EmbedderCatalogue.resolve`
overlays the settings' entries on the preset's `defaultProperties` name by name;
`ResolvedEmbedder.properties` carries the merge; `WireEmbedders.build` calls
`c.properties(resolved.properties())` on the adapter config (the `property` / `properties` pair
that record's §6a puts on all four embedder configs). No preset ships a default property in this
record: nothing measured asks for one.

### 7b. Where the helper lives -- decided here

The vendor-properties record put `VendorProperties` in `nessy-inference-spi` and left the
embedding side to this record, naming three options: `nessy-embedding-spi` depending on the
inference SPI for one class, a copy, or a shared support module. Read against the poms:

- `nessy-embedding-spi` depends on `nessy-api` and nothing else; `nessy-inference-spi` depends on
  `nessy-api`, `jackson-databind`, `jackson-annotations`, `codec-core` and `jspecify`. A
  dependency from the embedding SPI on the inference SPI would drag the codec into every embedding
  adapter and, worse, would say in the build graph what `Embedder`'s javadoc denies in words --
  "its own seam, apart from inference, on purpose". Rejected.
- A copy is two classes that agree about what `true` means until one of them is fixed. Rejected,
  for the reason that record gave against per-adapter copies.
- `nessy-api` was rejected overnight as the application-facing insulator, and that ruling stands.

**Chosen: a new module, `nessy-vendor-properties`**, holding the one public class
`org.jwcarman.nessy.vendor.VendorProperties` and depending on `jackson-databind` alone (the
`literal` and `nest` functions take a `JsonMapper`). The inference adapters and the embedding
adapters depend on it; neither SPI does, because the class appears in no SPI signature (that
record's §8e: "it appears in no signature"). It is the smallest module in the reactor and it says
exactly what it holds. Since the vendor-properties item is unbuilt on every branch, this is an
amendment to that record's §8e made before a line of it exists: `VendorProperties` is written in
the new module from the start, and step 1 of that record's sequencing creates the module. **A new
module is public surface and is proposed here** (§14). The alternative kept on the table is the
adapters -- not the SPI -- depending on `nessy-inference-spi` for the class, which costs no new
module and puts an inference jar on every embedding adapter's classpath; §15 (6).

### 7c. The adapters' tables

Unchanged from the vendor-properties record's §9f, restated so this record is whole: no known
names to start; every embedder passes through; the clash table is the typed width's wire spelling
plus the fields the adapter writes.

| adapter | prefix | clash table | pass-through lands in |
|---|---|---|---|
| `OpenAiEmbeddingProvider` | `openai.` | `model`, `input`, `dimensions` | `EmbeddingCreateParams.Builder.putAdditionalBodyProperty` |
| `GeminiEmbeddingProvider` | `gemini.` | `model`, `contents`, `outputDimensionality`, `taskType` | `EmbedContentConfig.Builder.httpOptions(...)`'s `extraBody` |
| `BedrockEmbeddingProvider` | `bedrock.` | `inputText`, `texts`, `dimensions`, `normalize`, `input_type`, `truncate` | a `put` on the `ObjectNode` body (lines 151-165) |
| `VoyageEmbeddingProvider` | `voyage.` | `model`, `input`, `output_dimension`, `input_type` | a `put` on the `ObjectNode` body (lines 146-150) |

`EmbeddingProvider.validate(EmbeddingOptions)` is called by `DefaultEmbedderFactory.create` after
resolution and before the embedder is minted, so a clash fails where a store is built and not at
its first summary. `Bedrock`'s `Family.of` check (`a model of an unknown family is refused when it
is used`) is the kind of thing `validate` exists for and moves there in the same step, so a Bedrock
embedder over a non-Titan, non-Cohere model fails at `create`; a behaviour improvement that costs
one line and is noted so the test name changes with it.

## 8. Consumers, and what switching means

### 8a. Who holds an embedder

By search, `Embedder` is held by exactly one production class: `JdbcEpisodes` (`Config.embedder
(Embedder)`, line 135; the field at 172). `JdbcNotebook` takes none -- the brief named the
notebook as a consumer, and it is not one yet (ROADMAP line 44: "the same for `AmbientSource` and
the notebook" is what remains). The examples: chat-web's `ChatConfiguration.episodes` mints one
(§6i); watchman, chat-cli, mcp and policy do not embed. So this record changes one store's
javadoc (`Config.embedder`: "the embedding model belongs to the store" gains "and the provider it
is minted from is named where the embedder is minted") and one example.

### 8b. Switching a store's embedder

Every stored vector already carries its model (`Embedding.model`, `nessy_episode.embedding_model`),
and `JdbcEpisodes.Row.similarity` ranks a row from another model below every real score. So the
consequence of changing `nessy.embedder` / `nessy.embedding-model`, or a store's own
`create(...)` call, is already decided by the code and this record only states it: the next
summary is embedded by the new model and ranks; every earlier summary ranks last until it is
embedded again; nothing is lost and nothing is compared that cannot be. The report (§6g) is where
an operator sees which model a boot is writing with, and `Embedder.model()` is where a store can
check it. **Re-embedding rows written by a previous embedder is a ROADMAP item** (line 33,
"re-embedding rows written by a previous embedder") and this record references it without
solving it: a re-embedding pass needs the old rows' text, a batch budget and a place to run, none
of which a registry provides.

One consequence is new. Two registered providers can serve one model name -- `openai` and a
custom `openai`-wire gateway both offering `text-embedding-3-small` -- and a row records the model,
not the provider. Vectors from the two are comparable if the gateway is honest and silently not if
it is LM Studio answering a name it does not serve (§6d). The stored model is the contract, and
§15 (2) is where the adapter is asked to hold the vendor to it.

## 9. Non-Spring applications

Register instances by id in code and nothing else:

```java
EmbedderFactory embedders = DefaultEmbedderFactory.of(f -> f
    .provider(ProviderId.of("openai"), OpenAiEmbeddingProvider.fromEnv())
    .provider(ProviderId.of("local"), OpenAiEmbeddingProvider.of(c -> c.apiKey("lm-studio").baseUrl("http://localhost:1234/v1").vendor("lmstudio")))
    .embedding(ProviderId.of("openai"), EmbeddingOptions.of("text-embedding-3-small"))
    .observations(registry));

Embedder forEpisodes = embedders.create(c -> {});                                   // the defaults
Embedder forNotes    = embedders.create(c -> c.provider("local").model("text-embedding-nomic-embed-text-v1.5"));
```

There is no catalogue outside Boot, for the reason the named-providers record gives (§8 there).
`docs/concepts/memory.md`'s two examples (lines 157-163 and 268-270) are rewritten to this shape.

## 10. Not being done

Each of these was raised and set aside, with the reason:

- **One namespace for both families.** Withdrawn by James (§3): URLs and wires differ per family.
- **A default model per preset.** The named-providers reason (model names churn) plus this
  record's own: a vendor changing its default embedding model would silently change what every
  store writes, and `Embedding.model` is the one thing a store must be able to trust.
- **Inferring the provider from the model name.** `nomic-embed-text` is served by Ollama, LM
  Studio and a dozen gateways; `text-embedding-3-small` by OpenAI, Azure and any proxy. Same
  ambiguity as inference, same rejection.
- **Choosing the embedder per agent type.** ROADMAP line 166 asked "who chooses the embedder (a
  store, not an agent type)" and the answer is the store: the vectors are the store's, the model is
  recorded beside them, and an agent type that could name an embedder would put a second model
  into one table. `EmbedderConfig` is where the choice is made and `InferenceConfig` does not get
  an embedding knob.
- **Re-embedding.** ROADMAP; §8b.
- **A `pgvector` index, an ONNX in-process embedder, a `Reranker`.** ROADMAP line 41 onward;
  none is a registry concern. An in-process embedder, when it comes, joins as an
  `EmbeddingProvider` bean under its bean name and needs nothing here.
- **Startup probing of local ports.** Named-providers §7c; the same rejection.
- **Aliases for `nessy.embedding.*`.** Ruled out; §6h.
- **Timeout setters on the OpenAI and Gemini embedder configs.** `WireProviders` sets
  `TransportTimeouts.PROVIDER_TRANSPORT` on every inference provider; `OpenAiEmbedderConfig` and
  `GeminiEmbedderConfig` have no `timeout(...)` (`VoyageEmbedderConfig` has). `WireEmbedders` sets
  what each config offers and no more; adding the two setters is a small follow-up, not this item.

## 11. Testing

House rules throughout: prose-style test names in each module's existing voice
(`a_voyage_key_registers_one_embedder`, `a_store_that_names_its_own_provider_gets_that_one`),
`@DisplayName` sentences, **no mocking library** -- providers in tests are small scripted classes
(`DefaultEmbedderFactoryTest`'s `Asked`, `KeywordEmbedder` in `nessy-memory-episodic`) or JDK
proxies (`OpenAiEmbeddingProviderTest`'s `Proxy.newProxyInstance` over `OpenAIClient`), and
`VoyageEmbedderTest`'s in-process HTTP server stays. Every `assertThatThrownBy` lambda holds
exactly one call that can throw, with the factory, the config and the ids built outside it
(S5778); an emptiness assertion precedes any `allMatch` / `noneMatch`. Everything in §11a-§11c
passes with no key and no network; keys in the matrix are `sk-test`-style strings never sent.

### 11a. The `ApplicationContextRunner` matrix

`EmbeddingProvidersAutoConfigurationTest` replaces `EmbeddingAutoConfigurationTest`. Each row is
a test; the point is that every outcome is decided by something written down.

| keys / properties | expected |
|---|---|
| none | no `EmbeddingProvider` bean; the factory bean exists; `create(c -> {})` fails listing `[]`; the report says no embedder is configured |
| `openai.api-key` | one embedding provider, bean `openaiEmbeddings`, wire `openai`, vendor `openai` |
| `openai.api-key` with `nessy-embedding-openai` filtered from the classpath | none registered; the INFO skip line names `nessy-embedding-openai` |
| `openai.api-key`, `gemini.api-key`, `voyage.api-key` | three registered, none chosen |
| `openai.api-key`, `voyage.api-key` | two -- the row that today makes Voyage "win" |
| `gemini.api-key` and `google.api-key` both set | **one** embedding provider, `gemini` |
| `nessy.embedders.voyage.api-key` with no `voyage.api-key` | `voyage` registered from the prefixed form |
| `NESSY_EMBEDDERS_VOYAGE_APIKEY` in a system-environment source | the same |
| `openai.api-key`, `openai.base-url=http://localhost:1234/v1` | `openai` at the overridden endpoint; **no** requirement that a model be named, unlike today |
| `nessy.embedders.lmstudio.enabled=true` | `lmstudio` registered, key `lm-studio`, vendor `lmstudio` |
| nothing about lmstudio | absent |
| `nessy.embedders.lmstudio.enabled=` (blank, the `${VAR:}` shape) | absent, and the context starts -- the binder fact §6h left open, pinned here |
| `gemini.api-key`, `nessy.embedders.gemini.enabled=false` | the Gemini embedder is absent while the Gemini inference preset (run with `InferenceProvidersAutoConfiguration` in the same context) is present |
| `nessy.embedders.mine.wire=openai`, `base-url`, `api-key` | `mine` registered, vendor `openai` by default |
| `nessy.embedders.mine.base-url` with no `wire` | fails to start naming `mine` and `wire` |
| `nessy.embedders.mine.wire=bogus` | fails to start naming `openai`, `gemini`, `voyage` |
| `nessy.embedders.mine.wire=openai-embeddings` | fails to start naming the three values -- the §4c decision made checkable |
| an application `EmbeddingProvider` bean `scripted` plus `openai.api-key` | two registered, `scripted` and `openai`; `create(c -> c.provider("scripted").model("m"))` reaches the bean |
| an application bean `openaiEmbeddings` plus `openai.api-key` | fails at startup naming both |
| an application `EmbedderFactory` bean plus `openai.api-key` | the starter's factory backs off; the `openaiEmbeddings` provider bean still exists |
| `nessy.embedder=voyage`, `nessy.embedding-model=voyage-3.5`, `voyage.api-key` | `create(c -> {})` mints `voyage-3.5` over the Voyage provider |
| `nessy.embedder=voyage` alone | fails to start naming the pair |
| `nessy.embedding-model=voyage-3.5` alone | fails to start naming the pair |
| `nessy.embedder=cohere` with `openai.api-key` | fails at startup listing `[openai]` |
| `nessy.embedding-dimension=256` with the pair | `create(c -> {})` reports `dimension() == 256` |
| `nessy.embedders.voyage.properties.voyage.truncation=false` with `voyage.api-key` | one entry keyed `voyage.truncation` reaches the provider; the report names it and not its value |
| every registered provider, with and without an `ObservationRegistry` bean | `create(...)` returns an `ObservedEmbedder` |

`EmbedderCatalogueTest`, `EmbedderSettingsTest` and `ResolvedEmbedderTest` mirror their inference
twins (`ProviderCatalogueTest` has twenty-seven cases today; the embedding one covers the same
branches over three wires and the skip rule); `EmbeddingReportTest` asserts the two lines of §6g
and the two INFO cases.

### 11b. The engine

`DefaultEmbedderFactoryTest` keeps its three width cases on the new construction and gains:

- a store naming a registered id gets that provider (two `Asked` providers, and the vector says
  which was asked);
- a store naming nothing gets the factory default;
- a store naming an unknown id fails at `create` listing every registered id (non-empty, then
  contents);
- no default and no id fails with the other message;
- registering one id twice fails at registration;
- two stores on one id share one provider and have two wrappers;
- `validate` is called with the resolved options and its failure surfaces from `create`;
- with a registry given, `create` returns an `ObservedEmbedder`; with none, it still does, over
  `NOOP`.

`ObservedEmbedderTest` is untouched.

### 11c. The adapters

Each adapter's `*ProviderConfigTest` / `what_is_refused_at_configuration` loses its `model` and
`dimension` cases (§5d) and `OpenAiEmbedderConfigTest` gains `vendor(String)` reported by
`vendor()`. `BedrockEmbedderTest`'s `a_model_of_an_unknown_family_is_refused_when_it_is_used`
becomes `..._is_refused_when_the_embedder_is_built`. The vendor-properties cases of that record's
§13a run against the four adapters once the helper module exists (§7b).

### 11d. Live

One row per embedding vendor, every test `@Tag("live")` and skipped without its key, as the four
existing live tests are (`assumeTrue(System.getenv("VOYAGE_API_KEY") != null, ...)` and the
others): `OpenAiEmbedderLiveTest`, `GeminiEmbedderLiveTest`, `BedrockEmbedderLiveTest`,
`VoyageEmbedderLiveTest` keep `near_texts_are_nearer_than_far_ones` on the new factory shape, and
each gains: a query embedded as a query and a document as a document are both the model's width
and the model name on the `Embedding` is the one asked for. Added:

- `LmStudioEmbedderLiveTest` (`nessy-spring-boot-autoconfigure`, beside `LmStudioPresetLiveTest`,
  gated the same way): `nessy.embedders.lmstudio.enabled=true` with the pair naming
  `text-embedding-nomic-embed-text-v1.5`, through the whole starter -- the vector is 768 wide,
  `dimension()` reports 768 when none was asked, and a `dimension(256)` embedder is the
  measurement behind §15 (3). Its pass is what moves the `lmstudio` row from candidate to preset.
- An `ollama` row in the same class, gated on `:11434` answering, which turns that row on when
  somebody with Ollama runs it; until then the catalogue does not carry it.

## 12. Sequencing

Five steps, each a reviewable commit, each green under `./mvnw -q clean verify` with no key and no
network before the next starts. Step 3 waits on the vendor-properties item; nothing else does.

1. **The engine** (§5): `EmbedderFactoryConfig`, `DefaultEmbedderFactory.of(...)`, the registry,
   `EmbedderConfig.provider(...)`, resolution and its messages, the observation wrap in the
   factory; the four configs lose `model` / `dimension` and the providers lose the two getters;
   `OpenAiEmbedderConfig.vendor(String)`; `ProviderId`'s javadoc; the engine and adapter tests.
   The three auto-configurations are moved to the new construction with one provider registered
   under its vendor's id and the old properties still read, so the starter keeps working between
   this step and the next -- the same bridge the named-providers record used in its step 2.
2. **Boot** (§6): `EmbeddingWire`, `EmbedderPreset`, `EmbedderSettings`, `ResolvedEmbedder`,
   `ResolvedEmbedders`, `EmbedderCatalogue`, `EmbedderRegistrar`, `WireEmbedders`,
   `EmbeddingReport`, `EmbeddingProvidersAutoConfiguration` with the factory bean; the pair on
   `NessyProperties`; the three old auto-configurations, two conditions and `EmbeddingModels`
   deleted; the `AutoConfiguration.imports` line; the matrix of §11a; the changelog's breaking
   lines with the table of §6h.
3. **Vendor properties** (§7), after that item lands: the `nessy-vendor-properties` module (or
   its move, if that item built the class in the inference SPI first), the four adapters reading
   their prefix, `EmbedderSettings.properties` through to `WireEmbedders`, `validate` called from
   `create`, the Bedrock family check moved.
4. **Live measurement** (§11d): the four live tests on the new shape, `LmStudioEmbedderLiveTest`;
   the `lmstudio` row committed alone on its pass; the `ollama` row when measured.
5. **Examples and docs**: chat-web's `ChatConfiguration.episodes`, `application.yml` and
   `EpisodesWiringTest` (which sets `nessy.embedding.openai.model` today, line 39);
   `docs/concepts/memory.md` (the examples, and its `Embedder` listing at lines 245-249, which
   still shows `embed(String)` / `embed(List)` -- the pre-2026-09-20 interface);
   `docs/guides/spring-boot.md`'s property table; `docs/guides/providers.md` gains an "Embedders"
   section with the catalogue and the LM Studio findings; `docs/guides/observability.md` lines
   141-152; the README and `docs/index.md` module tables, which name `nessy-embedding-api` -- a
   module that does not exist; it is `nessy-embedding-spi` (§15 (7)). Describing what is
   (`docs-describe-what-is`).

## 13. Order of the questions to James

§15 (1) shapes step 2 and is the only one that blocks it; (2) and (3) shape step 1's adapter
work but can land as their own commits after it; (4) is step 2's; (6) is step 3's; the rest are
records.

## 14. Design authority

Listed by name, as the rule requires. "Ruled" means decided by James on 2026-09-29; "proposed"
means this record needed it to honour a ruling and it awaits his yes; nothing proposed lands
before it.

| concept | where | status | § |
|---|---|---|---|
| two parallel namespaces, `nessy.providers.<id>` and `nessy.embedders.<id>`, same mechanics | Boot properties | ruled | 3, 6 |
| a vendor's key lights an embedder only when `nessy-embedding-<vendor>` is present; one INFO line otherwise | Boot behaviour | ruled | 6b |
| embedding wire values named for the vendor whose shape they are; Bedrock as a bean, not a preset | Boot property values | ruled; the choice of `openai` over `openai-embeddings` is this record's reading of the rule | 4c, 6d |
| `nessy.embedding.*` replaced by `nessy.embedders.*`, no aliases | Boot properties | ruled | 6h |
| `EmbedderConfig.property(String, String)` and `nessy.embedders.<id>.properties.*` | public method, Boot property | ruled (the vendor-properties record); the binding is this record's | 7a |
| stored vectors carry the model; switching is a ranking consequence, re-embedding is ROADMAP | contract | ruled | 8b |
| the words **embedding provider**, **embedder**, **preset**, **wire**, **vendor** as §3 reads them | design vocabulary | the last three reused as ruled; the first two are the SPI's and the API's existing names | 3 |
| `ProviderId` reused for embedding providers, javadoc widened | public type, meaning widened | proposed | 4a |
| `EmbedderFactoryConfig`; `DefaultEmbedderFactory.of(Customizer)` / `of(List)` replacing the three constructors; `provider(ProviderId, EmbeddingProvider)`, `embedding(ProviderId, EmbeddingOptions)`, `observations(ObservationRegistry)` | public type and methods | proposed | 5a |
| `EmbedderConfig.provider(ProviderId)` and `provider(String)` | public methods | proposed | 5b |
| both provider and model required for every embedder; the failure messages | contract | proposed, as the mirror of a ruling | 5b, 5c |
| deleting `model(...)` / `dimension(...)` from the four embedder configs and `defaultModel()` / `defaultDimension()` from the four providers | public methods, removed | proposed | 5d |
| the observation wrap inside `DefaultEmbedderFactory.create` | engine behaviour | proposed | 5e |
| `voyage.api-key` (`VOYAGE_API_KEY`) as Voyage's conventional key | Boot property | proposed | 6b |
| `OpenAiEmbedderConfig.vendor(String)` | public method | proposed | 6e |
| preset `EmbeddingProvider` beans named `<id>Embeddings`; registry ids are ids; application beans join under their bean names verbatim | Boot bean names | proposed | 6f |
| `nessy.embedder` + `nessy.embedding-model` as a both-or-neither pair, `nessy.embedding-dimension` optional | Boot properties | **proposed, for the controller's ruling** | 6i, 15 (1) |
| the `EmbedderFactory` bean always present, `@ConditionalOnMissingBean(EmbedderFactory.class)`; a store decides by the pair, not by the factory's absence | Boot behaviour | proposed | 6i |
| `EmbeddingProvidersAutoConfiguration` replacing the three vendor auto-configurations | public class | proposed | 6h, 6i |
| `nessy-vendor-properties`, a one-class module both adapter families depend on | new module | proposed | 7b |
| `EmbeddingProvider.validate` called from `create`; Bedrock's family check moved there | engine behaviour | proposed | 7c |
| the report's two lines, at INFO in the empty cases | Boot report | proposed | 6g |

Not new, and needing no yes: `EmbeddingWire` and every other package-private class named in
§12 step 2, the catalogue table's contents, the report's wording, the test names, the live rows.

## 15. Open, for James

1. **The default embedder's property names -- the one question the rulings left open.** This
   record proposes **`nessy.embedder`** (the default `ProviderId`, in the shape of
   `nessy.provider`) and **`nessy.embedding-model`** (the default model, keeping `nessy.model` as
   inference's), a both-or-neither pair with `requireBothOrNeither`'s message naming both, and
   **`nessy.embedding-dimension`** optional beside them. Both-or-neither for the reason the
   inference pair has it: a default model with no provider goes to whoever is first, and a default
   provider with no model is the vendor-default behaviour this record retires. Alternatives
   weighed: `nessy.embedder` / `nessy.embedder-model` (reads as the embedder's model, which is
   true, but "embedding model" is the phrase every vendor and the schema column use);
   `nessy.embedding.provider` / `nessy.embedding.model` (nested, tidy, and the prefix is the one
   §6h deletes, so a stale `nessy.embedding.openai.model` would sit one line from a live sibling
   and look current); `nessy.embedders.default` (an id named `default` would then be unnameable).
   Recommendation: the first, as written in §6a and §6i.
2. **The adapter stamps the model it asked for, not the one that answered.** Measured (§6d): LM
   Studio answers any model name with its loaded model and says so in the reply's `model` field;
   `OpenAiEmbeddingProvider` writes `options.modelName()` on every `Embedding` and never reads
   `response.model()`. A store then records vectors under a name no model produced, and a later
   boot with the right name cannot rank them. Proposal: the OpenAI adapter compares the reply's
   `model` with the one asked for and **refuses** with both names when they differ (`asked for
   text-embedding-3-small, answered by text-embedding-nomic-embed-text-v1.5`); Gemini and Voyage
   are checked for the same field and get the same rule if they carry it. The alternative --
   stamping what answered -- would make a store's model a fact of the server rather than of the
   store, which `Embedder`'s javadoc forbids. Recommendation: refuse.
3. **A width asked for and not honoured.** Measured (§6d): LM Studio ignores `dimensions`.
   `DefaultEmbedder.learn` only learns when no width was asked, so an embedder built with
   `dimension(256)` reports 256 and produces 768. Proposal: `learn` compares every reply's width
   with the requested one and refuses with both numbers when they differ, so a store never sizes
   an index for a width its vectors do not have. One `if`; recommendation: yes, in step 1.
4. **Preset bean names.** §6f names a preset's `EmbeddingProvider` bean `<id>Embeddings` because
   the id is already an `InferenceProvider` bean's name. The alternative set aside: register no
   per-preset beans at all and have the factory own and close the connections it builds. Rejected
   because the container closing what it created is the shape everything else in the starter has,
   and because a report that enumerates beans of a type is simpler than one that asks a factory.
   If James would rather the ids not appear in bean names at all, `nessy.embedders.<id>` as the
   bean name is the other honest spelling.
5. **Found in the code, at odds with the decision record.** The 2026-09-20 record says an
   embedding provider "holds credentials and an endpoint and nothing about a model"; all four
   providers hold `defaultModel` and `defaultDimension`, set from `model(...)` / `dimension(...)`
   on their configs. §5d deletes them; noting that the record and the code disagreed so the
   deletion reads as a return, not a break.
6. **The helper's home.** §7b proposes a one-class module. If a new module is more than the
   class deserves, the fallback is the adapters (not the SPI) depending on `nessy-inference-spi`,
   which puts the inference SPI and its codec on every embedding adapter's classpath but changes
   nothing an application sees. The copy and the SPI-on-SPI dependency stay rejected.
7. **Docs name a module that does not exist.** `README.md` line 183, `docs/index.md` line 142,
   `docs/concepts/memory.md` line 239 and `ROADMAP.md` line 42 say `nessy-embedding-api`; the
   module is `nessy-embedding-spi` (the 2026-09-20 record's last paragraph says
   `nessy-embedding-api` "went with it"). `memory.md` lines 245-249 also show the pre-rework
   `Embedder` with `embed(String)` / `embed(List)`. Step 5 fixes all of it; recorded so the fix
   is not mistaken for a rename.
8. **Found in the brief, at odds with the code.** (a) The brief named the notebook as an embedder
   consumer; `JdbcNotebook` takes none (§8a). (b) The brief said "one `EmbedderFactory` bean per
   vendor auto-config"; it is five bean methods across three classes (§1), because OpenAI splits on
   `openai.base-url` and Gemini on the two key spellings. (c) The brief said "application beans
   join by bean name" and "one bean per lit preset named by id"; both cannot hold in one Spring
   bean namespace beside the inference registry, which is §6f and (4). (d) The ROADMAP entry for
   this item (line 156) still lists "whether one `nessy.providers.<id>` entry serves both" as
   open; it was withdrawn on 2026-09-29 (§3). (e) The ROADMAP's neighbouring entry, "Reasoning
   effort, vendor-neutral (next, after the Responses adapter)", is contradicted by the Responses
   record's §8 and the vendor-properties record's §12 ("no typed reasoning-effort concept");
   outside this record, noted because step 5 touches the file.
9. **What this record cites from records not yet in code.** From the vendor-properties record:
   `EmbedderConfig.property`, `EmbeddingOptions.properties`, `EmbeddingProvider.validate`, the
   four configs' `property` / `properties`, `Preset.defaultProperties`, `ProviderSettings.
   properties`, and `VendorProperties` itself, whose module this record moves before it exists.
   From the Responses record: `Wire.OPENAI_CHAT`, which this branch still calls `Wire.OPENAI`;
   §4c's argument does not depend on which. If either record lands in a different shape, the
   names here follow it.
10. **`enabled=` bound from a blank placeholder.** §6h and §11a: whether Boot's binder turns the
    empty string into a null `Boolean` or fails is unmeasured; the matrix row decides it, and if it
    fails, `EmbedderSettings.enabled` becomes a `String` read through `firstNonBlank` like the key.
