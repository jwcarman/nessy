# Nessy Example: a policy engine decides

An `Approver` that asks [Open Policy Agent](https://www.openpolicyagent.org/)
instead of deciding in Java. The rules live in `policy/nessy.rego`, and
changing them is a policy review rather than a release.

## Why this example exists

`ApprovalRequest` is flat and JSON-shaped, and this is what that buys.
A policy engine's native input is a JSON document — OPA reads
`input.toolName` and `input.arguments.target` directly. Nothing here
translates between a Java object graph and something Rego can see, so the
adapter is about forty lines:

```java
Approver approver = new OpaApprover("http://localhost:8181", "nessy/tools", mapper);
```

Cedar, AWS Verified Permissions and most of the field take the same shape.
Swapping engines rewrites `OpaApprover` and nothing above it.

## What the policy shows

Three rules, each demonstrating something the request had to carry:

- **A read-only tool needs nobody.** Decided on `input.toolName` alone.
- **A destructive tool is scoped to the agent that owns it.** `prune_images`
  is the watchman's job; the same call from a chat agent is refused. This is
  why the request names the agent TYPE and not only the id.
- **Production is the line.** The rule reads `input.arguments.target`, which
  is why the arguments travel. "Restart a host" is not a decision anyone can
  make; "restart `prod-eu-1`" is.

Rego's default answer is no, so a tool no rule mentions is denied by
`default allow := false` rather than by somebody remembering to.

## The reply token is not sent

`ApprovalRequest.replyToken()` is a capability: whoever holds it can settle
the call. A policy engine logs its input and is frequently somebody else's
service, so `OpaApprover` builds the document field by field rather than
serializing the record. The field that matters is the one that is absent,
and there is a test whose only job is to keep it absent.

## A broken control is not permission

An engine that is down, slow or misconfigured denies. The failure of a gate
must never read as an open gate, and the reason says so plainly, because a
person looking at a denial deserves to know it came from plumbing rather
than from a rule.

## Running it

The tests are tagged `container` and skipped by default, so `clean verify`
still passes with no Docker:

```bash
./mvnw -pl nessy-examples/policy test -Dnessy.excludedGroups=
```

They load the shipped `nessy.rego` — not a copy — into the real OPA binary.
Rego cannot be reasoned about from Java: a rule either sees the field or it
does not, and the only honest way to know is to run it.

To try the policy by hand:

```bash
docker run -p 8181:8181 -v "$PWD/src/main/resources/policy:/policy" \
  openpolicyagent/opa:0.68.0 run --server --addr=0.0.0.0:8181 /policy

curl -s localhost:8181/v1/data/nessy/tools -d '{"input":{
  "toolName":"prune_images","agentType":"watchman",
  "arguments":{"target":"prod-eu-1"}}}' | jq
```
