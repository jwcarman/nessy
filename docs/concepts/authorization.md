# Authorization

Every tool call an agent makes passes through one chokepoint before it runs. What that
chokepoint knows, and how rigorously it judges, is a ladder you climb one rung at a
time — and a rung you never climb costs nothing.

## The domain map

An authorization decision has six parts, and they carry a rising trust gradient — from
an assertion nobody has to believe, to the record nothing can revise:

| Part | Speaker | Trust | Embodiment |
|---|---|---|---|
| Claim ("what I'm trying to do and why") | the model | untrusted assertion | intent (below) |
| Effect ("what will happen if you do it") | the tool | trusted, developer-authored | `effect(input)` |
| Assessment ("the risk of that effect") | enrichers | derived judgment | `Enricher<E>` (may do I/O) |
| Judgment (allow / deny / require approval) | the policy | pure law | `UsagePolicy<E>` |
| Adjudication | a human | authority | the approver, parking machinery |
| Record | the harness | — | transcript, parks, the report below |

The **who** — the principal a conversation acts for — is substrate underneath all of
this: a nominal, dynamically-typed slot in the context, never a shape nessy imposes.

Authorization governs **actions** — tool calls, the only effectful channel an agent
has. What the model says to a user, or reads back from a tool result, is a sibling
concern with its own machinery; this page is about what the agent may *do*, not what it
may say.

## The ladder

Each rung adds exactly one concept, and the decision vocabulary never changes: every
rung still answers with the same sealed three, `Allow`, `Deny(reason)`, or
`RequireApproval`. Rigor changes what a policy *knows*, never what it can *say*.

**Rung 0 — a static verdict.**

```java
ToolGrant.grant(new AddTool(), UsagePolicy.allow())
```

`allow()`, `deny(reason)`, and `requireApproval()` are the canonical statics. For
`allow()`/`deny(reason)` specifically — the two whose verdict never varies — the
chokepoint takes a fast path: no effect is rendered, no context is assembled, no
enricher runs. This is the rung-0 promise made literal: a grant that never climbs the
ladder pays nothing for rungs it doesn't use.

**Rung 1 — a lambda over the context.**

```java
UsagePolicy.<Object>of((context, effect) -> context.call().arguments().containsKey("admin")
    ? new PolicyDecision.Deny("admin flag not permitted")
    : new PolicyDecision.Allow())
```

Now the policy reads `context.call()`/`context.state()` — "deny after ten calls,"
"business hours only" — but the tool's own effect is still the untyped `Object` every
`Tool` renders by default (its `toString()`).

**Rung 2 — the typed effect.**

```java
ToolGrant.grant(tool, List.of(), policy)
```

The tool implements `EffectfulTool<I, E>`, and the grant welds `E` through to the
policy at compile time — a mismatch does not compile. The `List.of()` is not
decorative: a two-argument `grant(EffectfulTool<I,E>, UsagePolicy<? super E>)` overload
was tried and is genuinely ambiguous against the untyped door above, because `Object`
is always a valid witness for `? super E` — `javac` rejects it outright
(`reference to grant is ambiguous`) for any call passing one of the canonical
`UsagePolicy<Object>` statics. A varargs form was tried too, and silently drops the
type welding instead of failing to compile. `List.of()` costs one token and stays
unambiguous; that is the whole reason the empty list is there.

**Rung 3 — enrichers.**

```java
ToolGrant.grant(tool, List.of(new RushOrderEnricher()), policy)
```

An ordered list of `Enricher<? super E>` runs before judgment, each depositing into the
context the policy will see.

**Rung 4 — intent, shared enricher libraries, org policy suites.** Covered below and in
the sections that follow — the same primitives, composed at scale.

## The effect

`Tool<I>.effect(I input)` states what executing the call will do: "execute this call
with these arguments, and this is what will happen." The tool is the trusted,
developer-authored speaker of that statement — rendered once per evaluated call, and it
flows to the policy, the approval prompt, and the audit record alike.

```java
public interface Tool<T> {
  default Object effect(T input) {
    return String.valueOf(input);
  }
}

public interface EffectfulTool<T, E> extends Tool<T> {
  @Override
  E effect(T input);
}
```

