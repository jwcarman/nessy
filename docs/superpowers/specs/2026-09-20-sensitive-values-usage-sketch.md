# Sensitive values — usage sketch

> **Not a specification.** This is what an application developer writes, worked through a real
> billing scenario, drafted before any of it exists so we can find out whether it is pleasant while
> changing it is still free. Every name is provisional and needs James's sign-off.
>
> The test it has to pass: **an application that wants the guarantee should write almost nothing to
> get it.**

---

## 1. The whole vocabulary

Three verbs. There is no fourth way to touch a protected value.

| verb | what it does |
|---|---|
| `hold` | puts a value into the store, returns a `Sensitive<T>` |
| `derive` | makes a new stored value from existing ones, through a **registered** derivation |
| `dereference` | the single gated way back to plaintext |

`Sensitive<T>` is an id and a payload type. **It does not contain the value.** That is what makes the
gate unbypassable: you cannot forget to check, because there is nothing in the object to read.

### Four invariants everything else rests on

> **Possession is not authority.** Holding a handle does not permit reading it.

> **Presentation is not attribution.** A handle asserts identity only. Labels always come from the
> store — otherwise a model could forge an endorsed handle by writing one out.

> **A handle names a value; it does not describe how to make one.** No parents, no derivation, no
> structure. If a handle encoded its chain it would be a *recipe*, and handles arrive from the
> model — which would let the untrusted party author arbitrary computation over values it cannot
> read. The chain lives in the store. Consequently **lookup never computes**: an id is found or
> refused, never executed.

> **Inert references, registered behaviour.** Everything passed at a call site is a name. Every
> function and every ceiling lives in the registry, declared at wiring. Constructing a name confers
> nothing.

> **There is no dereference without a destination.** No overload omits it. You cannot obtain
> plaintext "in general" — only plaintext *for somewhere*, and that somewhere is what gets audited.

---

## 2. The scenario

A multi-tenant SaaS billing system. A customer emails support disputing a charge. The agent must
read the mail, identify the invoice, confirm it belongs to the person who wrote in, and refund it.

The data is genuinely awkward:

- the **email body** — untrusted, and contains the customer's own PII
- the **invoice and account** — authoritative, from the billing system
- the **payment token** — cardholder data, which must never reach any model, ever
- the **tenant** — cross-tenant leakage is the catastrophic failure of any SaaS

---

## 3. The application's attribution model

One record, one algebra. The only place the application describes what it cares about.

```java
public record BillingAttribution(
    Tenant tenant,          // whose data this is
    Integrity integrity,    // UNENDORSED | ENDORSED
    Tlp tlp,                // dissemination
    DataClass dataClass)    // NONE | PII | CARDHOLDER
{}
```

```java
static final AttributionAlgebra<BillingAttribution> ALGEBRA =
    Algebras.product(BillingAttribution::new)
        .field(BillingAttribution::tenant,    Algebras.exactOrConflict())
        .field(BillingAttribution::integrity, Algebras.ordinal(Integrity.class))
        .field(BillingAttribution::tlp,       Algebras.ordinal(Tlp.class))
        .field(BillingAttribution::dataClass, Algebras.ordinal(DataClass.class));
```

Four fields, four combinators, no join written by hand. The product of lattices is a lattice and its
join is componentwise, so if each field satisfies the laws the composed algebra does too.

`exactOrConflict()` earns its keep here: joining tenant A with tenant B yields `⊤`, which no
destination accepts. **A value derived from two tenants' data cannot go anywhere.** That bug class
becomes unrepresentable rather than merely discouraged.

### The posture, declared once

```java
@Bean
SensitiveValues<BillingAttribution> values() {
  return SensitiveValues.create(c -> c
      .algebra(ALGEBRA)
      .baseline(a -> a.integrity(ENDORSED).tlp(Tlp.CLEAR).dataClass(NONE)));
}
```

Destinations tighten or loosen from this. Wanting **less** than the baseline is an explicit act,
visible in review and in the startup manifest.

---

## 4. The scenario in code

### Step 1 — the mail arrives

```java
void onMessage(Message message) {
  Sensitive<String> body =
      values.hold(message.body(), c -> c
          .as("email-message")
          .attributed(a -> a
              .tenant(routing.tenantFor(message))
              .integrity(UNENDORSED)      // a customer wrote it
              .dataClass(PII))            // and put their own details in it
          .from("external-email", message.id()));

  harness.observe(agentFor(message), new DisputeRaised(message.from(), body));
}
```

