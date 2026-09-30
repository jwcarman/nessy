# Named embedders: a store says who embeds

**Status: DESIGN, NOTHING BUILT. The rulings were made in conversation with James on 2026-09-29
and this record writes them down. The proposals this record had to make to honour them -- the
default-embedder pair among them, the one question the rulings had left open -- were ruled on
overnight on 2026-09-30 by the controller James authorised for the run; those rulings are folded
in below and marked "accepted overnight" or "ruled overnight" in §14, pending his morning review.
The questions in §15 are the ones still open, and the places where the code, the earlier records
and the brief disagreed are recorded there rather than smoothed over.**

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
has no `properties`, and `VendorProperties` does not exist; §15 (4) says which names below are
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
with no rule inside it that `ProviderId` lacks. Proposed here and **accepted overnight** (§14):
reuse, with the javadoc amended.

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
puts it. **`EmbedderFactoryConfig` was proposed here and accepted overnight** (§14): a public
type, because `DefaultEmbedderFactory` is public and non-Spring applications build it.

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
with it (§15 (1)). This record sides with the decision record, proposed and **accepted
overnight** (§14), with a `CHANGELOG.md` breaking-change line of its own: the four
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

### 5f. A width asked for is a width received -- ruled overnight

`DefaultEmbedder.learn` learns the width from the first reply only when none was asked for
(`dimension == 0`), so an embedder built with `dimension(256)` at a server that ignores
`dimensions` (§6d measured one) would *report* 256 and *produce* 768, and a store that sized an
index from `dimension()` would be wrong in a way nothing says. **Ruled overnight:** every reply's
width is compared with the requested one, and a difference fails at once naming both --
`asked for 256 coordinates, the model returned 768` -- on every call rather than the first,
because the check is one comparison and the failure it prevents is an index full of vectors of
the wrong shape. A reply to an embedder that asked for no width still teaches it its width, as
today.

### 5g. Closing

Unchanged: a provider is `AutoCloseable` and owns its client; an embedder owns nothing. Under Boot
every registered provider is a bean and the container closes it (§6f); in code, whoever built it
closes it. `DefaultEmbedderFactory` closes nothing, as today.

## 6. Boot: presets, and what lights them

### 6a. Properties

```yaml
nessy:
  embedder: voyage                     # the factory default ProviderId (§6i)
  embedding-model: voyage-3.5          # the factory default model; paired with the above
  embedding-dimension: 1024            # optional; the factory default width
  embedders:
    voyage:
      api-key: ${VOYAGE_API_KEY}       # or just export VOYAGE_API_KEY (§6b)
    gemini:
      enabled: false                   # a Gemini key is set for chat; no Gemini embedder wanted
    local:                             # not in the catalogue: a custom embedder (§6c, §6e)
      wire: openai
      base-url: http://localhost:1234/v1
      api-key: lm-studio
      vendor: lmstudio
    my-gateway:                        # another, carrying vendor properties (§7)
      wire: openai
      base-url: https://gateway.example.com/v1
      api-key: ${GATEWAY_KEY}
      properties:
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

### 6c. `enabled`, and why no keyless preset ships

The mechanics are the inference ones: `enabled: false` turns any embedder off whatever its
ingredient, and a keyless preset would need `enabled: true`. The first matters at once -- a Gemini
key set for chat with no Gemini embeddings wanted is `nessy.embedders.gemini.enabled: false`, and
that is the only way to say it, since the key is shared. The second has nothing to act on in
`0.3.0`: **ruled overnight, no `lmstudio` and no `ollama` embedding preset ships**, on the
strength of §6d's measurement. A local server that ignores the width asked for, reports no usage
and answers any model name with whatever it has loaded is not something a catalogue row can vouch
for the way the inference rows vouch for a tool call and a forced answer; a preset is a promise
that the row works, and this one would be a promise that it answers. A local embedder is a custom
embedder (§6e), which states its URL, its key and its model in the open, and the providers guide
says so beside the findings. `EmbedderCatalogue` keeps the keyless branch so a measured row can
join later without a code shape changing; no startup probing of a port, for the reason the
named-providers record gives (§7c there).

### 6d. The catalogue

A preset is `(id, wire, base URL, vendor, key properties, keyless api-key, default properties)`,
the mirror of `Preset` with the vendor-properties record's `defaultProperties`. Only vendors with
an existing embedding adapter are in it, and no row ships unmeasured.

| id | wire | base URL | vendor | ingredient | status |
|---|---|---|---|---|---|
| `openai` | `openai` | the vendor's own; `openai.base-url` overrides | `openai` | `openai.api-key` | in: measured by `OpenAiEmbedderLiveTest` |
| `gemini` | `gemini` | the vendor's own | `gcp.gemini` | `gemini.api-key` or `google.api-key` | in: measured by `GeminiEmbedderLiveTest` |
| `voyage` | `voyage` | `https://api.voyageai.com/v1` | `voyage` | `voyage.api-key` | in: measured by `VoyageEmbedderLiveTest` |