A plain `Tool`'s default is fine for a low-stakes call — it reads like `Add[left=2,
right=2]` — but override it wherever an approver will actually read the prompt: a
line you skim is a line you approve without reading. `EffectfulTool<I, E>` is the same
idea with a real type behind it, so a policy or enricher can read fields instead of
parsing a string.

The order desk's own effect prices the order from a trusted collaborator, never from
the model's own arguments — the whole point of the effect being the tool's statement
rather than the model's:

```java
@Override
public FulfillmentEffect effect(Input input) {
  return new FulfillmentEffect(input.orderId(), input.items(), pricing.totalFor(input.items()));
}
```

`FulfillmentEffect` overrides `toString()` too — "Fulfill order 4711 (lantern, rope) —
$300.00" — so an approver reads a sentence, not a record dump.

## Enrichers and policy — one shape, said twice

```java
@FunctionalInterface
public interface Enricher<E> {
  AuthzContext enrich(AuthzContext context, E effect);
}

public interface UsagePolicy<E> {
  PolicyDecision evaluate(AuthzContext context, E effect);
}
```

The symmetry is the API's own documentation: an enricher is `(context, effect) →
context`, assembling; a policy is `(context, effect) → decision`, judging. Enrichers run
in wiring order, each handed the previous one's context and returning the next —
`AuthzContext.with(key, value)` is functional, so nothing upstream ever sees a later
enricher's deposit. The policy sees the final context, after every enricher has had its
turn, and stays pure: no I/O, nothing beyond a function of its two arguments.

Enrichers may do I/O — a principal exchange, a risk service call, a quota read — that's
the point of having an impure gathering stage in front of a pure judgment. The order
desk's enricher does not, deliberately, as the simplest honest member of the species:

```java
final class RushOrderEnricher implements Enricher<RequestFulfillmentTool.FulfillmentEffect> {

  static final Key<Boolean> RUSH_ORDER = new Key<>(Boolean.class, "order-desk.rush-order");

  private static final int RUSH_ITEM_COUNT = 3;

  @Override
  public AuthzContext enrich(AuthzContext context, RequestFulfillmentTool.FulfillmentEffect effect) {
    boolean rush = effect.items().size() >= RUSH_ITEM_COUNT;
    return rush ? context.with(RUSH_ORDER, true) : context;
  }

  @Override
  public Optional<String> displayName() {
    return Optional.of("rush-order flag");
  }
}
```

`Key<T>` is a typed slot — a class token plus a name, equal only by identity, so two
unrelated modules can't collide just by picking the same string. Declare one as a
`static final` constant the depositing enricher and the reading policy both import.

**Variance is the reuse story.** A grant accepts `List<? extends Enricher<? super E>>`,
so an effect-blind decorator written once as `Enricher<Object>` — quota, tier, a
principal exchange — composes into *any* grant, while an effect-aware assessor types
itself to its own `E` and the compiler welds it only to grants whose tool renders that
same effect.

The order desk's policy reads the enricher's deposit and applies a threshold — allow
**at or below** the limit, require approval **above** it, with a stricter line for a
flagged rush order (a bigger basket costs more to expedite, and is worth a second
look):

```java
final class OrderApprovalPolicy implements UsagePolicy<RequestFulfillmentTool.FulfillmentEffect> {

  static final BigDecimal STANDARD_THRESHOLD = new BigDecimal("500.00");
  static final BigDecimal RUSH_THRESHOLD = new BigDecimal("250.00");

  @Override
  public PolicyDecision evaluate(AuthzContext context, RequestFulfillmentTool.FulfillmentEffect effect) {
    boolean rush = context.get(RushOrderEnricher.RUSH_ORDER).orElse(false);
    BigDecimal threshold = rush ? RUSH_THRESHOLD : STANDARD_THRESHOLD;
    return effect.orderTotal().compareTo(threshold) > 0
        ? new PolicyDecision.RequireApproval()
        : new PolicyDecision.Allow();
  }
}
```

Wired at rung 3, through the typed door:

```java
ToolGrant.grant(
    new RequestFulfillmentTool(rabbit, pricing),
    List.of(new RushOrderEnricher()),
    new OrderApprovalPolicy())
