# Tools

A tool is a name, a description, an input type, and a method.

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public String name() { return "add"; }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }

    public Awaited<ToolResult> execute(ToolCallRequest<Add> call) {
        Add input = call.input();
        return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
}
```

## The input type is the contract

`inputType()` becomes the JSON schema the model is shown, and the JSON the
model produces is deserialized back into it before `execute` runs. So a
record with honest field names *is* the documentation, and a tool never
parses a string.

A tool with no input still needs a type — an empty record works, but give it
an explicit empty `properties` in its schema, because some providers reject
a schema without one.

## Granting a tool

```java
config.tool(new AddTool());                       // ungated
```

or, when it needs governing:

```java
config.tool(new SendEmailTool(), binding -> binding
        .approver(desk)
        .describer(email -> "Send an email to " + email.to()));
```

The customizer is typed to *that* tool's input, so the compiler ties a
describer to the tool it describes. That matters as soon as an agent has two
tools with different inputs.

## Answering now, or later

`Awaited` has two arms, and the difference is the whole durability story:

```java
Awaited.ready(ToolResult.ok("done"));                    // answered
Awaited.deferred(clock.instant().plus(Duration.ofDays(3)));  // answer later
```

**Ready** finishes the call. **Deferred** parks it: the agent records that
this call is waiting on the world, arms a durable alarm, and moves on. It
does not hold a thread, an actor, or a process.

Whoever will answer needs an address, and that is the `ReplyToken`:

```java
public Awaited<ToolResult> execute(ToolCallRequest<Order> call) {
    vendor.placeOrder(call.input(), call.replyToken());   // hand it out
    return Awaited.deferred(clock.instant().plus(Duration.ofHours(2)));
}
```

Days later, from a completely different process:

```java
replies.answer(token, ToolResult.ok("the vendor shipped it"));
```

The token names logical coordinates — agent type, agent id, turn, call — so
nothing that was waiting has to still exist. See
[Durable Computation](durable-computation.md).

## Results

```java
ToolResult.ok("42");
ToolResult.error("the host did not respond");
```

An error is a **result**, not an exception: the model is told the call
failed and decides what to do about it. The turn carries on. A tool that
throws is caught and reported the same way, with a note that it may have
partially completed.

The same is true of a refusal. A denied call is a completed call whose
result says it was denied, with the reason — the model gets to respond to
that, and it is not a failed turn.

## Execution is at-least-once

A tool that was running when its process died will be run again on
recovery, because nothing recorded that it had finished. No marker fixes
this — a "started" marker only moves the ambiguity — so it is stated as a
contract rather than hidden.

The engine's mitigation is `call.callKey()`: the turn and the call together,
stable across a re-drive, so a tool that cares can deduplicate on it. The
turn is in there because a model's call id is unique within ONE response —
two turns can each produce a `call_1`.

## One request, not two contexts and an input

`execute` takes one parameter. The request carries the call — who is asking,
which turn, which call, where an answer goes — **and the input**, already
bound to your type by the binding before anything runs.

A tool used to be handed the same call in two pieces: its input, and a
context describing the call the input came from. And an approver was handed a
third view of it, so the two had to be kept in step while neither could see
what the other got. Now a tool and an approver read the same record.

Carrying the raw JSON alongside the bound input would be a second
representation of one thing, and two representations drift — so the request
has `input()` and nothing else. A page that wants to show the arguments
serializes the input back out, which is what chat-web's approval desk does.

## What a tool never sees

A tool gets its input and the call: who is calling, which turn, which call,
the arguments, and where an answer goes. It does not get the transcript, the harness, or the agent's state —
those are not its business, and a tool that could reach them would be a tool
you could not test.

## MCP tools

`nessy-tool-mcp` imports a remote server's tools as ordinary `Tool`
instances, granted and gated exactly like the ones you wrote. See
[MCP Clients](../guides/mcp-clients.md).

## See also

- [Authorization](authorization.md) — approvers, risk thresholds, and describing what a person consents to
- [Durable Computation](durable-computation.md) — parking, alarms, and answering from outside
- [The Harness](../guides/harness.md) — granting tools
