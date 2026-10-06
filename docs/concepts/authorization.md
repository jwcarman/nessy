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

or defer to a person, and answer days later. The deferral is recorded in the
agent's story with the instant the request stands until and the facts the
approver was shown, whether or not there were any. Deferral works only on the
queued door. On the direct door a deferred call becomes a failed call, with no
facts, because a caller already waiting has nowhere for a late answer to arrive:

```java
Approver desk = request -> {
    notifier.send("Approve: " + request.action());
    return Awaited.deferred();
};
```

The approver keeps nothing. Nessy holds the call open until its deadline, and the approvals
waiting on a person are read from `AgentWork`; see
[Answering a waiting call](#answering-a-waiting-call).

The facts of an approval request are kept on the event that records the decision or the
deferral, as they stood for the approver. A decision made at once takes them after the approver
returns, so facts the approver added while deciding are in them. They go on the `ToolApproved` or
`ToolDenied` event; a request that had none leaves an empty object. A decision that arrives
after a deferral carries none, and the deferral's facts are its facts. Nothing about an approval
is written to the payload store. `StoryContent.approvalFacts(key)` reads the facts back for a
call, whether it is waiting or decided.

When the approver itself fails, by throwing, the call ends as not authorised and the failure
keeps the facts the approver was shown. The message the model reads is the approver's own. If the
approval policy asks again, the failure holds the facts of the ask that was made last; if a
retried request is not asked again before its deadline, the failure records no facts. A failure
that comes from an expired deferral carries none; the deferral's facts stand.

Facts are the application's own evidence and are not cut. Facts must be plain JSON, and must not change after the approver
returns (the request is not safe to share between threads). They are read whenever the agent's
events are read, so keep them small. They are not part of narration: a listener is never sent them.

How long the request stands is the binding's term, not the approver's:

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
    IdempotencyKey idempotencyKey,   // the call's own key; the same on the ToolCallRequest that runs it
    ToolName toolName,
    String arguments,      // the call's JSON, as the model wrote it
    String action,         // the sentence the binding's stringifier wrote
    Instant askedAt,
    Instant deadline,
    ObjectNode facts) {

  Optional<JsonNode> fact(String name);
  ApprovalRequest fact(String name, JsonNode value);   // adds the fact to this request and returns it
}
```

**Flat, and untyped on purpose.** One approver serves every gated tool, and
those tools have different inputs, so a typed request would force a generic
`Approver`. More to the point, the policy engines people plug in, OPA and
Cedar, take a JSON document. A typed request would be typed on its way to
being serialised back.

**`deadline` is the instant the request is held to.** It is the deadline the effect was written
with, not a time worked out when the approver is asked: a request that waited in the queue shows
the same instant the call is given up on.

**`arguments` is for deciding. `action` is for showing.** A policy reads
the arguments to decide. A page shows `action()`, the sentence the binding's
action `Stringifier` wrote. Rendering raw arguments at a person is the failure
this split exists to prevent: nobody can consent to
`{"customer_id":"cus_8823","op":"purge"}`, and everybody can consent to
"permanently delete Acme Corp's record".

**Something richer than a string?** Put it in `facts`. It is the channel
for structured evidence a policy deposits or reads: the risk gate writes its
assessment there, and a delegating policy writes the term it decided on there. An
`ApprovalEnricher` on the binding adds facts before any approver sees the
request.

## Describing what is being approved

`action` is the sentence a person consents to, and you write it as a
`Stringifier` of the tool's input:

```java
config.tool(sendEmail, binding -> binding
        .approver(desk)
        .action(email -> "Send an email to %s, subject \"%s\": %s"
                .formatted(email.to(), email.subject(), email.body())));