Three rows, and no local one. `lmstudio` and `ollama` were candidates in this record's first
draft, `lmstudio` on the strength of the measurement below; **ruled overnight, neither ships in
`0.3.0`** (§6c). Ollama was not measured at all -- nothing answered on `:11434` on this machine on
2026-09-30 -- and LM Studio measured as a server the catalogue cannot vouch for.

**LM Studio, measured on 2026-09-30 against the instance on this machine** (`/v1/models` listed
`text-embedding-nomic-embed-text-v1.5` and `text-embedding-qwen3-embedding-4b` beside the chat
models), with `curl` against our request shape (`model`, `input` as an array, `dimensions`):

- `POST /v1/embeddings` with two inputs answers the OpenAI shape: `object: list`, `data[]` with
  `index` and `embedding`, `model` echoed, 768 coordinates each for nomic. The adapter's reading
  (`OpenAiEmbeddingProvider.embedDocuments`, ordered by `index`) fits it.
- `usage` is `{prompt_tokens: 0, total_tokens: 0}`. Nothing here reads embedding usage, so nothing
  breaks; recorded so nobody later takes zero for a count.
- **`dimensions: 256` is silently ignored**: the reply is 768 wide. §5f's width check, ruled
  overnight, turns that into a failure naming both numbers instead of an index of the wrong
  shape.
- **Any model name is answered.** `model: text-embedding-3-small` -- OpenAI's name, not loaded
  here -- and `model: definitely-not-a-model` both return a 768-wide vector, and the reply's
  `model` field names `text-embedding-nomic-embed-text-v1.5`, the loaded model, not the one
  asked for. The adapter stamps `options.modelName()` on every `Embedding` it returns
  (`OpenAiEmbeddingProvider` line 120), so a store at such a server with a mistyped model name
  records vectors under a name no model made. **Ruled overnight: this is a documented limitation
  of OpenAI-compatible local servers, not something the client detects.** The reply's `model`
  field is the server's to fill, and a server that answers the wrong model is under no obligation
  to say so; a comparison in the adapter would catch LM Studio's candour and miss the next
  server's silence, a guard that teaches a false sense of safety and makes the stored model a
  fact of the server rather than of the store. The providers guide states the limitation beside
  the custom-embedder example, in these words: a local server may answer any model name with
  whatever it has loaded, and the model a store records is the one it asked for.

