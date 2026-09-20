# Embedding provider — decision record

> **This is not a specification.** It is the decisions taken in conversation on 2026-09-20, with
> the reasoning and what each one replaced, so none of it has to be argued twice. The work is
> **not implemented**: it was started, found to be larger than the session had left in it, and
> reverted to keep the tree green.

**Provenance.** Designed while adding `Nessy` as the door an application holds. Attempted the same
day, reverted at `68d5dcf0`. Nothing in the tree implements any of it.

---

## The defect that started it

`JdbcEpisodes` held one `Embedder` and used it on both sides: summaries on the way in, the turn's
question on the way out. Retrieval is asymmetric — a vendor trained for it places a document and
the query that finds it differently on purpose — so whichever task type was configured, one of the
two was wrong, and unset (the default) meant neither was right. It ranked worse and said nothing
about why.

**Fixed already**, in `8fe3eb10`, by giving `Embedder` the two flavours a retrieval system actually
has: `embedDocuments` and `embedQuery`. That is in the tree and works.

What follows is the rest of the shape that fix implies.

---

## What is wrong now

An embedder fuses two things that inference keeps apart: the **connection** and the **model**.
`OpenAiEmbedder` holds a client *and* a model, where a harness holds an `InferenceProvider` and is
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
inference already works, and checking it is what killed the first attempt.

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

// nessy-api — what a caller holds
public interface EmbedderFactory { Embedder create(Consumer<EmbedderConfig> customizer); }
public interface EmbedderConfig {
  EmbedderConfig model(String model);
  EmbedderConfig dimension(int dimension);
}

// nessy-embedding-api — the only implementations there need to be
final class DefaultEmbedder implements Embedder { /* provider + options; learns width */ }
public final class DefaultEmbedderFactory implements EmbedderFactory { /* provider + default model */ }
```

**After this there is exactly one `Embedder` implementation** (plus `ObservedEmbedder` decorating
it), and exactly one class per vendor. The providers are *thinner* than the embedders they replace:
model, width-learning and the degenerate single-text case all move to the facade. Most of the
migration deletes code rather than moving it.

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
says it is a property of the store that holds the vectors. Only one consumer exists today:
`ChatConfiguration.episodes`.

**Width is learned in the facade, not the provider.** Some vendors only say a model's width by
answering. `DefaultEmbedder` keeps the `volatile int` the four adapters keep today; the provider
stays pure request-and-response. Rejected: `dimension(EmbeddingOptions)` on the provider.

**Observability wraps the embedder, not the provider.** A report wants to say which model was asked
and how wide its vectors are — facts of the embedder rather than of the connection. The factory
wraps each one as it mints it: `customizer -> ObservedEmbedder.wrap(embedders.create(customizer),
observations)`. `ObservedEmbedder` already tags `gen_ai.embeddings.input_type` with `document` or
`query`, landed in `68d5dcf0`.

**`Embedder` and `Embedding` stay in `nessy-api`.** Moved there in `99aa1545`, for the rule that
the API names *what a door returns*. `EmbeddingProvider` is handed *in*, so it belongs in the SPI —
the same asymmetry as `InferenceProvider`.

---

## The trap that stopped the first attempt

**Connection ownership moves from the embedder to the provider.** Today a vendor embedder is
`AutoCloseable` and owns its client; after this, the provider does and an embedder owns nothing.

Every `try (XxxEmbedder embedder = ...)` in four vendors' tests becomes a try over the *provider*
with an embedder built inside it. That is the change that turned a rename into surgery, and it is
worth doing first rather than discovering it in the middle.

---

## Migration order

1. `EmbeddingOptions` and `EmbeddingProvider` into `nessy-spi`. Additive, compiles alone.
2. `EmbedderFactory` and `EmbedderConfig` into `nessy-api`. Additive.
3. `DefaultEmbedder` and `DefaultEmbedderFactory` into `nessy-embedding-api`, which gains a
   dependency on `nessy-spi`.
4. **One vendor end to end** — provider, config, tests, auto-configuration — before touching the
   others. OpenAI is the smallest. The `close()` restructuring is the real work; do it once, look
   at it, then repeat.
5. The other three vendors.
6. `NessyConfig.embedder(Embedder)` becomes `embedders(EmbedderFactory)`; `Nessy.embedder()`
   becomes `Nessy.embedders()`.
7. `ChatConfiguration.episodes` takes the factory and names its model.

Steps 1 to 3 are additive and green on their own, but land nothing a caller can use: do not commit
them alone, or the tree gains a door nobody walks through.

---

## Why it was reverted rather than finished

The attempt ran late in a long session and the error rate was climbing — a dependency put in
`dependencyManagement` instead of `dependencies`, a class named from the wrong package, a
vendor-flavoured factory built against the very symmetry being argued for. Each was small; the rate
was the signal, in a change that touches every embedding path. The tree was returned to
`68d5dcf0`, which is green, and the design written down here instead.
