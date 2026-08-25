# The Action Wave — authorization speaks the industry's language, tools shed their ceremony

> **Repeal notice — 2026-08-22.** The nessy-owned-binding machinery this spec
> introduced for sealed vocabularies (`SealedInputs`, the hand-rolled oneOf
> discriminator in `Schemas`) is repealed: every serialized sealed hierarchy —
> user vocabularies included — binds through its own standard Jackson
> `@JsonTypeInfo`/`@JsonSubTypes` annotations, and `Schemas` derives its
> discriminator schemas from those annotations. The two-tier intent design and
> the sealed-vocabulary concept itself are unchanged; only the bespoke binding
> layer dies. See the substrate spec §7 for the ruling.

**Date:** 2026-08-20
**Status:** RATIFIED in conversation (owner). Binding amendments to
`2026-08-16-authorization-design.md` (§0 anchor, §2, §5, §7) and to the core tool vocabulary.
Subordinate to `2026-08-18-agent-as-scope-design.md` and `2026-08-20-durable-computation.md`.

## 0. Why

Two forces. First, terminology: in every mainstream authorization model the thing being attempted
is the **action** (XACML's request is subject/action/resource/environment; AWS IAM's `Action:`;
Cedar's `permit(principal, action, resource)`) — and in those same systems **"effect" means the
verdict** (`Effect: Allow | Deny`). Nessy's "the tool's effect flows to the policy" reads
backwards to anyone from that world. Second, trust: the effect statement was baked into the
`Tool`, so governing a third-party tool (an MCP toolbox tool) meant wrapping it in a class — the
"trusted, developer-authored statement" was authored by whoever wrote the remote server.

## 1. Effect becomes action, and the speaker moves to the grant

The 2026-08-16 anchor sentence — "the authorization of a tool call begins with the tool's own
statement of its effect" — is amended: **authorization begins with the grant's statement of the
action.** A grant declares four parts, welded at construction: the tool, the **action
contributor**, the enrichers, and the policy.

- `ActionContributor<I, A>` — `A actionOf(I input)` — produces the action from the bound input.
  It is NOT an enricher (an enricher consumes the action and deposits assessments; the
  contributor produces it). It carries `displayName()`/`named(...)` exactly as `Enricher` does,
  for the report.
- The judgment closure runs: bind input by class token → contributor renders the action →
  deposit it under the well-known `AuthzContext.ACTION_KEY` (§4.2 of agent-as-scope: new facts
  are keys) → enrichers in order (each still receives the action as its typed parameter) →
  policy judges. The action is rendered exactly once per evaluated call; the fail-closed stage
  message renames from "effect stage: " to "action stage: ".
- Rung 0/1 grants get the default contributor `input -> String.valueOf(input)` — the approver
  is still guaranteed to see something. Rung 2/3 welds `<I, A>` inside the typed factory:
  `grant(tool, contributor, enrichers, policy)`, plus a no-enricher door
  `grant(tool, contributor, policy)`.
- **`EffectfulTool` is deleted and `Tool.effect(T)` is deleted.** A tool is name, description,
  input record, execution, and completion requirement. Authorization never appears in the tool
  API again. Third-party tools become governable without wrapping: the application states what
  the call means, per grant.
- Renames ripple: `ToolGrant.Judged.effect()` → `action()`; `ApprovalRequest.effect()` →
  `action()`; `Enricher<E>`/`UsagePolicy<E>` re-document their parameter as the action type
  `A`. `PolicyDecision` keeps its name — it is the verdict, and it is already unambiguous.
- **The report survives by declaration, not reflection.** `AuthorizationReport`'s
  `EffectfulTool`-interface reflection is deleted; a `GrantStory` reports the contributor's
  `displayName()` (or that the grant uses the default `String.valueOf` contributor). Type
  reflection on an erased lambda is not attempted.

Named non-goal: nessy does not adopt the full principal/action/resource request triple. `A` is
whatever record the org wants; a structured action may embed its resource.

## 2. The standard risk shape

A well-known slot plus one good default shape, so standard policies become shippable —
opinions, not mandates (an org with its own model deposits its own type under its own key).

- Three axes, three types (amended 2026-08-21 — the matrix is asymmetric, so a swapped
  likelihood/impact is a real severity bug the compiler should catch): `Likelihood`, `Impact`,
  and `RiskLevel`, each `VERY_LOW, LOW, MODERATE, HIGH, VERY_HIGH` — NIST SP 800-30's own five
  qualitative values on all three axes (Tables G-2/H-2/I-2), declaration order is severity
  order. Friendlier glosses ("very unlikely", "negligible impact") live in javadoc, never in
  identifiers.
- `record RiskAssessment(Likelihood likelihood, Impact impact, RiskLevel risk,
  Set<RiskFactor> factors)` — the assessor's full statement, `risk` stored (amended
  2026-08-21). The canonical constructor is the explicit-override door: an org's assessor may
  conclude a level the standard combination would not (deliberate elevation is legitimate).
  `RiskAssessment.of(likelihood, impact, factors...)` is the shipped opinion: it derives `risk`
  from the NIST-style combination matrix (the matrix is in the implementation plan and is part
  of this ruling).
