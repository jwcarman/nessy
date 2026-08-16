# Tools and Grants

A **tool** is something the model can ask the harness to do: a name, a sentence
explaining when to use it, a record describing its arguments, and a method that runs.
The JSON Schema the model sees is derived from `inputType()` rather than written by
hand.

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public String name() { return "add"; }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }

    public Awaited<ToolResult> execute(Add input, ToolContext context) {
        return Awaited.ready(ToolResult.ok(String.valueOf(input.left() + input.right())));
    }
}
```

`Tool<T>` also carries `describe(T input)`, whose default is the input record's
`toString()` — usable, but it reads like `Greet[name=Ada]`. Override it for anything an
approver will actually read: a prompt you skim is a prompt you approve without reading.

## The grant principle

A tool carries **zero authority** on its own. `ToolGrant.grant(tool, policy)` is the only
way to attach a tool to an agent — there is no `tools(Tool...)` overload, because no
policy can be derived for a bare tool:

```java
public record ToolGrant(Tool<?> tool, UsagePolicy policy) {
  public static ToolGrant grant(Tool<?> tool, UsagePolicy policy) { ... }
}
```

```java
Agent<String> agent =
    harness.agent(
        a ->
            a.name("guardian")
                .model("claude-sonnet-4-5")
                .tools(ToolGrant.grant(new AddTool(), UsagePolicy.allow()))
                .approver(Approver.denyAll("would fail if ever asked")));
```

The pairing — which tool, and the authority to call it — is stated together, per agent,
per tool. This is the security statement of the harness, structurally: it does not
compile without a policy.

## `UsagePolicy`: the authority half

`UsagePolicy#evaluate(ToolCall, ConversationState)` is consulted exactly once per call,
at the tool call executor's one authority chokepoint — before the tool ever runs and
before the approver is ever asked. The model has no say in the outcome; it only ever
sees the result.

- `UsagePolicy.allow()` — every call proceeds; the approver is never consulted. Always
  the same canonical instance, which is what lets the agent factory tell "no
  approval path can exist here" from an opaque custom policy that might.
- `UsagePolicy.deny(reason)` — every call is refused, with the same reason each time.
- `UsagePolicy.requireApproval()` — every call defers to the agent's `Approver`.

`evaluate` must be pure: no I/O, no mutation, nothing beyond a function of its two
arguments. The executor may call it from any thread and treats an escaping
`RuntimeException` as a deny — a broken policy fails closed rather than becoming an
allow.

!!! note "Durable re-drives execute at-least-once"
    A tool's `execute` javadoc says it plainly: a tool that cannot be safely re-run makes
    itself idempotent, or parks and lets its remote side deduplicate by token. This is the
    same at-least-once posture the whole loop is built around — see
    [The Durable Loop](durable-loop.md).

## Imported tools are no exception: MCP as import security

`nessy-tool-mcp`'s `McpToolbox` turns an MCP server's tools into ordinary `Tool<JsonNode>`
instances — but importing a whole server's toolbox does not import authority along with
it. Each tool is granted individually, exactly like a hand-written one, so a server
offering ten tools yields ten separate grant decisions, never one blanket "trust this
server":

```java
try (McpToolbox toolbox = McpToolbox.connect(transport, mapper)) {
  Agent<String> agent =
      harness.agent(
          a ->
              a.name("researcher")
                  .tools(
                      ToolGrant.grant(toolbox.tool("search"), UsagePolicy.allow()),
                      ToolGrant.grant(toolbox.tool("purchase"), UsagePolicy.requireApproval())));
}
```

`toolbox.tool(name)` fails noisy — `NoSuchElementException` naming every tool the server
actually advertised — rather than handing back `null` for a typo. `toolbox.tools()`
returns every tool the server advertised, for callers that want to grant the whole set —
still one `ToolGrant` per tool; there is no bulk-grant form, for the same reason
`tools(Tool...)` doesn't exist.

`McpToolbox` is `AutoCloseable`; closing it closes the underlying MCP session. A `Tool`
obtained before that point keeps working as a plain Java reference, but calling it
afterward fails loud rather than swallowing the closed session.

!!! warning "v1 boundaries worth knowing before you reach for MCP tools"
    - **Tools only** — resources, prompts, and roots are not wrapped.
    - **Text-first, with honest degradation** — non-text content (images, embedded
      resources) has no text-shaped Nessy analog yet, so it's JSON-encoded into the text
      output rather than silently dropped.
    - **No elicitation or sampling yet.**
    - **The SDK's 20-second request/init timeout applies as-is** — a slow server or a slow
      tool call can time out before it answers; configurability arrives with the starter
      wiring, a later generation.
    - **Progress notifications are not forwarded to `ToolContext.progress`** — the SDK's
      sync client exposes only a session-global progress consumer, not one scoped to a
      single call.

## Where next

- [The Durable Loop](durable-loop.md) — why at-least-once execution shapes every `Tool`.
- [Parks and Callbacks](parks-and-callbacks.md) — what a tool does when `execute` must
  outlive the process.
- [Planning](planning.md) — `PlanTools.updatePlan`, a tool granted `allow()` by
  convention, shows the grant principle applied to a self-bookkeeping tool.