`DisputeRaised` is an ordinary observation record. The story, the event stream and every later prompt
carry the handle. Nobody had to remember to keep the plaintext out — **there is no plaintext in the
object.**

### Step 2 — the planner sees a reference, and writes nothing to get it

```xml
<sensitive ref="sv_01JZV6QW2H2N9FPN2HE5T80E7P"
           type="email-message"
           integrity="unendorsed"/>
```

Produced by the store at render time. Rendering-as-reference is simply what happens when the gate
says no, and the vendor endpoint's ceiling says no. **Developer code for this: none.**

### Step 3 — the quarantined extractor reads it

```java
Extraction<Sensitive<DisputeClaim>> claim =
    nessy.extractors().create(c -> c.provider(quarantined))
        .extract(DisputeClaim.class, body);
```

Running on our own hardware, its ceiling accepts `UNENDORSED` and `PII` — never `CARDHOLDER`.
Reading the mail is its entire purpose.

The result inherits: still `UNENDORSED`, still `PII`, same tenant, with `body` as its lineage.
**Extraction launders nothing.**

### Step 4 — pull a field out

The id is inert. The function lives in the registry.

```java
public final class BillingDerivations {
  public static final DerivationId<DisputeClaim, InvoiceNumber> CLAIMED_INVOICE =
      DerivationId.of("DisputeClaim.invoiceNumber");
}
```

```java
@Bean
Derivation<DisputeClaim, InvoiceNumber> claimedInvoice() {
  return Derivations.deterministic(CLAIMED_INVOICE, DisputeClaim::invoiceNumber);
}
```

```java
Sensitive<InvoiceNumber> claimed = values.derive(claim, CLAIMED_INVOICE).orThrow();
```

Anyone may write `DerivationId.of("whatever")` and gain nothing: an unregistered name is refused, a
registered one resolves to exactly the reviewed implementation. The method reference is captured at
wiring, once, where it is reviewed.

Still `UNENDORSED`. An invoice number a customer typed is a question, not an answer.

### Step 5 — endorse it, by binding it to something the attacker doesn't control

```java
@Bean
Derivation<InvoiceNumber, Invoice> invoiceBelongsToSender(Invoices invoices) {
  return Derivations.nondeterministic(INVOICE_BELONGS_TO_SENDER, (number, ctx) ->
          invoices.find(number)
              .filter(invoice -> invoice.tenant().equals(ctx.tenant()))
              .filter(invoice -> invoice.account().email().equalsIgnoreCase(ctx.get("sender")))
              .map(Derived::of)
              .orElseGet(() -> Derived.refused("no such invoice for this sender")))
      .lowering(Integrity.class)
      .evidence(Evidence::lookupRecord);
}
```

This is the only kind of operation that may weaken a label, and it may weaken **only** `Integrity` —
it cannot quietly downgrade `dataClass` or cross a tenant as a side effect.

> **An endorsement is only as strong as what the check bound.** `invoice-exists` would confirm the
> record is real and let an attacker name any invoice. This one requires the invoice's account email
> to match the sender, so steering the selection means controlling that mailbox. Same label, utterly
> different assurance — which is why the label names the act (*someone endorsed this*) rather than
> asserting a fact, and why the manifest lists what each check claims to bind.

### Step 6 — the refund tool

```java
public record Refund(
    @JsonPropertyDescription("the invoice to refund, as a handle you were given")
        Sensitive<Invoice> invoice,
    Money amount) {}

@Override
public Awaited<ToolResult> call(ToolCallRequest<Refund> request) {
  Invoice invoice = request.dereference(request.input().invoice());
  return Awaited.of(ToolResult.of(payments.refund(invoice, request.input().amount())));
}
```

```java
@Bean
Tool<Refund> refundTool(Payments payments) {
  return RefundTool.create(payments, c -> c.accepting(a -> a.integrity(ENDORSED)));
}
```

The model emits only `{"invoice": "sv_01JZ...", "amount": "41.20"}`. Three things are checked against
the store, none taken from the wire: that the value exists, that its payload really is an `Invoice`,
and what it is labelled.

