# Nessy and the 12-Factor Agents

HumanLayer's [12-Factor Agents](https://github.com/humanlayer/12-factor-agents)
names twelve disciplines for building reliable LLM software — a checklist, not
a framework. Nessy wasn't built from that checklist, but the two arrived at
similar answers to similar problems. This page maps all twelve factors onto
Nessy machinery, one receipt each, and says plainly where the match is
partial.

## 1. Natural language to tool calls

The model doesn't choose freeform side effects — it emits a structured call
against a schema, and something else decides whether it runs. `Tool<T>` is
that structured half: a name, a description, an `inputType()` a JSON Schema
is derived from, and an `execute` that only ever sees a typed record. See
[Tools and Grants](tools-and-grants.md).

## 2. Own your prompts

The system prompt is an application-owned string, not a framework template —
`AgentBuilder` takes it as-is and does nothing to it. Where this factor gets
interesting is the injected context blocks: the plan checklist ([Planning](planning.md))
is real text, framed and appended to a user-role message, byte-exact and
reproducible. Own it plainly: that block **is** injected text into the
model's context, the same shape this factor warns about. The honest
difference is that it's opt-in (nothing appears unless an agent grants
`PlanTools.updatePlan` and wires the transformer), documented down to its
exact rendering, and pinned — `PlanTools`' own `renderChecklist` is the one
place that byte sequence is produced, not a template scattered across the
loop.

## 3. Own your context window

`Memory` owns what a model call actually sees, and it's a pipeline an
application composes explicitly: a hydrator that bootstraps the initial
`Context` from durable history, then an ordered list of `ContextTransformer`
stages an application chooses and orders itself — clamp, redact, elide,
append. Nothing about window shape happens implicitly inside the loop. See
[Memory and the Pipeline](memory-and-the-pipeline.md).

## 4. Tools are just structured outputs

A tool's input is a plain record (`Tool<Add>` over `record Add(int left, int
right)`), and the model's call is that record, parsed and validated against
the derived schema before `execute` ever runs — the loop never hands a tool a
bag of untyped JSON. See [Tools and Grants](tools-and-grants.md).

## 5. Unify execution state and business state

`ConversationState` is the one record both live in: `fold` advances it on
every fact, and it's the same state a durable store persists, resumes, and
hands back after a restart — there's no separate "business" record the
application keeps in sync by hand. Ids are application-minted, not
framework-generated: `order-desk`'s example mints a `ConversationId` from the
order id itself, so every fact about one order folds onto the same state
regardless of which process handles it. See [The Durable Loop](durable-loop.md)
and [Storage](storage.md).

## 6. Launch/pause/resume with simple APIs

A tool or approver that can't finish in-process returns `Awaited.parked(token)`
instead of a result; the loop persists and moves on. `agent.resume(token,
resolution)` answers it later, from any process — the gap can be 200
milliseconds or two days, and nothing about the API changes either way. See
[Parks and Callbacks](parks-and-callbacks.md).

## 7. Contact humans with tool calls

Human approval is a tool call the model already knows how to make, gated by
`UsagePolicy.requireApproval()` and answered through the same park-and-resume
door as any other long-running tool: `Approver.parkAll()` is the durable-HITL
posture nessy ships, where every approval parks and a UI is the thing that
eventually calls `agent.approve`/`agent.deny`. The `chat-web` example
survives a kill mid-approval on exactly this contract. See
[Parks and Callbacks](parks-and-callbacks.md) and
[Tools and Grants](tools-and-grants.md).

## 8. Own your control flow

There's no hidden agent loop making its own decisions about when to stop
retrying, when to hand off, or when to ask a human. The loop itself is an
explicit fold an application drives by calling `tell` or `resume` —
including from triggers that have nothing to do with a chat turn (a cron
firing, a queue message, a webhook), never the framework deciding on its own
that it's time to run again. A `TerminationPolicy` (an error-ceiling and
max-model-calls wallet guard, in `nessy-core` today) bounds a runaway loop
the same way — no dedicated site page yet, so check its Javadoc directly.
See [The Durable Loop](durable-loop.md) and [Triggers](../guides/triggers.md).

## 9. Compact errors into context window

A failed tool call doesn't throw out of the loop — it returns a `ToolResult`
the model reads in-band, the same channel a successful result uses, so the
model can see what went wrong and try again. The plan tool's own input
validation takes exactly this path: a blank task title surfaces as a failed
result, not an exception. See [Tools and Grants](tools-and-grants.md) and
[Planning](planning.md).

## 10. Small, focused agents

An agent is a cheap identity — a name, a model, a system prompt, a set of
grants — not a heavyweight runtime object; a `Harness` holds the shared
infrastructure and any number of narrowly-scoped agents run inside it. The
grant principle pushes the same way at the tool level: authority is stated
per tool, per agent, so a focused agent's blast radius is exactly the tools
it was handed, not everything the harness knows how to do. See
[The Durable Loop](durable-loop.md).

## 11. Trigger from anywhere, meet users where they are

`agent.converse().tell(...)` looks the same whether the caller is a person at
a keyboard, a browser request, a cron firing, or a message landing on a
queue — the durable inbox absorbs a telling the same way regardless of where
it came from. The example family demonstrates five trigger shapes end to end
on this one entry point. See [Triggers](../guides/triggers.md) and
[Examples](../examples/index.md).

## 12. Make your agent a stateless reducer

`ConversationState#fold(ConversationEvent)` is the reducer, and it's held to
the letter of this factor: pure, synchronous, total, no I/O, `f(state,
event) -> (state, effects)`. `EffectExecutors` performs the effects the fold
describes and feeds results back in as new facts — the fold itself never
retains anything between calls, and at-least-once delivery is designed for
rather than fought: replayed facts fold safely, redundant appends resolve to
the existing entry, redelivered resolutions drain quietly against a call
that already settled. See [The Durable Loop](durable-loop.md).

## Where next

- [The Durable Loop](durable-loop.md) — the fold, effects, and the
  at-least-once posture most of these factors lean on.
- [Parks and Callbacks](parks-and-callbacks.md) — the park/resume mechanics
  behind factors 6 and 7.
- [Triggers](../guides/triggers.md) — factor 11 in five worked shapes.
