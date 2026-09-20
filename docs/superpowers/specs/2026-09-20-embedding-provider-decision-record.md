# Embedding provider — decision record

> **This is not a specification.** It is the decisions taken in conversation on 2026-09-20, with
> the reasoning and what each one replaced, so none of it has to be argued twice. The shape
> described here is **implemented**.

**Provenance.** Designed while adding `Nessy` as the door an application holds.

---

## The defect that started it

`JdbcEpisodes` held one `Embedder` and used it on both sides: summaries on the way in, the turn's
question on the way out. Retrieval is asymmetric — a vendor trained for it places a document and
the query that finds it differently on purpose — so whichever task type was configured, one of the
two was wrong, and unset (the default) meant neither was right. It ranked worse and said nothing
about why.

Fixed in `8fe3eb10`, by giving `Embedder` the two flavours a retrieval system actually
has: `embedDocuments` and `embedQuery`. What follows is the rest of the shape that fix implies.

---

## What that fix left behind

An embedder fused two things that inference keeps apart: the **connection** and the **model**.
`OpenAiEmbedder` held a client *and* a model, where a harness holds an `InferenceProvider` and is
told a model per call.

The costs, measured on 2026-09-20:

- **Two models mean two connections.** A notebook and an episode log on different models open two
  clients, unless a caller threads a preconfigured one through the escape hatch.
- **Five `Embedder` implementations**, four of them per-vendor, each carrying a `model` field, a
  `volatile int dimension` and lazy width-learning. Seven duplicated `dimension == 0` sites and
  four duplicated float-array conversions.
- **Nessy cannot vend embedders**, only hold one that was handed in — which is why
  `NessyConfig.embedder(Embedder)` exists and reads oddly beside `harnesses()` and `extractors()`.

---

## The shape

The rule that settles it: **one generic factory, vendor-flavoured providers.** That is how
inference already works.

| | vendor implements (spi) | one generic factory | minted |
|---|---|---|---|
| inference | `InferenceProvider` | `DefaultHarnessFactory` | `Harness` |
| embedding | `EmbeddingProvider` | `DefaultEmbedderFactory` | `Embedder` |

```java
// nessy-spi — the only thing that codes to a vendor's SDK
public interface EmbeddingProvider {
  List<Embedding> embedDocuments(List<String> texts, EmbeddingOptions options);
  Embedding embedQuery(String query, EmbeddingOptions options);
  String providerName();
}

/** Mirrors InferenceOptions: the dials, kept apart from the connection that carries them. */
public record EmbeddingOptions(String modelName, OptionalInt dimension) {}

// nessy-api, package org.jwcarman.nessy.api.embedding — what a caller holds
public interface EmbedderFactory { Embedder create(Consumer<EmbedderConfig> customizer); }
public interface EmbedderConfig {
  EmbedderConfig model(String model);
  EmbedderConfig dimension(int dimension);
}

// nessy-engine — the only implementations there need to be
final class DefaultEmbedder implements Embedder { /* provider + options; learns width */ }
public final class DefaultEmbedderFactory implements EmbedderFactory { /* provider + default model */ }
```

**There is now exactly one `Embedder` implementation** (plus `ObservedEmbedder` decorating
it), and exactly one class per vendor. The providers are *thinner* than the embedders they replaced:
model, width-learning and the degenerate single-text case all moved to the facade. The migration
deleted more code than it moved, and `nessy-embedding-api` went with it.

---

## Decisions, and what each replaced

**Two methods on the provider, not a role enum.** `Embedder` is a facade into the provider's
specific logic, so the provider carries the same two flavours. Rejected: an `EmbeddingRole` enum in
the request, which would have been a new vocabulary word for something the method name already
says.

**No vendor-flavoured factories.** An `OpenAiEmbedderFactory` was built and thrown away. There is
exactly one `HarnessFactory` implementation and it is generic; embedding gets the same. *"That's
why we have the provider."*

**No default `Embedder` bean from the starter.** It vends an `EmbeddingProvider` and an
`EmbedderFactory`; `nessy.embedding.<vendor>.model` becomes the factory's **default model**. A
default embedder bean makes the model a property of the application, when `Embedder`'s own javadoc
says it is a property of the store that holds the vectors. `ChatConfiguration.episodes` is the one
consumer, and it mints its own.

**Width is learned in the facade, not the provider.** Some vendors only say a model's width by
answering. `DefaultEmbedder` keeps the `volatile int` the four adapters used to keep; the provider
stays pure request-and-response. Rejected: `dimension(EmbeddingOptions)` on the provider.

**Observability wraps the embedder, not the provider.** A report wants to say which model was asked
and how wide its vectors are — facts of the embedder rather than of the connection. The factory
wraps each one as it mints it: `customizer -> ObservedEmbedder.wrap(embedders.create(customizer),
observations)`. `ObservedEmbedder` tags `gen_ai.embeddings.input_type` with `document` or `query`.

**`Embedder` and `Embedding` stay in `nessy-api`**, in an `embedding` subpackage beside
`extraction`, for the rule that the API names *what a door returns*. `EmbeddingProvider` is handed *in*, so it belongs in the SPI —
the same asymmetry as `InferenceProvider`.

---

## Where the weight of the change sits

**Connection ownership sits with the provider, not the embedder.** A provider is `AutoCloseable`
and owns its client; an embedder owns nothing. A vendor's tests open a try over the *provider* and
build an embedder inside it. This is the part that made the change surgery rather than a rename.

---
