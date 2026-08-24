# Intent

Intent is the model's own untrusted claim of what it is about to do and
why — a declaration, recorded before it calls any other tool, that a
policy may read back and weigh alongside everything else it gathers. It is
never a grant of authority on its own.

The whole feature ships as its own artifact, `org.jwcarman.nessy:nessy-intent`
(package `org.jwcarman.nessy.intent`), depending on `nessy-api` and
`nessy-spi` — an application that never declares intent carries none of
this code or its storage kind.

## Two tiers, one generic kit

Nessy ships two tiers of declaration, riding the same `IntentTool<T>`:

**Freeform** — a plain sentence, when there's no vocabulary to anchor
against yet:

```java
public record Intent(String declaration) {
  public Intent {
    if (declaration == null || declaration.isBlank()) {
      throw new IllegalArgumentException("declaration must not be blank");
    }
  }
}
```

**Sealed vocabulary** — an organization's own closed set of declaration
shapes, when the domain has one, annotated with the two standard Jackson
polymorphism annotations so `Schemas` and the tool executor's binding read
the same vocabulary:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
  @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
})
sealed interface OpsIntent permits Restart, Diagnose {}
record Restart(String target, String reason) implements OpsIntent {}
record Diagnose(String target) implements OpsIntent {}
```

`IntentTool<T>` is generic over the vocabulary itself — not two separate
tool classes:

```java
public final class IntentTool<T> implements Tool<T> {
  public IntentTool(Class<T> vocabulary, IntentStore<T> store) { ... }
  public static IntentTool<Intent> freeform(IntentStore<Intent> store) {
    return new IntentTool<>(Intent.class, store);
  }
  // name() is always "declare-intent"
}
```

Because `inputType()` just returns the vocabulary class as-is, an annotated
sealed vocabulary rides `Schemas`' `oneOf` schema and Jackson's own
polymorphic binding with zero extra code — the same [sealed-input
machinery](tools.md#sealed-inputs-a-vocabulary-as-one-argument) any tool
gets. This is the NLU anchor: a sealed vocabulary turns "the model says
what it's about to do" into a small, closed grammar a policy can
pattern-match on, rather than a sentence it has to parse.

Test your vocabulary over `InMemorySubstrate` (see [Storage](storage.md)):
storage there is real encoded bytes, so a missing or mis-set annotation on
`OpsIntent` fails in your own unit tests, not in production.

## Declaring, and reading it back

`declare-intent`'s only job is to write the claim into an `IntentStore<T>`:

```java
public interface IntentStore<T> {
  void declare(T declaration);
  Optional<T> latest();
}
```

One store, pre-scoped like `Memory` — no id parameter anywhere. Last write
wins. `IntentEnricher` reads that store and deposits its latest declaration
under `AuthzContext.DECLARED_INTENT_KEY` for a policy to read back through
`context.declaredIntent(Class<T> type)`. Absent a declaration, the context
passes through untouched — a missing claim is a policy's own choice to
weigh, not the enricher's failure to report.

`SubstrateIntentStore<T>` is the shipped implementation: one document per
scope, `kind=intent`, written by a read-then-CAS retry loop over
[`Substrate`](storage.md) — the same document the scope's state and
backlog already live in, on the same substrate. It stores through a
`Codec<T>` (`org.jwcarman.codec.spi.Codec`); a constructor taking an
`ObjectMapper` and the vocabulary class derives one from a
`Jackson2CodecFactory` over that mapper for you. Build one directly
against whatever store the host is using:

```java
var substrate = new InMemorySubstrate();
var intentStore = new SubstrateIntentStore<>(substrate, "prod-eu", OpsIntent.class, mapper);
```

## `requireDeclared` — the teaching loop

`IntentPolicies.requireDeclared(vocabulary)` denies unless a declaration of
that exact type is on the context — absence and a wrong-typed declaration
both deny the same way:

```java
"no OpsIntent declared — declare your intent with the declare-intent tool before acting"
```

That in-band denial is the teaching loop: the model tries the risky call,
gets refused with an explicit instruction, calls `declare-intent`, then
retries — and the retry succeeds. `TypedIntentDemo` is the worked example:
a `restart_prod` call with no declaration is denied; the model declares a
`Restart("prod-eu", "stuck deploy")`; the retried restart parks for
approval and completes once granted.

```java
List.of(
    IntentPolicies.requireDeclared(OpsIntent.class),
    RiskPolicies.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH))
```

Composed with `UsagePolicy.allOf(...)`, `requireDeclared` and a risk
threshold gate the same grant together — no declaration denies before risk
is even consulted.

## The consistency tripwire

A vocabulary buys more than "did it declare something" — a policy can check
that what was declared and what was attempted **agree**. `TypedIntentDemo`
wires its own consistency policy alongside the two above:

```java
UsagePolicy.of(context -> {
  Optional<OpsIntent> declared = context.declaredIntent(OpsIntent.class);
  if (declared.isEmpty()) {
    return new PolicyDecision.Allow();
  }
  return switch (declared.get()) {
    case Restart(String target, _) -> {
      String rendered = context.action(String.class).orElse("");
      yield rendered.contains(target)
          ? new PolicyDecision.Allow()
          : new PolicyDecision.Deny(
              "declared intent targets \"" + target + "\" but the action is \"" + rendered + "\"");
    }
    case Diagnose _ -> new PolicyDecision.Allow();
  };
});
```

When the model declares `Restart("prod-eu", ...)` and then attempts to
restart `prod-us`, this policy denies naming both targets — before any
approver is ever asked. The declared claim and the rendered action are two
independent facts on the same context; nothing forces them to agree except
a policy that checks.

## The unrepresentable declaration

Because the vocabulary is a sealed input, a declaration outside it is
rejected by Jackson's own polymorphic binding, in-band, before
`declare-intent` ever runs — nothing is stored. In `TypedIntentDemo`, a
declaration carrying `"type": "Nuke"` against `OpsIntent`'s `Restart`/
`Diagnose` pair fails with a message naming both legal types, and
`intentStore.latest()` stays empty. The teaching loop and the schema
boundary are the same mechanism working at two different points — one on
the shape of the declaration, one on its content.

## Where next

- [Authorization](authorization.md) — where `declaredIntent()` fits among
  the other facts a policy judges, and the grant that wires
  `IntentEnricher` alongside a risk assessor.
- [Tools](tools.md) — the sealed-input schema and annotated discriminator
  binding `IntentTool` rides for free.
- [Agent as Scope](agent-as-scope.md) — how a tool call, denied or
  approved, fits into the phase transition that carries a turn forward.
- [Storage](storage.md) — the substrate `SubstrateIntentStore` rides, and why
  `intent` is a reserved document kind.