```

Naming the policy `OrderApprovalPolicy` rather than writing it as a lambda is
deliberate — the report below (§ "The report") identifies a policy by its class's
simple name, so a named class reads well there; a lambda would report as an opaque
synthetic token.

## Intent — the claim, and why nothing validates it

`AgentConfig.intent(Class<?>)` declares an agent's one vocabulary for
`declare_intent`/`clear_intent` — two tools whose input type *is* the vocabulary the
model fills in to state what it's about to try and why. `context.declaredIntent()`
reads back the latest declaration, or is empty if the agent never wired one.

The claim is the least trusted part of the whole domain: it is untrusted by definition,
and its sharpest use is cross-examining it against the effect — "declared read-only;
this effect writes."

**`intent(...)` performs no check on the vocabulary type beyond rejecting `null` and a
repeat call.** It is an ordinary tool input type; nessy validates no other tool's input
type either, and no check performed at wiring time can know whether the model will be
able to fill the schema, whether the declared JSON will bind back into an instance, or
whether that instance round-trips through the `IntentStore`. A gate that only inspects
the rendered schema shape while staying silent on binding and round-tripping would be a
partial check wearing a certifier's costume — nessy does not ship one.

Two things are therefore the vocabulary author's own responsibility, checked only at
runtime by the same fail-closed machinery every tool call already gets:

- **The type must render a schema a model can fill.** A concrete record or POJO with
  properties is the straightforward choice — victools derives a normal
  `{"type":"object","properties":{...}}` schema from it with no extra wiring.
- **A polymorphic vocabulary needs its own Jackson wiring.** A sealed interface or
  abstract base with several shapes needs `@JsonTypeInfo` + `@JsonSubTypes` so a type
  indicator rides in the JSON, plus a schema that conveys the alternatives — because
  nessy's own victools configuration adds no subtype resolution. Empirically: a sealed
  interface of records renders as a bare `{"type":"object"}` under nessy's pinned
  victools 4.38.0 — no `oneOf`, no properties, nothing for a model to discriminate on,
  until the author wires that resolution themselves. This is something nessy does not
  do, not something it cannot be done — the seam is open.

If the model's JSON doesn't bind, the executor's own argument-binding failure denies
that call with a reason the model sees and can correct against — the same fail-closed
path every tool call gets. If the store write itself fails, that surfaces at declare
time. Nothing about a broken vocabulary silently succeeds; it just isn't caught before
the model tries to use it.

## The principal seam

Nessy defines the *slot* a conversation's principal occupies, never its *shape*: no JWT
assumed, no `act` claim required, no marker interface.

```java
AgentConfig<T> principal(Function<ConversationId, ?> resolver)
```

`resolver` runs once per evaluated call, impure allowed — a token exchange or a
directory lookup belongs exactly here. Its return value is deposited into
`AuthzContext.PRINCIPAL_KEY`; a `null` return is a legitimate "no principal for this
conversation" answer, not a failure, and the slot simply stays absent for that call.
Recovery is a checked class-token lookup:

```java
Optional<P> principal(Class<P> type)
```

— a typed hit, a typed miss (wrong token: empty, not a `ClassCastException`), or
absence if `.principal(...)` was never wired: all three read as `Optional.empty()`
except the hit, zero ceremony either way.

A throwing resolver is not a different story from a throwing enricher: it fails that
one call closed, naming the enricher stage, rather than ever becoming an allow or
escaping into the loop.

## The report

A grant's authorization story is inspectable — read from the wiring itself, never from
running it, so it cannot drift from what actually executes:

```java
AuthorizationReport report = agent.authorizationReport();
report.render();
```

For the order-desk grant above, `report.render()` produces:

```
request_fulfillment:  FulfillmentEffect → rush-order flag → policy (OrderApprovalPolicy)
```

A rung-0 grant renders plainly, without an effect stage — `night-watchman`'s own
`check_vitals` tool, granted `UsagePolicy.allow()` with no enrichers, reads:

```
check_vitals: allow()
```

`AuthorizationReport.of(...)` reads each `ToolGrant`'s own `tool()`, `policy()`, and
`enrichers()` — one reflective probe to name an `EffectfulTool`'s `E` by simple name —
and never calls `effect(...)`, `enrich(...)`, or `evaluate(...)`. It's a pure read of
the same wiring the chokepoint consults, so building a report can never perturb
evaluation.

One subtlety worth knowing: a grant whose policy is one of the rung-0 statics
(`allow()`/`deny(...)`) reports **no rendered effect and an empty enricher list**, even
if that grant happens to carry enrichers in its own wiring — because the chokepoint's
rung-0 fast path never runs them either. The report is honest about what actually runs,
not about what the wiring merely lists.

An enricher's name in the report comes from `Enricher.displayName()` — empty by
default, since a bare lambda's `getClass()` is an unreadable synthetic token. Name one
without changing its behavior:

```java
Enricher.named("rush-order flag", someEnricher)
```

A policy's identity in the report is its `getClass().getSimpleName()` (or, for the
canonical statics, `allow()`/`deny("reason")`/`requireApproval()`) — there's no
separate naming field for policies. Name the class if you want it to read well in the
report, as `OrderApprovalPolicy` does above.

## An XACML bridge, for readers who know it

Nessy's authorization vocabulary isn't XACML, and doesn't try to be — but if you've
built policy engines before, the shapes will look familiar. This is a bridge for that
vocabulary, not a claim of conformance:

| XACML role | Nessy's shape |
|---|---|
| PIP (Policy Information Point) | An `Enricher<E>` — gathers and deposits the facts a decision needs, I/O welcome |
| PDP (Policy Decision Point) | A `UsagePolicy<E>` — pure judgment over the assembled context and effect |
| PEP (Policy Enforcement Point) | `GatedToolCallExecutor` — the one chokepoint every tool call passes through |
| PAP (Policy Administration Point) | Grant wiring — `ToolGrant.grant(...)`, stated once, per tool, per agent |
| Human adjudication | The approver and parking machinery — `RequireApproval` defers here |

Two differences worth naming rather than glossing over:

- **The resource lives inside the effect**, not as a separate axis. XACML typically
  models subject/resource/action/environment as parallel attribute categories; nessy's
  effect is a single typed object the tool itself constructs, and "what resource, what
  action" are just fields on it — there's no separate resource-attribute channel.
- **Nessy's vocabulary names the speaker and its trust level** (claim, effect,
  assessment, judgment, adjudication, record — see the domain map above), not XACML's
  attribute categories. The trust gradient is the organizing idea; XACML's categories
  aren't part of this model at all.

## No corners — the third-party audit

Could this structure preclude someone else's guardrails framework? Four seams, all
interfaces, none final, are where a third party plugs in:

- **Enrichers** — a screening or scoring service integrates as an `Enricher<Object>`,
  I/O explicitly welcome; a remote policy engine calling home and depositing a verdict
  is exactly this shape, paired with a thin policy that reads the deposit.
- **Policies** — their evaluation logic directly, as a `UsagePolicy<E>`.
- **Tool decoration** — `Tool` is an interface; wrapping `execute` covers the
  allow-but-transform/redact family without touching the decision vocabulary at all.
- **The approver** — their own human-in-the-loop product, behind the existing
  `Approver` seam.

`Key<T>`s and intent vocabularies are open data channels, not framework-owned shapes.
The one deliberate rigidity is the sealed three-outcome decision vocabulary — `Allow`,
`Deny(reason)`, `RequireApproval`, and nothing else. Tool decoration absorbs most
obligation-shaped needs today ("allow, but redact"); a new sealed case would be an
additive, compile-visible extension if the ecosystem ever earns one.

## Fail-closed, everywhere

A throwing effect, a throwing enricher, a throwing policy, or a throwing principal
resolver — each denies that one call, naming the stage that broke, and never lets an
exception escape into the conversation loop or become an allow:

| Stage that throws | Result |
|---|---|
| `effect(input)` (or argument binding) | `Deny("argument binding or effect failed: ...")` |
| an enricher | `Deny("enricher failed: ...")` |
| the policy | `Deny("policy failed: ...")` |
| a policy returning `null` | `Deny("policy returned no decision")` |
| a principal resolver | `Deny("enricher failed: ...")` — the resolver runs as an enricher internally |

A rendered effect that comes back `null` fails the same way, naming the effect stage,
rather than reaching the approver with nothing to show it.

## Named non-goals

These are floors that could be built on top of this substrate, deliberately not built
here:

- **Decide-and-reserve quotas.** Atomic decide-plus-debit semantics ("allow, and spend
  from the budget in the same step") is its own generation of work, not folded into
  `UsagePolicy`.
- **Sticky or plan-scoped approvals.** Representable today via an enricher that
  consults prior approval state; not first-classed until that pattern earns it.
- **Obligations.** "Allow, but redact" or "allow, but notify" would extend the outcome
  vocabulary beyond the sealed three — resisted until a real need shows up, since tool
  decoration already covers most of this shape.
- **Re-evaluation at resume.** A decision made at call time stands; a long-parked
  approval is not re-checked against a policy that may have changed by the time it
  wakes.
- **Content screening.** What the model says, or reads back from a tool result, is a
  sibling domain with its own machinery — out of scope here by design, not by
  oversight.
- **A typed refinement chain, and declared-keys completeness metadata.** Both were
  explored and banked rather than built: a payload type walking `E → T → U` link by
  link can layer on later as sugar over this substrate, and build-time completeness
  checking over declared keys was judged documentation wearing a validator's costume —
  fail-closed absence handling (a missing `Key` just reads `Optional.empty()`) is v1's
  actual answer to "what if a key is missing."

## Where next

- [Tools and Grants](tools-and-grants.md) — the grant principle this ladder extends,
  and how MCP-imported tools carry the same authority story.
- [Parks and Callbacks](parks-and-callbacks.md) — what happens when `RequireApproval`
  defers to a human who isn't watching right now.
- [Testing](../guides/testing.md) — the no-mocking-library house style this domain's
  own tests follow.
