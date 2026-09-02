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
    ToolCallRequest call, String description, Instant askedAt, ObjectNode facts) {}

record ToolCallRequest(
    AgentType agentType,
    AgentId agentId,
    String turnId,
    String callId,
    String toolName,
    JsonNode arguments,
    ReplyToken replyToken) {}
```

`call` is the whole of what the tool itself would be handed — the same record,
so an approver can decide on exactly the values the tool will act on. `facts` is the
[intent](intent.md) channel — what the model *said* it was doing, which a
policy may weigh or ignore.

## Describing what is being approved

`description` is the sentence a person consents to, and you write it:

```java
config.tool(sendEmail, binding -> binding
        .approver(desk)
        .describer(email -> "Send an email to %s%n  subject: %s%n  body: %s"
                .formatted(email.to(), email.subject(), trimmed(email.body()))));
```

**Consenting to a message you have not read is not consent.** Include the
body. Trim it if your surface is a terminal prompt; don't if it is a page
with room. A describer that names only the recipient and subject asks
somebody to approve an email they cannot see.

The default describer is the input's `toString()`, which is honest for a
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

`request.replyToken()` is the address an answer comes back to — the same one
the tool would be handed, because both settle the same call. The
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

## The Spring Boot desk

`nessy-spring-boot-starter` ships a pending-approvals projection: an
approver that defers, a table of what is waiting, and the plumbing for a
page that approves or denies. See [Spring Boot](../guides/spring-boot.md).

## See also

- [Tools](tools.md) — `Awaited`, and how a tool defers
- [Intent](intent.md) — the claim channel an approver may weigh
- [Durable Computation](durable-computation.md) — reply tokens and alarms
