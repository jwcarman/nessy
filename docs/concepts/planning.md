# Planning

The Planning pattern, as `nessy-planning`: a plan the model writes, holds
across turns, and works through.

```java
PlanStore plans = new JdbcPlanStore(dataSource, TYPE);

Harness<String> harness = factory.create(config -> config
        .agentType(TYPE)
        .systemPrompt(prompt)
        .inference(in -> in.context(ctx -> ctx.ambient(PlanTools.plan(plans))))
        .tool(PlanTools.updatePlan(plans)));
```

Two halves. `PlanTools.plan(store)` is the read half, an `AmbientSource`
that puts the agent's current plan in front of it on every call.
`PlanTools.updatePlan(store)` is the write half, one tool named
`update_plan` that replaces the plan wholesale.

## The plan is minimal on purpose

```java
public record Plan(List<Task> tasks) {
  public record Task(String title, Status status) {}
  public enum Status { PENDING, IN_PROGRESS, DONE }
}
```

A title and a status, nothing else. No ids, because wholesale replacement
makes them unnecessary; no notes, no nesting, no timestamps. Every one of
those is easy to add when something asks for it and impossible to remove
once written down. No plan and an empty plan are one state: saving an empty
list clears it, and a context gets no plan block either way.

## Why wholesale, and why ambient

The model resends the **whole** list on every update rather than patching
it. That is both what models are trained to do and what this engine needs:
a durable re-drive is at-least-once, so a replayed write stores the
identical list. Idempotent by construction, with no task ids to reconcile.

The plan is ambient rather than a message. A plan written into the story
would be re-sent as it was when written, so a task marked done on turn four
would still read as pending forever. Asked afresh on every call, it is the
current plan or nothing at all. An empty plan contributes nothing: a heading
over no tasks tells the model it has a plan, which is a claim, and saying
nothing is not.

## The rest of the family

The task list is the first pattern of several, and the rest layer onto it.
In the order they pay off:

- **Plan-and-Execute with replanning.** After a step's result the model
  revises the remaining steps rather than only ticking one off: a `replan`
  tool, and a policy for when a revision is prompted (a failed step, every N
  steps). Its first form is an ambient nudge built on the read-only turn
  histories; its second is a separate planner call by an event listener,
  so a strong, slow planner can sit behind a cheap, fast executor.
- **Plan critique.** Before execution, a second inference call reviews the
  plan for missing steps, wrong order or unsafe actions.
- **Hierarchical decomposition.** A step can itself be a plan, which is
  exactly what a delegated sub-agent gets handed.
- **Goals and progress.** A standing goal with success criteria on the plan,
  checked against its state each turn.
- **Budget-aware planning**, **plan with placeholders** (ReWOO) and **plan
  search** (Tree of Thoughts) further out.

See the [roadmap](https://github.com/jwcarman/nessy/blob/main/ROADMAP.md#planning).

## Where next

- [Memory](memory.md), the notebook, and why a note and a plan differ
- [Tools](tools.md), how a tool's input becomes its schema
