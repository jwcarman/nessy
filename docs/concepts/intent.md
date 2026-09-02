# Intent

A model can be made to *say* what it is about to do, in your vocabulary,
before it does it. That statement is an **intent**, and an approver may
weigh it.

It is a claim, not a fact. The model produced it, so it is evidence about
the model's stated reasoning and nothing more. The value is that a claim can
be checked against the call that follows.

## Declaring

`IntentTool` is an ordinary tool the agent is granted:

```java
IntentStore<Intent> store =
    new JdbcIntentStore<>(dataSource, "assistant", agentId, Intent.class, mapper);

config.tool(IntentTool.freeform(store));
```

A store is a view of ONE agent's declaration, so it is built with the agent
type and id it speaks for — an id is unique within its type and no further.
The model calls the tool, the declaration replaces whatever that agent
declared before, and `store.latest()` is what an approver reads.

## Your own vocabulary

Freeform prose is the weakest version. A typed vocabulary makes the claim
something a policy can actually judge:

```java
record OpsIntent(String action, String target, String reason) {}

IntentStore<OpsIntent> store =
    new JdbcIntentStore<>(dataSource, "ops", agentId, OpsIntent.class, mapper);

config.tool(new IntentTool<>(OpsIntent.class, store));
```

The record becomes the tool's schema, so the model is asked for exactly
those fields — and a claim with a `target` can be compared against the
arguments of the call that follows.

## Reaching an approver

`IntentEnricher` puts the latest declaration onto the `facts` of an approval
request, where an approver can read it:

```java
var enricher = new IntentEnricher<>(store, mapper);
```

And `IntentPolicy` is the shipped rule that uses it:

```java
config.tool(new RestartTool(), binding -> binding
        .approver(IntentPolicy.requireDeclared(enricher, desk)));
```

`requireDeclared` fails **closed**: a call with no declaration behind it is
denied, and only a call that declared something is passed to the approver
you wrapped. Wiring no intent at all is not the same as wiring a lenient
policy.

## What it is not

Intent does not authorize anything by itself. It is one input to a decision
that an approver still makes — see
[Authorization](authorization.md). A model that has learned to declare
something harmless and then ask for something else is exactly the case the
comparison exists to catch, which only works if somebody does the comparing.

## See also

- [Authorization](authorization.md) — approvers, and what they are told
- [Tools](tools.md) — how a tool's input becomes its schema