These findings are why no local row ships (§6c). They hold for any custom `openai`-wire embedder
pointed at a local server, and the guide says them there.

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
mirror of `OpenAiProviderConfig.vendor(String)`, so a custom `openai`-wire embedder -- a local
server, a gateway -- can report who it really is. Proposed and **accepted overnight** (§14), a
public method on a vendor module.

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
`voyageEmbeddings` -- while its **registry id is the id**: the registrar
publishes a `ResolvedEmbedders` bean (id, bean name, wire, endpoint, vendor, property names) and
the factory bean (§6i) reads it to register each preset under its id, then sweeps every other
`EmbeddingProvider` bean under its bean name verbatim. No stripping of a suffix, no guessing:
an application bean called `bedrock` joins as `bedrock`, one called `bedrockEmbeddings` joins as
`bedrockEmbeddings`, and an application bean called `openaiEmbeddings` beside a lit `openai`
preset fails at the registrar with the same message the inference registrar uses (`a bean named
'openaiEmbeddings' and the openai embedder would both be registered as 'openaiEmbeddings'`).
An application bean called `openai` collides with the *inference* preset's bean, and Spring says
so before this record's code runs. Proposed and **accepted overnight** (§14), because "named by
its id" was the ruling and this is the nearest a second registry in one bean namespace can come
to it. It is also what resolves the contradiction this record's first draft found in the brief:
"one bean per lit preset named by id" and "application beans join by bean name" cannot both hold
literally across two registries in one Spring namespace, and the resolution is that the
*registry* id is the id, a *preset's* bean name carries the suffix, and an application's bean
name is taken verbatim. The alternative set aside: no per-preset beans, with the factory owning
and closing what it built; rejected because the container closing what it created is the shape
everything else in the starter has, and because a report that enumerates beans of a type is
simpler than one that asks a factory.

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
| `openai.api-key` + `openai.base-url` + `nessy.embedding.openai.model` (the OpenAI-compatible bean) | a custom embedder -- `nessy.embedders.local.wire: openai`, `.base-url`, `.api-key`, `.vendor: lmstudio` -- plus the pair naming `local`; or the `openai` preset with `openai.base-url` overriding its endpoint, as before, plus the pair |
| `*EmbedderConfig.model(...)` / `.dimension(...)` and `*EmbeddingProvider.defaultModel()` / `.defaultDimension()` (§5d) | gone; the factory default `embedding(ProviderId, EmbeddingOptions)`, or the store's own `create(...)` |
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

