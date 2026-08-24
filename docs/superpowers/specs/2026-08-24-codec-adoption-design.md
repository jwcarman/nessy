# Codec Adoption — the homegrown codec retires

**Date:** 2026-08-24
**Status:** Ratified (James, 2026-08-24: "codec 0.2.0 is out. We should
probably replace our codec with it (it includes the andThen stuff)")
**Amends:** the typed-stores spec (its Codec/CodecFactory vocabulary is now
supplied by an external library; view semantics unchanged).

## 1. The ruling

`org.jwcarman.nessy.spi.substrate.Codec<T>` and its `CodecFactory` retire.
Nessy adopts `org.jwcarman.codec:codec-core` (the SPI: `Codec<T>` with
`encode`/`decode`/`andThen(Codec<byte[]>)`, `CodecFactory` with
`create(TypeRef<T>)`/`create(Class<T>)`, plus the transform codecs) and
`org.jwcarman.codec:codec-jackson2` (the Jackson 2 backend), both 0.2.0
from Central — no snapshots. This also aligns Nessy's codec vocabulary
with Continuum's ahead of that adaptation.

## 2. The mapping

- `Codec.json(mapper, type)` → `new Jackson2CodecFactory(mapper).create(type)`
  (composition roots hold ONE factory over the copy-and-pinned mapper and
  mint from it; no per-call-site factory construction).
- Nessy's `then` chaining → `andThen`.
- `Substrate.codecs()` returns `org.jwcarman.codec.spi.CodecFactory`;
  `SubstrateSupport` holds a `Jackson2CodecFactory` over the pinned mapper
  (copy-and-pin stays exactly where it is — the boundary law is untouched).
- Exception contract: the external codecs throw `UncheckedIOException`.
  Nessy's VIEW layer keeps its documented contextual
  `IllegalArgumentException` wrapping where it exists today (the teaching
  messages and their tests survive); raw factory users get the external
  contract. No view semantics change.

## 3. What does not change

Wire formats on disk (same mapper, same `writeValueAsBytes` path — existing
tests are the proof); the Substrate byte contract; kind scoping; the
views' CAS/retry semantics; the in-memory byte-round-trip enforcement.
