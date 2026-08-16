# Authorization — the tool's effect starts the decision

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner; the typed-chain posture was explored and
deliberately simplified to the context-and-enrichers shape recorded here).
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`
(extends the grant principle); judged against the mission lens in `ROADMAP.md`
(opinions-not-mandates; every seam an escape hatch).

## 0. The anchor, and the domain map

**The authorization of a tool call begins with the tool's own statement of its
effect.** After the arguments parse, the tool renders `effect(input)` — "execute this
call with these arguments, and this is what will happen" — an object of whatever type
the tool author chose, `E`. The grant welds that type through to the policy at compile
time; everything else the decision needs assembles around it.

The domain decomposes into six parts with a rising trust gradient:

| Part | Speaker | Trust | Embodiment |
|---|---|---|---|
| Claim ("what I'm trying to do and why") | the model | untrusted assertion | intent, a bolt-on tool module (§7) |
| Effect ("what will happen if you do it") | the tool | trusted (developer-authored) | `effect(input)` → `E` (§2) |
| Assessment ("the risk of that effect") | org-supplied enrichers | derived judgment | `Enricher<E>` (§4; may do I/O) |
| Judgment (allow / deny / require approval) | the policy | pure law | `UsagePolicy<E>` (§5) |
| Adjudication | a human | authority | the existing approver/park machinery, better fed (§9) |
| Record | the harness | — | transcript + parks + inbox, already durable |

The **who** — the principal a conversation acts for — is substrate: a nominal,
dynamically-typed slot in the context (§6), never a framework-imposed shape.

Authorization governs **actions** — tool calls, the only effectful channel an agent
has. Content screening (model output reaching users; prompt injection riding tool
results inward) is a sibling domain with different machinery, deliberately out of
scope so this design stays about "what may the agent DO."

## 1. The ladder law

Each rung of rigor adds exactly one concept, and **you cannot feel a rung you didn't
climb to**:

- **Rung 0** — `grant(tool, allow())`: today's shape, byte-identical behavior. Static
  policies are canonical singletons checked by identity; for them the executor renders
  no effect, assembles no context, pays nothing.
- **Rung 1** — a lambda over the context: "deny after ten calls," "business hours
  only" — `UsagePolicy<Object>` reading `context.call()`/`context.state()`.
- **Rung 2** — the effect: the tool implements `EffectfulTool<I, E>`; the typed grant
  hands the policy `(context, E)` with compile-time agreement.
- **Rung 3** — enrichers: org decorators and assessors deposit into the context
  before judgment.
- **Rung 4** — intent (§7), shared enricher libraries, org policy suites.

The decision vocabulary NEVER changes across rungs: `Allow / Deny(reason) /
RequireApproval`, the existing sealed three. Deny reasons compact into context
(factor 9); approval parks as today. Rigor changes what a policy knows, never what it
can say.

## 2. The effect — Tool.describe becomes Tool.effect

- `Tool<I>.describe(I input)` is RENAMED `effect(I input)` and widens its return type
  to `Object` (mechanical rename repo-wide; no release exists). A String is a
  perfectly valid effect statement — every existing tool's one-line description
  carries over as its effect, and approval prompts render `toString()` as they always
  have.
- The typed tier: `interface EffectfulTool<I, E> extends Tool<I> { @Override E effect(I input); }`
- The typed grant overload welds `E` end to end at compile time:
  `grant(EffectfulTool<I, E> tool, enrichers..., UsagePolicy<? super E> policy)` — a
  mismatch does not compile.
- Order at the chokepoint: parse → effect → enrichers → policy → decide. The effect
  is rendered once per evaluated call and flows to the policy, the approver, and the
  audit record.

## 3. AuthorizationContext — non-generic, immutable, a typed-key bag over core facts

One concrete context type across the entire system — deliberately NOT generic, so
enrichers written against it compose into any grant:

```java
public interface AuthorizationContext {
  ConversationId conversationId();
  String agentName();
  ToolCall call();                    // the raw call: tool name + parsed arguments
  ConversationState state();          // the conversation's control block