**Why the injection fails.** A hostile email says "refund invoice INV-9999". The extractor may record
it — a schema constrains shape, not honesty. But that is a claim at `UNENDORSED`, and the tool's
ceiling will not take it. The model cannot mint an endorsed handle; only
`invoice-belongs-to-sender` can, and that one checks the sender.

### Step 7 — the card token, which no model may ever see

```java
public static final DestinationId PAYMENT_PROCESSOR = DestinationId.of("stripe");
```

```java
@Bean
Destination<BillingAttribution> paymentProcessor() {
  return Destinations.named(PAYMENT_PROCESSOR,
      ctx -> a -> a.dataClass(CARDHOLDER).integrity(ENDORSED));
}
```

```java
String token = values.dereference(invoice.paymentToken(), PAYMENT_PROCESSOR).orThrow();
stripe.refund(token, amount);
```

The ceiling is declared at wiring, not at the call site — otherwise anyone could write
`Destinations.named("mine", a -> a.dataClass(CARDHOLDER))` and grant themselves permission in one
line.

`PAYMENT_PROCESSOR` is the **only** destination whose ceiling admits `CARDHOLDER`. Every model
endpoint, log sink and approval card sits below it in that lattice. The token is structurally
incapable of reaching a prompt — not by policy, by arithmetic.

### Step 8 — what the approver sees

A refund over the threshold goes to a person, who needs the card's last four to decide.

```java
@Bean
Derivation<Invoice, Last4> invoiceCardLast4() {
  return Derivations.deterministic(INVOICE_CARD_LAST4, invoice -> invoice.card().last4())
      .lowering(DataClass.class)                                  // CARDHOLDER -> PII
      .availableTo(ToolNames.of("prepare_approval"));
}
```

This **cannot** be an ordinary derivation, because ordinary derivations only join and joining cannot
weaken a label. Truncating cardholder data to four digits genuinely *is* a declassification — it is
exactly what a PCI auditor asks about — so it is privileged, it appears in the manifest, and it is
available only to the tool that builds approval cards.

The approval card itself is the one destination whose ceiling varies by **who is looking**:

```java
@Bean
Destination<BillingAttribution> approvalCard() {
  return Destinations.named(APPROVAL_CARD,
      ctx -> a -> a.dataClass("finance".equals(ctx.get("clearance")) ? PII : NONE));
}
```

---

## 5. Destinations in this system

| destination | integrity | tlp | dataClass |
|---|---|---|---|
| vendor LLM | ENDORSED | CLEAR | NONE |
| sovereign LLM | ENDORSED | AMBER | PII |
| quarantined extractor | **UNENDORSED** | CLEAR | PII |
| refund tool | ENDORSED | — | PII |
| approval card | ENDORSED | AMBER | PII *if finance*, else NONE |
| payment processor | ENDORSED | RED | **CARDHOLDER** |
| logs and spans | ENDORSED | CLEAR | NONE |

The dual-LLM split is two rows of this table, not a special case in the code.

---

## 6. How a decision is made

Every gate, one call. The framework never interprets a label; it asks the algebra.

```text
dereference(handle, destinationId, context):
  destination = registry.resolve(destinationId)          # unregistered -> refused
  value       = store.read(handle.id())                  # sole authority on labels
  refuse unless value.payloadType matches the handle's claimed type
  refuse unless algebra.permits(value.attribution, destination.ceiling(context))
  audit(value, destination, context, ALLOW)
  allow
```

```java
public interface AttributionAlgebra<A> {
  A join(A left, A right);                 // ⊔ — associative, commutative, idempotent
  A bottom();                              // identity: the unconstrained default
  default boolean permits(A value, A ceiling) {
    return join(value, ceiling).equals(ceiling);   // value ⊑ ceiling
  }
}
```

`permits` is derived from `join`, so the order and the merge can never disagree.

### Orientation is what makes it uniform

Every lattice is oriented so **up means more constrained**:

| field | bottom (⊥) | top (⊤) |
|---|---|---|
| tenant | none | irreconcilable |
| integrity | ENDORSED | UNENDORSED |
| tlp | CLEAR | RED |
| dataClass | NONE | CARDHOLDER |

Derivation is then `parents.fold(join)`, and because join is monotone, **ordinary derivation cannot
weaken any label.** Not a check the implementation performs — a thing it cannot do.

### Identity enters here, and only here

For machine destinations the ceiling is the whole story. For **human** destinations it varies, and
the audit obligation is unconditional. So identity arrives as opaque context:

