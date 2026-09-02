# Nessy Example: a policy engine decides

Wiring, and nothing else. The pieces live in the library now:

- `nessy-approval-policy` — `Verdict`, `PolicyEngine`, `PolicyApprover`
- `nessy-approval-policy-opa` — `OpaPolicyEngine`, `InputRenderer`, `DecisionInterpreter`

This example used to carry its own copy of the OPA adapter. It doesn't any
more, which is the point: an example that reimplements the library isn't
showing you how to use the library.

## The whole thing

```java
var opa = OpaPolicyEngine.create(policy -> policy
    .url("http://localhost:8181")
    .decisionPath("nessy/tools/decision"));

Approver gate = PolicyApprover.create(config -> config
    .engine(opa)
    .delegate("humans", desk));
```

Two decisions belong to the application and nothing else: **which policy**, and
**who the policy is allowed to name**. `delegate` is an allowlist — given a
registry of every approver in the process, a policy file could name one that
always says yes, and the gate would be one text edit from being no gate at all.

## Three verdicts, and why not four

```rego
default decision := {"effect": "deny", "reason": "no rule allowed this"}

decision := {"effect": "allow"} if {
    input.toolName in {"disk_usage", "containers"}
    not production
}

decision := {
    "effect": "delegate",
    "to":     "humans",
    "term":   "PT72H",
    "reason": sprintf("%s targets production", [input.toolName]),
} if production

production if startswith(object.get(input, ["arguments", "target"], ""), "prod-")
```

`allow`, `deny`, `delegate`. There's no `ask`, because a desk that parks a call
and waits **is** an approver — so asking a person was never a kind of answer,
only delegation to a particular one. That's what lets a policy name a review
agent or a change board tomorrow without any Java changing.

Note `term`. "Production waits three days" is a sentence in the policy rather
than a constant in Java, and it reaches the desk as the fact `policy.term`.
Everything else the policy attaches rides along the same way.

## Your decision rule needs a default

Not style — it's the only way to tell a working gate from a broken one.
Measured against the real binary:

| | HTTP | body |
|---|---|---|
| `/v1/data/nessy/tools/decision` | 200 | `{"result":{...}}` |
| `/v1/data/nessy/tools/decisionn` *(typo)* | **200** | **`{}`** |
| rule undefined | 200 | `{}` |
| policy never loaded | 200 | `{}` |

OPA answers 200 to nearly everything, and a mistyped path is byte-identical to
a rule that legitimately didn't fire. Read `{}` as "no" and a misconfiguration
denies **everything, forever**, with nothing in any log. With a `default` the
rule is always defined, so the presence of `result` becomes a health check —
and its absence is reported as a broken gate instead of served as a denial.

## The reply token is never sent

`ApprovalRequest.replyToken()` is a capability: whoever holds it settles the
call. A policy engine logs its input and is frequently somebody else's service,
so `InputRenderer` builds the document field by field rather than serializing
the record. There are tests whose only job is to keep it absent — one for the
standard shape, one for AuthZEN's.

## A broken control is not permission

An engine that's down, slow, or misconfigured **denies**, and logs an `error`
rather than a plain denial. The distinction is what you need at 3am: `error`
means the gate is broken; a denial alone means the gate worked.

## AuthZEN

`InputRenderer.authzen()` and `DecisionInterpreter.authzen()` speak the OpenID
Foundation's Authorization API 1.0, for a shop already running such endpoints.
Be aware of the limit: AuthZEN's response is
`{"decision": <boolean>, "context": {...}}` — it standardizes allow and deny
and **cannot express `delegate`**. A policy that needs to route a call to a
person needs a richer answer than AuthZEN defines, which is the argument for
the interpreter being a seam rather than one shape for everybody.

## Running it

Tagged `container` and skipped by default, so `clean verify` still passes with
no Docker:

```bash
./mvnw -pl :nessy-example-policy test -Dnessy.excludedGroups=
```

It loads the shipped `nessy.rego` — not a copy — into the real OPA binary.
Rego can't be reasoned about from Java: a rule either sees a field or it
doesn't, and the only honest way to know is to run it.
