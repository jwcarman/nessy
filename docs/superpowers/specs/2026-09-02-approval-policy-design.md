# Externalized approval policy

**Status:** designed 2026-09-02, not built. Generalizes the OPA adapter shipped as
`nessy-examples/policy` (de86cb4c) into a framework, and names the trust model for
judging agents. Amends `2026-08-16-authorization-design.md`: an approver may now be
chosen by a policy rather than wired per tool.

## 1. What is wrong

A gate is currently a Java lambda wired to a tool at startup:

```java
config.tool(new PruneTool(), binding -> binding.approver(desk));
```

Three things follow from that, and all three are the same problem — **the rule ships
when the application ships**:

- Changing who must approve what is a release. The people who own the risk cannot
  read the rule, let alone change it.
- The rule cannot see the call. "Restart a host" is wired per tool; "restart
  `prod-eu-1`" is a property of the arguments, and there is nowhere to say it.
- Every gated tool needs its own wiring, so the policy is scattered across the
  wiring code rather than written down in one place.

The OPA example proved the seam works. This spec makes it a framework, and adds the
thing the example cannot express: **a policy that routes the decision to somebody
else**.

## 2. The shape

**A policy decides now. An approver may take three days. `Delegate` is the bridge.**

That sentence settles the whole layering. Deciding is fast, synchronous, and pure
enough to run on any thread. Approving may park a call for a person for three days
and survive a restart. They are different jobs, and conflating them is what makes
policy frameworks either too slow or too weak.

```
Approver  (exists)         may defer; Awaited<ApprovalResult>
   ^
   └── PolicyApprover      asks a PolicyEngine, executes the Verdict
                              │
                              ├── Approve         → ready(approved)
                              ├── Deny(reason)    → ready(denied)
                              └── Delegate(name)  → look up an Approver, ask IT
```

```java
@FunctionalInterface
public interface PolicyEngine {
  Verdict decide(ApprovalRequest request);
}

public sealed interface Verdict {
  record Approve()           implements Verdict {}
  record Deny(String reason) implements Verdict {}
  record Delegate(String to) implements Verdict {}
}

public final class PolicyApprover implements Approver {
  PolicyApprover(PolicyEngine engine, Map<String, Approver> delegates, int maxDepth);
}
```

`PolicyApprover` holds no HTTP and no JSON. A `PolicyEngine` may be a remote OPA, a
remote Cedar, or a plain Java function — **"externalized" is a deployment choice, not
a requirement of the design.**

### 2.1 Why three verdicts and not four

An earlier draft had `Ask(term)` — park for a human. It is redundant, and finding out
why sharpened the design. The watchman already has this:

```java
@Bean Approver humanApprover(PendingApprovalsListener listener, Clock clock) {
  return request -> {
    listener.expecting(request.callId(), request.replyToken());
    return Awaited.deferred(clock.instant().plus(APPROVAL_TERM));
  };
}
```

That **is** an approver. "Ask a person" was never a distinct kind of answer; it is
delegation to an approver that happens to be a desk. Keeping `Ask` would have made the
human case special and blocked every other kind of approver from being named by a
policy. Three arms, and a desk registered under `"humans"`.

The consequence to state plainly: **a policy cannot park a call by itself.** It can
only name something that can. That is the correct authority boundary — parking mints a
capability, and a policy engine is not trusted with one (§4.2).

### 2.2 Extra fields ride on `facts`

A policy that says `{"effect":"delegate","to":"humans","term":"PT72H"}` wants to tell
the desk how long. `Approver.approve(request)` takes no term, and widening it for this
would change an interface every tool author sees.

`ApprovalRequest.facts` already exists for exactly this — *"whatever approvers have
added so far; empty when the framework first asks"* — and `RiskAssessor` already reads
it. So `PolicyApprover` deposits whatever the policy attached into `facts` before
calling the delegate, and a delegate reads what it understands. No new channel, no
signature change.

## 3. Delegation, and what makes it safe

`Delegate` is the extension point: a new kind of approval process is a registration
plus a line of policy. That is the property James asked for — *"if you have an agent
based approval process, you should be able to have your policy say that is going to be
necessary."*

It is also where authority leaks if it is built carelessly. Three rules:

**An allowlist, not a lookup.** Delegates resolve against the explicit
`Map<String, Approver>` given at construction. A policy that could name *any* approver
in the process could name one that always says yes. An unresolvable name is a denial
**and** a logged error, never a fallthrough.

