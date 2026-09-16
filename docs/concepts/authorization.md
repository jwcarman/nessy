# Authorization

Some tools should not run without a decision. In Nessy that decision is an
**approver**, and it is asked per call.

```java
public interface Approver {
  Awaited<ApprovalResult> approve(ApprovalRequest request);
}
```

## Ungated, gated, and deferred

A tool granted with no approver runs when the model asks for it:

```java
config.tool(new SearchTool());
```

A tool granted with one is asked first:

```java
config.tool(new PurchaseTool(), binding -> binding.approver(approver));
```

The approver can answer immediately:

```java
Approver always = request -> Awaited.ready(ApprovalResult.approved());
Approver never  = request -> Awaited.ready(ApprovalResult.denied("not in this tenant"));
```

or defer to a person, and answer days later:

```java
Approver desk = request -> {
    pending.save(request, request.replyToken());
    return Awaited.deferred();
};
```

How long the question stands is the binding's term, not the approver's:

```java
config.tool(new PurchaseTool(), binding -> binding
        .approver(desk, terms -> terms.timeout(Duration.ofDays(3))));
```

The default is ten minutes. When the term passes unanswered the call is
recorded as failed with a message saying so, and the model carries on.

## What the approver is told

```java
public record ApprovalRequest(
    AgentType agentType,
    AgentId agentId,
    TurnId turn,
    CallId callId,
    ToolName toolName,
    String arguments,      // the call's JSON, as the model wrote it
    String action,         // the sentence the binding's renderer wrote
    Instant askedAt,
    Instant deadline,
    ReplyToken replyToken,
    ObjectNode facts) {

  Optional<JsonNode> fact(String name);
  ApprovalRequest fact(String name, JsonNode value);   // a copy, with the fact added
  String callKey();                                     // turn + "/" + callId
}
```

**Flat, and untyped on purpose.** One approver serves every gated tool, and
those tools have different inputs, so a typed request would force a generic
`Approver`. More to the point, the policy engines people plug in, OPA and
Cedar, take a JSON document. A typed request would be typed on its way to
being serialised back.

**`arguments` is for deciding. `action` is for showing.** A policy reads
the arguments to decide. A page shows `action()`, the sentence the binding's
`ActionRenderer` wrote. Rendering raw arguments at a person is the failure
this split exists to prevent: nobody can consent to
`{"customer_id":"cus_8823","op":"purge"}`, and everybody can consent to
"permanently delete Acme Corp's record".

**Something richer than a string?** Put it in `facts`. It is the channel
for structured evidence a policy deposits or reads: the risk gate writes its
assessment there, an intent enricher writes the model's declaration there,
and a delegating policy writes the term it decided on there. An
`ApprovalEnricher` on the binding adds facts before any approver sees the
request.

## Describing what is being approved

`action` is the sentence a person consents to, and you write it:

```java
config.tool(sendEmail, binding -> binding
        .approver(desk)
        .action(email -> "Send an email to %s%n  subject: %s%n  body: %s"
                .formatted(email.to(), email.subject(), trimmed(email.body()))));
```

**Consenting to a message you have not read is not consent.** Include the
body. Trim it if your surface is a terminal prompt; don't if it is a page
with room. The default renderer is the input's `toString()`, which is
honest for a small record and useless for a large one.

## A denial is an answer

```java
replies.approve(token, ApprovalResult.denied("not this time"));
```

The model is told the call was refused, with the reason, and decides what to
do about that. It is not a failed turn, and it must not look like a broken
tool. `ApprovalResult.approvedBy(reference)` and `deniedBy(reason,
reference)` carry who decided, for the record.

## Reply tokens

`request.replyToken()` is the address an answer comes back to. The
coordinates inside it, agent type, agent id, request and call, are
**encrypted with AES-GCM**, so whoever holds it can neither read them nor
forge a token for a different call. It is a credential: never render it,
never log it, and never send it to a policy engine.

Being authentic is not the same as being open: a token that reads cleanly
says only that this engine issued it, **never** that the call is still
waiting. Answering a call that already settled or expired comes back as
`NotAwaiting` rather than silently changing nothing.

The keys are AES keys of 16, 24 or 32 bytes; **use 32**, and any other
length is refused when it is configured rather than at the first mint. Mint
one with `openssl rand -base64 32`. Tokens are minted with the **first** key
and read by trying **every** one, so a rotation does not invalidate a token
already sitting in somebody's inbox:

```java
new DefaultHarnessFactory(engine -> engine
        .replyTokens(ReplyTokens.withKeys(currentKey, previousKey))     // byte[32] each
        ...);
```

By default they are **ephemeral**, a fresh key per process, so tokens die
with the JVM. That is right for a test and wrong for anything that parks
work for days, because every approval waiting on a person becomes
unanswerable after a restart.

## Recovery leaves parked calls alone

