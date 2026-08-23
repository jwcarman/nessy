# Durable Dissolves — the spine stops pretending to be a tier

**Date:** 2026-08-23
**Status:** Ratified (James, in conversation, 2026-08-23: "Meaning split out.
With the substrate concept, that feels clunky" → "Go")
**Amends:** `2026-08-20-durable-computation.md` (the module dissolves; the
computation semantics are untouched) and `2026-08-22-harness-first-design.md`
§7-fallout (the OutcomeCodec/ScopeRouting public widening is reversed by
construction).
**Follows:** `2026-08-21-scoped-store-design.md` — the Substrate is the one
durability seam; there is no second one above it.

## 1. The ruling

Since the deliveries reform killed awaited execution, the computation
pipeline is not a durability option — it is the only execution path, on
in-memory and JDBC alike. A separate `nessy-durable` module and a
`.durable` package name a tier that no longer exists. Both dissolve.

## 2. What moves where

- **`nessy-durable` (module) is deleted.** Its vocabulary records move by
  consumer need, smallest surface wins:
  - `ComputationId` → `org.jwcarman.nessy.api.computation` (new subpackage,
    existing vocabulary word — `CallAddress` already returns it, which was
    the dep edge making the layering rule false).
  - Each remaining record (`ToolInvocationId`, `Continuation`, `Outcome`,
    `PendingComputation`, `CreateResult`, `CompletionResult`) goes to
    `api.computation` ONLY if a public signature outside `nessy-agent`
    requires it; otherwise into `org.jwcarman.nessy.agent`, package-private
    where possible.
- **`DurableComputationBackend` (interface) dies.** One implementation, no
  swap story — the Substrate beneath it is the seam you swap. Consumers
  hold `SubstrateComputations` concretely.
- **`org.jwcarman.nessy.agent.durable` (package) collapses into
  `org.jwcarman.nessy.agent`.** `OutcomeCodec`, `ScopeRouting`, and their
  nested records return to package-private — the harness-first fix round's
  disclosed widening is hereby reversed, not blessed.
- **The desks stay public** (`ApprovalDesk`, `CompletionDesk`) — they are
  the ratified harness surface (`approvals()`/`completions()`).

## 3. What does not change

Computation semantics, delivery semantics, the §5a gate, the type-filtered
sweep law, key formats, wire formats, test coverage. This is a relocation
and a deletion of dead abstraction — byte-identical behavior.

## 4. Vocabulary

"Durable" leaves the code's structural vocabulary (no module, no package,
no interface bearing the word). It remains teaching prose in the specs and
the docs concept page, where it describes what a durable substrate buys.