**A depth bound.** A delegates to B, B delegates to A. `maxDepth` (default 3) caps the
chain; exceeding it denies and logs.

**Total interpretation.** Anything unrecognised — an unknown effect, an unparseable
body, a name not in the allowlist — maps to `Deny`. There is no default that allows.

### 3.1 Composition

A composite is an ordinary `Approver`, so it needs no new type:

```java
Map.of("change-board", Approvers.all(security, cost, sre))
```

`all`, `any`, `quorum(n)`. Immediate answers fold: for `all`, any `Deny`
short-circuits — **evaluate cheap approvers before expensive ones, so a denial never
costs a human's attention or a model call**.

**Deferral is where composition stops being free.** `replyToken()` is memoized, so two
approvers that both defer hand the *same* token to two different answerers. The first
click settles the call outright and the second answer lands on a settled call — "first
past the post" wearing unanimity's clothes, with an audit trail claiming two people
reviewed it.

| deferrals | `all` does |
|---|---|
| 0 | fold immediately |
| 1, rest allow | defer — sound, the outcome genuinely hinges on that one answer |
| 2 or more | **deny, loudly**, naming dual control as unsupported |

`any` has a further wart to document rather than fix: if a later approver allows
immediately, the call settles while a human still holds an open question, and **there
is no way to retract a parked question**. The desk degrades correctly (the watchman's
controller already redirects rather than throwing when a row is not waiting), so it is
litter, not a bug.

### 3.2 Dual control is out of scope, deliberately

The two-person rule — "security *and* an SRE must both sign off" — is not expressible,
and it is the control regulated environments actually require for destructive
production changes. It needs a **collector**: an approver that never hands out the
engine's token, runs its own desk with its own handles, records partial answers in its
own table, survives restart, expires them, and calls `Replies.approve(realToken, …)`
only once its quorum is met.

It cannot reuse `PendingApprovalsListener`, because that desk answers through the
engine's `Replies` and the engine rejects a handle it did not mint ("not a reply token
issued by this engine"). That is a component with durable state, not a combinator, and
it gets its own spec. **Until then the loud refusal in §3.1 is the whole feature** —
silently honouring the first deferral is the failure that looks fine for a year and
then matters exactly once.

## 4. OPA, as one implementation

### 4.1 What OPA actually returns

Measured against `openpolicyagent/opa:0.68.0`, not assumed:

| Case | HTTP | Body |
|---|---|---|
| rule with a `default`, true/false | 200 | `{"result":true}` / `{"result":false}` |
| package query | 200 | `{"result":{…}}` — **undefined rules are ABSENT, not null** |
| rule undefined (no `default`) | 200 | `{}` |
| **mistyped decision path** | **200** | **`{}`** |
| package never loaded | 200 | `{}` |
| no `input` key sent | 200 | `{…,"warning":{"code":"api_usage_warning"}}` |
| malformed JSON body | 400 | `{"code":"invalid_parameter","message":…}` |
| builtin type error | 200 | rule silently undefined |

**A typo'd path, an undefined rule and an unloaded policy are byte-identical.** Treat
"no result" as a denial and a misconfiguration denies everything, forever, with
nothing in any log. Fail-closed and invisible.

**The decision rule must therefore carry a `default`.** Then it is always defined, and
the presence of `result` becomes a health check: absent means the policy is not
answering, which is reported as misconfiguration rather than served as a denial.

### 4.2 The two seams inside the OPA engine

```java
new OpaPolicyEngine(baseUrl, "nessy/tools/decision", mapper,
                    InputRenderer.standard(mapper),
                    DecisionInterpreter.effectStyle());
```

**`InputRenderer`** builds the input document — OPA's own word, unambiguous behind the
adapter boundary, though elsewhere in Nessy `input` means a tool's bound arguments.
Named for what it does, matching `ActionRenderer`.

> **The reply token is never rendered.** It is a capability: whoever holds one settles
> the call. A policy engine logs its input and is frequently somebody else's service.
> The document is built field by field rather than by serializing the record, so a
> field added to `ApprovalRequest` later cannot arrive in somebody's policy engine
> without a decision here. A test exists whose only job is to keep it absent.