- `record RiskFactor(String name)` — a typed, open value (value equality, deliberately unlike
  `Key`: two modules saying "destructive" mean the same factor). `RiskFactors` holds the typed
  constants seeding the vocabulary from MCP tool annotations: `destructive`, `irreversible`,
  `external-world`, `read-only`, plus `spends-money` and `touches-pii` as nessy's own
  additions. The vocabulary stays open — org factors are new `RiskFactor`s, never a sealed
  grammar.
- `AuthzContext.RISK_KEY` (typed `RiskAssessment`) with a `risk()` convenience accessor,
  beside `PRINCIPAL_KEY`/`DECLARED_INTENT_KEY`.
- **The threshold policy** ships as the canonical consumer:
  `RiskPolicies.threshold(RiskLevel approveAt, RiskLevel denyAt)` — severity below `approveAt`
  allows, below `denyAt` requires approval, at-or-above denies. An absent assessment fails
  closed (Deny naming the empty slot); the policy reads the stored `risk()`. This is the one-line policy every deployment actually
  wants, composable with any org's assessor enricher.

## 3. Intent reborn

Only `DECLARED_INTENT_KEY` survived the distillation; the bolt-on returns, rebuilt against the
new machine (kit in `nessy-agent`, SPI in `nessy-spi`, `Intent` in `nessy-api`):

- `record Intent(String declaration)` — the model's untrusted claim, free text.
- `IntentStore` SPI (pre-scoped, like `Memory`); `InMemoryIntentStore` reference implementation.
- `IntentTool` — the tool the model calls to declare intent before acting (IMMEDIATE, always
  allowed by design — it is the claim channel, not an effectful act).
- `IntentEnricher` — deposits the latest declaration under `DECLARED_INTENT_KEY`. The claim is
  untrusted by definition (2026-08-16 §7); policies weigh it accordingly.

**Amendment (2026-08-21, ratified in conversation — intent speaks two tiers, and the typed tier
constrains the model):**

- **Two tiers, both industry-anchored.** The unstructured tier is the **purpose string** (the
  word is Apple's: the free-text justification accompanying a privileged request) — today's
  `Intent(String declaration)`, zero ceremony, always available, honest about being
  string-inspectable at best. The structured tier is the **intent vocabulary**, the NLU
  tradition's meaning of "intent" (Dialogflow/Rasa/LUIS: a closed set with typed slots; outside
  the set is unrepresentable): the organization declares a sealed interface of intent records,
  and the model must declare within it. Typing sharpens the claim's *structure*, never its
  *trustworthiness* — the §7 trust table stands.
- **One generic kit carries both.** `IntentStore<T>` / `InMemoryIntentStore<T>` /
  `IntentTool<T>`; the freeform tier is the pre-built `T = Intent` instance. `IntentEnricher`
  itself stays unparameterized over an `IntentStore<?>` (amended at execution, 2026-08-21): it
  deposits whatever `latest()` yields under the untyped `DECLARED_INTENT_KEY`, so a type
  parameter would bind nothing — a phantom generic. Policies recover the type with
  `declaredIntent(Class)`. The store's write verb renames `record(T)` → `declare(T)` (the old name collided
  with the `record` keyword — S6213 — and "declare" is the domain word anyway).
- **The schema is the constraint.** `Schemas` learns sealed interfaces: the wire schema is a
  `oneOf` over the permitted records, each carrying a required const discriminator property
  `"type"` whose value is the record's simple name, as written. **Nessy performs the
  discriminator binding itself** — read `"type"`, match against `getPermittedSubclasses()`,
  bind the remainder into that record — so vocabularies carry no Jackson annotations and no
  Jackson-version roulette. A missing or unknown `"type"`, or a malformed body, fails binding
  in-band: the model reads the error and corrects. The constraint compounds across three
  layers: the schema bounds what can be said, the binding rejects what slipped through, the
  policy judges what bound. Sealed-input support is general — any tool may take a sealed
  interface as its input type.