```java
@Bean
AccessContextContributor security() {
  return () -> Map.of(
      "principal", SecurityContextHolder.getContext().getAuthentication().getName(),
      "clearance", currentUser().clearance().name());
}
```

Nessy never interprets the bag. An application with no identity registers no contributor and it is
empty. **There is no `Principal` type in the model** — only a seam at the one point where who is
asking can change the answer, plus the audit line that records it.

### Deriving

```text
derive(handle, derivationId, context):
  derivation = registry.resolve(derivationId)            # unregistered -> refused
  refuse unless derivation is availableTo(context)
  refuse unless algebra.permits(value.attribution, derivation.ceiling)   # it is a destination too
  result = parents.map(::attribution).fold(algebra::join)
  if privileged:
      refuse unless algebra.permits(declared, result)    # it must actually lower
      result = declared; record evidence
  store a new value with result, lineage and origin
```

**A derivation is itself a destination** — it receives plaintext in order to compute — so
authorising one needs no new mechanism. And the framework can *prove* a privileged derivation
lowers rather than raises or moves sideways, without knowing what any label means.

Invoking a derivation grants no exfiltration: the output is still labelled and still gated. **The
risk is what a privileged derivation may produce, not who invoked it** — which is a
registration-time property, reviewed by a human, and the reason the manifest exists.

### Saying no

```java
sealed interface Derived<T> {
  record Made<T>(Sensitive<T> value)              implements Derived<T> {}
  record Refused<T>(Reason reason, String detail) implements Derived<T> {}
  record NeedsApproval<T>(ApprovalRequirement r)  implements Derived<T> {}
}
```

`Reason` distinguishes *unregistered*, *type mismatch*, *label exceeds the ceiling*, and *not
available here*. The first two should be impossible at runtime because startup validated the
registry — hitting one is a bug and should be loud.

`NeedsApproval` rides the existing `Approver` / `ReplyToken` / `Replies` machinery. Declassification
requiring a human is a real enterprise pattern and needs no parallel path.

---

## 7. Every derivation stores a value

Not views. Two reasons:

**Audit immutability.** A stored value is what it was; a view is whatever today's code computes. Edit
`Invoice.card.last4` and every historical view silently changes meaning, so the access log that says
"we showed the approver sv_X" becomes a lie.

**Views don't reduce plaintext exposure** — only ciphertext at rest. A view decrypts its parent and
runs the function on every access, so the plaintext is in memory exactly as often.

**The deterministic/non-deterministic distinction survives for a better reason: replay.** Nessy
re-runs turns. A derivation that mints a fresh id each execution produces a *different* handle on
replay than the one already written into the story — the transcript references a value that no
longer matches, and the first run's row is orphaned.

```text
deterministic      ->  id = hash(parentIds, derivationId, registrationVersion)
non-deterministic  ->  id = fresh
```

Content-addressing gives replay idempotence, free deduplication, and an id that is itself evidence
of what produced it. `registrationVersion` must be in the hash: change an implementation and you get
new ids rather than silently reinterpreted old ones.

**Cost to be honest about:** every projection is a row. Content-addressing removes duplicates and
§39's `expiresAt` is the rest of the answer, but it is a real operational consideration.

**Deletion gets simpler than expected.** Lineage is recorded for every derived value, so "erase this
customer" is a graph traversal from the root rather than a cascade anyone designs.

---

## 8. Every place a handle meets a destination

| binding site | who mediates | what the developer writes |
|---|---|---|
| prompt assembly | engine | nothing |
| tool arguments | engine (same pass as json-sKema validation) | `.accepting(...)` in tool config |
| **tool results** | engine | `hold` it, if the tool returns sensitive data |
| **embeddings** | engine | nothing — an embedding endpoint is a destination |
| **episode summariser** | engine | nothing |
| **approval cards** | engine | a narrow derivation for what the approver sees |
| **human transcript** | application | how a handle renders for a person |
| your own sinks | application | a `DestinationId`, registered at wiring |

**Tool results are the main enterprise leak path** — data arrives in context because a tool fetched
it, with nobody deciding to put it there. One `hold` at the tool's edge closes it.

**Embeddings are a destination.** Sending a restricted summary to a vendor's embedding API is the
same leak as sending it to their chat API, and it hides because the output looks like anonymous
numbers. Embedding-inversion can partially reconstruct source text, so a vector inherits its
source's labels.

