# JSON Repeal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Execute the 2026-08-22 repeal — every serialized sealed hierarchy binds through its own standard Jackson annotations; all bespoke sealed-binding machinery and hand-rolled JSON dies.

**Architecture:** `SealedInputs` is deleted; `Codec.json` becomes a plain tolerant mapper call (no sealed special-casing — `SealedJsonCodec` dies); tool-input binding is `mapper.treeToValue` with the pinned mapper; `Schemas` derives oneOf/discriminator schemas from `@JsonTypeInfo`/`@JsonSubTypes` via victools' Jackson module so schema and binding agree by construction; user vocabularies (examples, tests) carry the two standard annotations; `SubstrateBacklog`'s hand-bound envelope returns to a threaded mapper. No string-assembled or hand-tree-walked JSON survives outside vendor wire modules.

**Spec:** `docs/superpowers/specs/2026-08-21-scoped-store-design.md` §3 + §7 as amended by the repeal ruling (commit 49c57557), and the repeal banner on `2026-08-20-action-and-tool-vocabulary.md`. Binding.

## Global Constraints

- Build economics per CLAUDE.md; house law (no mocking libraries, no @SuppressWarnings, no star imports, prose camelCase tests, S5778, S5841); license+spotless before every commit; full `./mvnw -q clean verify` once per task; release profile before final commits of tasks touching published javadoc.
- STORED-FORMAT CONTINUITY where data exists: the intent-declaration discriminator stays the record simple name (users choose `@JsonSubTypes` names; our examples/tests use simple names so stored shapes are unchanged). The message/phase/computation golden pins are untouched by this branch.
- The MODEL-FACING schema contract: sealed tool inputs still advertise a oneOf with a required `"type"` discriminator per subtype; the exact JSON Schema layout MAY change (victools output) but must remain functionally equivalent — providers pass oneOf through (AnthropicSchemas test must stay green). Schemas' collision guard semantics (type-component collision) must survive or be replaced by Jackson's own failure with an IAE naming the offense.
- Design freeze: no new public types; the only dependency addition is victools' `jsonschema-module-jackson` (managed version).
- Deletions must be total: grep-clean for SealedInputs and SealedJsonCodec at the end of the branch (docs/superpowers historical exempt).

## Tasks

### Task 1: Schemas learns Jackson, SealedInputs dies (nessy-api)
Rewire `Schemas` to derive sealed oneOf schemas from `@JsonTypeInfo`/`@JsonSubTypes` via victools' Jackson module (add `com.github.victools:jsonschema-module-jackson`, managed in the root/BOM per convention). DELETE `SealedInputs`; rewire its consumers in nessy-api/nessy-agent: tool-input binding in `RegistryToolCallExecutor` becomes a plain `treeToValue` through the pinned mapper (Jackson's polymorphic machinery reads the annotations), with malformed input / unknown discriminator surfacing as IAE naming the offense (wrap Jackson exceptions — the existing `Codecs` boundary conventions). Annotate every sealed test vocabulary in nessy-api/nessy-agent tests with the two standard annotations (names = record simple names). `Schemas` tests updated to the victools output shape while asserting the functional contract (oneOf present, each branch requires its `"type"` const/enum, unannotated sealed type rejected with a helpful message). Commit: `feat: the schema reads the annotations — SealedInputs dies`

### Task 2: Codec goes plain and the envelope stops being artisanal (nessy-spi, nessy-agent, nessy-intent, examples)
`CodecSupport`: delete `SealedJsonCodec` and the sealed branch in `Codec.json` — one plain tolerant-binding path; the `type`-component collision guard and nested-sealed guards die with it (Jackson's own annotated binding subsumes them; a decode failure still surfaces as IAE naming the offense). `CodecTest`'s sealed round-trips re-seat onto an ANNOTATED test vocabulary (same assertions). nessy-intent: `IntentTool`/`SubstrateIntentStore` ride the plain `Codec.json` over annotated vocabularies — verify the double-discrimination problem is GONE (an annotated vocabulary through Codec.json now single-discriminates; add one test proving it). Examples: `OpsIntent` in governed gains the two annotations (names = simple names — stored-shape continuity); example still runs scripted. `SubstrateBacklog`: delete the hand-rolled envelope parser/writer; the envelope binds `List<String>` through a mapper THREADED via a new constructor parameter (builder passes the pinned mapper; the String-door and typed-door construction sites update); statics-die law holds (threaded, never static/ambient); existing backlog tests keep passing with assertions unmodified except the four malformed-payload parser tests, which re-target the mapper-based rejection (same IAE contract, message may name the field per Codecs convention — update those four assertions honestly, do not weaken to exception-type-only). Commit: `feat: one binding path — SealedJsonCodec and the hand-rolled envelope retire`

### Task 3: Paper trail
docs: intent + tools pages show vocabularies with the two annotations (the "no annotations needed" claims die); storage.md's Codec.json double-discrimination admonition REVERSES (annotated types are now the one true path; the old warning becomes "annotate your sealed vocabularies — unannotated sealed types are rejected"); autonomous-agents/mcp pages swept for SealedInputs mentions; CHANGELOG unreleased entry. mkdocs strict. Commit: `docs: annotate your vocabulary — the site learns the repeal`

## Model policy
| Task | Implementer | Review |
|---|---|---|
| 1 | Sonnet | **Opus** (model-facing schema contract) |
| 2 | Sonnet | Sonnet |
| 3 | docs-writer (Sonnet) | Haiku scoped |
| Final | — | **Opus** |