  Optional<Object> principal();       // nominal slots, present when wired (§6, §7):
  <P> Optional<P> principal(Class<P> type);
  Optional<Object> declaredIntent();
  <T> Optional<T> declaredIntent(Class<T> type);

  <T> Optional<T> get(Key<T> key);    // anything enrichers deposited
  <T> AuthorizationContext with(Key<T> key, T value);  // functional extension
}
```

- Immutable; `with` returns a new context. Enrichers extend it functionally (§4); the
  policy receives the final context — a stable, sealed view, keeping judgment pure.
- `Key<T>` is a typed key (class token + name); the well-known slots nessy ships
  (principal, intent) are the opinions; app-defined keys are the escape hatch. A
  missing key is `Optional.empty()` — policies fail closed on absences they care
  about, and the deny reason names what was missing.
- The core facts (conversationId, agent, call, state) are what the harness knows
  before any app code runs; they are also the lookup keys most impure enrichers turn.

(Two explored ideas BANKED, not built: the typed refinement chain — payload types
walking `E → T → U` link by link — can layer on later as pure sugar over this
substrate; and declared-keys build-time completeness metadata was judged
documentation in a validator's costume — fail-closed absence handling is v1's
answer.)

## 4. Enrichers — one shape, two species

```java
@FunctionalInterface
public interface Enricher<E> {
  AuthorizationContext enrich(AuthorizationContext context, E effect);
}
```

- Wired per grant as an ordered list; each receives the previous context and returns
  the next. Enrichers MAY do I/O (principal exchange, risk services, quota reads) —
  they are the impure gathering stage; the policy stays pure.
- **Variance is the reuse story**: wiring accepts `Enricher<? super E>`, so
  effect-blind decorators written once as `Enricher<Object>` (quota, tier, principal
  exchange) compose into ANY grant, while effect-aware assessors type themselves to
  their `E` and the compiler welds them to matching grants only.
- **Fail closed, everywhere**: a throwing `effect`, a throwing enricher, or a throwing
  policy each yields `Deny` with a reason naming the failed stage — never an allow,
  never an escaped exception into the loop.
- Enrichers may carry an optional display name (for §8's report); behavior never
  depends on it.
- The symmetry is the API's self-documentation: `Enricher<E>: (context, E) → context`
  assembles; `UsagePolicy<E>: (context, E) → decision` judges. One shape, said twice.

## 5. UsagePolicy<E> — generics, variance, unchanged soul

- `UsagePolicy<E>` with `PolicyDecision evaluate(AuthorizationContext context, E effect)`;
  pure, any-thread, fail-closed — the existing contract, better fed.
- `allow()` and `deny(reason)` are canonical `UsagePolicy<Object>` singletons (the
  emptyList pattern); every accepting site takes `UsagePolicy<? super E>`, so they
  terminate any grant. Identity-comparison drives the rung-0 skip: static policy → no
  effect rendered, no enrichers, no context assembly.
- `requireApproval()` keeps a context-blind form; context-aware policies return
  `RequireApproval` conditionally (approval-above-threshold and the like).
- Combinators (all-of, first-deny, etc.) are ordinary library code over the
  functional interface — userland, not framework.
- Migration: today's `evaluate(ToolCall, ConversationState)` policies become rung-1
  lambdas reading `context.call()`/`context.state()`; mechanical, and the break is
  free — no release exists.

## 6. The who — principal as substrate

- **Any principal type.** Nessy defines the slot, never the shape: no JWT assumed, no
  `act` claim required, no marker interface. Typed recovery by class token; audit and
  approval surfaces render `toString()`. The moment a grant wants static typing, an
  enricher lifts the principal into a deposited key — one checked recovery at the
  boundary.
- **v1 feeding (this generation):** an agent-level resolver seam —
  `a.principal(conversationId -> ...)` — impure-allowed, fail-closed. The context API
  is the stable surface; how the principal gets there can upgrade underneath.
- **v2 feeding (the identity generation, banked):** durable door attachment (the
  telling carries the principal through the inbox — audit-grade, replay-exact),
  automatic propagation to subagent conversations, and an `act`-claim/OAuth
  token-exchange helper shipped as an opinion. Already in force structurally:
  subagent grants are lexically declared by the parent, so delegated authority
  attenuates by construction — delegation can narrow, never widen.

## 7. The claim — intent as a bolt-on module

`spi.intent`, the plan/notebook pattern applied to authority:

- Two tools: `declare_intent`, whose input type IS the vocabulary, and `clear_intent`,
  which takes no input. (Ruled at planning: a clearing MEMBER inside the vocabulary
  was rejected — it would force every app's type to carry a "disregard me" constant
  and make the store's delete path depend on a blessed member.)
- **The vocabulary must be a STRUCTURED type (owner ruling — open-ended `String`
  intent is withdrawn).** A tool's parameters render as an OBJECT schema, so a bare
  `String` — and equally a bare enum, the spec's own earlier example — renders a naked
  string schema that is not a valid parameter object for OpenAI or Anthropic. The
  vocabulary is therefore a record, a POJO, or a sealed interface of records; an enum
  is how you box a FIELD inside one, never the vocabulary itself. Open-ended intent
  means declaring your own shape (`record OpenIntent(String what, String why)`) —
  which is better than a free string anyway, because the fields carry the questions.
  `intent(...)` REJECTS at wiring time any type that cannot render as an object schema
  (String, primitives and their boxes, enums, collections, arrays), with a message
  naming the offending type and telling the caller to wrap it in a record.
- **Lifetime: until re-declared or cleared**, scoped to the conversation.
- **Wired by one field on the agent (amended after owner review — IntentSupport is
  WITHDRAWN):** `AgentConfig<I>.intent(Class<?> intentType)` is the whole public
  surface. The build assembles the declare tool, the clear tool, and the reading
  enricher internally from that type plus the harness's store; the user never learns
  a second noun. The withdrawn `IntentSupport<V>` failed on its own signature —
  `AgentConfig<I>` is parameterized on the AGENT'S input type, so `V` could only ever
  be a method-level type variable appearing in one argument and nowhere in the return:
  decoration, not typing. It would have bought `support.declaredIn(ctx)` over
  `context.declaredIntent(RefundIntent.class)` — one class token, written where the
  vocabulary is already known — at the price of a permanent public type at 0.1.0.
  - One field makes the one-intent-per-agent rule true by construction (a second
    call is a wiring-time error, not a runtime row overwrite).
  - `HarnessConfig.intentStore(...)` follows every other store (in-memory default,
    inherited by agents), so the shipped example reads `.intent(RefundIntent.class)`
    and nothing else. No ordering hazard against `.grant(...)`: policy lambdas run
    per call, not at wiring.
  - Policies read the blessed accessor `context.declaredIntent(Class<T>)`; storage
    stays the single `INTENT_KEY` (`Key<Object>`) so vocabulary-blind readers (the §8
    report, audit rendering) keep working, and app-defined keys remain the seam for
    anyone running their own intent discipline.
  - Reads FAIL CLOSED: a stored intent of a different vocabulary, or one whose class
    no longer resolves after a rename, reads as ABSENT — never a ClassCastException,
    never an allow. The deny-teaches-protocol path handles it.
- **Backed by its own tiny store (amended after owner review — the plan-store
  pattern, not transcript scanning):** `IntentStore` — `(conversation_id) → the
  declared intent (serialized, with its type)` — LWW on declare, delete on clear;
  in-memory default + `JdbcIntentStore` + TCK contract, the eighth store. The
  declare tool WRITES it; the intent reader FETCHES one row per decision (O(1),
  never a transcript scan). Replay-idempotent by the familiar argument: a
  re-executed declaration rewrites the identical value. The earlier
  transcript-derivation idea is REJECTED: linear scan cost on the authorization hot
  path, and a coupling to transcript completeness that retention policies (and
  transcript-less deployments) would silently break.
- Wired → `context.declaredIntent()` is present; unwired → absent, zero ceremony. The
  claim is untrusted by definition; its sharpest use is cross-examination against the
  effect ("declared read-only; this effect writes") — a policy or enricher move nessy
  may ship as an opinion.

## 8. Self-documentation — the report is the wiring

A grant's authorization story is inspectable: effect type, enricher names in order,
the policy's identity. The harness renders per agent:

```
transfer:  TransferEffect → principal → risk score → policy (approval above $1,000)
clock:     allow
```

Generated from the actual wiring, so it cannot drift. The audit surface's raw
material, and the "self-documenting" requirement made literal.

## 9. Adjudication parity

The approver receives what the policy saw — the final context and the rendered
effect — because the approval UI is exactly where the effect and the assessment
matter most. Exact approver signature evolution settles at planning against the
current `Approver` seam.

## 10. No corners — the third-party audit

Could this structure preclude a guardrails framework someone else wants to build?
Audited deliberately (owner's question). A third party gets four seams, all
interfaces, none final: **enrichers** (their screening/scoring service as an
`Enricher<Object>`, I/O explicitly welcome — a remote policy engine integrates as an
enricher that calls home and deposits the verdict, with a thin `UsagePolicy` reading
the deposit), **policies** (their evaluation logic directly), **tool decoration**
(`Tool` is an interface — wrapping `execute` covers the allow-but-transform/redact
family without touching the decision vocabulary), and the **approver** (their HITL
product). Intent vocabularies and `Key<T>`s are open data channels.

The ONE deliberate rigidity is the sealed three-outcome decision vocabulary. Its
pressure valves: tool decoration absorbs most obligation-shaped needs today, and a
new sealed case is an additive, compile-visible extension if the ecosystem ever
earns it. Content-screening frameworks need the sibling domain's future seams —
scope honesty, not a trap.

## 11. Named non-goals (floors above this substrate, deliberately not built here)

1. **Decide-and-reserve quotas** — atomic decide+debit semantics; its own generation.
2. **Sticky/plan-scoped approvals** — representable via enrichers consulting approval
   state; first-classed later if earned.
3. **Obligations** — "allow BUT redact/notify" extends the OUTCOME vocabulary;
   resisted until a real need shows (§10's pressure valves apply meanwhile).
4. **Re-evaluation at resume** — v1 keeps today's semantics (decision made at call
   time; resume delivers it); re-check-on-wake for long-parked approvals is banked
   and deliberate.
5. **Content screening** — a sibling domain (what the agent says/reads).
6. **The typed refinement chain and declared-keys validation** — banked sugar and
   banked metadata respectively (§3).

## 12. Testing

House rules. The ladder pinned per rung (rung-0 grants: effect never rendered, no
assembly — spy tool proves it); covariance compatibility (every existing example tool
compiles unmodified); typed-grant welding (compile-time — the API shape is the test);
fail-closed at each stage (throwing effect / enricher / policy → Deny naming the
stage); variance (canonical `allow()` terminates any grant; an `Enricher<Object>`
composes into a typed grant); context immutability (an enricher's context is not the
policy's if a later enricher extended it); intent lifetime (declare → decide →
redeclare → clear; replay sees the transcript-derived value); principal recovery
(typed hit, typed miss, absent); the report pinned against wiring; approver parity.

## 13. Sequencing

Ratified. Plan → build as the generation after reflection ships; the guardrail
engine, quotas, identity generation, and obligations stack on this substrate later.
