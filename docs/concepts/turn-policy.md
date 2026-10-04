# Turn Policy

Per-effect terms already bound a single call — a timeout on one model
call, one tool call. Nothing bounded the loop around them: a turn that
keeps calling tools was unbounded in both time and spend, with every
individual call sitting comfortably inside its own budget. `TurnPolicy` is
what closes that gap.

## The shape

```java
@FunctionalInterface
public interface TurnPolicy {
  TurnDecision decide(TurnStats stats, Instant now);
}
```

Consulted where the engine would otherwise ask the model again, so a turn
is bounded at the one point it could go round forever. `now` is passed in
rather than read from a clock inside the policy, because a policy that
cares about elapsed time needs a clock `TurnStats` cannot carry — a stored
duration would be measured against whatever clock was running at replay.

`decide` returns a `TurnDecision`, one of three:

```java
public sealed interface TurnDecision {
  record Continue() implements TurnDecision {}
  record AnswerNow() implements TurnDecision {}
  record FailTurn(String reason) implements TurnDecision {}
}
```

**`Continue`** is the overwhelmingly common answer, and what a turn nobody
bounded always gets.

**`AnswerNow`** asks the model to answer, and offers it no tools this
time. It is the response that does not destroy work: a turn deep in a
loop cannot be told apart from a long honest one — research and
multi-file work genuinely run long — so the first thing a bound does is
ask for an answer rather than take the turn away. A turn that was fine
returns a real answer, slightly early; one that was stuck stops spending.
The caller still receives `Outcome.Answered`.

**`FailTurn(reason)`** ends the turn outright. It produces an
`AgentEvent.TurnFailed`, a watcher hears `Narration.TurnStopped`, and a
caller receives `Outcome.Failed` — one vocabulary from the decision to the
answer. `reason` cannot be blank; a turn ended on purpose must say why.

## The default: `TurnPolicy.calls(20, 25)`

```java
static TurnPolicy calls(int answerAt, int failAt)
```

`HarnessConfig.turnPolicy` defaults to `TurnPolicy.calls(20, 25)` on both
doors. A default is here at all because doing nothing is not the safe
choice — unlike a retry policy, where doing nothing costs nothing, an
unbounded turn that will not converge spends until somebody notices.

Twenty is the ecosystem's number, not Nessy's own: it is the threshold
several agent frameworks converge on independently, and there is no
principled reason to pick a different one when this is a default meant to
be replaced by an application that knows its own numbers.

The first response is `AnswerNow`, not `FailTurn`, because at twenty calls
you cannot tell a stuck loop from a long honest turn apart. Asking the
model to answer from what it already has is the move that costs nothing
if the turn was fine, and stops the spend if it was not.

There is a second threshold, `failAt`, because `AnswerNow` is a request,
not a guarantee. How a model honours it — see below — differs by vendor,
and a model that ignores the request would otherwise loop forever anyway.
`failAt` is where the turn actually ends.

Both thresholds read as **"at or past," never as equality**: `decide`
checks `modelCalls() >= failAt`, not `==`. The count does not advance once
per consultation — a tool call completing moves a turn on without calling
the model — so a policy asking whether the count *is* twenty could be
consulted at nineteen, then at twenty-one, and never fire.

Model calls, not tool calls, because model calls are what cost money.

## Anything else is a lambda

```java
static TurnPolicy unbounded() {
  return (stats, now) -> new TurnDecision.Continue();
}
```

Time and spend are perfectly good things to bound a turn on; they are not
things a shipped default may assume, so a policy that bounds on either is
written as a lambda by whoever knows their own numbers:

```java
TurnPolicy fiveMinutes = (stats, now) ->
    stats.elapsed(now).compareTo(Duration.ofMinutes(5)) >= 0
        ? new TurnDecision.FailTurn("the turn ran past five minutes")
        : new TurnDecision.Continue();
```

There are no combinators, and that is deliberate. `TurnPolicy` is read
from configuration at the moment it is needed and never stored, unlike
`RetryPolicy`, which is sealed and serialisable because it is frozen onto
an effect row and read back later. What ships is one implementation,
`calls`, because a default has to be nameable. A second unit, two
thresholds in different units, or two policies at once is a lambda —
helpers can follow once somebody has written the same one twice.

## Configuring it

```java
config.turnPolicy(TurnPolicy.calls(10, 15));
```

`HarnessConfig.turnPolicy` sits on both doors' configuration, because both
fold the same way and neither has a reason to differ. From Spring Boot it
is reached through a `Customizer` bean, not a property — there is no
`nessy.turn-policy.*`; a policy that bounds on anything but a call count
is a lambda, and a lambda has no property syntax to bind to.

## Each adapter honours `AnswerNow` differently, on purpose

`TurnDecision.AnswerNow` becomes `ToolChoice.Answer` on the request:

```java
public sealed interface ToolChoice {
  record Auto() implements ToolChoice {}
  record None() implements ToolChoice {}
  record Answer() implements ToolChoice {}
  record Any() implements ToolChoice {}
  record Named(ToolName name) implements ToolChoice {}
}
```

`Answer` is the one arm that is emulated rather than translated. Every
other arm names something each vendor spells directly; this one names an
*intent* and leaves each adapter to honour it however its own wire allows.
That is deliberate, because the obvious literal translation — send no
tools — is worse: it throws away the cached prefix on every vendor that
caches, including the ones that need not lose it. Keeping the tool offers
in the request is what preserves that cached prefix, which is why the
choice of how to honour `Answer` belongs to the adapter rather than to one
shared translation.

**OpenAI and Gemini** keep the tool offers in the request and set their
own documented "none" mode — `ChatCompletionToolChoiceOption.Auto.NONE` on
OpenAI, `FunctionCallingConfigMode.NONE` on Gemini — the same wire value
each already uses for "no tool this turn," reused here for a different
intent that happens to coincide: both vendors document that mode as
producing a message rather than a call. This is **documented rather than
measured** — if a model ever returns empty content under it, the fallback
is to send no tools at all, at the cost of the cache.

**Anthropic and Bedrock** send no tools at all when the choice is
`Answer`. On Anthropic this is measured, not assumed: a ban with tools
still present in the request was measured, on 2026-09-20, to end the turn
with no content at all. Bedrock's Converse API cannot express "no tool"
as a choice in the first place — `chooseTool` refuses outright if asked to
translate `ToolChoice.None` literally — so its adapter never reaches that
choice at all when answering; it drops the tool configuration from the
request instead. Both adapters treat reaching their `ToolChoice.Answer`
case in the ordinary tool-choice switch as unreachable, and throw if it
ever is:

```java
case ToolChoice.Answer _ ->
    throw new IllegalStateException("answering sends no tools, so no choice to make");
```

## Where next

- [Cost](cost.md), the `TurnStats` a policy reads
- [Durable Computation](durable-computation.md), the retries a model call
  can still take before a policy is ever consulted
- [Outcomes](outcomes.md), what a caller sees when a policy ends a turn