- **Enforcement is a policy, never an enricher** (ruled 2026-08-21: enrichers gather, policies
  judge — absence of a declaration is a successfully gathered fact, and a policy denial teaches
  where an enricher throw malfunctions). `IntentPolicies.requireDeclared(Class<?> vocabulary)`:
  no declaration of that type on the context → `Deny` with a message telling the model to use
  the declare tool first — the in-band denial doubles as the teaching loop.
- **Policies gain the composition the enricher chain always had:**
  `UsagePolicy.allOf(List<UsagePolicy<Object>>)` — evaluate in order; the first `Deny` wins;
  else any `RequireApproval` wins; else `Allow`. Deny-biased, boringly predictable, never
  `Static`, and rejects an empty list. List-only by ruling (2026-08-21, at execution): a
  generic-varargs overload carries an unadjudicatable heap-pollution warning, and both
  silencers are against house law. This closes the gap that made judgment-in-enrichers tempting.
- **Consistency checking is the payoff.** A typed declaration lets an org policy pattern-match
  the declared intent against the rendered action (declared `Restart("prod-eu")` vs an action
  touching `prod-us` → deny naming the mismatch) — the prompt-injection tripwire upgraded from
  string-grep to typed field comparison, with sealed exhaustiveness breaking every unconsidered
  vocabulary member at compile time.

## 4. The principal kit

The slot existed; the kit did not. `Enrichers.principal(Supplier<?> resolver)` — a named
enricher depositing the resolved principal under `PRINCIPAL_KEY`. Nessy still never imposes an
identity shape; authorization here is never authentication — the resolver hands over an
already-authenticated identity.

## 5. `Tool.of` — the config factory

First-party tools stop requiring a class. Per the dsl-coherence law (no public `build`/`builder`
anywhere — `NoPublicBuildersTest`), the factory takes a named customizer and returns the
finished thing, mirroring the `TurnObserver`/`TurnObserverConfig`/`TurnObserverCustomizer`
precedent exactly:

```java
var tool = Tool.of(CreateAccount.class, t -> t
    .name("create-account")                       // defaults to kebab-case of the record name
    .description("Create a new bank account.")    // mandatory — it is for the model
    .executes(cmd -> bankSvc.createAccount(cmd.name(), cmd.type())));
```

- Handler doors, exactly one required: `executes(Function<T, ?>)`,
  `executes(BiFunction<T, ToolContext, ?>)`, and `defers(BiConsumer<T, ToolContext>)` — the
  deferring door returns `Awaited.deferred()` and sets `requiredCompletion() = DURABLE`
  automatically, so a deferring tool cannot forget to declare itself.
- Return rendering: a `String` passes through as `ToolResult.ok`; a `ToolResult` passes as-is;
  `null` renders as `ToolResult.ok("done")` (ratified 2026-08-21 at the final review — a
  side-effecting handler with nothing to say gets an honest acknowledgment, not the string
  "null"); anything else JSON-serializes. A thrown exception is the existing in-band failure.
- `requires(CompletionPolicy)` overrides the completion requirement.
- The verb is `executes`, never `action` — the tool *executes*, the grant states the *action*,
  the policy renders a *decision*. Three words, three concepts.
- The `Tool` interface underneath is unchanged; the class-based route remains for complex tools.

## 6. The sealed tool-event channel

`EventEmitter.emit(Object)` and the `ToolProgress` wire record are deleted. The channel had one
real shape and a `String.valueOf` fallback — an untyped funnel in a sealed-grammar codebase.

- Speakers keep specific methods: `ToolContext.progress(String)` stays; future events each get
  their own method.
- Listeners get the fixed vocabulary: `sealed interface ToolEvent` (sole member today:
  `record Progress(String message)`) and `ToolEventListener { void on(ToolEvent event); }` as
  `ToolContext`'s second component. The executor narrates by exhaustive switch; the stringly
  fallback dies at compile time.
- The channel is sealed, deliberately: everything emitted must be interpreted by the harness.
  Open-ended facts for authorization already have their open bag (`AuthzContext` keys); a tool
  that wants to log, logs.
- `ToolProgress.toolCallId` dissolves — it existed only because the channel was untyped; the
  executor already holds the call.

## 7. Sequencing