```

**Consenting to a message you have not read is not consent.** Include the
body. The sentence is stored and shown as one line: runs of whitespace,
line breaks included, become one space, and the line is cut to at most
1,000 characters. Without a stringifier of your own the line is cut at 255
and keeps its start. When the part that matters is not the start, name the
cut yourself, as in `.action(Stringifier.<SendEmail>json(mapper).dropMiddle(300))`.
A cut that is already at or below 1,000 is used as given. The default is the
input's `toString()`, which is honest for a small record and useless for a
large one. See [Tools](tools.md#what-a-call-leaves-behind) for the
stringifier, the droppers and the limits.

What the approver is shown is the line stored when the model asked. When a
stringifier gives nothing, that line is the tool's name, and the approver is
shown the name.

Two cases are never put to an approver. A call whose arguments do not read into
the tool's input type could not run whatever anybody answered, so it is
discharged as a failure the model reads. A call whose action stringifier threw
has no sentence to consent to, and a yes would run a call nobody could see, so
it is refused the same way, with a message saying that what the call would do
could not be described and it was not put to an approver. Where a gate cannot be
shown what it gates, it refuses.

## A denial is an answer

```java
replies.approve(agentType, agentId, key, ApprovalResult.denied("not this time"));
```

The model is told the call was refused, with the reason, and decides what to
do about that. It is not a failed turn, and it must not look like a broken
tool.

## Who decided

`ApprovalResult.approvedBy(decidedBy)` and `deniedBy(reason, decidedBy)` name
who or what decided: a user id, a ticket number, a policy's name, however the
application chooses to say it. `approved()` and `denied(reason)` name no one.

`decidedBy` is an opaque string. Nessy never interprets it. It is stored on
`ToolApproved` and `ToolDenied` and told on `CallApproved` and `CallDenied`, as
given, except that Nessy cuts one longer than 1,000 characters to that length
and never refuses it. The record of the decision itself, the evidence and the reasons, stays
with the application. Every event about a call carries the call's
`IdempotencyKey`, and that key is the join from the story to the
application's own record.

## Answering a waiting call

The agent type, the agent id and the call's idempotency key together are the address of a
waiting call. An approval request carries all three, and so does the request a deferring tool
is handed.

An approver that defers may hand the three values to whatever will answer, such as a ticket or a
chat message, or it may keep nothing. Nessy holds the call open, and the approvals waiting on a
person can be read from `AgentWork.waitingApprovals()`, each as the `ApprovalRequest` the approver
was shown. A page built on that read needs no table of its own. An application that keeps its own
record of an approval, for its own workflow, may do so; Nessy does not need it.

A tool that defers must hand the three values on. Waiting tool calls are only counted in an
agent's status (`waitingToolCalls`) and are never listed, so nothing else can find them.

When the person answers, the application hands the three values and the verdict to `Replies`:

```java
ReplyOutcome outcome = replies.approve(agentType, agentId, key, ApprovalResult.approvedBy("u_carol"));
```

`Replies` has two methods. `approve` gives a verdict to a call that is waiting for one.
`complete` gives a result to a call whose tool deferred. A verdict cannot settle a call that is
already running, and a result cannot settle a call that is still waiting for permission. The
approval and the tool call of one call share the key, so the kind of answer is what tells them
apart.

The outcome is `Applied` or `Ignored`:

- `Applied` means the agent took the answer and its story changed.
- `Ignored` means nothing changed. The call was already decided, its deadline had passed, the
  answer was the wrong kind for the call's stage, or this process serves no such agent or call.
  A caller does the same thing in each case. The agent's story holds the detail.

An answer that arrives at or after the call's deadline is ignored, even if the engine has not yet
recorded the expiry. It is never applied late.

Two answers at once to one call give one `Applied` and one `Ignored`.

**Nessy does not check who is answering.** Anyone who has the three values and can reach your
endpoint can answer the call. Your endpoint checks who is calling it, not Nessy. Send the address
only to the party who should answer.

To find which approval requests are waiting without a table of your own, read
`AgentWork`; see
[The Harness](../guides/harness.md#what-is-waiting-and-answering-it).

A reply runs on the calling thread. On a JDBC backend it joins a transaction the caller has open,
so an application can record its own decision and answer the call in one commit, and if that
transaction rolls back, the call is still waiting. The in-memory backend has no transaction to
join. Narration and the dispatcher's nudge happen when the reply returns, which can be before the
caller's transaction commits.

## Recovery leaves parked calls alone

A call waiting on a person is **not** re-asked when its process restarts.
The row is running until its term is up, and the address already handed out stays
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

`RiskFactors` names the stock reasons, `DESTRUCTIVE`, `IRREVERSIBLE`,
`EXTERNAL_WORLD`, `SPENDS_MONEY`, `TOUCHES_PII` and `READ_ONLY`; a
`RiskFactor` is a name, so a domain adds its own. They are recorded with the
assessment for the person to read, and do not change the level.

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

It sees the whole request, tool name, described action, arguments, and any
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
PolicyEngine opa = OpaPolicyEngine.of(policy -> policy
    .url("http://localhost:8181")
    .decisionPath("nessy/tools/decision"));

Approver gate = PolicyApprover.of(config -> config
    .engine(opa)
    .delegate("humans", desk));
```

A gate written in Java ships when the application ships. A gate written in
Rego is data: reviewed by whoever owns the risk, versioned on its own, and
changed without a release.

Two seams shape the conversation with OPA. An `InputDocumentRenderer` builds
the `input` document from the request (`standard(mapper)` is the
field-by-field default), and a
`DecisionInterpreter` reads
the result back into a `Verdict` (`effectStyle()` understands the
`{"effect": ...}` shape below). Replace either when your Rego is shaped
differently, and set `timeout` and `connectTimeout` for the call.

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

- **Only what a rule needs is sent.** The document is built field by field
  rather than serialized, so a field added to the request later cannot reach a
  policy engine until somebody decides it should.
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

Both page examples are on the queued door. In each, the approval request
waits in Nessy, so it outlives the page and the process, and the application
keeps nothing. In chat-web the email tool's approver returns
`Awaited.deferred()` for every request. In the watchman, the approver sits
behind a risk gate: it approves below `RiskLevel.MODERATE`, denies at or
above `RiskLevel.VERY_HIGH`, and defers the rest to a deferring approver.

`nessy-examples/chat-web` is a conversation. A person talks to the agent, and
the email tool needs approval. The page lists the cards from
`AgentWork.status(type, agent).waitingApprovals()`, and a decision goes to
`Replies.approve(type, agent, key, result)`, with
`ApprovalResult.approved()` or `ApprovalResult.denied(note)`. `Applied` is a
`202`. `Ignored` is a `409`: a second tab that answers after the first, or an
answer after the deadline, changes nothing. A person has `chat.approval-term`,
five minutes by default; after that the call is recorded as failed and the
card is gone.

`nessy-examples/watchman` is an agent nobody is talking to. It wakes on a
schedule, and its page lists every approval waiting for the agent type with
`AgentWork.waitingApprovals(type)`. Its approval term is three days.

In both, the endpoint that takes the decision does not authenticate anyone.
Nessy does not check who is answering, so an application that copies one of
these endpoints must guard it.

## See also

- [Tools](tools.md), `Awaited`, and how a tool defers
- [Durable Computation](durable-computation.md), answering and deadlines