A call waiting on a person is **not** re-asked when its process restarts.
The row is running until its term is up, and the token already issued stays
the one that settles it. See
[Durable Computation](durable-computation.md#deferring).

## Gating on risk

Some calls are worth a person's attention and some are not, and "which tool
is it" is a blunt way to decide. `nessy-approval-risk` splits that into two
decisions that belong to different people:

```java
binding.approver(
    Risk.assessing(assessor)
        .approvingBelow(RiskLevel.MODERATE)
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)
        .otherwiseAsking(desk));
```

Below the floor runs unasked. At or above the ceiling is refused without
waking anybody. Everything between is what a person is for, and the middle
band is the whole point, because a gate with no middle band is a boolean.

**The assessment is somebody's judgement about a tool; the thresholds are
somebody's appetite for risk.** A staging box and a production box run the
same assessor with different numbers.

### The assessment

```java
RiskAssessment.of(Likelihood.HIGH, Impact.MODERATE,
                  RiskFactors.DESTRUCTIVE, RiskFactors.IRREVERSIBLE);
```

`Likelihood`, `Impact` and `RiskLevel` are three separate five-value enums,
deliberately: swapping a likelihood for an impact is then a compile error
rather than a silent severity bug. `of` derives the level from NIST SP
800-30's qualitative combination matrix, and the canonical constructor is
the door for an assessor whose own judgement differs from the matrix.

### The assessor

```java
public interface RiskAssessor {
  RiskAssessment assess(ApprovalRequest request);
}
```

It sees the whole question, tool name, described action, arguments, and any
facts an earlier approver deposited, so a policy can turn on what was
actually asked. `RiskAssessor.always(...)` is the common case: a tool whose
danger does not vary with its input. It is **not** asked whether to allow
the call; the thresholds turn its answer into one.

The level is recorded on the request under the `risk` fact before anyone is
asked, so a desk can show *why* it is asking. A ceiling below the floor is
refused when configured; equal thresholds are allowed and mean nobody is
ever asked.

## Rules that live outside the application

An approver can hand the decision to a policy engine. This is what the flat,
JSON-shaped request buys: OPA reads `input.toolName` and `input.arguments`
directly.

```java
PolicyEngine opa = OpaPolicyEngine.create(policy -> policy
    .url("http://localhost:8181")
    .decisionPath("nessy/tools/decision"));

Approver gate = PolicyApprover.create(config -> config
    .engine(opa)
    .delegate("humans", desk));
```

A gate written in Java ships when the application ships. A gate written in
Rego is data: reviewed by whoever owns the risk, versioned on its own, and
changed without a release.

### Three verdicts

**A policy decides now; an approver may take three days.** A `PolicyEngine`
answers synchronously with a `Verdict`:

| Verdict | What the approver does |
|---|---|
| `Approve` | the call runs |
| `Deny(reason)` | the model is told why |
| `Delegate(to, facts)` | a named approver decides, which may park for days |

```rego
default decision := {"effect": "deny", "reason": "no rule allowed this"}

decision := {"effect": "allow"} if input.toolName in {"disk_usage", "containers"}

decision := {"effect": "delegate", "to": "humans", "term": "PT72H"} if production
```

There is deliberately no `ask`. A desk that parks a call and waits **is** an
approver, so asking a person was never a kind of answer, only delegation to
a particular one. `Delegate` resolves against an **allowlist** given at
construction, and a chain of delegations is bounded by `maxDepth`.

### The rules a gate has to get right

- **The reply token is not sent.** The document is built field by field
  rather than serialized, and a test exists whose only job is to keep the
  token out.
- **A control that did not answer is not a control that said yes.** An
  engine that is down, a mistyped decision path, an unknown effect: each
  denies **and** logs an `error`. With OPA that is sharper than it sounds: a
  mistyped path returns `{}`, byte-identical to a rule that did not fire, so
  a decision rule must carry a `default`, which makes the presence of a
  result a health check.

### It need not be external

`PolicyEngine` is one method, so rules in Java are a lambda:

```java
PolicyEngine rules = request ->
    READ_ONLY.contains(request.toolName()) ? new Verdict.Approve() : new Verdict.Delegate("humans");
```

You give up rules-as-data and keep everything else. `nessy-approval-policy`
and `nessy-approval-policy-opa` are the modules; `nessy-examples/policy`
wires them.

## A desk on a page

`nessy-examples/chat-web` is the worked desk: an approver that remembers the
request and defers, a card pushed to the browser over the approvals event
stream, and a `POST` that answers through `Replies` with `approvedBy` or
`deniedBy` and the note the person typed. A second tab that answers first
gets a `409`, because losing a race to another person is not an error.

## See also

- [Tools](tools.md), `Awaited`, and how a tool defers
- [Intent](intent.md), the claim channel an approver may weigh
- [Durable Computation](durable-computation.md), reply tokens and deadlines
