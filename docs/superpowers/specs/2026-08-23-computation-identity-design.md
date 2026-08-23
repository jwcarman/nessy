# Computation Identity — opaque ids, kind-scoped keyspaces, replayable delivery

**Date:** 2026-08-23
**Status:** Ratified (James, in conversation, 2026-08-23: "I do not like
baking anything semantic into the computation ID" / "I do not want that to
be reverse engineer able" / kind approach + per-harness reaper: "I like it")
**Amends:** `2026-08-22-durable-deliveries-design.md` (identity derivation
and keyspace mechanics; execution semantics untouched) and
`2026-08-22-harness-first-design.md` §5 (the type-filtered sweep law's
MECHANISM is superseded — the law's guarantee survives, achieved by
construction).
**Non-goals:** the generic reaper-as-subsystem and the shared deadline-index
kind stay parked (the reaper remains per-harness); HMAC-salted ids stay
parked until ids travel to untrusted parties.

## 1. The three rulings

1. **Ids are opaque and one-way.** A computation id is a digest over its
   identity tuple — deterministic (a CAS-retry re-derives the same id, so
   idempotent create survives) but carrying no extractable structure. The
   system only ever needs address → id (derive, look up); id → address is
   never needed because the continuation carries the address as data.
   Nothing anywhere parses an id. Nothing about an agent's topology is
   recoverable from an id that leaks into a log, URL, or callback.
2. **Keyspaces are kind-scoped per agent type.** `Substrate.keys(kind,
   limit)` is already kind-scoped; each agent type gets its own kinds
   (execution computations, approval computations, outbox). Foreign-type
   isolation holds by construction: a harness's worker and reaper scan only
   their own type's kinds.
3. **Delivery keys are deterministic.** A completion's delivery is keyed by
   the completed computation's id. Creating a delivery under an
   already-present key is idempotent (converges, never duplicates).

## 2. Identity derivation

- Digest: SHA-256 over an unambiguous encoding of the tuple
  `(purpose, agentType, agentId, responseId, callId)` where `purpose`
  discriminates approval vs execution (the two computations
  `CallAddress` already mints for one `ToolInvocationId`). Length-prefixed
  or delimiter-escaped encoding — no ambiguity between tuples; hex or
  base64url rendering. `CallAddress.approval()`/`.execution()` keep their
  signatures and become the digest sites; `ComputationId` stays an opaque
  wrapper and moves to `org.jwcarman.nessy.api.tool` beside `CallAddress`
  (whittle ruling, James 2026-08-23): the `api.computation` package dies —
  its other residents sink into `nessy-agent` at minimal visibility, public
  only where a desk signature forces them; demos/tests move into the package
  or through the desks rather than holding members public.
- **The carrier whittle** (mid-round amendment, James 2026-08-23, "the
  approvals continuation had everything needed" → "Yes"): `ApprovalRequest`
  flattens — it carries the approval `ComputationId` directly (the ticket;
  taught path `approve(request.id())`), the `ToolCall`, and plain display
  strings for the asking scope; `CallAddress` leaves the record.
  `ToolContext` drops `address` and `invocationId` for one opaque
  `invocation` token — the execution `ComputationId`, a tool's stable
  idempotency key for external effects under at-least-once redelivery.
  `CallAddress` withdraws from `nessy-api` into `nessy-agent` at the
  narrowest forced visibility; `ToolInvocationId` is eliminated.
- Determinism-from-the-fold is unchanged law (durable-deliveries §2): same
  fold, same tuple, same id.

## 3. Keyspaces

- Kinds carry the semantics ids no longer do: per-type kind names for
  execution computations, approval computations, and the outbox. Kinds are
  namespaces — semantic by design — and never travel outward.
- Approvals live in their own kind, so the reaper (which sweeps only
  execution computations for deadlines) never reads or skips them: the
  `approval:` prefix skip dies. `isForeignTypeComputation` dies. The outbox
  minimal-peek type filter dies. The reap scan cap is per-type by
  construction.
- The `AgentType` colon fence stays as kind-name hygiene (message reworded
  to say so) — nothing parses keys anymore, but kind strings embed the
  type and stay boring.

## 4. Replayable delivery

Completion remains `batch[delete computation, create delivery]`. Two rails
now close the grant-delivery-pending window (parked in the deliveries spec,
pinned by `GrantDeliveryPendingWindowTest`):
- while the computation record exists, a second completion loses the batch
  (delete of a gone record fails it) — as today;
- while the delivery record exists, a replayed creation under the same
  deterministic key converges instead of duplicating — new.
After the delivery folds (fold-advance batch deletes it), the computation
is long gone, so no path can re-mint the pair. The pinning test graduates
from documenting the window to proving it shut. At-least-once EXTERNAL
effects remain the honest boundary (deliveries spec §5a amendments) — replay
gives exactly-once folding, not exactly-once side effects.

## 5. What does not change

Execution semantics, the §5a gate, fold purity, retry/deadline semantics,
the per-harness reaper and worker ownership, wire formats of continuations
and outcomes, test coverage meaning. Pre-1.0: stored-format compatibility is
not owed; no migration.

## 6. The continuation audit (James, in conversation, 2026-08-23)

Field-by-field, every persisted continuation field has a named consumer;
nothing to delete, nothing missing. Ruled belt-and-suspenders:

- **`responseId` stays in the identity digest and both continuations** as
  the assertion fence: completion must be able to assert it is returning a
  tool result for the RIGHT model response. The invariant "no new turn
  until every tool call of the previous one completes" (staleness re-fires
  the SAME outstanding effects — a scope suspended on an approval is quiet
  on purpose, never stale; turns are never abandoned) does NOT make the
  fence redundant, because at-least-once execution leaks zombies forward
  in time: a re-fired duplicate of turn R's call can complete AFTER R
  advanced, and callIds carry no cross-turn uniqueness guarantee — without
  responseId in the digest, that late completion would land on a new
  turn's same-callId computation. With it, the zombie targets R's key,
  finds nothing, no-ops.
- **The full `ToolCall` (id, name, arguments) stays in both continuations**
  (callId reuse is assumed real): the approval arm dispatches from it on
  grant (§5a, no fold read); the execution arm needs it for the reaper's
  RETRYABLE re-dispatch and for handing memory the complete
  `Remembrance.ToolExchange` pair at fold time.
- **`retrySemantics`/`timeout`** ride the execution arm only (approvals
  wait forever), serving the reaper's bump-or-fail with no registry
  lookup.
- **`ApprovalRequest` loses `responseId`** — the un-ratified fifth field
  reverts to the ratified four `{id, call, agentType, agentId}`. The
  approval machinery sources the committed responseId from the agent's
  state at ASK time, where the no-new-turn invariant guarantees exactly
  one candidate; the continuation (pinned at that same moment) remains
  the durable carrier. The request is a human decision surface, not a
  routing packet.
