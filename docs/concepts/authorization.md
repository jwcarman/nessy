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
Approver always = (request, context) -> Awaited.ready(ApprovalResult.approved());
Approver never  = (request, context) -> Awaited.ready(ApprovalResult.denied("not in this tenant"));
```

or defer to a person, and answer days later:

```java
Approver desk = (request, context) -> {
    pending.save(request, context.replyToken());
    return Awaited.deferred(clock.instant().plus(Duration.ofDays(3)));
};
```

**Every call goes through an approver**, even an ungated one — the ungated
case just answers on the spot. One path through the code is worth more than
the message it would save, and it is the path recovery has to work on too.

## What the approver is told

```java
record ApprovalRequest(
    AgentType agentType,
    AgentId agentId,
    String turnId,
    String callId,
    String toolName,
    JsonNode arguments,
    String action,
    Instant askedAt,
    Supplier<ReplyToken> replyTokens,
    ObjectNode facts) {

  ReplyToken replyToken();   // mints on first call, then remembers
  String callKey();          // turnId + "/" + callId
}
```

**Flat, and untyped on purpose.** One approver serves every gated tool, and
those tools have different inputs — so a typed request would mean a generic
`Approver`, and a desk would have to be written as `Approver<Object>` to serve
more than one tool. More to the point, the policy engines people actually plug
in — OPA, Cedar — take a JSON document. A typed request would be typed on its
way to being serialised back.

**`arguments` is for deciding. `action` is for showing.**

A policy reads `arguments.path("host").asText()` to decide. A page shows
`action()` — the sentence the binding's `ActionRenderer` wrote. Rendering raw
arguments at a person is the failure this split exists to prevent: nobody can
consent to `{"customer_id":"cus_8823","op":"purge"}`, and everybody can consent
to "permanently delete Acme Corp's record".

**Not `description`.** A `Tool` has a description, and it means something else:
what the tool IS, written for the model. This is what one call, with these
arguments, would actually do.

**Something richer than a string?** Put it in `facts` — it is an `ObjectNode`,
and that is the channel for structured evidence a policy deposits or reads.
`Risk` does exactly this. A typed *action* object would only restate the tool's
input, which the renderer already receives.

`call` is the whole of what the tool itself would be handed — the same record,
so an approver can decide on exactly the values the tool will act on. `facts` is the
[intent](intent.md) channel — what the model *said* it was doing, which a
policy may weigh or ignore.

## Describing what is being approved

`description` is the sentence a person consents to, and you write it:

```java
config.tool(sendEmail, binding -> binding
        .approver(desk)
        .action(email -> "Send an email to %s%n  subject: %s%n  body: %s"
                .formatted(email.to(), email.subject(), trimmed(email.body()))));
```

**Consenting to a message you have not read is not consent.** Include the
body. Trim it if your surface is a terminal prompt; don't if it is a page
with room. A action renderer that names only the recipient and subject asks
somebody to approve an email they cannot see.

The default renderer is the input's `toString()`, which is honest for a
small record and useless for a large one.

## A denial is an answer

```java
replies.approve(token, ApprovalResult.denied("not this time"));
```

The model is told the call was refused, with the reason, and decides what to
do about that. It is not a failed turn, and it must not look like a broken
tool — a denial that reached a model as a missing result once produced an
agent apologising for an error it had not had.

## Reply tokens

`request.replyToken()` is the address an answer comes back to, and the tool
gets one for the same call, because both settle it.

**It is minted when you ask for it, not before.** A token is a capability —
whoever holds it can settle this call — and most calls are answered on the
spot and hand one to nobody. So asking is what mints it, and asking twice
gives you the same one rather than two addresses that happen to mean the same
thing.

The
coordinates inside it — agent type, agent id, turn, call — are **encrypted
with AES-GCM**, so whoever holds it can neither read them nor forge a token
for a different call.

Being authentic is not the same as being open: a token that reads cleanly
says only that this engine issued it, **never** that the call is still
waiting. Answering a call that already settled or expired is reported
honestly rather than silently changing nothing.

The same token reaches the approver and the tool, because both settle the
same call and two addresses meaning one thing is two things to get wrong.

The keys are AES keys of 16, 24 or 32 bytes — **use 32**, and any other
length is refused when it is configured rather than at the first mint. Mint
one with `openssl rand -base64 32`, or in Java:

```java
KeyGenerator generator = KeyGenerator.getInstance("AES");
generator.init(256);
SecretKey key = generator.generateKey();
```

Tokens are minted with the **first** key and read by trying **every** one, so
a rotation does not invalidate a token already sitting in somebody's inbox:

```java
new PekkoHarnessFactory(engine -> engine
        .replyTokens(ReplyTokens.withKeys(currentKey, previousKey)));   // byte[32] each
```

By default they are **ephemeral** — a fresh key per process, so tokens die
with the JVM. That is right for a test and wrong for anything that parks
work for days, because every approval waiting on a person becomes
unanswerable after a restart.

## Deadlines

Deferring names a moment the answer must arrive by. That deadline is a
**row**, not a timer, so it outlives the process that set it. When it
passes, the agent is told the deadline passed — distinct from an answer,
because whether a timeout is a denial, an error or a retry is your policy
and not the engine's.

## Recovery leaves parked calls alone

A call waiting on a person is **not** re-asked when its process restarts.
Re-asking would mint a second reply token and invalidate the one already
sitting in somebody's inbox. That is why the engine records *what kind* of
waiting a call is doing rather than just that it is unfinished.

## Gating on risk

Some calls are worth a person's attention and some are not, and "which tool
is it" is a blunt way to decide. `Risk` splits that into two decisions that
belong to different people:

```java
binding.approver(
    Risk.assessing(assessor)
        .approvingBelow(RiskLevel.MODERATE)
        .denyingAtOrAbove(RiskLevel.VERY_HIGH)
        .otherwiseAsking(desk));
```

Below the floor runs unasked. At or above the ceiling is refused without
waking anybody. Everything between is what a person is for — and the middle
band is the whole point, because a gate with no middle band is just a boolean.

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
800-30's qualitative combination matrix, and the canonical constructor is the
door for an assessor whose own judgement differs from the matrix.

### The assessor

```java
public interface RiskAssessor {
  RiskAssessment assess(ApprovalRequest request);
}
```

It sees the whole question — tool name, described action, arguments, and any
facts an earlier approver deposited — so a policy can turn on what was
actually asked. `RiskAssessor.always(...)` is the common case: a tool whose
danger does not vary with its input.

It is **not** asked whether to allow the call. It says how bad the call would
be and how likely that is; the thresholds turn that into an answer.

### What the person sees

The assessment is recorded on the request under `Risk.FACT` before anyone is
asked, so a desk can show *why* it is asking rather than only what it is
asking about. That is the difference between a prompt and an interruption.

### Contradictory thresholds are refused

A ceiling below the floor would deny calls it also approves, so
`denyingAtOrAbove` rejects it when it is configured rather than behaving
oddly later. Equal thresholds are allowed and mean something coherent: every
call is either approved or denied, and nobody is ever asked.

## The Spring Boot desk

`nessy-spring-boot-starter` ships a pending-approvals projection: an
approver that defers, a table of what is waiting, and the plumbing for a
page that approves or denies. See [Spring Boot](../guides/spring-boot.md).

## See also

- [Tools](tools.md) — `Awaited`, and how a tool defers
- [Intent](intent.md) — the claim channel an approver may weigh, and one input a risk assessor can read
- [Durable Computation](durable-computation.md) — reply tokens and alarms