**The default embedder is a both-or-neither pair -- proposed here as the one question the
rulings had left open, and accepted overnight as proposed (§14).** `NessyProperties` gains
`embedder` (the default `ProviderId`, in the shape of `nessy.provider`), `embeddingModel` (the
default model, keeping `nessy.model` as inference's) and `embeddingDimension`; the first two are a
pair (`requireBothOrNeither`, the message naming both), the third is optional and needs the pair.
The reason is the one `nessy.provider` / `nessy.model` have: a default model with no provider is
a model sent to whoever comes first, and a default provider with no model is the vendor-default
behaviour §6h retires. The alternatives weighed before the ruling, so the choice reads as one:
`nessy.embedder` / `nessy.embedder-model` (true, but "embedding model" is the phrase every vendor
and the schema column use); `nessy.embedding.provider` / `nessy.embedding.model` (nested and tidy,
but the prefix is the one §6h deletes, so a stale `nessy.embedding.openai.model` would sit one
line from a live sibling and look current); `nessy.embedders.default` (an id named `default`
would then be unnameable).

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
  embedder: ${CHAT_EMBEDDER:}            # set to local for relevance ranking; blank for recency
  embedding-model: ${CHAT_EMBEDDING_MODEL:}
  embedders:
    local:                               # LM Studio's embeddings, a custom embedder (§6c, §6e)
      wire: openai
      base-url: ${CHAT_MODEL_URL:http://localhost:1234/v1}
      api-key: not-needed
      vendor: lmstudio
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

**Chosen, and ruled overnight: a new module, `nessy-vendor-properties`**, holding the one public
class `org.jwcarman.nessy.vendor.VendorProperties` and depending on `jackson-databind` alone (the
`literal` and `nest` functions take a `JsonMapper`). **Both SPIs depend on it** -- the ruling's
shape, so that an adapter of either family gets the helper by depending on its SPI and nothing
else, and so that the `validate` hooks' javadoc can cite the class they expect an adapter to use.
It is the smallest module in the reactor and it says exactly what it holds. **This amends the
vendor-properties record's §8e**, which placed the class in `nessy-inference-spi` and left the
embedding side to this record: since that item is unbuilt on every branch, `VendorProperties` is
written in the new module from the start, and step 1 of that record's sequencing creates the
module rather than adding the class to the inference SPI. The alternative set aside was the
adapters -- not the SPI -- depending on `nessy-inference-spi` for the class, which costs no new
module and puts an inference jar on every embedding adapter's classpath.

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
it is a local server answering a name it does not serve (§6d). The stored model is the contract,
the client cannot police it, and the guide says so where a local embedder is configured.

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
- **`lmstudio` and `ollama` embedding presets.** Ruled out overnight for `0.3.0` (§6c, §6d): a
  custom embedder reaches either, and a preset would vouch for behaviour the measurement showed
  the server does not have.
- **Detecting a model-name mismatch in the adapter.** Ruled out overnight (§6d): a documented
  limitation of OpenAI-compatible local servers, not a client-side check.
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
| `nessy.embedders.lmstudio.enabled=true` | nothing registered: `lmstudio` is not a preset, and an id with `enabled` and no `wire` fails to start naming `lmstudio` and `wire` -- the §6c ruling made checkable |
| `nessy.embedders.local.wire=openai`, `base-url=http://localhost:1234/v1`, `api-key`, `vendor=lmstudio` | `local` registered, vendor `lmstudio` |
| `nessy.embedders.gemini.enabled=` (blank, the `${VAR:}` shape) with `gemini.api-key` | `gemini` registered and the context starts -- the binder fact §6h left open, pinned here |
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
  `NOOP`;
- an embedder that asked for 256 coordinates and is answered with 768 fails naming both (§5f),
  on the first call and on a later one; an embedder that asked for none learns 768 and reports it.

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

- `LocalEmbedderLiveTest` (`nessy-spring-boot-autoconfigure`, beside `LmStudioPresetLiveTest`,
  gated the same way on `:1234` answering): a **custom** embedder `local` on the `openai` wire at
  LM Studio, with the pair naming `text-embedding-nomic-embed-text-v1.5`, through the whole
  starter -- the vector is 768 wide, `dimension()` reports 768 when none was asked, and a
  `dimension(256)` embedder fails naming 256 and 768 (§5f measured on the wire). It proves the
  custom-embedder route the guide documents for local servers; it promotes nothing to a preset.

## 12. Sequencing

Five steps, each a reviewable commit, each green under `./mvnw -q clean verify` with no key and no
network before the next starts. Step 3 waits on the vendor-properties item; nothing else does.

1. **The engine** (§5): `EmbedderFactoryConfig`, `DefaultEmbedderFactory.of(...)`, the registry,
   `EmbedderConfig.provider(...)`, resolution and its messages, the observation wrap in the
   factory, the width check in `DefaultEmbedder` (§5f); the four configs lose `model` /
   `dimension` and the providers lose the two getters, with their changelog line;
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
3. **Vendor properties** (§7), after that item lands: the `nessy-vendor-properties` module with
   both SPIs depending on it, the four adapters reading their prefix, `EmbedderSettings.properties`
   through to `WireEmbedders`, `validate` called from `create`, the Bedrock family check moved.
4. **Live measurement** (§11d): the four live tests on the new shape, `LocalEmbedderLiveTest`
   through a custom embedder at LM Studio. No catalogue row changes on its pass (§6c).
5. **Examples and docs, including the stale ones this record found -- ruled overnight to be fixed
   in this branch's docs step, not deferred**: chat-web's `ChatConfiguration.episodes`,
   `application.yml` and `EpisodesWiringTest` (which sets `nessy.embedding.openai.model` today,
   line 39); `docs/guides/spring-boot.md`'s property table; `docs/guides/providers.md` gains an
   "Embedders" section with the catalogue, the custom-embedder route for local servers and the
   LM Studio findings as the documented limitation of §6d; `docs/guides/observability.md` lines
   141-152 (the wrap is the factory's now); `docs/concepts/memory.md` -- its two factory examples
   (lines 157-163, 268-270), its `Embedder` listing at lines 245-249, which still shows
   `embed(String)` / `embed(List)`, the pre-2026-09-20 interface, and line 239's
   `nessy-embedding-api`; `README.md` line 183 and `docs/index.md` line 142, whose module tables
   name `nessy-embedding-api`, a module that does not exist -- it is `nessy-embedding-spi`;
   `ROADMAP.md` line 42 (the same name), lines 156-167 (this item's entry, which still lists the
   one-namespace question as open when it was withdrawn on 2026-09-29, §3), and lines 150-155
   (the "Reasoning effort, vendor-neutral" entry, which the Responses record's §8 and the
   vendor-properties record's §12 ruled out -- it becomes a line saying reasoning is configured
   through vendor properties, or goes); `CHANGELOG.md` `[Unreleased]` with the table of §6h and
   the §5d deletions. Describing what is (`docs-describe-what-is`).

## 13. Order of the questions to James

Nothing in §15 blocks a step. (1) and (2) are records of what the code and the earlier documents
said; (3) is pinned by a test in step 2; (4) follows whichever shape the two records beneath this
one land in.

## 14. Design authority

Listed by name, as the rule requires. "Ruled" means decided by James on 2026-09-29. "Accepted
overnight" and "ruled overnight" mean this record proposed it to honour a ruling, or the
measurement raised it, and the controller James authorised for the overnight run ruled on it on
2026-09-30, as proposed or with the change named; every such row is **pending James's morning
review**, and nothing lands before it.

| concept | where | status | § |
|---|---|---|---|
| two parallel namespaces, `nessy.providers.<id>` and `nessy.embedders.<id>`, same mechanics | Boot properties | ruled | 3, 6 |
| a vendor's key lights an embedder only when `nessy-embedding-<vendor>` is present; one INFO line otherwise | Boot behaviour | ruled | 6b |
| embedding wire values named for the vendor whose shape they are; Bedrock as a bean, not a preset | Boot property values | ruled | 4c, 6d |
| `nessy.embedding.*` replaced by `nessy.embedders.*`, no aliases | Boot properties | ruled | 6h |
| `EmbedderConfig.property(String, String)` and `nessy.embedders.<id>.properties.*` | public method, Boot property | ruled (the vendor-properties record); the binding is this record's | 7a |
| stored vectors carry the model; switching is a ranking consequence, re-embedding is ROADMAP | contract | ruled | 8b |
| the words **embedding provider**, **embedder**, **preset**, **wire**, **vendor** as §3 reads them | design vocabulary | the last three reused as ruled; the first two are the SPI's and the API's existing names | 3 |
| `nessy.embedder` + `nessy.embedding-model` as a both-or-neither pair, `nessy.embedding-dimension` optional | Boot properties | accepted overnight, as proposed, pending James's morning review | 6i |
| `ProviderId` reused for embedding providers, javadoc widened | public type, meaning widened | accepted overnight, as proposed, pending James's morning review | 4a |
| `EmbedderFactoryConfig`; `DefaultEmbedderFactory.of(Customizer)` / `of(List)` replacing the three constructors; `provider(ProviderId, EmbeddingProvider)`, `embedding(ProviderId, EmbeddingOptions)`, `observations(ObservationRegistry)` | public type and methods | accepted overnight, as proposed, pending James's morning review | 5a |
| `EmbedderConfig.provider(ProviderId)` and `provider(String)` | public methods | accepted overnight, as proposed, pending James's morning review | 5b |
| both provider and model required for every embedder; the failure messages | contract | accepted overnight, as the mirror of a ruling, pending James's morning review | 5b, 5c |
| deleting `model(...)` / `dimension(...)` from the four embedder configs and `defaultModel()` / `defaultDimension()` from the four providers, with a changelog line | public methods, removed | accepted overnight, as proposed, pending James's morning review | 5d |
| the observation wrap inside `DefaultEmbedderFactory.create` | engine behaviour | accepted overnight, as proposed, pending James's morning review | 5e |
| the width check: a reply whose width differs from the requested dimension fails, naming both | engine behaviour | **ruled overnight**, raised by the measurement, pending James's morning review | 5f |
| the embedding wire value `openai` (not `openai-embeddings`) | Boot property value | accepted overnight, as proposed, pending James's morning review | 4c |
| `voyage.api-key` (`VOYAGE_API_KEY`) as Voyage's conventional key | Boot property | accepted overnight, as proposed, pending James's morning review | 6b |
| `OpenAiEmbedderConfig.vendor(String)` | public method | accepted overnight, as proposed, pending James's morning review | 6e |
| preset `EmbeddingProvider` beans named `<id>Embeddings`; registry ids are ids; application beans join under their bean names verbatim | Boot bean names | accepted overnight, as proposed, pending James's morning review; resolves the two-registries contradiction | 6f |
| **no `lmstudio` or `ollama` embedding preset in `0.3.0`**; local servers are custom embedders | Boot catalogue | **ruled overnight**, against this record's first draft, pending James's morning review | 6c, 6d |
| a model-name mismatch at a compatible server is a documented limitation, not a client-side check | contract, guide | **ruled overnight**, against this record's first draft, pending James's morning review | 6d |
| the `EmbedderFactory` bean always present, `@ConditionalOnMissingBean(EmbedderFactory.class)`; a store decides by the pair, not by the factory's absence | Boot behaviour | accepted overnight, as proposed, pending James's morning review | 6i |
| `EmbeddingProvidersAutoConfiguration` replacing the three vendor auto-configurations | public class | accepted overnight, as proposed, pending James's morning review | 6h, 6i |
| `nessy-vendor-properties`, a one-class module **both SPIs** depend on; amends the vendor-properties record's §8e | new module | accepted overnight, with the dependents named, pending James's morning review | 7b |
| `EmbeddingProvider.validate` called from `create`; Bedrock's family check moved there | engine behaviour | accepted overnight, as proposed, pending James's morning review | 7c |
| the report's two lines, at INFO in the empty cases | Boot report | accepted overnight, as proposed, pending James's morning review | 6g |
| the stale docs found by this record fixed in this branch's docs step | docs | **ruled overnight** | 12, step 5 |

Not new, and needing no yes: `EmbeddingWire` and every other package-private class named in
§12 step 2, the catalogue table's contents, the report's wording, the test names, the live rows.

## 15. Open, for James

Closed overnight and folded in above: the default-embedder pair (§6i); the width check (§5f);
the model-name mismatch (§6d, a documented limitation); the preset bean names (§6f); the helper's
home (§7b, both SPIs); the local presets (§6c, none). What remains:

1. **Found in the code, at odds with the decision record.** The 2026-09-20 record says an
   embedding provider "holds credentials and an endpoint and nothing about a model"; all four
   providers hold `defaultModel` and `defaultDimension`, set from `model(...)` / `dimension(...)`
   on their configs. §5d deletes them, accepted overnight; noting that the record and the code
   disagreed so the deletion reads as a return, not a break.
2. **Found in the brief, at odds with the code.** (a) The brief named the notebook as an embedder
   consumer; `JdbcNotebook` takes none (§8a). (b) The brief said "one `EmbedderFactory` bean per
   vendor auto-config"; it is five bean methods across three classes (§1), because OpenAI splits on
   `openai.base-url` and Gemini on the two key spellings. (c) The brief said "application beans
   join by bean name" and "one bean per lit preset named by id"; both cannot hold literally in one
   Spring bean namespace beside the inference registry -- resolved overnight by §6f. (d) The
   brief's "keyless presets pending measurement" produced a measurement that ruled them out (§6c).
   (e) The stale documents -- `nessy-embedding-api` in four files, `memory.md`'s pre-rework
   `Embedder`, the ROADMAP's withdrawn one-namespace question and its "vendor-neutral reasoning
   effort" line -- are in §12 step 5 by overnight ruling.
3. **`enabled=` bound from a blank placeholder.** §6h and §11a: whether Boot's binder turns the
   empty string into a null `Boolean` or fails is unmeasured; the matrix row decides it, and if it
   fails, `EmbedderSettings.enabled` becomes a `String` read through `firstNonBlank` like the key.
4. **What this record cites from records not yet in code.** From the vendor-properties record:
   `EmbedderConfig.property`, `EmbeddingOptions.properties`, `EmbeddingProvider.validate`, the
   four configs' `property` / `properties`, `Preset.defaultProperties`, `ProviderSettings.
   properties`, and `VendorProperties` itself, whose module this record names before it exists.
   From the Responses record: `Wire.OPENAI_CHAT`, which this branch still calls `Wire.OPENAI`;
   §4c's argument does not depend on which. If either record lands in a different shape, the
   names here follow it.