**`DecisionInterpreter`** maps the response to a `Verdict`. It exists because **none of
this is standardized**: OPA passes any JSON through untouched, and `effect`/`reason`/
`term` is a Nessy convention, verified by renaming the rule and inventing keys. Shapes
*are* dictated elsewhere — OPA-Envoy expects `{"allowed":…,"http_status":…}`,
Gatekeeper matches k8s `AdmissionReview` — and swapping interpreters is how those, or
AuthZEN, are supported without touching transport.

**AuthZEN checked** (OpenID Foundation, Authorization API 1.0, read 2026-09-02).
Its request maps onto ours cleanly:

```json
{"subject":  {"type":"agent","id":"house-12","properties":{"agentType":"watchman"}},
 "resource": {"type":"tool","id":"prune_images"},
 "action":   {"name":"call","properties":{"arguments": …}},
 "context":  {"facts": …}}
```

**Its response cannot carry our third verdict.** It is
`{"decision": <boolean>, "context": {"reason_admin": …, "reason_user": …}}` — allow
or deny, and nothing else. One could smuggle `{"decision": false, "context":
{"delegate_to": "humans"}}`, but a delegation is not a denial, and inventing
`delegate_to` is a private convention wearing a standard's clothes.

**So: support AuthZEN as one interpreter, do not adopt it as the shape.** Ship an
AuthZEN `InputRenderer` for shops already running such endpoints — that half IS
portable — and an interpreter mapping `decision` to `Approve`/`Deny(context.reason_user)`.
A policy that needs delegation needs a richer response than AuthZEN defines, which is
the argument for the interpreter seam rather than against our own convention.

One modelling note for that renderer: AuthZEN's `resource` is the thing being
protected, and generically that is the tool — but a policy usually cares about the
tool's TARGET (`prod-eu-1`), and which argument that is cannot be known here. Default
to `resource = tool`, and let an application that knows better supply its own
renderer. That is the seam earning its keep.

### 4.3 The policy this ships with

```rego
package nessy.tools
import rego.v1

default decision := {"effect": "deny", "reason": "no rule allowed this"}

decision := {"effect": "allow"} if {
	input.toolName in {"disk_usage", "containers"}
	not production
}

decision := {
	"effect": "delegate",
	"to": "humans",
	"term": "PT72H",
	"reason": sprintf("%s targets production", [input.toolName]),
} if production

production if startswith(object.get(input, ["arguments", "target"], ""), "prod-")
```

Three things it demonstrates, each corresponding to something the request had to
carry: a decision on `toolName` alone; a decision on `agentType`, which is why an id
is not enough; and a decision on `arguments`, which is why they travel.

### 4.4 Not every engine can route

Checked 2026-09-02, and it constrains where `Delegate` can come from:

| Engine | Response | Verdicts it can express |
|---|---|---|
| **OPA** | any JSON a rule produces | all three |
| **AuthZEN** | `{"decision": <boolean>, "context": {…}}` | Approve, Deny |
| **Cedar** | `Allow`/`Deny` + diagnostics (determining policies, errors) | Approve, Deny |

**Only an engine that returns arbitrary structure can name a delegate.** Cedar's
decision is strictly two-valued; the richest thing it offers is *which policy*
determined the outcome. Routing could be smuggled through policy IDs — a `forbid`
named `must-ask-humans` read as `Delegate("humans")` — but that is a private
convention wearing a standard's clothes, which is the same objection that ruled out
faking it in AuthZEN's `context`.

**This is not a flaw in the three-verdict model.** A two-valued engine is still a
perfectly good `PolicyEngine`; it simply cannot route, and an application using one
wires the routing itself — policy first, desk second, composed. Delegation is an
option a capable engine unlocks, not a requirement the framework imposes.

The practical consequence for choosing a second implementation: **a two-valued
engine exercises the least of what this design does.** Cedar is a reasonable thing
to ship the day somebody is already running it, and a poor choice for proving the
seam, which was the reason to reach for it.

## 5. Agent-based approval

A **separate** agent reviews the call. Never the originating agent approving its own
work.

It falls out of §3 as a `Delegate` target: an `AgentApprover` is an `Approver`, never a
`PolicyEngine`, precisely because it must defer. Mechanically it reuses the durable
path whole — `approve()` observes the judging agent and returns `Awaited.deferred(term)`;
the judge's decide-tool settles via `Replies.approve(token, verdict)`. **A person and a
judging agent become interchangeable answerers**, which is the property worth having.

### 5.1 The trust model

The requesting agent's output is untrusted input to the judge. This is a trust
boundary, and it is the reason this section exists rather than being a commit message.

