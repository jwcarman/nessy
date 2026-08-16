# Authorization — the tool's effect starts the chain

**Date:** 2026-08-16
**Status:** DRAFT for owner review — the product of a long design dialogue; every §0
decision was converged in conversation, none is speculative.
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`
(extends the grant principle); sibling to the mission lens in `ROADMAP.md`
(opinions-not-mandates; every seam an escape hatch).

## 0. The anchor, and the domain map

**The authorization of a tool call begins with the tool's own statement of its
effect.** After the arguments parse, the tool renders `effect(input)` — "execute this
call with these arguments, and this is what will happen" — an object of whatever type
the tool author chose, `E`, and that value, at that type, seeds the authorization
chain. Everything downstream is typed forward from the consequence the tool warrants.

The domain decomposes into six parts with a rising trust gradient:

| Part | Speaker | Trust | Embodiment |
|---|---|---|---|
| Claim ("what I'm trying to do and why") | the model | untrusted assertion | intent, a bolt-on tool module |
| Effect ("what will happen if you do it") | the tool | trusted (developer-authored) | `effect(input)` → `E`, the chain's seed |
| Assessment ("the risk of that effect") | org-supplied assessors | derived judgment | chain links (may do I/O) |
| Judgment (allow / deny / require approval) | the policy | pure law | `UsagePolicy<C>`, end of the chain |
| Adjudication | a human | authority | the existing approver/park machinery, better fed |
| Record | the harness | — | transcript + parks + inbox, already durable |

And the **who** — the principal a conversation acts for — is substrate, not payload:
it lives in the facts, dynamically typed, lifted into the chain's static types the
moment a grant wants rigor (§6).

Authorization governs **actions** — tool calls, the only effectful channel an agent
has. Content screening (model output reaching users; prompt injection riding tool
results inward) is a sibling domain with different machinery, deliberately out of
scope here so this design stays about "what may the agent DO."

## 1. The ladder law

Each rung of rigor adds exactly one concept, and **you cannot feel a rung you didn't
climb to**:

- **Rung 0** — `grant(tool, allow())`: today's shape, byte-identical behavior. Static
  policies are canonical singletons checked by identity; for them the executor renders
  no effect, assembles no chain, pays nothing.
- **Rung 1** — a lambda over facts: "deny after ten calls," "business hours only."
- **Rung 2** — the effect: the tool implements `EffectfulTool<I, E>`; the grant
  hands the policy `E` directly. Sugar for a zero-link chain — not a second idiom.
- **Rung 3** — the typed chain: links refine `E → T → U`; the policy takes the end
  type. Appears exactly when assembled inputs are needed.
- **Rung 4** — intent, shared assessor libraries, org policy suites.

The decision vocabulary NEVER changes across rungs: `Allow / Deny(reason) /
RequireApproval`, the existing sealed three. Deny reasons compact into context
(factor 9); approval parks as today. Rigor changes what a policy knows, never what it
can say.

## 2. The effect — Tool.describe becomes Tool.effect

- `Tool<I>.describe(I input)` is RENAMED `effect(I input)` and widens its return type
  to `Object` (mechanical rename repo-wide; no release exists). A String is a
  perfectly valid effect statement — every existing tool's one-line description
  carries over as its effect, and approval prompts render `toString()` as they
  always have.
- The typed tier: `interface EffectfulTool<I, E> extends Tool<I> { @Override E effect(I input); }`
- The typed grant overload ties `E` between tool and policy at compile time:
  `grant(EffectfulTool<I, E> tool, ...)` paths only accept chains/policies rooted at
  `E`. A mismatch does not compile.
- Rendering order at the chokepoint: parse → effect → chain → policy → decide. The
  effect is rendered once per evaluated call and flows to the policy, the approver,
  and the audit record.

## 3. The facts — an accumulating builder as the RECOMMENDED pattern

The contract is only this: links receive the facts and the payload, the payload type
walks, and the policy judges an immutable snapshot. HOW facts assemble is an opinion,
not a mandate (owner ruling): nessy's shipped shape is an accumulating BUILDER —
mutable only during chain traversal, single-threaded, per-call, so decorator-style
links deposit into it as they run, freezing into the immutable `AuthFacts` snapshot
the policy receives. Apps that prefer carrying everything in their own payload types
through pure `Function` links may ignore the facts lane entirely — both idioms are
first-class. The shipped builder is seeded by the executor per evaluated call with:

- `conversationId()`, `agentName()`, `call()` (the raw ToolCall), `state()` (the
  conversation control block — what today's two-arg policies see).
- `principal()` → `Optional<Object>` and `principal(Class<P>)` → `Optional<P>` — the
  nominal, dynamically-typed identity slot (§6).
- `declaredIntent()` → `Optional<Object>` and `declaredIntent(Class<T>)` — filled by
  the intent module when wired (§5), absent otherwise. Nominal slot, app-typed value,
  same recovery pattern as the principal.

- `put(Key<T>, T)` / `get(Key<T>)` → typed-key deposits and recovery — the open
  half: any link may contribute any fact under an app-defined key; the principal and
  intent slots are simply pre-seeded well-known keys in the same mechanism.

Facts are substrate: nominally named so shipped machinery (approval UIs, the audit
report) can always find and render them, dynamically typed so nessy never dictates
their shape. The two-lane guidance (recommendation, not rule): spine-shaped data walks the
PAYLOAD types (§4); cross-cutting data deposits into the FACTS builder — each link
chooses its lane, and a chain that uses only one lane is perfectly idiomatic.

## 4. The chain — typed refinement, Stream.map for authorization

```java
ToolGrant.grant(transferTool,                              // EffectfulTool<Transfer, TransferEffect>
    authorize(TransferEffect.class)
        .transform("principal", (facts, effect) ->
            new Attributed(facts.principal(AcmePrincipal.class).orElseThrow(), effect))
        .transform("risk score", (facts, attributed) -> riskService.score(attributed))
        .policy(scored -> scored.risk() > 700 ? deny("risk too high") : allow()));
