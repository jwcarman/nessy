# Subagents

A subagent call is a tool call whose work is another agent's conversation.
That single sentence buys the whole feature: everything Nessy already
guarantees about tool calls — at-least-once replay, parking, durable
resumption, approval gates, observability — applies to delegation for free.
The parent's model sees an ordinary tool; the child is an ordinary agent
with its own identity, prompt, grants, and memory.

## Defining a subagent

A subagent is defined inside its parent's own builder, not assembled and
wired up separately:

```java
Agent<String> writer = harness.agent()
    .name("writer")
    .model(MODEL)
    .systemPrompt(WRITER_PROMPT)
    .subagent(sub -> sub
        .name("researcher")
        .description("Delegate research questions to a focused researcher.")
        .model(MODEL)
        .systemPrompt(RESEARCHER_PROMPT)
        .tools(ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
               ToolGrant.grant(new AskQuestionTool(pending), UsagePolicy.requireApproval())))
    .build();
```

Building the writer builds the researcher: a delegation tool named after the
child is granted on the parent, the links store that carries a parked
child's parent token is wired from the harness's own store family, and the
listener that wakes the parent once the child settles is registered
internally. Nothing here needs manual assembly.

`AgentBuilder#subagent` hands the lambda a `SubagentConfig<T>` — a config,
not a builder. It has fluent setters and no `build()`: the parent's own
builder is the only thing that ever turns it into an `Agent`. `name` and
`description` are required (`description` becomes the delegation tool's own
description — what the parent's model reads to decide whether to delegate);
`build()` throws `IllegalStateException` naming whichever is missing.
Everything else trims to `model`, `systemPrompt`, `maxTokens`, `tools`,
`memory`, `termination`, and `policy` (the delegation tool's own usage
policy, default `UsagePolicy.allow()`).

A `SubagentConfig` can itself declare `.subagent(...)`, nesting a
grandchild the same way. The delegation tree is exactly this lexical
nesting — A→B→C is `A`'s builder calling `.subagent(...)` on a config that
itself calls `.subagent(...)`. A cycle is unrepresentable: a child is always
defined inside its parent and can never refer back to it. A name already
taken anywhere in the whole tree — a sibling, an ancestor, an unrelated
top-level agent — is rejected at `build()`.

**Every agent and subagent a harness ever builds is registered under its
name for the harness's own lifetime, permanently.** Building the same
name twice from one harness — deliberately (a redeployed agent bean, a
retried startup) or by accident — throws `IllegalArgumentException`; there
is no unregister door and no expiry. A harness that builds agents
per-request rather than once at startup will eventually collide on names
for exactly this reason, so build once and keep the `Agent`/`Harness`
around rather than rebuilding on every call. A failed `build()` does clean
up after itself, though: if a multi-child declaration fails partway
through — most concretely, two siblings sharing a name — every child that
build attempt had already registered is unregistered before the exception
reaches the caller, so a corrected retry never collides with the attempt
that failed.

## Two doors: a task string, or a typed record

`.subagent(SubagentCustomizer<String>)` is the degenerate door: the
delegation tool's wire shape is `Delegation(String task)`, one required
string field wrapping the plain-text task, and the child is an
`Agent<String>` told the task text.

`.subagent(Class<T>, SubagentCustomizer<T>)` is the typed door: `T` becomes
the delegation tool's wire shape directly — the model calls the tool with
`T`'s own generated schema, structured arguments instead of a prose-packed
string — and the child is an `Agent<T>`:

```java
record ResearchRequest(String question, int depth) {}

.subagent(ResearchRequest.class, sub -> sub
    .name("researcher")
    .description("Delegates a structured research request.")
    .model(MODEL)
    .renderer(request ->
        List.of(new TextBlock("Q: " + request.question() + " (depth " + request.depth() + ")")))
    .tools(...))
```

`renderer(InputRenderer<T>)` is required on the typed door — `build()`
throws `IllegalStateException` naming the missing renderer if the
customizer never calls it. There is no silent render-as-JSON default;
`InputRenderer.json(mapper)` is available as an explicit choice, but an
explicit renderer call is what the door requires. The degenerate `String`
door never reads `renderer` even if one is set — its wire shape is always
the `Delegation` wrapper, not `T`.

The subagent's input type IS its tool schema. Typed OUTPUT — a structured
result back to the parent instead of the child's final text — is a separate
question, out of scope here.

## Reaching a child: the parent's doors

`Agent#subagent(String name)` returns a `Subagent` — a narrow doors view
onto a direct child:

```java
Subagent researcher = writer.subagent("researcher");
researcher.approve(token);
researcher.deny(token, "not this one");
researcher.resume(token, resolution);
researcher.snapshot(childConversationId);
researcher.subagent("archivist"); // a grandchild, traversed one door at a time
```

An unknown name throws `IllegalArgumentException` naming the parent and the
requested child. Deliberately absent: `converse()` and `tell()`. A
subagent's conversations exist only through delegation — the parent's own
tool call is what tells a child anything. `Subagent` exists only to answer
what a parked or completed child still needs answered from the outside: an
approval, a denial, a resolved wait, a snapshot for rendering. A deeper
descendant is reached by chaining, `writer.subagent("researcher").subagent("archivist")`,
matching the lexical nesting the builders built.

## What's inherited, what's owned

A subagent shares by construction: the harness's provider, its whole
conversation store family (including the links store), `Parks`, the
approver, observations, and harness-seeded listeners. A subagent owns: its
name, prompt, model, tool grants, memory transformers, and termination
policy. The shared half is the coordination infrastructure; the owned half
is the agent's own identity and competence. Nothing else on `SubagentConfig`
overrides the harness — there's no per-subagent provider or store.

Because the approver is inherited rather than a config knob, declaring it
once on the parent cascades to every descendant. A researcher's gated tool
parks under whichever approver the writer that defined it carries.

## The park chain: two waits, not one

A subagent call is an ordinary tool call, so when the child parks, the
parent's own delegation call parks right alongside it — the parent tool
mints its own `ParkToken`, saves `childId → parentToken`, and the parent's
turn returns `RunOutcome.Parked`.

A parked call is fully supported at both ends of the chain, including a
delegation tool itself gated behind `UsagePolicy.requireApproval()` whose
child then parks for its own reason. Parking is two waits, not one:

1. **Permission.** The parent's approver may park the delegation call itself
   before the child is ever told anything.
2. **Work.** Once approved, the delegation runs; if the child's own tool
   parks — its own HITL gate, its own webhook wait — that's a second,
   independent wait, on its own fresh token.

At most one approval wait and one execution wait are ever outstanding at
once: approval gating only ever runs from the call's first execution, never
from a resume, and only an `Allow` decision re-invokes the tool — so a
third park is structurally unreachable. A resolved park is history, not a
lock; a fresh park after an approval is a legal fold, not a violation. A
re-driven execution that parks with the same token as the call's own
outstanding park is an idempotent no-op — the tool returns the stored link
token on replay, the ordinary redelivery shape.

When the child eventually settles, it publishes a `ConversationSettled`
fact; the internally-wired completions listener looks up the parent token,
reads the routing agent name off the parent's own park stamp, and resumes
the parent — inside the same call that approved, denied, or otherwise
settled the child. Nothing in the application drives that resume directly.

## Deterministic child ids and true replay idempotency

A child's `ConversationId` is derived, not generated:
`<parent-conversation-id>/<tool-call-id>`. A redelivered parent turn replays
with the same call id and lands on the *same* child conversation rather
than spawning a sibling.

Before telling the child anything, the delegation tool inspects the
child's own snapshot, and only a genuinely fresh or idle child is told at
all:

- A **completed** child answers from its last assistant message — no
  re-telling.
- A **failed** child answers with a generic already-failed error — no
  re-telling.
- A **parked** child answers with the parent token already on file, rather
  than minting a fresh one.

Only a conversation with no status yet gets a fresh telling.

## Fresh child per call, continuity via the Notebook

Every delegation opens a new child conversation — the derived id guarantees
it. There is no follow-up-the-same-researcher affordance. Continuity across
a parent and its children instead comes from sharing a `SubjectId`: give
every agent in the family the same subject and they share a
[Notebook](notebook.md), durable model-gated memory across the whole
delegation tree with zero new machinery.

## Fan-out is sequential

The loop executes a turn's tool calls in order, so several delegations in
one parent turn run their children sequentially, one at a time. There is no
scatter/gather across several outstanding delegations — parallel fan-out is
a loop-level feature with its own design questions and not part of this
generation.

## What this deliberately omits

- **No child-delta streaming into the parent.** The delegation tool relays
  one coarse activity ping per tool call the child requests, so a long
  delegation with tool calls in it never looks frozen, but the child's own
  text deltas never reach the parent's observer. Forwarding them is a later
  feature — whose turn is it, exactly? — and `SubagentConfig` already owns
  both sides of that future decision.
- **No fan-out coordinator.** The model can already fan out by calling
  several subagent tools, or the same one with different tasks, in one
  turn, sequentially; a "wait for all" combinator is application logic, not
  framework logic.
- **No cross-harness delegation.** Parent and child share one harness and
  one store family. Delegation to an agent running in a different process
  or service is a later generation's story.
- **No typed delegation OUTPUT.** The typed door covers the tool's own
  input; a structured result flowing back to the parent instead of the
  child's final text is a separate design question, banked for later.

## Where next

- [Parks and Callbacks](parks-and-callbacks.md) — the park tokens, the
  agent-name stamp, and the doors a subagent's own park rides.
- [The Notebook](notebook.md) — the shared-subject continuity story a
  parent and its children use instead of a follow-up affordance.
- [Tools and Grants](tools-and-grants.md) — the grant vocabulary a
  subagent's own tool declarations use.