- **The judge is told data, never instructions.** Its prompt says so, and content
  originating from the requester is labelled as such.
- **The judge sees the framework-rendered request**, the same document `InputRenderer`
  builds — not the requester's prose. Provenance is ours.
- **The verdict is structurally constrained** — a tool call with an enum, not free
  text. Injected `"APPROVED"` in an argument string cannot become a verdict.
- **The reply token never enters any model's context.** The judge's settle-tool holds
  it, keyed by call. A capability in a prompt is a capability an injection can
  exfiltrate, and it would let a hostile requester approve arbitrary calls.
- **A judge must never hold the tool it judges.** Otherwise the requester induces the
  judge to "check" the call, the action happens, approval is bypassed, and the audit
  trail says a review occurred. Enforced at construction: an `AgentApprover` whose
  harness grants a tool it may be asked to judge refuses to be built.
- **A judge is memoryless across decisions — a fresh agent id per call.** A long-lived
  judge accumulates every request it has reviewed, so a hostile requester's text sits
  in its context while it judges somebody else's call. That is a persistent injection
  surface built out of a feature. A judge that learns is a deliberate decision with its
  own threat model, not a default.
- **The requester chooses nothing** — not the judge, not the policy, not the term — and
  cannot address the judge directly.
- **Fail closed.** Judge errors, timeouts and unparseable verdicts deny.

Different judges may hold different tools, prompts and models — a CVE lookup for
dependency review, a billing API for spend, metrics for production changes — and the
policy routes by what the call is. Each is its own `AgentType`, so isolation is the
existing sharding boundary rather than something new.

## 6. Failure modes

Every row denies. That is the point: **a control that did not answer is not a control
that said yes.**

| What happened | Result | Logged |
|---|---|---|
| engine unreachable, timeout, non-200 | deny | error |
| `result` absent (typo'd path, policy not loaded) | deny | error — misconfiguration, not a decision |
| unknown `effect` | deny | error |
| delegate name not in the allowlist | deny | error |
| depth bound exceeded | deny | error |
| two or more delegates defer | deny | error, naming dual control |
| unparseable `term` | delegate anyway, using the approver's own term | warn |
| policy said deny | deny | — |

The distinction the log level carries: **`error` means the gate is broken; `deny` alone
means the gate worked.** Silently denying a misconfiguration is how a policy that was
never consulted looks healthy for a year.

## 7. Modules

```
nessy-approval/
  policy/       -> nessy-approval-policy      Verdict, PolicyEngine, PolicyApprover, Approvers
  policy-opa/   -> nessy-approval-policy-opa  OpaPolicyEngine, InputRenderer, DecisionInterpreter
  agent/        -> nessy-approval-agent       AgentApprover                        (§5, later)
```

`Verdict` lives in `nessy-approval-policy`, **not** `nessy-api`. It is meaningful only
to this framework, and `nessy-api` is what every tool author compiles against; the
framework owning its own vocabulary lets it evolve without touching that surface.

The family directory is `nessy-approval/` rather than `nessy-approval-policy/` so the
agent judge has somewhere to sit — it is an approver, not a policy — and so no parent
directory shares a name with its own core module.

## 8. Testing

- **Against the real binary.** Rego cannot be reasoned about from Java: a rule either
  sees a field or it does not. Every row of §4.1 is a test against a real OPA
  container, tagged `container` and skipped by default so `clean verify` passes with
  no Docker.
- **The token-absence test stays**, and gains a sibling for the judge's prompt.
- **Failure modes are tests, not comments** — §6 is a table because each row is a case.
- **Delegation**: allowlist rejection, depth bound, and the two-deferral refusal each
  get a test; the last asserts the *reason text*, since that message is the entire
  user-facing feature.
- **Written to fail first.** Bugs found this way in the work leading here — a denial's
  reason bound to nothing, an alarm outliving its call — were each proved by reverting
  the fix and watching the test fail.

## 9. Out of scope

- **Dual control** (§3.2) — needs the collector; its own spec.
- **Retracting a parked question** — would let `any` withdraw outstanding questions;
  currently litter the desk tolerates.
- **Guardrails at the other three boundaries** (pre-model, post-model, post-tool). The
  ROADMAP wants policy-as-data at all four; this is the pre-tool one. Whether they
  share `PolicyEngine` is a question for the second one, not this one.
- **Bundles / hot reload.** OPA polls for its own bundles; nothing here needs to know.
