# Testing

**You will never need a mocking library to test a Nessy agent.** The fold is
pure, so most of the loop is tested with plain unit tests and no doubles at
all. Where a test does need a model, `nessy-testing`'s
`ScriptedModelProvider` plays one back deterministically — no key, no
network, no real model. The framework's own suite holds itself to this
promise: its only test dependencies are JUnit and AssertJ.

This is a testing utility, not a production model provider. Reach for a real
one — `nessy-model-anthropic`, `nessy-model-openai` — for anything an agent
actually talks to; reach for this one from `src/test`.

## Why offline and deterministic is the point

A real model is slow, costs money per call, and answers differently each
time you ask. None of that is acceptable in a test suite that runs on every
push. `ScriptedModelProvider` fixes all three: it returns exactly the events
you scripted, in order, with no network call and no key — so CI runs with no
`ANTHROPIC_API_KEY` set at all, and an assertion against its output is
replay-safe by construction.

It also records what it was asked. Tool-calling, streaming, and approval
flows are usually more interesting on the *request* side than the response
side, and `requests()` hands back exactly what the harness sent, oldest
first, for a test to assert against.

Add it as a test-scoped dependency:

```xml
<dependency>
  <groupId>org.jwcarman.nessy</groupId>
  <artifactId>nessy-testing</artifactId>
  <scope>test</scope>
</dependency>
```

## Scripting a conversation

The builder scripts one turn at a time. Each turn is a sequence of model
events closed by exactly one terminator — `endTurn()` (the model is done) or
`endWithToolUse()` (the model wants a tool run, and the harness will call
back with another turn):

```java
ObjectNode args = JsonNodeFactory.instance.objectNode();
args.put("left", 2);
args.put("right", 2);

ScriptedModelProvider provider = ScriptedModelProvider.builder()
        .toolUse("c1", "add", args)
        .endWithToolUse()
        .text("The answer is 4.")
        .endTurn()
        .build();

Agent<String> agent =
    Nessy.harness(provider)
        .build()
        .agent()
        .name("hello")
        .model("fake-model")
        .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
        .build();
```

The first turn emits a tool call and ends with `TOOL_USE`; the harness runs
`AddTool`, feeds the result back, and the second scripted turn supplies the
model's reply to that. Nothing else about calling the agent changes —
`converse().tell(...)` and the returned `RunOutcome` are the same shape a
real provider produces. This exact wiring is a runnable module,
`nessy-examples/hello`:

```bash
./mvnw -q -pl nessy-examples/hello -am compile exec:java
```

The builder's other event methods cover the rest of what a model can emit
mid-turn: `text(String)` for a prose chunk, `thinking(String)` /
`thinkingSigned(String)` / `redactedThinking(String)` for extended-thinking
content, and `toolUse(id, name, arguments)` for a tool call. `endTurn(Usage)`
attaches a specific token count when a test cares about usage accounting;
plain `endTurn()` reports zero. Calling `build()` with an open, unterminated
turn — no trailing `endTurn()`/`endWithToolUse()` — throws
`IllegalStateException` rather than silently dropping it.

## Sharp edges

A script is not a stub that answers forever. Each `stream(ModelRequest)`
call consumes the next scripted turn; asking for one past the end of the
script throws `IllegalStateException` naming how many turns were scripted.
Size the script to the exact number of model round-trips the test expects,
including the extra turn a tool call always costs.

Each `ModelStream` this provider returns is one-shot: iterating it a second
time throws rather than replaying, so an implementation bug that reads a
stream twice fails loudly instead of quietly doubling events.

`ScriptedModelProvider` never parks anything — every `capabilities()` call
returns an empty set — so a conversation driven entirely through it always
finishes `COMPLETE`, never `PARKED`. Testing park-and-resume behavior needs a
real approval gate (`Approver.parkAll()` or `requireApproval()` on a
`UsagePolicy`) around it, not a different script.

## RecordingSubscriber

`RecordingSubscriber` captures whatever it is handed, for tests asserting on
declared-listener or conversation-event traffic rather than on the model
turns themselves. Wire it up as a declared listener
(`.listen(Object.class, recorder)` on a `HarnessBuilder`/`AgentBuilder`) or
as a conversation-local subscription via `Conversation#events()`, then read
back `all()` for everything received or `ofType(TurnEvent.class)` filtered
to one event type.

## The test tiers

`./mvnw verify` runs the offline suite only — everything above, plus every
unit test — no key, no Docker, no network. Tests that spend real tokens
against a live vendor are tagged `live` and excluded by default; tests that
need a real database are tagged `container` and need a Docker daemon. Clear
either exclusion to opt in:

```bash
# add the container-tagged tests (needs Docker, spends no tokens)
./mvnw test -Dnessy.excludedGroups=live

# clear every exclusion: container AND live together (Docker + real tokens)
./mvnw test -Dnessy.excludedGroups=
```

`nessy-jdbc`'s own five-vendor matrix carries a further `vendor` tag on top
of `container`, kept out of CI specifically
(`-Dnessy.excludedGroups=live,vendor`) so an ordinary push doesn't pull four
extra database images. See
[`nessy-jdbc/README.md`](https://github.com/jwcarman/nessy/blob/main/nessy-jdbc/README.md#testing-this-module)
for that module's full tier breakdown.

## Where next

- [Getting Started](getting-started.md) — the real five-minute example this
  guide's script stands in for.
- [The Durable Loop](../concepts/durable-loop.md) — the fold, `Awaited`, and
  the at-least-once delivery every test double still has to respect.
- [Durable Persistence](durable-persistence.md) — swapping in `nessy-jdbc`
  once a scripted test proves the wiring.