One plan, six tasks in dependency order: (1) the sealed event channel (ToolContext settles
first), (2) the action grammar in core, (3) the agent-side action fallout (ApprovalRequest,
chokepoint, demos move their effect overrides to grant contributors), (4) risk + principal kit,
(5) `Tool.of`, (6) intent rebirth + the governed-turn flagship (intent declared, risk assessed,
threshold policy requires approval, desk approves, tool runs — the whole gate in one narrated
demo through the autonomous host).

## 8. The context is the pipeline (ratified 2026-08-21)

The action wave left a contradiction standing: the action is deposited under `ACTION_KEY`
(facts are keys — agent-as-scope §4.2) AND threaded as a typed parameter through
`Enricher<A>`/`UsagePolicy<A>` — two paths to one fact, the §10.8 disease, and the sole reason
the grant grammar was riddled with wildcards and needed a stored `Judgment` closure to survive
erasure. The evidence that the parameter was vestigial: every shipped policy
(`ThresholdPolicy`, `RequireDeclaredPolicy`, the canonical statics) ignores it and reads keys.

Ruled:

- **The pipeline is monomorphic.** `Enricher { AuthzContext enrich(AuthzContext context); }`
  and `UsagePolicy { PolicyDecision evaluate(AuthzContext context); }` — no type parameters,
  no wildcards, anywhere. An `AuthzContext` goes through; the policy reads it.
- **The typed read is one general primitive** (ratified 2026-08-21):
  `<S extends T> Optional<S> get(Key<T> key, Class<S> type)` on `AuthzContext` — the deposit,
  narrowed by class token; a non-instance or an absence are both `empty` (fail-closed on the
  reader's own terms). The `action(Class)`/`principal(Class)`/`declaredIntent(Class)`
  conveniences are sugar over it.
- **The action travels only as the key.** The contributor renders it (still typed `<I, A>` at
  the production site, welded to the tool's input inside the grant factory — the one place
  generics remain live), it enters the bag under `ACTION_KEY` before enrichers run, and
  consumers recover it with `context.action(Class)`.
- **The compile-time weld is traded away, eyes open**: an action-aware enricher or policy that
  finds the slot empty or mistyped fails closed on its own terms (deny naming the absent/wrong
  fact) — a runtime `Optional` miss instead of a compile error. The grant author writes both
  sides; the loss is small and the wildcard tax it paid for was not.
- **`ToolGrant` becomes a final class with a private constructor** — the `grant(...)` factories
  are now the enforced-single door ("exactly one way to write it" restored to literal truth),
  the four parts stay as accessors for the report, the render function is a private captured
  field, and the pipeline is two methods speaking only existing vocabulary (amended 2026-08-21,
  owner: "Judged is cute, not helpful"): `AuthzContext assemble(AuthzContext base, Object
  input)` — bind, render the action, deposit `ACTION_KEY`, enrich — and `PolicyDecision
  decide(AuthzContext assembled)`. No result record: the enriched context IS the carrier.
  `Judgment`, the `judgment` component, and `Judged` are all deleted. Consequently
  `ApprovalRequest` sheds its `action` component — `(address, call, context)` — because the
  action lives in the context it was carrying alongside (two paths to one fact, closed).
- **The ladder law simplifies**: "typed" stops being a rung of its own — a typed policy is one
  that reads a typed key. Stage-named fail-closed, render-once, and the Static rung-0 fast
  path are unchanged.

## Amendment (2026-08-25): the decision vocabulary collapses to `Approval`

`PolicyDecision {Allow, Deny, RequireApproval}` and `AuthzContext`'s `decide` step retire.
`2026-08-25-approval-lifecycle-design.md` §1 replaces the three-way verdict with one type,
`Approval {Approved(reference), Denied(reason, reference)}`, answered by an `Approver` a grant
carries directly instead of a `UsagePolicy`; a policy that used to answer `RequireApproval` now
calls `context.defer()` and the harness records the wait in the scope's own phase rather than a
`Judged`-adjacent side channel. `RiskPolicies`/`IntentPolicies` become `RiskRules`/`IntentRules`,
rules for the ladder `Approvers.rules(...)` runs; `AuthzContext`'s typed-fact mechanism survives
as `Facts`, and its role — the enriched question this section built — is `ApprovalRequest`, now a
JSON document by contract rather than a live object graph.

The render-once, stage-named-fail-closed, and Static rung-0 fast-path rulings above all carry
forward unchanged — only the verdict's shape and the parked branch's mechanics move. See
`2026-08-25-approval-lifecycle-design.md` §1, §4.