**If plaintext is materialised into a prompt, the reply is derived from it** and inherits its
attributions — otherwise restricted data would launder back out as assistant text. Handles rendered
as references contribute nothing, so keeping data behind references keeps the transcript clean. The
cheap path is the safe path.

---

## 9. The developer surface

| name | when you meet it |
|---|---|
| `Sensitive<T>` | everywhere a protected value travels |
| `SensitiveValues<A>` | `hold`, `derive`, `dereference` |
| `DerivationId` / `DestinationId` | inert names you pass |
| `Derivation` / `Destination` | declared once at wiring |
| `BillingAttribution` + `Algebras.product(...)` | written once, four lines |

**What the developer never writes:** copying attributions; lineage bookkeeping; "is this safe to
render / log / send" checks; a policy file; anything about encryption or key wrapping.

An application with simpler needs uses the shipped `StandardAttribution(Tlp, Integrity, Provenance)`
and defines no `A` at all.

### Shipped schemes

| scheme | status |
|---|---|
| `Tlp` | a real standard (FIRST.org, TLP 2.0) |
| `Impact` LOW/MODERATE/HIGH | a real standard (NIST FIPS 199) |
| `Sensitivity` PUBLIC…RESTRICTED | **a widespread convention, not a standard** |
| `Integrity` UNENDORSED/ENDORSED | **ours; no standard integrity ladder exists** |
| `Provenance` | shaped to W3C PROV (entity / activity / agent) |

In US government nomenclature CONFIDENTIAL sits *below* SECRET; in the corporate convention it sits
near the top. Same word, opposite rung — which is why none is mandatory.

### The laws are executable

A TCK property-tests any algebra: associativity, commutativity, idempotence, `bottom` as identity,
`permits` agreeing with `join`, and value semantics. The last two matter most — `permits` is defined
through `.equals`, so a broken one fails silently, permitting or denying everything.

---

## 10. What is formal, what is parametric, what is neither

**Formal, with zero semantic knowledge:** the lattice laws hold; derivation is monotone so labels
never weaken accidentally; the only downward moves are registered, privileged and audited; complete
mediation, because the handle holds no plaintext; lineage recorded for every derived value.

Sound rather than merely well-read because **Denning's 1976 lattice model is parametric over the
lattice** — its theorems hold for any lattice, so supplying ours is using the result as intended.

**Parametric:** what the labels are and how they combine. The meaning is not lost, it is encoded in
the algebra. "Tenants never mix" is `join(a,b) = ⊤`.

**Neither — stated non-goals:**

- **Whether a check is strong enough.** No algebra separates `invoice-exists` from
  `invoice-belongs-to-sender`. Human review, aided by the manifest.
- **Whether your lattice matches your compliance obligations.**
- **Anything after `dereference`.** Mitigation is narrow operations, so most tools never dereference:
  ```java
  Invoice invoice = request.dereference(input.invoice());          // Nessy loses sight of it
  boolean ok = values.check(input.invoice(), BELONGS_TO, sender);  // never leaves the store
  ```
- **Which value the model chose.** A hostile email cannot forge an endorsed invoice, but it can
  argue for a different, real one. That is robust declassification, it is unsolved, and it is why
  approvals and narrow tools remain separate layers.

---

## 11. Unresolved

1. **`hold` is a laundering path.** Dereference a value, then `hold` the plaintext with weaker
   labels, and you have declassified with no privileged derivation and no evidence. Technically a
   case of "after `dereference` we cannot follow it", but it *looks* like a supported API rather
   than an escape hatch, so someone will do it by accident. At minimum `hold` must record origin and
   caller so the audit distinguishes asserted from derived values, and the manifest should list
   ingestion points the way it lists label-weakening operations.
2. **`Extraction<Sensitive<DisputeClaim>>`** — nested generics at the most common call.
3. **The schema generator must present `Sensitive<Invoice>` as a ref string**, not an object graph.
   Same boundary as the wanted json-sKema validation; build them together.
4. **Existing stores are unprotected.** `nessy_episode` summaries and vectors are derived values
   under this model.
5. **Naming**, all provisional: `Sensitive<T>`, `SensitiveValues`, `.lowering(...)`, and whether
   `Derivations.deterministic/nondeterministic` are the right words for what is really *replayable*
   versus not.