```

- Two link flavors, one pass-through object:
  - `transform(name, (facts, payload) -> nextPayload)` — the payload lane: returns
    the chain retyped at `<T>`; the next link must accept `T` or compilation fails;
    the terminal `policy(...)` accepts `UsagePolicy<? super T>`.
  - `enrich(name, facts -> { facts.put(KEY, ...); })` — the decorator lane: deposits
    into the facts builder, payload type unchanged. Because an enrich never touches
    `E`, org decorator libraries compose into ANY grant's chain regardless of its
    effect type — the reuse story for per-grant chains.
  - Both receive the same accumulating facts builder; a plain `Function<C, T>` lifts
    into a transform trivially.
- Links MAY do I/O (assessors consult risk services, budget counters, IdPs). Links
  are the impure gathering stage; the policy stays pure.
- **Fail closed, everywhere**: a throwing link, a throwing `effect`, or a throwing
  policy each yields `Deny` with a reason naming the failed stage — never an allow,
  never an escaped exception into the loop.
- Chains are **per-grant** (they must be — each starts at that tool's `E`); reuse is
  composition: shared links are just functions passed into many grants. Nothing is
  registered centrally.
- Named links (`transform(name, fn)`; name optional) exist for §8's report, not for
  behavior.
- the rung-2 sugar — is literally `authorize(E).policy(policy)`; naming settles at planning.

## 5. UsagePolicy<C> — generics, variance, unchanged soul

- `UsagePolicy<C>` with `PolicyDecision evaluate(AuthFacts facts, C context)`; pure,
  any-thread, fail-closed — the existing contract, better fed.
- `allow()` and `deny(reason)` are canonical `UsagePolicy<Object>` singletons (the
  emptyList pattern — one instance, inferred at any type via `? super T` acceptance
  everywhere policies are taken). Identity-comparison drives the rung-0 skip: static
  policy → no effect rendered, no chain, no facts assembly.
- `requireApproval()` keeps a context-blind form; context-aware policies return
  `RequireApproval` conditionally (rung 2+ approval-on-threshold, as in §4's example).
- Migration: the current two-arg `evaluate(call, state)` shape becomes a rung-1
  factory (`UsagePolicy.of((facts) -> ...)` reading `facts.call()`/`facts.state()`);
  existing custom policies migrate mechanically. No release exists; the break is free.

## 6. The who — principal as substrate

- **Any principal type.** Nessy defines the slot, never the shape: no JWT assumed, no
  `act` claim required, no marker interface. Typed recovery by class token; audit and
  approval surfaces render `toString()`.
- **v1 feeding (this generation):** an agent-level resolver seam —
  `a.principal(conversationId -> ...)` — impure-allowed, fail-closed. The facts API is
  the stable surface; how the principal gets there can upgrade underneath.
- **v2 feeding (the identity generation, banked):** durable door attachment (the
  telling carries the principal through the inbox — audit-grade, replay-exact),
  automatic propagation to subagent conversations, and an `act`-claim/OAuth
  token-exchange helper shipped as an opinion. Nessy's structural guarantee already
  in force: subagent grants are lexically declared by the parent, so delegated
  authority attenuates by construction — delegation can narrow, never widen.

## 7. The claim — intent as a bolt-on module

`spi.intent`, the plan/notebook pattern applied to authority:

- `IntentTools.declare(vocabulary)` yields the `declare_intent` tool (plus a clear
  verb — one tool with a clearing value or a second tool, settled at planning). The
  vocabulary IS the tool's schema: an enum or sealed class boxes the model into a
  strict vocabulary at parse time; `String.class` permits open-ended intent.
- **Lifetime: until re-declared or cleared**, scoped to the conversation.
- **Derived from the transcript** — the declaration is a tool call, already durably
  recorded; the machinery reads the latest declaration back. No new store; replayed
  decisions see what the original saw.
- Wired → `facts.declaredIntent()` is present; unwired → absent, zero ceremony. The
  claim is untrusted by definition; its sharpest use is cross-examination against the
  effect ("declared read-only; this effect writes") — a policy or assessor move nessy
  may ship as an opinion.

## 8. Self-documentation — the report is the wiring

A grant's authorization story is inspectable: effect type, named links in order, the
policy's identity. The harness renders per-agent:

```
transfer:  TransferEffect → principal → risk score → policy (approval above $1,000)
clock:     allow
```

Generated from the actual wiring, so it cannot drift. This is the audit surface's
raw material and the "self-documenting" requirement made literal.

## 9. Adjudication parity

The approver receives the same assembled context the policy saw — the effect
(rendered), facts, chain product — because the approval UI is exactly where the
account and the assessment matter most. Exact approver signature evolution settles
at planning against the current `Approver` seam.

## 10. Named non-goals (floors above this substrate, deliberately not built here)

1. **Decide-and-reserve quotas** — "$10k/day org-wide" needs atomic decide+debit
   semantics a pure decision cannot transact; its own generation.
2. **Sticky/plan-scoped approvals** — "approve the whole plan," "allow this class for
   the turn"; representable via assessors consulting approval state, first-classed
   later if earned.
3. **Obligations** — "allow BUT redact/notify/log-at-evidence-level" extends the
   OUTCOME vocabulary; resisted until a real need shows.
4. **Re-evaluation at resume** — v1 keeps today's semantics: the decision is made at
   call time; a resume delivers the already-made decision. Re-check-on-wake for
   long-parked approvals (revocation windows) is banked, named, and deliberate.
5. **Content screening** — a sibling domain (what the agent says/reads), not
   authorization (what the agent does).

## 11. Testing

House rules. The ladder rule pinned per rung (rung-0 grants: effect never rendered,
no assembly — spy tool proves it); covariance compatibility (every existing example
tool compiles unmodified); chain typing (compile-time — the API shape is the test);
fail-closed at each stage (throwing describe / link / policy → Deny naming the
stage); variance (canonical allow() terminates any chain); intent lifetime
(declare → decide → redeclare → decide; clear; replay sees transcript-derived value);
principal recovery (typed hit, typed miss, absent); the report rendering pinned
against wiring; approver parity.

## 12. Sequencing

Spec review by owner → plan → build as the generation after reflection ships.
Reflection is unaffected (different files); the guardrail engine, quotas, identity
generation, and obligations all stack on this substrate later.
